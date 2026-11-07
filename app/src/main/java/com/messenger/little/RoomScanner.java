package com.messenger.little;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * Checks one Bluetooth device at a time for a room.
 *
 * A short RFCOMM connection to the room service UUID is tried. Devices that are not hosting
 * a room fail quickly (their service lookup finds nothing); hosts answer a "hello {probe}"
 * with a "room" description and hang up. The caller pauses discovery during a probe,
 * because connecting while discovering is unreliable on Android 4.4.
 */
final class RoomScanner {
    interface ProbeCallback {
        /**
         * Called on the probe thread once per {@link #probe} call.
         *
         * @param room   the room, or null if the device is not hosting one
         * @param detail short result for the scan log, e.g. "room found", "no room"
         */
        void onProbeResult(BluetoothDevice device, FoundRoom room, String detail);
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

    /** Bluetooth "Computer / Tablet" device class (0x011C); Android 4.4 has no constant for it. */
    private static final int DEVICE_COMPUTER_TABLET = 0x011C;

    /** Per-device limit for connecting and reading the room description. */
    private static final long PROBE_DEVICE_TIMEOUT_MS = 8000L;

    /**
     * Only smartphones and tablets can host a room, so only they are probed. Headsets,
     * speakers, cars, laptops, desktops and devices with an unknown class are skipped.
     * Tablets that report themselves as handheld or tablet computers count as tablets.
     */
    static boolean isPhoneOrTablet(BluetoothClass bluetoothClass) {
        if (bluetoothClass == null) {
            return false;
        }
        if (bluetoothClass.getMajorDeviceClass() == BluetoothClass.Device.Major.PHONE) {
            return true;
        }
        int deviceClass = bluetoothClass.getDeviceClass();
        return deviceClass == BluetoothClass.Device.COMPUTER_HANDHELD_PC_PDA
                || deviceClass == BluetoothClass.Device.COMPUTER_PALM_SIZE_PC_PDA
                || deviceClass == DEVICE_COMPUTER_TABLET;
    }

    /** Device class for the scan log, e.g. "class 0x020C", or "unknown class". */
    static String describeClass(BluetoothClass bluetoothClass) {
        return bluetoothClass == null
                ? "unknown class"
                : String.format(Locale.US, "class 0x%04X", bluetoothClass.getDeviceClass());
    }

    private volatile boolean cancelled = false;
    private volatile BluetoothSocket currentSocket;

    /** Checks one device on a background thread and reports the result to the callback. */
    void probe(final BluetoothDevice device, final ProbeCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                probeNow(device, callback);
            }
        }, "LittleChatterRoomProbe").start();
    }

    /** Aborts the probe in progress; its result is still reported (as cancelled). */
    void cancel() {
        cancelled = true;
        closeQuietly(currentSocket);
    }

    private void probeNow(BluetoothDevice device, ProbeCallback callback) {
        final boolean[] timedOut = {false};
        BluetoothSocket socket = null;
        Thread watchdog = null;
        FoundRoom room = null;
        String detail;
        try {
            socket = device.createInsecureRfcommSocketToServiceRecord(RoomProtocol.SERVICE_UUID);
            currentSocket = socket;
            if (cancelled) {
                throw new IOException("cancelled");
            }
            final BluetoothSocket guarded = socket;
            watchdog = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(PROBE_DEVICE_TIMEOUT_MS);
                        timedOut[0] = true;
                        closeQuietly(guarded);
                    } catch (InterruptedException finishedInTime) {
                        // Probe ended before the time limit.
                    }
                }
            }, "LittleChatterProbeWatchdog");
            watchdog.start();

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
                throw new IOException("bad reply");
            }
            byte[] frame = new byte[length];
            input.readFully(frame);
            JSONObject reply = new JSONObject(new String(frame, RoomProtocol.UTF_8));
            if (RoomProtocol.ROOM.equals(reply.optString("t"))) {
                String name = device.getName();
                room = new FoundRoom(device,
                        name == null || name.trim().length() == 0 ? "Unknown device" : name,
                        RoomProtocol.cleanNickname(reply.optString("hostNick")),
                        reply.optInt("people", 1),
                        reply.optInt("max", RoomProtocol.MAX_MEMBERS + 1),
                        SystemClock.elapsedRealtime() + reply.optLong("endsInMs", 0L));
                detail = "room found";
            } else if (RoomProtocol.REJECT.equals(reply.optString("t"))) {
                detail = "no room (" + reply.optString("reason", "rejected") + ")";
            } else {
                detail = "no room (unexpected reply)";
            }
        } catch (Exception error) {
            if (cancelled) {
                detail = "cancelled";
            } else if (timedOut[0]) {
                detail = "no answer within " + (PROBE_DEVICE_TIMEOUT_MS / 1000) + " s";
            } else {
                String message = error.getMessage();
                // A device that does not run a room fails the service lookup.
                detail = "no room" + (message == null ? "" : " (" + message + ")");
            }
        } finally {
            if (watchdog != null) {
                watchdog.interrupt();
            }
            closeQuietly(socket);
            currentSocket = null;
        }
        callback.onProbeResult(device, room, detail);
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
