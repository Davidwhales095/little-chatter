package com.messenger.little;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Hosts a room: shows join requests to the host user, relays messages, and enforces the
 * room rules (time limit; anyone leaving or failing to reconnect deletes the room).
 *
 * All room state is touched only on the single {@link #loop} thread.
 */
final class RoomHost implements RoomSession, BluetoothLink.Callback {
    /** State of a link that has not joined yet. */
    private static final class Pending {
        boolean helloReceived;
        String nick;
        String model;
        /** Set once the request is queued for the host user. */
        String requestId;
        ScheduledFuture<?> timeout;
    }

    private static final class Member {
        final String token;
        final String nick;
        final String address;
        BluetoothLink link;
        ScheduledFuture<?> graceTimer;

        Member(String token, String nick, String address) {
            this.token = token;
            this.nick = nick;
            this.address = address;
        }

        boolean connected() {
            return link != null;
        }
    }

    private final BluetoothAdapter adapter;
    private final Listener listener;
    private final String hostNick;
    private final String title;
    private final long durationMs;
    private final SecureRandom random = new SecureRandom();
    private final ScheduledThreadPoolExecutor loop = new ScheduledThreadPoolExecutor(1);

    // Loop-thread state.
    private final Map<String, Member> members = new LinkedHashMap<String, Member>();
    private final Set<BluetoothLink> pendingLinks = new HashSet<BluetoothLink>();
    /** Join requests waiting for the host user, oldest first. */
    private final LinkedList<BluetoothLink> requestQueue = new LinkedList<BluetoothLink>();
    /** Devices the host declined; blocked for the rest of the room. */
    private final Set<String> declinedAddresses = new HashSet<String>();
    private final LinkedList<JSONObject> recentMessages = new LinkedList<JSONObject>();
    private long seq = 0;
    private long endsAtElapsed;
    private boolean closed = false;
    private ScheduledFuture<?> endTimer;
    private ScheduledFuture<?> heartbeat;
    private AcceptThread acceptThread;

    private volatile RoomState state = RoomState.IDLE;

    RoomHost(BluetoothAdapter adapter, Listener listener, String hostNick, long durationMs) {
        this.adapter = adapter;
        this.listener = listener;
        this.hostNick = RoomProtocol.cleanNickname(hostNick);
        this.title = RoomState.roomTitle(this.hostNick);
        this.durationMs = durationMs;
        loop.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        loop.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    // ---- RoomSession ------------------------------------------------------------------------

    @Override
    public void start() {
        post(new Runnable() {
            @Override
            public void run() {
                endsAtElapsed = SystemClock.elapsedRealtime() + durationMs;
                acceptThread = new AcceptThread();
                acceptThread.start();
                endTimer = loop.schedule(new Runnable() {
                    @Override
                    public void run() {
                        closeRoom("Time is up. The room has been deleted.",
                                "Time is up. The room has been deleted.");
                    }
                }, durationMs, TimeUnit.MILLISECONDS);
                heartbeat = loop.scheduleWithFixedDelay(new Runnable() {
                    @Override
                    public void run() {
                        heartbeat();
                    }
                }, RoomProtocol.HEARTBEAT_MS, RoomProtocol.HEARTBEAT_MS, TimeUnit.MILLISECONDS);
                publishState();
                listener.onNotice("Room created. People nearby can now find it and ask to join.");
                listener.onStatus(title + " is open.");
            }
        });
    }

    @Override
    public RoomState getState() {
        return state;
    }

    @Override
    public boolean sendChat(final String text) {
        if (text.length() == 0 || text.length() > RoomProtocol.MAX_TEXT_CHARS) {
            return false;
        }
        post(new Runnable() {
            @Override
            public void run() {
                if (closed) {
                    return;
                }
                JSONObject message = RoomProtocol.packet(RoomProtocol.MSG,
                        "id", RoomProtocol.newMessageId(),
                        "sender", hostNick,
                        "text", text,
                        "seq", ++seq);
                remember(message);
                broadcast(message, null);
                listener.onChat(hostNick, text, true);
            }
        });
        return true;
    }

    @Override
    public void answerJoinRequest(final String requestId, final boolean accept) {
        post(new Runnable() {
            @Override
            public void run() {
                if (closed) {
                    return;
                }
                BluetoothLink link = findRequest(requestId);
                if (link == null) {
                    return; // Already cancelled or timed out.
                }
                Pending pending = (Pending) link.tag;
                requestQueue.remove(link);
                cancelTimeout(pending);
                if (!accept) {
                    declinedAddresses.add(link.address);
                    reject(link, "The host declined your request.");
                    listener.onStatus("Declined " + pending.nick + ".");
                } else if (members.size() >= RoomProtocol.MAX_MEMBERS) {
                    reject(link, "The room is full.");
                } else {
                    join(link, pending);
                }
                publishState();
            }
        });
    }

    @Override
    public void leave() {
        post(new Runnable() {
            @Override
            public void run() {
                closeRoom(hostNick + " (host) left. The room has been deleted.", "You left the room.");
            }
        });
    }

    // ---- Links (called from link threads) -----------------------------------------------------

    @Override
    public void onPacket(final BluetoothLink link, final JSONObject packet) {
        post(new Runnable() {
            @Override
            public void run() {
                if (closed) {
                    return;
                }
                if (link.tag instanceof Pending) {
                    handlePending(link, (Pending) link.tag, packet);
                } else if (link.tag instanceof Member) {
                    handleMember((Member) link.tag, packet);
                }
            }
        });
    }

    @Override
    public void onClosed(final BluetoothLink link, final String reason) {
        post(new Runnable() {
            @Override
            public void run() {
                if (closed) {
                    return;
                }
                if (link.tag instanceof Pending) {
                    // For example, the requester cancelled while waiting for approval.
                    boolean wasQueued = requestQueue.contains(link);
                    dropPending(link);
                    if (wasQueued) {
                        publishState();
                    }
                    return;
                }
                if (link.tag instanceof Member) {
                    Member member = (Member) link.tag;
                    if (member.link == link) {
                        memberLost(member);
                    }
                }
            }
        });
    }

    // ---- Loop-thread logic --------------------------------------------------------------------

    private void onAccepted(BluetoothSocket socket) {
        if (closed) {
            closeQuietly(socket);
            return;
        }
        final BluetoothLink link;
        try {
            link = new BluetoothLink(socket, this);
        } catch (IOException error) {
            closeQuietly(socket);
            return;
        }
        final Pending pending = new Pending();
        link.tag = pending;
        pendingLinks.add(link);
        link.start();
        pending.timeout = loop.schedule(new Runnable() {
            @Override
            public void run() {
                if (link.tag == pending && pending.requestId == null) {
                    dropPending(link);
                    link.cancel();
                }
            }
        }, RoomProtocol.HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void handlePending(final BluetoothLink link, final Pending pending, JSONObject packet) {
        if (!RoomProtocol.HELLO.equals(packet.optString("t")) || pending.helloReceived) {
            reject(link, "Bad handshake.");
            return;
        }
        pending.helloReceived = true;
        if (packet.optInt("v") != RoomProtocol.VERSION) {
            reject(link, "Incompatible app version.");
            return;
        }

        // 1. Room list probe: describe the room and hang up.
        if (packet.optBoolean("probe", false)) {
            dropPending(link);
            link.sendAndClose(RoomProtocol.packet(RoomProtocol.ROOM,
                    "hostNick", hostNick,
                    "people", 1 + members.size(),
                    "max", RoomProtocol.MAX_MEMBERS + 1,
                    "endsInMs", remainingMs()));
            return;
        }

        pending.nick = RoomProtocol.cleanNickname(packet.optString("nick"));
        pending.model = RoomProtocol.cleanModel(packet.optString("model"));

        // 2. Reconnect with a member token: no approval needed.
        String token = packet.optString("token", "");
        if (token.length() > 0) {
            rejoin(link, pending, token, packet.optLong("lastSeq", 0L));
            return;
        }

        // 3. New join request: ask the host user.
        if (declinedAddresses.contains(link.address)) {
            reject(link, "The host declined your request.");
            return;
        }
        if (members.size() >= RoomProtocol.MAX_MEMBERS) {
            reject(link, "The room is full.");
            return;
        }
        for (BluetoothLink queued : requestQueue) {
            if (queued.address.equals(link.address)) {
                reject(link, "A request from this device is already waiting.");
                return;
            }
        }
        cancelTimeout(pending);
        pending.requestId = RoomProtocol.newToken(random);
        pending.timeout = loop.schedule(new Runnable() {
            @Override
            public void run() {
                if (requestQueue.remove(link)) {
                    reject(link, "The host did not answer in time.");
                    listener.onStatus("A join request from " + pending.nick + " timed out.");
                    publishState();
                }
            }
        }, RoomProtocol.APPROVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        requestQueue.addLast(link);
        link.send(RoomProtocol.packet(RoomProtocol.WAITING,
                "timeoutMs", RoomProtocol.APPROVAL_TIMEOUT_MS));
        listener.onStatus(pending.nick + " wants to join.");
        publishState();
    }

    private void join(BluetoothLink link, Pending pending) {
        Member member = new Member(RoomProtocol.newToken(random), uniqueNick(pending.nick), link.address);
        members.put(member.token, member);
        attach(link, member);
        broadcastNotice(member.nick + " joined.");
        publishState();
    }

    private void rejoin(BluetoothLink link, Pending pending, String token, long lastSeq) {
        Member found = null;
        for (Member member : members.values()) {
            if (RoomProtocol.constantTimeEquals(member.token, token)) {
                found = member;
                break;
            }
        }
        // The token only works from the same device it was given to.
        if (found == null || !found.address.equals(link.address)) {
            reject(link, "Your seat in this room is gone.");
            return;
        }
        cancelTimeout(pending);
        if (found.connected()) {
            // A stale link is still open; replace it with the new one.
            BluetoothLink old = found.link;
            found.link = null;
            old.cancel();
        }
        if (found.graceTimer != null) {
            found.graceTimer.cancel(false);
            found.graceTimer = null;
        }
        attach(link, found);
        // Resend what the member missed while away.
        for (JSONObject message : recentMessages) {
            if (message.optLong("seq") > lastSeq && !found.nick.equals(message.optString("sender"))) {
                link.send(message);
            }
        }
        broadcastNotice(found.nick + " reconnected.");
        publishState();
    }

    private void attach(BluetoothLink link, Member member) {
        pendingLinks.remove(link);
        link.tag = member;
        member.link = link;
        link.send(RoomProtocol.packet(RoomProtocol.WELCOME,
                "token", member.token,
                "nick", member.nick,
                "hostNick", hostNick,
                "endsInMs", remainingMs(),
                "graceMs", RoomProtocol.RECONNECT_GRACE_MS,
                "members", memberArray()));
    }

    private void handleMember(Member member, JSONObject packet) {
        String type = packet.optString("t");
        if (RoomProtocol.MSG.equals(type)) {
            String text = packet.optString("text", "").trim();
            if (text.length() == 0 || text.length() > RoomProtocol.MAX_TEXT_CHARS) {
                return;
            }
            // The host sets the sender name, so members cannot pretend to be someone else.
            JSONObject message = RoomProtocol.packet(RoomProtocol.MSG,
                    "id", packet.optString("id", RoomProtocol.newMessageId()),
                    "sender", member.nick,
                    "text", text,
                    "seq", ++seq);
            remember(message);
            broadcast(message, member);
            listener.onChat(member.nick, text, false);
        } else if (RoomProtocol.LEAVE.equals(type)) {
            String reason = member.nick + " left. The room has been deleted.";
            closeRoom(reason, reason);
        }
        // PING needs no reply: receiving it already refreshed the link's lastReceivedAt.
    }

    private void memberLost(final Member member) {
        member.link = null;
        broadcastNotice(member.nick + " lost connection. Waiting up to "
                + (RoomProtocol.RECONNECT_GRACE_MS / 1000) + " seconds for them to reconnect.");
        member.graceTimer = loop.schedule(new Runnable() {
            @Override
            public void run() {
                if (!member.connected()) {
                    String reason = member.nick + " could not reconnect in time. The room has been deleted.";
                    closeRoom(reason, reason);
                }
            }
        }, RoomProtocol.RECONNECT_GRACE_MS, TimeUnit.MILLISECONDS);
        publishState();
    }

    private void heartbeat() {
        if (closed) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        JSONObject info = infoPacket();
        for (Member member : new ArrayList<Member>(members.values())) {
            if (!member.connected()) {
                continue;
            }
            if (now - member.link.lastReceivedAt() > RoomProtocol.SILENCE_TIMEOUT_MS) {
                // Reported through onClosed -> memberLost, which starts the grace period.
                member.link.closeWithReason("No response from " + member.nick);
            } else {
                member.link.send(info);
            }
        }
    }

    private void closeRoom(String reasonForMembers, String reasonForMe) {
        if (closed) {
            return;
        }
        closed = true;
        if (endTimer != null) {
            endTimer.cancel(false);
        }
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        if (acceptThread != null) {
            acceptThread.cancel();
        }
        JSONObject closedPacket = RoomProtocol.packet(RoomProtocol.CLOSED, "reason", reasonForMembers);
        for (Member member : members.values()) {
            if (member.graceTimer != null) {
                member.graceTimer.cancel(false);
            }
            if (member.connected()) {
                member.link.sendAndClose(closedPacket);
            }
        }
        JSONObject gone = RoomProtocol.packet(RoomProtocol.REJECT, "reason", "The room has been deleted.");
        for (BluetoothLink link : pendingLinks) {
            if (link.tag instanceof Pending) {
                cancelTimeout((Pending) link.tag);
            }
            link.sendAndClose(gone);
        }
        pendingLinks.clear();
        requestQueue.clear();
        state = RoomState.IDLE;
        listener.onClosed(reasonForMe);
        loop.shutdown();
    }

    // ---- Helpers (loop thread) ------------------------------------------------------------

    private void reject(BluetoothLink link, String reason) {
        dropPending(link);
        link.sendAndClose(RoomProtocol.packet(RoomProtocol.REJECT, "reason", reason));
    }

    private void dropPending(BluetoothLink link) {
        if (link.tag instanceof Pending) {
            cancelTimeout((Pending) link.tag);
        }
        pendingLinks.remove(link);
        requestQueue.remove(link);
        link.tag = null;
    }

    private static void cancelTimeout(Pending pending) {
        if (pending.timeout != null) {
            pending.timeout.cancel(false);
            pending.timeout = null;
        }
    }

    private BluetoothLink findRequest(String requestId) {
        for (BluetoothLink link : requestQueue) {
            if (link.tag instanceof Pending && requestId.equals(((Pending) link.tag).requestId)) {
                return link;
            }
        }
        return null;
    }

    private RoomState.JoinRequest headRequest() {
        if (requestQueue.isEmpty()) {
            return null;
        }
        BluetoothLink link = requestQueue.getFirst();
        Pending pending = (Pending) link.tag;
        return new RoomState.JoinRequest(pending.requestId, pending.nick, link.deviceName,
                RoomProtocol.addressTail(link.address), pending.model);
    }

    private void broadcast(JSONObject packet, Member except) {
        for (Member member : members.values()) {
            if (member != except && member.connected()) {
                member.link.send(packet);
            }
        }
    }

    private void broadcastNotice(String text) {
        broadcast(RoomProtocol.packet(RoomProtocol.NOTICE, "text", text), null);
        listener.onNotice(text);
    }

    private void remember(JSONObject message) {
        recentMessages.addLast(message);
        while (recentMessages.size() > RoomProtocol.HISTORY_FOR_REJOIN) {
            recentMessages.removeFirst();
        }
    }

    private void publishState() {
        state = new RoomState(RoomState.Phase.ACTIVE, true, title, endsAtElapsed, memberLines(), headRequest(), hostNick);
        listener.onStateChanged(state);
        broadcast(infoPacket(), null);
    }

    private JSONObject infoPacket() {
        return RoomProtocol.packet(RoomProtocol.INFO,
                "endsInMs", remainingMs(),
                "members", memberArray());
    }

    private List<String> memberLines() {
        List<String> lines = new ArrayList<String>();
        lines.add(hostNick + " (host)");
        for (Member member : members.values()) {
            lines.add(member.connected() ? member.nick : member.nick + " (reconnecting)");
        }
        return lines;
    }

    private JSONArray memberArray() {
        JSONArray array = new JSONArray();
        for (String line : memberLines()) {
            array.put(line);
        }
        return array;
    }

    private long remainingMs() {
        return Math.max(0L, endsAtElapsed - SystemClock.elapsedRealtime());
    }

    private String uniqueNick(String nick) {
        Set<String> taken = new HashSet<String>();
        taken.add(hostNick);
        for (Member member : members.values()) {
            taken.add(member.nick);
        }
        String candidate = nick;
        for (int i = 2; taken.contains(candidate); i++) {
            candidate = nick + " (" + i + ")";
        }
        return candidate;
    }

    private void post(Runnable task) {
        try {
            loop.execute(task);
        } catch (RejectedExecutionException ignored) {
            // Room already closed.
        }
    }

    private static void closeQuietly(BluetoothSocket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private final class AcceptThread extends Thread {
        private volatile BluetoothServerSocket serverSocket;
        private volatile boolean running = true;

        AcceptThread() {
            super("LittleChatterRoomAccept");
        }

        @Override
        public void run() {
            try {
                serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord(
                        RoomProtocol.SERVICE_NAME, RoomProtocol.SERVICE_UUID);
            } catch (IOException error) {
                listener.onStatus("Unable to open the room: " + error.getMessage());
                post(new Runnable() {
                    @Override
                    public void run() {
                        closeRoom("The room could not be opened.", "The room could not be opened (Bluetooth error).");
                    }
                });
                return;
            }
            if (!running) {
                closeServer();
                return;
            }
            while (running) {
                try {
                    final BluetoothSocket socket = serverSocket.accept();
                    if (socket != null) {
                        try {
                            loop.execute(new Runnable() {
                                @Override
                                public void run() {
                                    onAccepted(socket);
                                }
                            });
                        } catch (RejectedExecutionException closedAlready) {
                            closeQuietly(socket);
                        }
                    }
                } catch (IOException error) {
                    if (running) {
                        listener.onStatus("Stopped accepting new people: " + error.getMessage());
                    }
                    break;
                }
            }
            closeServer();
        }

        void cancel() {
            running = false;
            closeServer();
        }

        private void closeServer() {
            BluetoothServerSocket server = serverSocket;
            if (server != null) {
                try {
                    server.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
