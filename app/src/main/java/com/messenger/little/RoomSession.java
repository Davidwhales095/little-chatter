package com.messenger.little;

/** A room this device is hosting ({@link RoomHost}) or has joined ({@link RoomMember}). */
interface RoomSession {
    /** Events from a room. Called on the room's own background thread. */
    interface Listener {
        void onStatus(String message);

        void onStateChanged(RoomState state);

        void onChat(String sender, String text, boolean mine);

        /** A system line shown in the chat, for example "Bob joined." */
        void onNotice(String text);

        /** The room is gone (time up, someone left, reconnect failed, or join failed). Final event. */
        void onClosed(String reason);
    }

    void start();

    RoomState getState();

    /** @return false if the message cannot be sent right now (the caller keeps the text). */
    boolean sendChat(String text);

    /** Host only: accept or decline a pending join request. Members ignore it. */
    void answerJoinRequest(String requestId, boolean accept);

    /** Leaves on purpose. By the room rules this deletes the room for everyone. */
    void leave();
}
