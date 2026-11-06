package com.messenger.little;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Finds rooms among discovered Bluetooth devices.
 *
 * Each device is tried in turn with a short RFCOMM connection to the room service UUID.
 * Devices that are not hosting a room fail quickly (their service lookup finds nothing);
 * hosts answer a "hello {probe}" with a "room" description and hang up. Devices are probed
 * one at a time, because parallel connects are unreliable on Android 4.4.
 */
final class RoomScanner {
    interface Callback {
        /** Called on the scanner thread. */
        void onRoomFound(FoundRoom room);

        /** Called on the scanner thread when every device has been tried (or after cancel). */
        void onScanFinished(int roomsFound, boolean cancelled);
    }

    static final class FoundRoom {
        final BluetoothDevice device;
        final String deviceName;
        final String hostNick;
        final int people;
        final int max;
        final long endsAtElapsed;

        FoundRoom(BluetoothDevice device, String deviceName, String hostNick, int people, int max,
                  long endsAtElapsed) {
            this.device = device;
            this.deviceName = deviceName;
            this.hostNick = hostNick;
            this.people = people;
            this.max = max;
            this.endsAtElapsed = endsAtElapsed;
        }

        @Override
        public String toString() {
            long minutes = Math.max(0L, (endsAtElapsed - SystemClock.elapsedRealtime()) / 60000L);
            return RoomState.roomTitle(hostNick) + "  ·  " + people + "/" + max + " people  ·  "
                    + minutes + " min left\n"
                    + deviceName + "  (" + RoomProtocol.addressTail(device.getAddress()) + ")";
        }
    }

    /** Per-device limit for connecting and reading the room description. */
    private static final long PROBE_DEVICE_TIMEOUT_MS = 8000L;

    private final BluetoothAdapter adapter;
    private final Callback callback;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean cancelled = false;
    private volatile BluetoothSocket currentSocket;

    RoomScanner(BluetoothAdapter adapter, Callback callback) {
        this.adapter = adapter;
        this.callback = callback;
    }

    void start(final List<BluetoothDevice> candidates) {
        final List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>(candidates);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    adapter.cancelDiscovery();
                } catch (Exception ignored) {
                }
                int found = 0;
                for (BluetoothDevice device : devices) {
                    if (cancelled) {
                        break;
                    }
                    FoundRoom room = probe(device);
                    if (room != null && !cancelled) {
                        found++;
                        callback.onRoomFound(room);
                    }
                }
                watchdog.shutdownNow();
                callback.onScanFinished(found, cancelled);
            }
        }, "LittleChatterRoomScanner").start();
    }

    void cancel() {
        cancelled = true;
        closeQuietly(currentSocket);
    }

    private FoundRoom probe(BluetoothDevice device) {
        BluetoothSocket socket = null;
        ScheduledFuture<?> timeout = null;
        try {
            socket = device.createInsecureRfcommSocketToServiceRecord(RoomProtocol.SERVICE_UUID);
            currentSocket = socket;
            final BluetoothSocket guarded = socket;
            timeout = watchdog.schedule(new Runnable() {
                @Override
                public void run() {
                    closeQuietly(guarded);
                }
            }, PROBE_DEVICE_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            // Fails within a few seconds if the device is not hosting a room.
            socket.connect();

            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            byte[] hello = RoomProtocol.packet(RoomProtocol.HELLO, "probe", true)
                    .toString().getBytes(RoomProtocol.UTF_8);
            output.writeInt(hello.length);
            output.write(hello);
            output.flush();

            DataInputStream input = new DataInputStream(socket.getInputStream());
            int length = input.readInt();
            if (length <= 0 || length > RoomProtocol.MAX_FRAME_BYTES) {
                return null;
            }
            byte[] frame = new byte[length];
            input.readFully(frame);
            JSONObject reply = new JSONObject(new String(frame, RoomProtocol.UTF_8));
            if (!RoomProtocol.ROOM.equals(reply.optString("t"))) {
                return null;
            }
            String name = device.getName();
            return new FoundRoom(device,
                    name == null || name.trim().length() == 0 ? "Unknown device" : name,
                    RoomProtocol.cleanNickname(reply.optString("hostNick")),
                    reply.optInt("people", 1),
                    reply.optInt("max", RoomProtocol.MAX_MEMBERS + 1),
                    SystemClock.elapsedRealtime() + reply.optLong("endsInMs", 0L));
        } catch (Exception notARoom) {
            return null;
        } finally {
            if (timeout != null) {
                timeout.cancel(false);
            }
            closeQuietly(socket);
            currentSocket = null;
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
}
