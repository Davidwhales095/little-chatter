package com.messenger.little;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.UUID;

final class BluetoothClassicMeshService {
    interface Listener {
        void onStatus(String message);

        void onPeerCountChanged(int peerCount);

        void onMessage(String sender, String text, boolean mine, int hops);
    }

    private static final String SERVICE_NAME = "LittleChatter";
    private static final UUID SERVICE_UUID = UUID.fromString("86f73d6d-1a5a-4cf7-9274-70d223edda1f");
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final int MAX_FRAME_BYTES = 64 * 1024;
    private static final int MAX_HOPS = 3;
    private static final int MAX_SEEN_IDS = 512;
    private static final int MAX_CONNECTIONS = 1;

    private final BluetoothAdapter adapter;
    private final Listener listener;
    private final Map<String, ConnectedThread> connections = new HashMap<String, ConnectedThread>();
    private final LinkedList<String> seenOrder = new LinkedList<String>();
    private final Map<String, Boolean> seenIds = new HashMap<String, Boolean>();

    private AcceptThread acceptThread;
    private boolean connectionInProgress = false;

    BluetoothClassicMeshService(BluetoothAdapter adapter, Listener listener) {
        this.adapter = adapter;
        this.listener = listener;
    }

    synchronized void startListening() {
        if (adapter == null) {
            notifyStatus("Bluetooth is not available on this device.");
            return;
        }
        if (acceptThread != null) {
            notifyStatus("Already listening for Bluetooth Classic peers.");
            return;
        }

        acceptThread = new AcceptThread();
        acceptThread.start();
        notifyStatus("Listening for incoming Bluetooth Classic connections.");
    }

    void connect(BluetoothDevice device) {
        if (device == null) {
            notifyStatus("Select a device first.");
            return;
        }

        synchronized (this) {
            if (connections.size() >= MAX_CONNECTIONS) {
                notifyStatus("Already connected. Disconnect before connecting to another device.");
                return;
            }
            if (connectionInProgress) {
                notifyStatus("A connection attempt is already in progress.");
                return;
            }
            connectionInProgress = true;
        }

        new ConnectThread(device).start();
    }

    void sendLocalMessage(String sender, String text) {
        LegacyChatPacket packet = new LegacyChatPacket(
                UUID.randomUUID().toString(),
                sender,
                text,
                System.currentTimeMillis(),
                0
        );
        remember(packet.id);
        listener.onMessage(packet.sender, packet.text, true, packet.hops);
        broadcast(packet, null);
    }

    synchronized void stopAll() {
        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
        }

