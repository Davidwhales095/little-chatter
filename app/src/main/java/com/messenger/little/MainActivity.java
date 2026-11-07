package com.messenger.little;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MainActivity extends Activity implements ChatService.UiListener, RoomScanner.Callback {
    private static final int REQUEST_ENABLE_BLUETOOTH = 3001;
    /** Android 4.4 allows at most one hour of discoverability per request. */
    private static final int MAX_DISCOVERABLE_SECONDS = 3600;
    private static final int[] ROOM_MINUTES = {10, 30, 60};
    private static final int DEFAULT_ROOM_MINUTES_INDEX = 1;

    private final Handler handler = new Handler();
    /** Devices seen during the current "Find rooms" scan, by address. */
    private final Map<String, BluetoothDevice> discovered = new LinkedHashMap<String, BluetoothDevice>();
    /** Addresses of non-phone/tablet devices seen and skipped during the current scan. */
    private final Set<String> skipped = new HashSet<String>();
    private final List<RoomScanner.FoundRoom> rooms = new ArrayList<RoomScanner.FoundRoom>();

    private BluetoothAdapter bluetoothAdapter;
    private ChatService chatService;
    private boolean serviceBound = false;
    private boolean uiVisible = false;
    private RoomState shownState = RoomState.IDLE;
    private ArrayAdapter<RoomScanner.FoundRoom> roomAdapter;
    private boolean findingRooms = false;
    private RoomScanner scanner;
    private AlertDialog openDialog;
    private AlertDialog requestDialog;
    private String shownRequestId;
    /** The request the host just answered; ignored until the room confirms it is handled. */
    private String answeredRequestId;
    /** Set when the user leaves or cancels on purpose, so no "room is gone" dialog is shown. */
    private boolean closingByUser = false;

    private LinearLayout lobbyScreen;
    private LinearLayout waitingScreen;
    private LinearLayout roomScreen;
    private TextView statusText;
    private TextView localDeviceText;
    private EditText nicknameInput;
    private RadioGroup durationGroup;
    private Button findButton;
    private TextView waitingTitleText;
    private TextView waitingStateText;
    private TextView waitingDeviceText;
    private TextView roomTitleText;
    private TextView timeLeftText;
    private TextView membersText;
    private LinearLayout messagesContainer;
    private ScrollView messagesScroll;
    private EditText messageInput;
    private Button sendButton;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            chatService = ((ChatService.LocalBinder) binder).getService();
            chatService.setUiListener(MainActivity.this);
            chatService.setUiVisible(uiVisible);
            restoreFromService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            chatService = null;
        }
    };

    private final Runnable countdownTick = new Runnable() {
        @Override
        public void run() {
            updateTimeLeft();
            handler.postDelayed(this, 1000L);
        }
    };

    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && findingRooms) {
                    BluetoothClass bluetoothClass = intent.getParcelableExtra(BluetoothDevice.EXTRA_CLASS);
                    addCandidate(device, bluetoothClass != null ? bluetoothClass : device.getBluetoothClass());
                    setStatus("Looking for nearby phones and tablets... (" + discovered.size() + " found)");
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                if (findingRooms && scanner == null) {
                    checkDevicesForRooms();
                }
            }
        }
    };

    // ---- Lifecycle ------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!isSupportedAndroidVersion()) {
            setContentView(buildUnsupportedView());
            return;
        }

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        setContentView(buildContentView());
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(discoveryReceiver, filter);
        refreshLocalDeviceInfo();

        // The room lives in ChatService so it survives minimizing the app.
        serviceBound = bindService(new Intent(this, ChatService.class), serviceConnection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        uiVisible = true;
        if (chatService != null) {
            chatService.setUiVisible(true);
            showUnseenClosedReason();
        }
    }

    @Override
    protected void onStop() {
        uiVisible = false;
        if (chatService != null) {
            chatService.setUiVisible(false);
        }
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        // While in (or joining) a room, Back only minimizes; the room keeps running in ChatService.
        if (chatService != null && chatService.hasRoom()) {
            moveTaskToBack(true);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            refreshLocalDeviceInfo();
            setStatus(resultCode == RESULT_OK ? "Bluetooth enabled." : "Bluetooth was not enabled.");
        }
    }

    @Override
    protected void onDestroy() {
        if (!serviceBound) {
            // Unsupported Android version: nothing was started.
            super.onDestroy();
            return;
        }
        handler.removeCallbacks(countdownTick);
        dismissDialog();
        dismissRequestDialog();
        stopFindingRooms();
        try {
            unregisterReceiver(discoveryReceiver);
        } catch (Exception ignored) {
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (chatService != null) {
            // A room (if any) keeps running in the service; reopening the app shows it again.
            chatService.setUiListener(null);
        }
        unbindService(serviceConnection);
        serviceBound = false;
        chatService = null;
        super.onDestroy();
    }

    /** little chatter is maintained for Android 4.4.x (KitKat, API 19) only. */
    static boolean isSupportedAndroidVersion() {
        return Build.VERSION.SDK_INT == Build.VERSION_CODES.KITKAT;
    }

    // ---- ChatService.UiListener (main thread) ----------------------------------------------

    @Override
    public void onStatus(String message) {
        setStatus(message);
    }

    @Override
    public void onRoomStateChanged(RoomState state) {
        render(state, false);
    }

    @Override
    public void onChatEntry(ChatService.ChatEntry entry) {
        if (roomScreen.getVisibility() == View.VISIBLE) {
            appendEntry(entry);
        }
    }

    @Override
    public void onRoomClosed(String reason) {
        boolean wasInRoom = shownState.inRoom();
        render(RoomState.IDLE, false);
        if (closingByUser) {
            closingByUser = false;
            return;
        }
        showClosedDialog(wasInRoom ? "The room is gone" : "Could not join", reason);
    }

    // ---- RoomScanner.Callback (scanner thread) ----------------------------------------------

    @Override
    public void onRoomFound(final RoomScanner.FoundRoom room) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                rooms.add(room);
                roomAdapter.notifyDataSetChanged();
            }
        });
    }

    @Override
    public void onScanFinished(final int roomsFound, final boolean cancelled) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                scanner = null;
                findingRooms = false;
                findButton.setText("Find rooms");
                if (!cancelled) {
                    setStatus(roomsFound == 0
                            ? "No rooms found. Ask the host to tap Create and allow their phone to be visible."
                            : "Found " + roomsFound + (roomsFound == 1 ? " room" : " rooms") + ". Tap one to ask to join.");
                }
            }
        });
    }

    // ---- Actions --------------------------------------------------------------------------------

    private void createRoom() {
        if (!ensureBluetoothReady() || !ensureService()) {
            return;
        }
        if (chatService.hasRoom()) {
            setStatus("You are already in a room.");
            return;
        }
        int minutes = selectedRoomMinutes();
        stopFindingRooms();
        chatService.createRoom(nickname(), minutes);
        // Let people nearby find this phone for as long as the room lasts (up to an hour).
        Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, Math.min(minutes * 60, MAX_DISCOVERABLE_SECONDS));
        startActivity(intent);
    }

    private void toggleFindRooms() {
        if (findingRooms) {
            stopFindingRooms();
            setStatus("Stopped looking for rooms.");
            return;
        }
        if (!ensureBluetoothReady()) {
            return;
        }
        rooms.clear();
        roomAdapter.notifyDataSetChanged();
        discovered.clear();
        skipped.clear();
        // Paired phones and tablets are checked too, in case the host is no longer discoverable.
        for (BluetoothDevice device : bluetoothAdapter.getBondedDevices()) {
            addCandidate(device, device.getBluetoothClass());
        }
        findingRooms = true;
        findButton.setText("Stop");
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
        if (bluetoothAdapter.startDiscovery()) {
            setStatus("Looking for nearby devices (about 12 seconds)...");
        } else {
            checkDevicesForRooms();
        }
    }

    /** Second step of "Find rooms": ask every device found whether it is hosting a room. */
    private void checkDevicesForRooms() {
        if (discovered.isEmpty()) {
            onScanFinished(0, false);
            return;
        }
        setStatus("Checking " + discovered.size() + " phones/tablets for rooms"
                + (skipped.isEmpty() ? "" : " (skipped " + skipped.size() + " other devices)") + "...");
        scanner = new RoomScanner(bluetoothAdapter, this);
        scanner.start(new ArrayList<BluetoothDevice>(discovered.values()));
    }

    /** Keeps smartphones and tablets for the room check; other kinds of devices are skipped. */
    private void addCandidate(BluetoothDevice device, BluetoothClass bluetoothClass) {
        String address = device.getAddress();
        if (RoomScanner.isPhoneOrTablet(bluetoothClass)) {
            discovered.put(address, device);
            skipped.remove(address);
        } else if (!discovered.containsKey(address)) {
            skipped.add(address);
        }
    }

    private void stopFindingRooms() {
        findingRooms = false;
        if (findButton != null) {
            findButton.setText("Find rooms");
        }
        if (scanner != null) {
            scanner.cancel();
            scanner = null;
        }
        try {
            if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
        } catch (Exception ignored) {
        }
    }

    private void askToJoin(RoomScanner.FoundRoom room) {
        if (!ensureBluetoothReady() || !ensureService()) {
            return;
        }
        if (chatService.hasRoom()) {
            setStatus("You are already in a room.");
            return;
        }
        stopFindingRooms();
        chatService.joinRoom(room.device, nickname());
    }

    private void cancelJoin() {
        if (chatService != null && chatService.hasRoom()) {
            closingByUser = true;
            chatService.leaveRoom();
        }
    }

    private void confirmLeave() {
        if (chatService == null || !chatService.hasRoom()) {
            return;
        }
        dismissDialog();
        openDialog = new AlertDialog.Builder(this)
                .setTitle("Leave the room?")
                .setMessage("If you leave, the room is deleted for everyone in it.")
                .setPositiveButton("Leave", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (chatService != null && chatService.hasRoom()) {
                            closingByUser = true;
                            chatService.leaveRoom();
                        }
                    }
                })
                .setNegativeButton("Stay", null)
                .show();
    }

    private void sendMessage() {
        if (chatService == null) {
            return;
        }
        String text = messageInput.getText().toString().trim();
        if (text.length() == 0) {
            return;
        }
        if (chatService.sendChat(text)) {
            messageInput.setText("");
        }
    }

    private void requestEnableBluetooth() {
        if (bluetoothAdapter == null) {
            setStatus("Bluetooth is not available on this device.");
            return;
        }
        if (bluetoothAdapter.isEnabled()) {
            setStatus("Bluetooth is already enabled.");
            refreshLocalDeviceInfo();
            return;
        }
        startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH);
    }

    // ---- Rendering ------------------------------------------------------------------------------

    private void restoreFromService() {
        setStatus(chatService.getLastStatus());
        render(chatService.getRoomState(), true);
        if (uiVisible) {
            showUnseenClosedReason();
        }
    }

    private void render(RoomState state, boolean forceRebuild) {
        boolean wasInRoom = shownState.inRoom();
        shownState = state;
        if (state.inRoom()) {
            if (!wasInRoom || forceRebuild || roomScreen.getVisibility() != View.VISIBLE) {
                showScreen(roomScreen);
                rebuildMessages();
            }
            roomTitleText.setText(state.title + (state.host ? "  (you are the host)" : ""));
            membersText.setText("People (" + state.members.size() + "/" + (RoomProtocol.MAX_MEMBERS + 1)
                    + "): " + join(state.members));
            boolean canSend = state.phase == RoomState.Phase.ACTIVE;
            sendButton.setEnabled(canSend);
            messageInput.setEnabled(canSend);
            updateTimeLeft();
        } else if (state.joining()) {
            showScreen(waitingScreen);
            waitingTitleText.setText(state.title);
            waitingStateText.setText(state.phase == RoomState.Phase.WAITING_APPROVAL
                    ? "Waiting for the host to accept you (up to "
                    + (RoomProtocol.APPROVAL_TIMEOUT_MS / 1000) + " seconds)..."
                    : "Connecting to the host...");
        } else {
            if (wasInRoom || forceRebuild || lobbyScreen.getVisibility() != View.VISIBLE) {
                showScreen(lobbyScreen);
                messagesContainer.removeAllViews();
                messageInput.setText("");
                refreshLocalDeviceInfo();
            }
        }
        renderJoinRequest(state.host ? state.pendingRequest : null);
    }

    private void showScreen(LinearLayout screen) {
        lobbyScreen.setVisibility(screen == lobbyScreen ? View.VISIBLE : View.GONE);
        waitingScreen.setVisibility(screen == waitingScreen ? View.VISIBLE : View.GONE);
        roomScreen.setVisibility(screen == roomScreen ? View.VISIBLE : View.GONE);
        handler.removeCallbacks(countdownTick);
        if (screen == roomScreen) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            stopFindingRooms();
            handler.post(countdownTick);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /** Host only: shows the oldest pending join request as a dialog. */
    private void renderJoinRequest(final RoomState.JoinRequest request) {
        if (request == null || request.id.equals(answeredRequestId)) {
            dismissRequestDialog();
            return;
        }
        if (request.id.equals(shownRequestId) && requestDialog != null && requestDialog.isShowing()) {
            return;
        }
        dismissRequestDialog();
        shownRequestId = request.id;
        String deviceName = request.deviceName == null ? "(name not known yet)" : request.deviceName;
        requestDialog = new AlertDialog.Builder(this)
                .setTitle("Join request")
                .setMessage("Nickname: " + request.nick
                        + "\nDevice name: " + deviceName
                        + "\nAddress ends in: " + request.addressTail
                        + "\nModel: " + request.model
                        + "\n\nCheck that this matches the waiting screen of the person asking."
                        + "\nNo answer within " + (RoomProtocol.APPROVAL_TIMEOUT_MS / 1000)
                        + " seconds declines automatically.")
                .setCancelable(false)
                .setPositiveButton("Accept", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        answer(request.id, true);
                    }
                })
                .setNegativeButton("Decline", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        answer(request.id, false);
                    }
                })
                .show();
    }

    private void answer(String requestId, boolean accept) {
        requestDialog = null;
        shownRequestId = null;
        answeredRequestId = requestId;
        if (chatService != null) {
            chatService.answerJoinRequest(requestId, accept);
        }
    }

    private void rebuildMessages() {
        messagesContainer.removeAllViews();
        if (chatService != null) {
            for (ChatService.ChatEntry entry : chatService.getHistory()) {
                appendEntry(entry);
            }
        }
    }

    private void updateTimeLeft() {
        RoomState state = shownState;
        if (!state.inRoom()) {
            return;
        }
        String left = "";
        if (state.endsAtElapsed > 0) {
            long seconds = Math.max(0L, (state.endsAtElapsed - SystemClock.elapsedRealtime()) / 1000L);
            left = String.format(Locale.US, "%d:%02d left", seconds / 60, seconds % 60);
        }
        if (state.phase == RoomState.Phase.RECONNECTING) {
            left = "Reconnecting...  " + left;
        }
        timeLeftText.setText(left);
    }

    private void appendEntry(ChatService.ChatEntry entry) {
        TextView row;
        if (entry.notice) {
            row = messageRow("- " + entry.text + " -", Gravity.CENTER_HORIZONTAL, Color.rgb(96, 108, 106));
            row.setTextSize(13f);
        } else {
            String prefix = entry.mine ? "me" : entry.sender;
            row = messageRow(prefix + ": " + entry.text,
                    entry.mine ? Gravity.RIGHT : Gravity.LEFT,
                    entry.mine ? Color.rgb(0, 105, 92) : Color.rgb(34, 46, 44));
        }
        messagesContainer.addView(row);
        messagesScroll.post(new Runnable() {
            @Override
            public void run() {
                messagesScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void showUnseenClosedReason() {
        if (chatService == null) {
            return;
        }
        String reason = chatService.takeUnseenClosedReason();
        if (reason != null) {
            render(RoomState.IDLE, false);
            if (closingByUser) {
                closingByUser = false;
                return;
            }
            showClosedDialog("little chatter", reason);
        }
    }

    private void showClosedDialog(String title, String reason) {
        dismissDialog();
        openDialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(reason)
                .setPositiveButton("OK", null)
                .show();
    }

    private void dismissDialog() {
        if (openDialog != null && openDialog.isShowing()) {
            openDialog.dismiss();
        }
        openDialog = null;
    }

    private void dismissRequestDialog() {
        if (requestDialog != null && requestDialog.isShowing()) {
            requestDialog.dismiss();
        }
        requestDialog = null;
        shownRequestId = null;
    }

    private void setStatus(String message) {
        if (statusText != null) {
            statusText.setText(message);
        }
    }

    // ---- Layout -------------------------------------------------------------------------------

    private View buildUnsupportedView() {
        TextView message = new TextView(this);
        message.setText("little chatter only runs on Android 4.4.x (KitKat).\n"
                + "This device is running Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ").\n\n"
                + "little chatter는 Android 4.4.x(킷캣) 전용입니다.");
        message.setTextSize(16f);
        message.setTextColor(Color.rgb(24, 35, 34));
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(24), dp(24), dp(24), dp(24));
        return message;
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
        statusText.setTextSize(13f);
        statusText.setTextColor(Color.rgb(64, 78, 76));
        statusText.setPadding(0, dp(6), 0, dp(4));
        root.addView(statusText);

        lobbyScreen = buildLobbyScreen();
        waitingScreen = buildWaitingScreen();
        roomScreen = buildRoomScreen();
        root.addView(lobbyScreen, weighted());
        root.addView(waitingScreen, weighted());
        root.addView(roomScreen, weighted());
        waitingScreen.setVisibility(View.GONE);
        roomScreen.setVisibility(View.GONE);
        return root;
    }

    private LinearLayout buildLobbyScreen() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);

        nicknameInput = new EditText(this);
        nicknameInput.setHint("Nickname");
        nicknameInput.setSingleLine(true);
        nicknameInput.setText("little-" + Build.MODEL);
        nicknameInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(RoomProtocol.MAX_NICKNAME_CHARS)});
        screen.addView(nicknameInput, matchWrap());

        localDeviceText = new TextView(this);
        localDeviceText.setTextSize(12f);
        localDeviceText.setTextColor(Color.rgb(64, 78, 76));
        screen.addView(localDeviceText);

        // Create a room.
        screen.addView(sectionTitle("Create a room"));
        LinearLayout createRow = new LinearLayout(this);
        createRow.setOrientation(LinearLayout.HORIZONTAL);
        createRow.setGravity(Gravity.CENTER_VERTICAL);
        durationGroup = new RadioGroup(this);
        durationGroup.setOrientation(RadioGroup.HORIZONTAL);
        for (int i = 0; i < ROOM_MINUTES.length; i++) {
            RadioButton option = new RadioButton(this);
            option.setId(1000 + i);
            option.setText(ROOM_MINUTES[i] + " min");
            option.setTextSize(13f);
            durationGroup.addView(option);
        }
        durationGroup.check(1000 + DEFAULT_ROOM_MINUTES_INDEX);
        createRow.addView(durationGroup, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        createRow.addView(button("Create", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createRoom();
            }
        }));
        screen.addView(createRow, matchWrap());

        // Join a room.
        screen.addView(sectionTitle("Rooms nearby"));
        findButton = button("Find rooms", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleFindRooms();
            }
        });
        screen.addView(buttonRow(
                button("Enable BT", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        requestEnableBluetooth();
                    }
                }),
                findButton
        ));

        roomAdapter = new ArrayAdapter<RoomScanner.FoundRoom>(this, android.R.layout.simple_list_item_1, rooms);
        ListView roomList = new ListView(this);
        roomList.setAdapter(roomAdapter);
        roomList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                askToJoin(rooms.get(position));
            }
        });
        screen.addView(roomList, weighted());

        TextView hint = new TextView(this);
        hint.setText("Host: pick a time and tap Create. Others: tap Find rooms, then tap a room to ask "
                + "to join. The host accepts each person. Up to " + (RoomProtocol.MAX_MEMBERS + 1) + " people.");
        hint.setTextSize(12f);
        hint.setTextColor(Color.rgb(80, 91, 89));
        hint.setPadding(0, dp(6), 0, 0);
        screen.addView(hint);
        return screen;
    }

    private LinearLayout buildWaitingScreen() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setGravity(Gravity.CENTER_HORIZONTAL);
        screen.setPadding(0, dp(24), 0, 0);

        waitingTitleText = new TextView(this);
        waitingTitleText.setTextSize(18f);
        waitingTitleText.setTypeface(Typeface.DEFAULT_BOLD);
        waitingTitleText.setTextColor(Color.rgb(24, 35, 34));
        waitingTitleText.setGravity(Gravity.CENTER);
        screen.addView(waitingTitleText, matchWrap());

        waitingStateText = new TextView(this);
        waitingStateText.setTextSize(14f);
        waitingStateText.setTextColor(Color.rgb(64, 78, 76));
        waitingStateText.setGravity(Gravity.CENTER);
        waitingStateText.setPadding(0, dp(8), 0, dp(20));
        screen.addView(waitingStateText, matchWrap());

        TextView showHost = new TextView(this);
        showHost.setText("Show this screen to the host so they can check it's you:");
        showHost.setTextSize(13f);
        showHost.setTextColor(Color.rgb(64, 78, 76));
        showHost.setGravity(Gravity.CENTER);
        screen.addView(showHost, matchWrap());

        waitingDeviceText = new TextView(this);
        waitingDeviceText.setTextSize(22f);
        waitingDeviceText.setTypeface(Typeface.DEFAULT_BOLD);
        waitingDeviceText.setTextColor(Color.rgb(0, 105, 92));
        waitingDeviceText.setGravity(Gravity.CENTER);
        waitingDeviceText.setBackgroundColor(Color.WHITE);
        waitingDeviceText.setPadding(dp(12), dp(16), dp(12), dp(16));
        LinearLayout.LayoutParams deviceParams = matchWrap();
        deviceParams.setMargins(0, dp(8), 0, dp(20));
        screen.addView(waitingDeviceText, deviceParams);

        screen.addView(button("Cancel request", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelJoin();
            }
        }));
        return screen;
    }

    private LinearLayout buildRoomScreen() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(0, dp(6), 0, 0);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        roomTitleText = new TextView(this);
        roomTitleText.setTextSize(18f);
        roomTitleText.setTypeface(Typeface.DEFAULT_BOLD);
        roomTitleText.setTextColor(Color.rgb(24, 35, 34));
        header.addView(roomTitleText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(button("Leave", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmLeave();
            }
        }));
        screen.addView(header, matchWrap());

        timeLeftText = new TextView(this);
        timeLeftText.setTextSize(14f);
        timeLeftText.setTypeface(Typeface.DEFAULT_BOLD);
        timeLeftText.setTextColor(Color.rgb(0, 105, 92));
        screen.addView(timeLeftText);

        membersText = new TextView(this);
        membersText.setTextSize(13f);
        membersText.setTextColor(Color.rgb(64, 78, 76));
        screen.addView(membersText);

        messagesContainer = new LinearLayout(this);
        messagesContainer.setOrientation(LinearLayout.VERTICAL);
        messagesScroll = new ScrollView(this);
        messagesScroll.setBackgroundColor(Color.WHITE);
        messagesScroll.addView(messagesContainer);
        LinearLayout.LayoutParams messagesParams = weighted();
        messagesParams.setMargins(0, dp(8), 0, dp(8));
        screen.addView(messagesScroll, messagesParams);

        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        messageInput = new EditText(this);
        messageInput.setHint("Message");
        messageInput.setMinLines(1);
        messageInput.setMaxLines(3);
        messageInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(RoomProtocol.MAX_TEXT_CHARS)});
        composer.addView(messageInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        sendButton = button("Send", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendMessage();
            }
        });
        composer.addView(sendButton);
        screen.addView(composer, matchWrap());
        return screen;
    }

    private TextView sectionTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(24, 35, 34));
        title.setPadding(0, dp(10), 0, 0);
        return title;
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
        row.setPadding(0, dp(6), 0, 0);
        for (Button button : buttons) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            params.setMargins(dp(2), 0, dp(2), 0);
            row.addView(button, params);
        }
        return row;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private boolean ensureService() {
        if (chatService == null) {
            setStatus("Starting up. Please try again in a moment.");
            return false;
        }
        return true;
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

    private int selectedRoomMinutes() {
        int index = durationGroup.getCheckedRadioButtonId() - 1000;
        if (index < 0 || index >= ROOM_MINUTES.length) {
            index = DEFAULT_ROOM_MINUTES_INDEX;
        }
        return ROOM_MINUTES[index];
    }

    private String nickname() {
        String value = nicknameInput.getText().toString().trim();
        return value.length() == 0 ? "little-" + Build.MODEL : value;
    }

    /** This phone's Bluetooth name, address tail and model, as the host will see them. */
    private void refreshLocalDeviceInfo() {
        String identity;
        if (bluetoothAdapter == null) {
            identity = "no Bluetooth adapter";
        } else {
            String name = bluetoothAdapter.getName();
            identity = (name == null || name.trim().length() == 0 ? "unnamed" : name)
                    + "  (" + RoomProtocol.addressTail(bluetoothAdapter.getAddress()) + ")  "
                    + RoomProtocol.cleanModel(Build.MODEL);
        }
        localDeviceText.setText("This device: " + identity);
        waitingDeviceText.setText(identity.replace("  (", "\n(").replace(")  ", ")\n"));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(value);
        }
        return builder.toString();
    }
}
