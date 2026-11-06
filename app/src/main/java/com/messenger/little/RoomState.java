package com.messenger.little;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable snapshot of the current room, safe to hand to the UI thread. */
final class RoomState {
    enum Phase {
        /** No room. */
        IDLE,
        /** Member only: connecting to the host. */
        CONNECTING,
        /** Member only: the host is deciding whether to let this device in. */
        WAITING_APPROVAL,
        /** In the room. */
        ACTIVE,
        /** Member only: the link to the host dropped; trying to reconnect within the grace period. */
        RECONNECTING
    }

    /** A pending join request shown to the host. */
    static final class JoinRequest {
        final String id;
        final String nick;
        /** Bluetooth name as read by the host's own Bluetooth stack (may be unknown). */
        final String deviceName;
        /** Last four hex digits of the requester's Bluetooth address, e.g. "3F:A2". */
        final String addressTail;
        /** Phone model reported by the requester's app, e.g. "GT-I9500". */
        final String model;

        JoinRequest(String id, String nick, String deviceName, String addressTail, String model) {
            this.id = id;
            this.nick = nick;
            this.deviceName = deviceName;
            this.addressTail = addressTail;
            this.model = model;
        }
    }

    static final RoomState IDLE = new RoomState(Phase.IDLE, false, "", 0L, new ArrayList<String>(), null);

    final Phase phase;
    final boolean host;
    /** Room title, for example "Minsu's room". */
    final String title;
    /** {@link android.os.SystemClock#elapsedRealtime()} at which the room ends (0 = unknown). */
    final long endsAtElapsed;
    /** Display lines, for example "Alice (host)", "Bob (reconnecting)". */
    final List<String> members;
    /** Host only: the join request to show now, or null. */
    final JoinRequest pendingRequest;

    RoomState(Phase phase, boolean host, String title, long endsAtElapsed, List<String> members,
              JoinRequest pendingRequest) {
        this.phase = phase;
        this.host = host;
        this.title = title;
        this.endsAtElapsed = endsAtElapsed;
        this.members = Collections.unmodifiableList(new ArrayList<String>(members));
        this.pendingRequest = pendingRequest;
    }

    boolean inRoom() {
        return phase == Phase.ACTIVE || phase == Phase.RECONNECTING;
    }

    boolean joining() {
        return phase == Phase.CONNECTING || phase == Phase.WAITING_APPROVAL;
    }

    static String roomTitle(String hostNick) {
        return hostNick + "'s room";
    }
}