        connectionInProgress = false;
        for (ConnectedThread connection : new ArrayList<ConnectedThread>(connections.values())) {
            connection.cancel();
        }
        connections.clear();
        notifyPeerCount();
        notifyStatus("All Bluetooth Classic connections stopped.");
    }

    private void connected(BluetoothSocket socket) {
        BluetoothDevice device = socket.getRemoteDevice();
        ConnectedThread connection;

        synchronized (this) {
            if (connections.size() >= MAX_CONNECTIONS) {
                closeQuietly(socket);
                notifyStatus("Rejected connection from " + safeName(device) + " because a chat is already active.");
                return;
            }
            connection = new ConnectedThread(socket);
            connections.put(device.getAddress(), connection);
        }

        connection.start();

        notifyStatus("Connected to " + safeName(device) + " (" + device.getAddress() + ").");
        notifyPeerCount();
    }

    private void onConnectionClosed(ConnectedThread thread, String reason) {
        synchronized (this) {
            ConnectedThread current = connections.get(thread.address);
            if (current == thread) {
                connections.remove(thread.address);
            }
        }
        notifyPeerCount();
        notifyStatus(reason);
    }

    private void handleIncomingPacket(ConnectedThread source, LegacyChatPacket packet) {
        if (!remember(packet.id)) {
            return;
        }

        listener.onMessage(packet.sender, packet.text, false, packet.hops);

        if (packet.hops < MAX_HOPS) {
            broadcast(packet.withHopIncremented(), source.address);
        }
    }

    private void broadcast(LegacyChatPacket packet, String exceptAddress) {
        ArrayList<ConnectedThread> targets;
        synchronized (this) {
            targets = new ArrayList<ConnectedThread>(connections.values());
        }

        if (targets.isEmpty()) {
            notifyStatus("No connected peers. Message is shown locally only.");
            return;
        }

        for (ConnectedThread target : targets) {
            if (exceptAddress == null || !exceptAddress.equals(target.address)) {
                target.write(packet);
            }
        }
    }

    private synchronized boolean remember(String packetId) {
        if (seenIds.containsKey(packetId)) {
            return false;
        }
        seenIds.put(packetId, Boolean.TRUE);
        seenOrder.addLast(packetId);
        while (seenOrder.size() > MAX_SEEN_IDS) {
            String oldest = seenOrder.removeFirst();
            seenIds.remove(oldest);
        }
        return true;
    }

    private void notifyStatus(String message) {
        listener.onStatus(message);
    }

    private void notifyPeerCount() {
        int size;
        synchronized (this) {
            size = connections.size();
        }
        listener.onPeerCountChanged(size);
    }

    private static void closeQuietly(BluetoothSocket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null || name.trim().length() == 0 ? "Unknown device" : name;
        } catch (SecurityException ignored) {
            return "Bluetooth device";
        }
    }

    private final class AcceptThread extends Thread {
        private BluetoothServerSocket serverSocket;
        private volatile boolean running = true;

        AcceptThread() {
            super("LittleChatterAcceptThread");
        }

        @Override
        public void run() {
            try {
                serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID);
            } catch (IOException error) {
                notifyStatus("Unable to listen: " + error.getMessage());
                synchronized (BluetoothClassicMeshService.this) {
                    acceptThread = null;
                }
                return;
            }

            while (running) {
                try {
                    BluetoothSocket socket = serverSocket.accept();
                    if (socket != null) {
                        connected(socket);
                    }
                } catch (IOException error) {
                    if (running) {
                        notifyStatus("Accept failed: " + error.getMessage());
                    }
                    break;
                }
            }

            synchronized (BluetoothClassicMeshService.this) {
                if (acceptThread == this) {
                    acceptThread = null;
                }
            }
        }

        void cancel() {
            running = false;
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private final class ConnectThread extends Thread {
        private final BluetoothDevice device;

        ConnectThread(BluetoothDevice device) {
            super("LittleChatterConnectThread");
            this.device = device;
        }

        @Override
        public void run() {
            notifyStatus("Connecting to " + safeName(device) + "...");
            try {
                adapter.cancelDiscovery();
            } catch (Exception ignored) {
            }

            BluetoothSocket socket = null;
            try {
                socket = device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID);
                socket.connect();
                connected(socket);
            } catch (IOException error) {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
                notifyStatus("Connection failed: " + error.getMessage());
            } finally {
                synchronized (BluetoothClassicMeshService.this) {
                    connectionInProgress = false;
                }
            }
        }
    }

    private final class ConnectedThread extends Thread {
        private final BluetoothSocket socket;
        private final String address;
        private final DataInputStream input;
        private final DataOutputStream output;
        private volatile boolean running = true;

        ConnectedThread(BluetoothSocket socket) {
            super("LittleChatterConnectedThread");
            this.socket = socket;
            this.address = socket.getRemoteDevice().getAddress();

            DataInputStream tempInput = null;
            DataOutputStream tempOutput = null;
            try {
                tempInput = new DataInputStream(socket.getInputStream());
                tempOutput = new DataOutputStream(socket.getOutputStream());
            } catch (IOException error) {
                notifyStatus("Stream setup failed: " + error.getMessage());
            }
            input = tempInput;
            output = tempOutput;
        }

        @Override
        public void run() {
            if (input == null || output == null) {
                onConnectionClosed(this, "Connection closed: stream setup failed.");
                cancel();
                return;
            }

            while (running) {
                try {
                    int frameLength = input.readInt();
                    if (frameLength <= 0 || frameLength > MAX_FRAME_BYTES) {
                        throw new IOException("Invalid frame length: " + frameLength);
                    }

                    byte[] frame = new byte[frameLength];
                    input.readFully(frame);
                    LegacyChatPacket packet = LegacyChatPacket.fromJson(new String(frame, UTF_8));
                    handleIncomingPacket(this, packet);
                } catch (Exception error) {
                    if (running) {
                        onConnectionClosed(this, "Connection closed: " + error.getMessage());
                    }
                    cancel();
                    break;
                }
            }
        }

        synchronized void write(LegacyChatPacket packet) {
            if (!running || output == null) {
                return;
            }
            try {
                byte[] frame = packet.toJson().getBytes(UTF_8);
                output.writeInt(frame.length);
                output.write(frame);
                output.flush();
            } catch (Exception error) {
                onConnectionClosed(this, "Write failed: " + error.getMessage());
                cancel();
            }
        }

        void cancel() {
            running = false;
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
