package com.messenger.little;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Asks to join a room hosted on another device and, once the host accepts, stays in it.
 * If the link to the host drops without the host closing the room, it keeps reconnecting
 * with its member token (no new approval) until the grace period ends; after that the room
 * counts as deleted.
 *
 * All state is touched only on the single {@link #loop} thread.
 */
final class RoomMember implements RoomSession, BluetoothLink.Callback {
    private static final int MAX_SEEN_IDS = 512;
    /** Extra time on top of the host's approval timeout before the member gives up by itself. */
    private static final long APPROVAL_SLACK_MS = 15000L;

    private final BluetoothAdapter adapter;
    private final BluetoothDevice hostDevice;
    private final Listener listener;
    private final String requestedNick;
    private final ScheduledThreadPoolExecutor loop = new ScheduledThreadPoolExecutor(1);

    // Loop-thread state.
    private BluetoothLink link;
    private boolean welcomed = false;
    private String token = "";
    private String nick;
    private String title;
    private long lastSeq = 0;
    private long endsAtElapsed;
    private List<String> members = new ArrayList<String>();
    private RoomState.Phase phase = RoomState.Phase.CONNECTING;
    private boolean everJoined = false;
    private boolean closed = false;
    private boolean attemptInFlight = false;
    private long reconnectDeadline;
    private ConnectAttempt attempt;
    private ScheduledFuture<?> handshakeTimer;
    private ScheduledFuture<?> approvalTimer;
    private ScheduledFuture<?> heartbeat;
    private ScheduledFuture<?> reconnectGiveUp;
    private final Set<String> seenIds = new HashSet<String>();
    private final LinkedList<String> seenOrder = new LinkedList<String>();

    private volatile RoomState state;

    RoomMember(BluetoothAdapter adapter, BluetoothDevice hostDevice, Listener listener, String nickname) {
        this.adapter = adapter;
        this.hostDevice = hostDevice;
        this.listener = listener;
        this.requestedNick = RoomProtocol.cleanNickname(nickname);
        this.nick = requestedNick;
        String hostName = hostDevice.getName();
        this.title = "Room on " + (hostName == null ? hostDevice.getAddress() : hostName);
        this.state = new RoomState(RoomState.Phase.CONNECTING, false, title, 0L, members, null);
        loop.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        loop.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    // ---- RoomSession ------------------------------------------------------------------------

    @Override
    public void start() {
        post(new Runnable() {
            @Override
            public void run() {
                publishState();
                listener.onStatus("Connecting to the room host...");
                heartbeat = loop.scheduleWithFixedDelay(new Runnable() {
                    @Override
                    public void run() {
                        heartbeat();
                    }
                }, RoomProtocol.HEARTBEAT_MS, RoomProtocol.HEARTBEAT_MS, TimeUnit.MILLISECONDS);
                startAttempt();
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
        if (state.phase != RoomState.Phase.ACTIVE) {
            listener.onStatus("Not connected to the room right now.");
            return false;
        }
        post(new Runnable() {
            @Override
            public void run() {
                if (closed || link == null || !welcomed) {
                    listener.onStatus("Not connected to the room right now.");
                    return;
                }
                String id = RoomProtocol.newMessageId();
                remember(id);
                link.send(RoomProtocol.packet(RoomProtocol.MSG, "id", id, "text", text));
                listener.onChat(nick, text, true);
            }
        });
        return true;
    }

    @Override
    public void answerJoinRequest(String requestId, boolean accept) {
        // Only the host answers join requests.
    }

    @Override
    public void leave() {
        post(new Runnable() {
            @Override
            public void run() {
                if (closed) {
                    return;
                }
                if (link != null && welcomed) {
                    // Tell the host so it deletes the room for everyone.
                    BluetoothLink leaving = link;
                    link = null;
                    leaving.sendAndClose(RoomProtocol.packet(RoomProtocol.LEAVE));
                }
                // While waiting for approval, closing the link withdraws the request.
                finish(everJoined ? "You left the room." : "Join request cancelled.");
            }
        });
    }

    // ---- Links ------------------------------------------------------------------------------

    @Override
    public void onPacket(final BluetoothLink from, final JSONObject packet) {
        post(new Runnable() {
            @Override
            public void run() {
                if (!closed && from == link) {
                    handlePacket(packet);
                }
            }
        });
    }

    @Override
    public void onClosed(final BluetoothLink from, final String reason) {
        post(new Runnable() {
            @Override
            public void run() {
                if (closed || from != link) {
                    return;
                }
                link = null;
                welcomed = false;
                cancelHandshakeTimers();
                if (everJoined) {
                    reconnectLater(reason);
                } else {
                    finish("Could not join: " + reason);
                }
            }
        });
    }

    // ---- Loop-thread logic --------------------------------------------------------------------

    private void startAttempt() {
        if (closed || attemptInFlight) {
            return;
        }
        attemptInFlight = true;
        attempt = new ConnectAttempt();
        attempt.start();
    }

    private void onSocketConnected(BluetoothSocket socket) {
        attemptInFlight = false;
        attempt = null;
        if (closed) {
            closeQuietly(socket);
            return;
        }
        try {
            link = new BluetoothLink(socket, this);
        } catch (IOException error) {
            closeQuietly(socket);
            onConnectFailed("stream setup failed");
            return;
        }
        welcomed = false;
        link.start();
        link.send(RoomProtocol.packet(RoomProtocol.HELLO,
                "nick", requestedNick,
                "model", RoomProtocol.cleanModel(Build.MODEL),
                "token", token,
                "lastSeq", lastSeq));
        final BluetoothLink current = link;
        handshakeTimer = loop.schedule(new Runnable() {
            @Override
            public void run() {
                if (link == current && !welcomed) {
                    current.closeWithReason("the room host did not answer");
                }
            }
        }, RoomProtocol.HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void onConnectFailed(String reason) {
        attemptInFlight = false;
        attempt = null;
        if (closed) {
            return;
        }
        if (everJoined) {
            reconnectLater(reason);
        } else {
            finish("Could not join: " + reason);
        }
    }

    private void handlePacket(JSONObject packet) {
        String type = packet.optString("t");
        if (RoomProtocol.WAITING.equals(type)) {
            // The host user is now looking at our request.
            cancelHandshakeTimers();
            phase = RoomState.Phase.WAITING_APPROVAL;
            final BluetoothLink current = link;
            approvalTimer = loop.schedule(new Runnable() {
                @Override
                public void run() {
                    if (link == current && !welcomed) {
                        current.cancel();
                        link = null;
                        finish("The host did not answer your request.");
                    }
                }
            }, packet.optLong("timeoutMs", RoomProtocol.APPROVAL_TIMEOUT_MS) + APPROVAL_SLACK_MS,
                    TimeUnit.MILLISECONDS);
            publishState();
            listener.onStatus("Waiting for the host to accept you...");
        } else if (RoomProtocol.WELCOME.equals(type)) {
            cancelHandshakeTimers();
            welcomed = true;
            token = packet.optString("token", token);
            nick = packet.optString("nick", nick);
            String hostNick = packet.optString("hostNick", "");
            if (hostNick.length() > 0) {
                title = RoomState.roomTitle(hostNick);
            }
            boolean rejoined = everJoined;
            everJoined = true;
            phase = RoomState.Phase.ACTIVE;
            if (reconnectGiveUp != null) {
                reconnectGiveUp.cancel(false);
                reconnectGiveUp = null;
            }
            applyInfo(packet);
            listener.onStatus(rejoined ? "Reconnected to the room." : "Joined the room.");
            listener.onNotice(rejoined ? "Reconnected to the room." : "You joined the room as " + nick + ".");
        } else if (RoomProtocol.REJECT.equals(type)) {
            String reason = packet.optString("reason", "Rejected by the room host.");
            link.cancel();
            link = null;
            finish(everJoined ? "Could not rejoin: " + reason + " The room has ended." : reason);
        } else if (RoomProtocol.MSG.equals(type)) {
            lastSeq = Math.max(lastSeq, packet.optLong("seq", lastSeq));
            if (remember(packet.optString("id"))) {
                listener.onChat(packet.optString("sender", "Unknown"), packet.optString("text", ""), false);
            }
        } else if (RoomProtocol.NOTICE.equals(type)) {
            listener.onNotice(packet.optString("text"));
        } else if (RoomProtocol.INFO.equals(type)) {
            if (welcomed) {
                applyInfo(packet);
            }
        } else if (RoomProtocol.CLOSED.equals(type)) {
            BluetoothLink closing = link;
            link = null;
            closing.cancel();
            finish(packet.optString("reason", "The room has been deleted."));
        }
    }

    private void applyInfo(JSONObject packet) {
        if (packet.has("endsInMs")) {
            endsAtElapsed = SystemClock.elapsedRealtime() + packet.optLong("endsInMs");
        }
        JSONArray array = packet.optJSONArray("members");
        if (array != null) {
            List<String> lines = new ArrayList<String>();
            for (int i = 0; i < array.length(); i++) {
                lines.add(array.optString(i));
            }
            members = lines;
        }
        publishState();
    }

    private void reconnectLater(String reason) {
        if (phase != RoomState.Phase.RECONNECTING) {
            phase = RoomState.Phase.RECONNECTING;
            reconnectDeadline = SystemClock.elapsedRealtime() + RoomProtocol.RECONNECT_GRACE_MS;
            reconnectGiveUp = loop.schedule(new Runnable() {
                @Override
                public void run() {
                    finish("Could not reconnect within " + (RoomProtocol.RECONNECT_GRACE_MS / 1000)
                            + " seconds. The room has been deleted.");
                }
            }, RoomProtocol.RECONNECT_GRACE_MS, TimeUnit.MILLISECONDS);
            listener.onNotice("Connection to the room was lost. Reconnecting (up to "
                    + (RoomProtocol.RECONNECT_GRACE_MS / 1000) + " seconds)...");
            publishState();
        }
        listener.onStatus("Reconnecting: " + reason);
        if (SystemClock.elapsedRealtime() >= reconnectDeadline) {
            return;
        }
        loop.schedule(new Runnable() {
            @Override
            public void run() {
                startAttempt();
            }
        }, RoomProtocol.RECONNECT_RETRY_MS, TimeUnit.MILLISECONDS);
    }

    private void heartbeat() {
        if (closed || link == null || !welcomed) {
            return;
        }
        if (SystemClock.elapsedRealtime() - link.lastReceivedAt() > RoomProtocol.SILENCE_TIMEOUT_MS) {
            link.closeWithReason("the room host stopped responding");
        } else {
            link.send(RoomProtocol.packet(RoomProtocol.PING));
        }
    }

    private void finish(String reason) {
        if (closed) {
            return;
        }
        closed = true;
        cancelHandshakeTimers();
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        if (reconnectGiveUp != null) {
            reconnectGiveUp.cancel(false);
        }
        if (attempt != null) {
            attempt.cancel();
            attempt = null;
        }
        if (link != null) {
            link.cancel();
            link = null;
        }
        phase = RoomState.Phase.IDLE;
        state = RoomState.IDLE;
        listener.onClosed(reason);
        loop.shutdown();
    }

    private void publishState() {
        state = new RoomState(phase, false, title, endsAtElapsed, members, null);
        listener.onStateChanged(state);
    }

    private void cancelHandshakeTimers() {
        if (handshakeTimer != null) {
            handshakeTimer.cancel(false);
            handshakeTimer = null;
        }
        if (approvalTimer != null) {
            approvalTimer.cancel(false);
            approvalTimer = null;
        }
    }

    private boolean remember(String id) {
        if (id == null || id.length() == 0) {
            return true;
        }
        if (!seenIds.add(id)) {
            return false;
        }
        seenOrder.addLast(id);
        while (seenOrder.size() > MAX_SEEN_IDS) {
            seenIds.remove(seenOrder.removeFirst());
        }
        return true;
    }

    private void post(Runnable task) {
        try {
            loop.execute(task);
        } catch (RejectedExecutionException ignored) {
            // Already finished.
        }
    }

    private static void closeQuietly(BluetoothSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    /** Blocking RFCOMM connect on its own thread; cancel() closes the socket to abort it. */
    private final class ConnectAttempt extends Thread {
        private volatile BluetoothSocket socket;
        private volatile boolean cancelled = false;

        ConnectAttempt() {
            super("LittleChatterRoomConnect");
        }

        @Override
        public void run() {
            try {
                adapter.cancelDiscovery();
            } catch (Exception ignored) {
            }
            BluetoothSocket attemptSocket = null;
            try {
                attemptSocket = hostDevice.createInsecureRfcommSocketToServiceRecord(RoomProtocol.SERVICE_UUID);
                socket = attemptSocket;
                if (cancelled) {
                    throw new IOException("cancelled");
                }
                attemptSocket.connect();
                final BluetoothSocket connected = attemptSocket;
                if (cancelled) {
                    closeQuietly(connected);
                    return;
                }
                try {
                    loop.execute(new Runnable() {
                        @Override
                        public void run() {
                            onSocketConnected(connected);
                        }
                    });
                } catch (RejectedExecutionException finishedAlready) {
                    closeQuietly(connected);
                }
            } catch (final IOException error) {
                closeQuietly(attemptSocket);
                if (!cancelled) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            onConnectFailed(error.getMessage() == null ? "connection failed" : error.getMessage());
                        }
                    });
                }
            }
        }

        void cancel() {
            cancelled = true;
            closeQuietly(socket);
        }
    }
}
