package com.messenger.little;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * One RFCOMM connection carrying length-prefixed JSON frames.
 *
 * Reading runs on its own thread; writing runs on a single background thread so callers
 * (including the UI thread) never block on the socket. {@link Callback#onClosed} is reported
 * at most once, and never after {@link #cancel()}.
 */
final class BluetoothLink {
    interface Callback {
        void onPacket(BluetoothLink link, JSONObject packet);

        void onClosed(BluetoothLink link, String reason);
    }

    /** Small pause so a final packet is flushed before the socket is closed. */
    private static final long CLOSE_AFTER_SEND_DELAY_MS = 300L;

    final String address;
    /** Bluetooth name of the remote device as known to this phone's Bluetooth stack, or null. */
    final String deviceName;
    /** Owner data (handshake state or member record), only touched by the owner's thread. */
    Object tag;

    private final BluetoothSocket socket;
    private final Callback callback;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final Object closeLock = new Object();
    private boolean closed = false;
    private volatile boolean running = true;
    private volatile long lastReceivedAt = SystemClock.elapsedRealtime();

    BluetoothLink(BluetoothSocket socket, Callback callback) throws IOException {
        this.socket = socket;
        this.callback = callback;
        BluetoothDevice device = socket.getRemoteDevice();
        this.address = device.getAddress();
        String name = device.getName();
        this.deviceName = name == null || name.trim().length() == 0 ? null : name;
        this.input = new DataInputStream(socket.getInputStream());
        this.output = new DataOutputStream(socket.getOutputStream());
    }

    void start() {
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "LittleChatterLinkReader");
        reader.start();
    }

    long lastReceivedAt() {
        return lastReceivedAt;
    }

    void send(final JSONObject packet) {
        submit(new Runnable() {
            @Override
            public void run() {
                writeNow(packet);
            }
        });
    }

    /** Sends a final packet (for example "closed" or "reject"), then closes without reporting. */
    void sendAndClose(final JSONObject packet) {
        boolean queued = submit(new Runnable() {
            @Override
            public void run() {
                writeNow(packet);
                SystemClock.sleep(CLOSE_AFTER_SEND_DELAY_MS);
                cancel();
            }
        });
        if (!queued) {
            cancel();
        }
    }

    /** Closes the link and reports it once to the callback. */
    void closeWithReason(String reason) {
        if (markClosed()) {
            shutdown();
            callback.onClosed(this, reason);
        }
    }

    /** Closes the link without reporting it. */
    void cancel() {
        markClosed();
        shutdown();
    }

    private void readLoop() {
        while (running) {
            try {
                int frameLength = input.readInt();
                if (frameLength <= 0 || frameLength > RoomProtocol.MAX_FRAME_BYTES) {
                    throw new IOException("Invalid frame length: " + frameLength);
                }
                byte[] frame = new byte[frameLength];
                input.readFully(frame);
                lastReceivedAt = SystemClock.elapsedRealtime();
                JSONObject packet = new JSONObject(new String(frame, RoomProtocol.UTF_8));
                callback.onPacket(this, packet);
            } catch (Exception error) {
                closeWithReason("Connection closed: " + error.getMessage());
                return;
            }
        }
    }

    private boolean submit(Runnable task) {
        if (!running) {
            return false;
        }
        try {
            writer.execute(task);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    private void writeNow(JSONObject packet) {
        if (!running) {
            return;
        }
        try {
            byte[] frame = packet.toString().getBytes(RoomProtocol.UTF_8);
            if (frame.length > RoomProtocol.MAX_FRAME_BYTES) {
                return;
            }
            output.writeInt(frame.length);
            output.write(frame);
            output.flush();
        } catch (Exception error) {
            closeWithReason("Write failed: " + error.getMessage());
        }
    }

    private boolean markClosed() {
        synchronized (closeLock) {
            if (closed) {
                return false;
            }
            closed = true;
            return true;
        }
    }

    private void shutdown() {
        running = false;
        writer.shutdown();
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
