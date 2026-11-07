package com.messenger.little;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Owns the current room so it survives while the app is minimized or the screen is off.
 *
 * - Foreground service (ongoing notification) for as long as a room exists, so Android 4.4
 *   does not kill the process in the background.
 * - Partial wake lock for as long as a room exists, so sockets and room timers (time limit,
 *   reconnect grace period, heartbeats) keep running with the screen off.
 * - The room's messages live in memory only and are cleared when the room ends.
 *
 * All UiListener callbacks are delivered on the main thread.
 */
public final class ChatService extends Service implements RoomSession.Listener {
    interface UiListener {
        void onStatus(String message);

        void onRoomStateChanged(RoomState state);

        void onChatEntry(ChatEntry entry);

        void onRoomClosed(String reason);
    }

    static final class ChatEntry {
        final String sender;
        final String text;
        final boolean mine;
        /** True for system lines such as "Bob joined." */
        final boolean notice;

        ChatEntry(String sender, String text, boolean mine, boolean notice) {
            this.sender = sender;
            this.text = text;
            this.mine = mine;
            this.notice = notice;
        }
    }

    final class LocalBinder extends Binder {
        ChatService getService() {
            return ChatService.this;
        }
    }

    private static final int NOTIFICATION_ID = 1001;
    private static final int CLOSED_NOTIFICATION_ID = 1002;
    private static final int REQUEST_NOTIFICATION_ID = 1003;
    private static final int MAX_HISTORY = 500;

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<ChatEntry> history = new ArrayList<ChatEntry>();

    private RoomSession session;
    private RoomState state = RoomState.IDLE;
    private UiListener uiListener;
    private PowerManager.WakeLock wakeLock;
    private boolean foreground = false;
    private boolean uiVisible = false;
    private boolean bound = false;
    private String lastStatus = "Create a room, or join one nearby.";
    private String lastIncomingPreview;
    /** Why the last room closed, kept until the UI has shown it. */
    private String unseenClosedReason;
    /** The join request the host was last notified about, so each request alerts once. */
    private String notifiedRequestId;

    @Override
    public void onCreate() {
        super.onCreate();
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LittleChatter:room");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // If the process dies, the Bluetooth sockets die with it; do not restart empty.
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        bound = true;
        return binder;
    }

    @Override
    public void onRebind(Intent intent) {
        bound = true;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        bound = false;
        uiListener = null;
        uiVisible = false;
        stopIfIdle();
        return true;
    }

    @Override
    public void onDestroy() {
        if (session != null) {
            session.leave();
            session = null;
        }
        releaseWakeLock();
        stopForeground(true);
        foreground = false;
        super.onDestroy();
    }

    // ---- API for MainActivity (main thread) -------------------------------------------------

    void setUiListener(UiListener listener) {
        uiListener = listener;
    }

    void setUiVisible(boolean visible) {
        uiVisible = visible;
        if (visible) {
            lastIncomingPreview = null;
            updateNotification();
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            manager.cancel(CLOSED_NOTIFICATION_ID);
            manager.cancel(REQUEST_NOTIFICATION_ID);
        }
    }

    boolean hasRoom() {
        return session != null;
    }

    RoomState getRoomState() {
        return state;
    }

    String getLastStatus() {
        return lastStatus;
    }

    List<ChatEntry> getHistory() {
        return Collections.unmodifiableList(new ArrayList<ChatEntry>(history));
    }

    /** Returns the reason the last room closed if the UI has not shown it yet, then forgets it. */
    String takeUnseenClosedReason() {
        String reason = unseenClosedReason;
        unseenClosedReason = null;
        return reason;
    }

    void createRoom(String nickname, int durationMinutes) {
        if (session != null) {
            setStatus("You are already in a room.");
            return;
        }
        startSession(new RoomHost(BluetoothAdapter.getDefaultAdapter(), this,
                nickname, durationMinutes * 60L * 1000L));
    }

    void joinRoom(BluetoothDevice hostDevice, String nickname) {
        if (session != null) {
            setStatus("You are already in a room.");
            return;
        }
        startSession(new RoomMember(BluetoothAdapter.getDefaultAdapter(), hostDevice, this, nickname));
    }

    /** Host only: accept or decline the join request with this id. */
    void answerJoinRequest(String requestId, boolean accept) {
        if (session != null) {
            session.answerJoinRequest(requestId, accept);
        }
    }

    boolean sendChat(String text) {
        return session != null && session.sendChat(text);
    }

    /** Leaves on purpose (deletes the room for everyone) or cancels a pending join. */
    void leaveRoom() {
        if (session != null) {
            session.leave();
        }
    }

