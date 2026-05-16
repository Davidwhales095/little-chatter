package com.messenger.little;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MainActivity extends Activity implements BluetoothClassicMeshService.Listener {
    private static final int REQUEST_ENABLE_BLUETOOTH = 3001;
    private static final int REQUEST_RUNTIME_PERMISSIONS = 3002;
    private static final int DISCOVERABLE_SECONDS = 300;
    private static final long BLE_SCAN_MILLIS = 10000L;

    private final Handler handler = new Handler();
    private final Map<String, DeviceRow> devicesByKey = new HashMap<String, DeviceRow>();
    private final List<DeviceRow> devices = new ArrayList<DeviceRow>();

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothClassicMeshService meshService;
    private ArrayAdapter<DeviceRow> deviceAdapter;
    private DeviceRow selectedDevice;
    private boolean bleScanning = false;
    private int connectedPeerCount = 0;

    private LinearLayout scanScreen;
    private LinearLayout chatScreen;
    private TextView statusText;
    private TextView peerText;
    private TextView localDeviceText;
    private LinearLayout messagesContainer;
    private ScrollView messagesScroll;
    private EditText nicknameInput;
    private EditText messageInput;

    private final BroadcastReceiver classicDiscoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                setStatus("Classic discovery started.");
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                setStatus("Classic discovery finished.");
            } else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                addDevice(device, "classic", rssi);
            }
        }
    };

    private final BluetoothAdapter.LeScanCallback leScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(final BluetoothDevice device, final int rssi, byte[] scanRecord) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    addDevice(device, "ble-scan", rssi);
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        meshService = new BluetoothClassicMeshService(bluetoothAdapter, this);

        setContentView(buildContentView());
        registerClassicDiscoveryReceiver();
        refreshLocalDeviceInfo();
        refreshPairedDevices();
    }

    private View buildContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(10));
        root.setBackgroundColor(Color.rgb(244, 247, 246));

        TextView title = new TextView(this);
        title.setText("little chatter");
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(24, 35, 34));
        root.addView(title);

        statusText = new TextView(this);
        statusText.setText("Bluetooth Classic transport for Android 4.4/API 19.");
        statusText.setTextSize(13f);
        statusText.setTextColor(Color.rgb(64, 78, 76));
        statusText.setPadding(0, dp(6), 0, dp(4));
        root.addView(statusText);

        peerText = new TextView(this);
        peerText.setText("Peers: 0");
        peerText.setTextSize(13f);
        peerText.setTextColor(Color.rgb(64, 78, 76));
        root.addView(peerText);

        scanScreen = buildScanScreen();
        chatScreen = buildChatScreen();

        root.addView(scanScreen, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));
        root.addView(chatScreen, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        resetChatWindow();
        showScanScreen();
        return root;
    }

    private LinearLayout buildScanScreen() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(0, dp(10), 0, 0);

        TextView screenTitle = screenTitle("Bluetooth Scan");
        screen.addView(screenTitle);

        nicknameInput = new EditText(this);
        nicknameInput.setHint("Nickname");
        nicknameInput.setSingleLine(true);
        nicknameInput.setText(defaultNickname());
        screen.addView(nicknameInput, matchWrap());

        localDeviceText = new TextView(this);
        localDeviceText.setTextSize(13f);
        localDeviceText.setTextColor(Color.rgb(64, 78, 76));
        localDeviceText.setPadding(0, dp(4), 0, dp(4));
        screen.addView(localDeviceText);

        screen.addView(buttonRow(
                button("Enable", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        requestEnableBluetooth();
                    }
                }),
                button("Listen", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startListening();
                    }
                }),
                button("Discoverable", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        requestDiscoverable();
                    }
                })
        ));

        screen.addView(buttonRow(
                button("Scan Classic", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startClassicDiscovery();
                    }
                }),
                button("BLE Scan", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleBleScan();
                    }
                }),
                button("Connect", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        connectSelectedDevice();
                    }
                }),
                button("Stop", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        stopEverything();
                    }
                })
        ));

        deviceAdapter = new ArrayAdapter<DeviceRow>(this, android.R.layout.simple_list_item_activated_1, devices);
        ListView deviceList = new ListView(this);
        deviceList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        deviceList.setAdapter(deviceAdapter);
        deviceList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                selectedDevice = devices.get(position);
                setStatus("Selected " + selectedDevice);
            }
        });
        screen.addView(deviceList, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        TextView hint = new TextView(this);
        hint.setText("Select a device, then Connect. BLE Scan only helps discover BLE advertisers; chat uses Classic.");
        hint.setTextSize(12f);
        hint.setTextColor(Color.rgb(80, 91, 89));
        hint.setPadding(0, dp(6), 0, 0);
        screen.addView(hint);

        return screen;
    }

    private LinearLayout buildChatScreen() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(0, dp(10), 0, 0);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView screenTitle = screenTitle("Chat");
        header.addView(screenTitle, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));
        header.addView(button("Disconnect", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopEverything();
            }
        }));
        screen.addView(header);

        messagesContainer = new LinearLayout(this);
        messagesContainer.setOrientation(LinearLayout.VERTICAL);

        messagesScroll = new ScrollView(this);
        messagesScroll.setBackgroundColor(Color.WHITE);
        messagesScroll.addView(messagesContainer);
        LinearLayout.LayoutParams messagesParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        messagesParams.setMargins(0, dp(8), 0, dp(8));
        screen.addView(messagesScroll, messagesParams);

        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.CENTER_VERTICAL);

        messageInput = new EditText(this);
        messageInput.setHint("Message");
        messageInput.setMinLines(1);
        messageInput.setMaxLines(3);
        composer.addView(messageInput, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));
        composer.addView(button("Send", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendMessage();
            }
        }));
        screen.addView(composer);

        return screen;
    }

    private void registerClassicDiscoveryReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        registerReceiver(classicDiscoveryReceiver, filter);
    }

    private void requestEnableBluetooth() {
        if (bluetoothAdapter == null) {
            setStatus("Bluetooth is not available on this device.");
            return;
        }
        if (bluetoothAdapter.isEnabled()) {
            setStatus("Bluetooth is already enabled.");
            refreshLocalDeviceInfo();
            refreshPairedDevices();
            return;
        }
        startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH);
    }

    private void startListening() {
        if (!ensureBluetoothReady() || !ensureRuntimePermissions()) {
            return;
        }
        meshService.startListening();
        refreshPairedDevices();
    }

    private void requestDiscoverable() {
        if (!ensureBluetoothReady() || !ensureRuntimePermissions()) {
            return;
        }
        Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS);
        startActivity(intent);
    }

    private void startClassicDiscovery() {
        if (!ensureBluetoothReady() || !ensureRuntimePermissions()) {
            return;
        }
        refreshPairedDevices();
        try {
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
            boolean started = bluetoothAdapter.startDiscovery();
            setStatus(started ? "Starting Classic discovery..." : "Classic discovery could not start.");
        } catch (SecurityException error) {
            setStatus("Bluetooth permission denied: " + error.getMessage());
        }
    }

    private void toggleBleScan() {
        if (bleScanning) {
            stopBleScan();
        } else {
            startBleScan();
        }
    }

    @SuppressWarnings("deprecation")
    private void startBleScan() {
        if (!ensureBluetoothReady() || !ensureRuntimePermissions()) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
            setStatus("BLE scan requires Android 4.3/API 18 or newer.");
            return;
        }

        try {
            bleScanning = bluetoothAdapter.startLeScan(leScanCallback);
            setStatus(bleScanning ? "BLE scan started for 10 seconds." : "BLE scan could not start.");
            if (bleScanning) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        stopBleScan();
                    }
                }, BLE_SCAN_MILLIS);
            }
        } catch (SecurityException error) {
            setStatus("BLE scan permission denied: " + error.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private void stopBleScan() {
        stopBleScanInternal(true);
    }

    @SuppressWarnings("deprecation")
    private void stopBleScanSilently() {
        stopBleScanInternal(false);
    }

    @SuppressWarnings("deprecation")
    private void stopBleScanInternal(boolean announce) {
        if (!bleScanning || bluetoothAdapter == null) {
            return;
        }
        try {
            bluetoothAdapter.stopLeScan(leScanCallback);
        } catch (Exception ignored) {
        }
        bleScanning = false;
        if (announce) {
            setStatus("BLE scan stopped.");
        }
    }

    private void connectSelectedDevice() {
        if (!ensureBluetoothReady() || !ensureRuntimePermissions()) {
            return;
        }
        if (connectedPeerCount > 0) {
            setStatus("Already connected. Disconnect before connecting to another device.");
            showChatScreen();
            return;
        }
        if (selectedDevice == null) {
            setStatus("Select a paired or discovered device first.");
            return;
        }
        meshService.connect(selectedDevice.device);
    }

    private void stopEverything() {
        stopBleScan();
        try {
            if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
        } catch (Exception ignored) {
        }
        meshService.stopAll();
    }

    private void sendMessage() {
        String text = messageInput.getText().toString().trim();
        if (text.length() == 0) {
            setStatus("Type a message first.");
            return;
        }

        String nickname = nicknameInput.getText().toString().trim();
        if (nickname.length() == 0) {
            nickname = defaultNickname();
        }
        meshService.sendLocalMessage(nickname, text);
        messageInput.setText("");
    }

    private boolean ensureBluetoothReady() {
        if (bluetoothAdapter == null) {
            setStatus("Bluetooth is not available on this device.");
            return false;
        }
        if (!bluetoothAdapter.isEnabled()) {
            requestEnableBluetooth();
            return false;
        }
        return true;
    }

    private boolean ensureRuntimePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }

        List<String> missing = new ArrayList<String>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }

        if (missing.isEmpty()) {
            return true;
        }

        requestPermissions(missing.toArray(new String[missing.size()]), REQUEST_RUNTIME_PERMISSIONS);
        return false;
    }

    private void refreshLocalDeviceInfo() {
        if (bluetoothAdapter == null) {
            localDeviceText.setText("Local: no Bluetooth adapter");
            return;
        }
        try {
            String name = bluetoothAdapter.getName();
            String address = bluetoothAdapter.getAddress();
            localDeviceText.setText("Local: " + safeLabel(name, "This device") + " / " + address);
        } catch (SecurityException error) {
            localDeviceText.setText("Local: Bluetooth permission required");
        }
    }

    private void refreshPairedDevices() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            return;
        }
        try {
            for (BluetoothDevice device : bluetoothAdapter.getBondedDevices()) {
                addDevice(device, "paired", Short.MIN_VALUE);
            }
        } catch (SecurityException error) {
            setStatus("Cannot read paired devices: " + error.getMessage());
        }
    }

    private void addDevice(BluetoothDevice device, String source, int rssi) {
        if (device == null) {
            return;
        }

        String key = device.getAddress() + ":" + source;
        DeviceRow old = devicesByKey.get(key);
        if (old != null) {
            old.rssi = rssi;
            deviceAdapter.notifyDataSetChanged();
            return;
        }

        DeviceRow row = new DeviceRow(device, source, rssi);
        devicesByKey.put(key, row);
        devices.add(row);
        if (deviceAdapter != null) {
            deviceAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public void onStatus(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                setStatus(message);
            }
        });
    }

    @Override
    public void onPeerCountChanged(final int peerCount) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                connectedPeerCount = peerCount;
                peerText.setText("Peers: " + peerCount);
                if (peerCount > 0) {
                    showChatScreen();
                } else {
                    showScanScreen();
                }
            }
        });
    }

    @Override
    public void onMessage(final String sender, final String text, final boolean mine, final int hops) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                appendChatMessage(sender, text, mine, hops);
            }
        });
    }

    private void setStatus(String message) {
        statusText.setText(message);
    }

    private void appendSystemMessage(String message) {
        TextView row = messageRow("[system] " + message, Gravity.CENTER_HORIZONTAL, Color.rgb(80, 91, 89));
        messagesContainer.addView(row);
    }

    private void appendChatMessage(String sender, String message, boolean mine, int hops) {
        String prefix = mine ? "me" : sender;
        String suffix = hops > 0 ? "  hops=" + hops : "";
        int gravity = mine ? Gravity.RIGHT : Gravity.LEFT;
        int color = mine ? Color.rgb(0, 105, 92) : Color.rgb(34, 46, 44);
        TextView row = messageRow(prefix + ": " + message + suffix, gravity, color);
        messagesContainer.addView(row);
        messagesScroll.post(new Runnable() {
            @Override
            public void run() {
                messagesScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void showScanScreen() {
        if (scanScreen == null || chatScreen == null) {
            return;
        }
        scanScreen.setVisibility(View.VISIBLE);
        chatScreen.setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        resetChatWindow();
        refreshLocalDeviceInfo();
        refreshPairedDevices();
    }

    private void showChatScreen() {
        if (scanScreen == null || chatScreen == null) {
            return;
        }
        scanScreen.setVisibility(View.GONE);
        chatScreen.setVisibility(View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        stopBleScanSilently();
        try {
            if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
        } catch (Exception ignored) {
        }
    }

    private void resetChatWindow() {
        if (messagesContainer != null) {
            messagesContainer.removeAllViews();
            appendSystemMessage("Connected. Messages in this chat session will appear here.");
        }
        if (messageInput != null) {
            messageInput.setText("");
        }
    }

    private TextView messageRow(String text, int gravity, int color) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setTextSize(15f);
        row.setTextColor(color);
        row.setGravity(gravity);
        row.setPadding(dp(10), dp(6), dp(10), dp(6));
        return row;
    }

    private TextView screenTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextSize(18f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(24, 35, 34));
        title.setPadding(0, 0, 0, dp(6));
        return title;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12f);
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout buttonRow(Button... buttons) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, 0);

        for (Button button : buttons) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
            );
            params.setMargins(dp(2), 0, dp(2), 0);
            row.addView(button, params);
        }
        return row;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private String defaultNickname() {
        return "little-" + Build.MODEL;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safeLabel(String value, String fallback) {
        return value == null || value.trim().length() == 0 ? fallback : value;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            refreshLocalDeviceInfo();
            refreshPairedDevices();
            setStatus(resultCode == RESULT_OK ? "Bluetooth enabled." : "Bluetooth was not enabled.");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RUNTIME_PERMISSIONS) {
            return;
        }

        boolean allGranted = true;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        setStatus(allGranted ? "Permissions granted." : "Bluetooth/location permissions are required.");
        if (allGranted) {
            refreshLocalDeviceInfo();
            refreshPairedDevices();
        }
    }

    @Override
    protected void onDestroy() {
        stopEverything();
        try {
            unregisterReceiver(classicDiscoveryReceiver);
        } catch (Exception ignored) {
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onDestroy();
    }

    private static final class DeviceRow {
        final BluetoothDevice device;
        final String source;
        int rssi;

        DeviceRow(BluetoothDevice device, String source, int rssi) {
            this.device = device;
            this.source = source;
            this.rssi = rssi;
        }

        @Override
        public String toString() {
            String name;
            try {
                name = device.getName();
            } catch (SecurityException ignored) {
                name = null;
            }
            String label = safeLabel(name, "Unknown device");
            String signal = rssi == Short.MIN_VALUE ? "" : "  RSSI " + rssi;
            return label + "\n" + device.getAddress() + "  [" + source + "]" + signal;
        }
    }
}