    private void startSession(RoomSession newSession) {
        history.clear();
        lastIncomingPreview = null;
        unseenClosedReason = null;
        notifiedRequestId = null;
        session = newSession;
        state = newSession.getState();
        acquireWakeLock();
        goForeground();
        newSession.start();
    }

    // ---- RoomSession.Listener (room thread -> main thread) ------------------------------------

    @Override
    public void onStatus(final String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                setStatus(message);
            }
        });
    }

    @Override
    public void onStateChanged(final RoomState newState) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (session == null) {
                    return;
                }
                state = newState;
                updateNotification();
                updateRequestNotification(newState.pendingRequest);
                if (uiListener != null) {
                    uiListener.onRoomStateChanged(newState);
                }
            }
        });
    }

    @Override
    public void onChat(final String sender, final String text, final boolean mine) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (session == null) {
                    return;
                }
                if (!mine && !uiVisible) {
                    lastIncomingPreview = sender + ": " + text;
                    updateNotification();
                }
                addEntry(new ChatEntry(sender, text, mine, false));
            }
        });
    }

    @Override
    public void onNotice(final String text) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (session == null) {
                    return;
                }
                if (!uiVisible) {
                    lastIncomingPreview = text;
                    updateNotification();
                }
                addEntry(new ChatEntry("", text, false, true));
            }
        });
    }

    @Override
    public void onClosed(final String reason) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                session = null;
                state = RoomState.IDLE;
                history.clear();
                lastIncomingPreview = null;
                releaseWakeLock();
                if (foreground) {
                    stopForeground(true);
                    foreground = false;
                }
                updateRequestNotification(null);
                setStatus(reason);
                if (uiListener != null && uiVisible) {
                    uiListener.onRoomClosed(reason);
                } else {
                    unseenClosedReason = reason;
                    showClosedNotification(reason);
                    if (uiListener != null) {
                        uiListener.onRoomStateChanged(RoomState.IDLE);
                    }
                }
                stopIfIdle();
            }
        });
    }

    // ---- Helpers (main thread) ----------------------------------------------------------------

    private void setStatus(String message) {
        lastStatus = message;
        if (uiListener != null) {
            uiListener.onStatus(message);
        }
    }

    private void addEntry(ChatEntry entry) {
        history.add(entry);
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
        if (uiListener != null) {
            uiListener.onChatEntry(entry);
        }
    }

    private void goForeground() {
        if (!foreground) {
            // Started (not only bound), so the service outlives the Activity.
            startService(new Intent(this, ChatService.class));
            startForeground(NOTIFICATION_ID, buildRoomNotification());
            foreground = true;
        }
    }

    private void stopIfIdle() {
        if (session == null && !bound) {
            stopSelf();
        }
    }

    private void updateNotification() {
        if (!foreground) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildRoomNotification());
    }

    private Notification buildRoomNotification() {
        String text;
        if (lastIncomingPreview != null) {
            text = lastIncomingPreview;
        } else if (state.phase == RoomState.Phase.CONNECTING) {
            text = "Connecting to a room...";
        } else if (state.phase == RoomState.Phase.WAITING_APPROVAL) {
            text = "Waiting for the host to accept you...";
        } else if (state.phase == RoomState.Phase.RECONNECTING) {
            text = "Reconnecting to the room...";
        } else {
            text = state.title + " - " + state.members.size() + " people";
        }
        return new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("little chatter")
                .setContentText(text)
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    /** Alerts the host about a new join request while the app is not on screen. */
    private void updateRequestNotification(RoomState.JoinRequest request) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (request == null) {
            notifiedRequestId = null;
            manager.cancel(REQUEST_NOTIFICATION_ID);
            return;
        }
        if (uiVisible || request.id.equals(notifiedRequestId)) {
            return;
        }
        notifiedRequestId = request.id;
        Notification notification = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Join request: " + request.nick)
                .setContentText((request.deviceName == null ? "Unknown device" : request.deviceName)
                        + " (" + request.addressTail + ") - open to accept or decline")
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_LIGHTS)
                .build();
        manager.notify(REQUEST_NOTIFICATION_ID, notification);
    }

    private void showClosedNotification(String reason) {
        Notification notification = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("little chatter: room closed")
                .setContentText(reason)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                // Sound + light only: vibration would need the VIBRATE permission on 4.4.
                .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_LIGHTS)
                .build();
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(CLOSED_NOTIFICATION_ID, notification);
    }

    private PendingIntent openAppIntent() {
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, openApp, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void acquireWakeLock() {
        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
    }
}
