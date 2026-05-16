# little chatter

A short-range messenger for older Android smartphones.

`little chatter` is a lightweight Bluetooth Classic chat app designed for legacy Android devices. It targets Android 4.4.1 through Android 6.0.1 and focuses on direct, nearby messaging without internet access, servers, or modern Android-only frameworks.

## Features

- Short-range peer-to-peer messaging over Bluetooth Classic RFCOMM/SPP
- Supports Android 4.4.1-6.0.1/API 19-23
- Separate Bluetooth scan and chat screens
- Automatically opens the chat screen when a peer connects
- Automatically returns to the scan screen when the connection is lost
- Clears the chat window when returning to the scan screen
- Keeps the screen awake while the chat screen is active
- Blocks additional connection attempts while already connected
- Optional BLE scan helper for nearby BLE advertisers on supported devices

## How To Use

1. Install the app on two Android devices.
2. Enable Bluetooth on both devices.
3. Open the app. It starts on the `Bluetooth Scan` screen.
4. On one device, tap `Listen`.
5. If needed, tap `Discoverable` so the other device can find it.
6. On the other device, tap `Scan Classic`.
7. Select a discovered or paired device, then tap `Connect`.
8. Once connected, the app automatically switches to the `Chat` screen.
9. If the connection is lost or disconnected, the app returns to `Bluetooth Scan`.

## Notes

- Messages use Bluetooth Classic, not BLE.
- BLE scanning is only a helper for discovering nearby BLE advertisers.
- Android 4.4 does not support BLE advertising/peripheral APIs, so BLE mesh messaging is not implemented.
- Bluetooth pairing prompts may appear depending on the device and manufacturer.
- This project is intended for direct APK installation and legacy device testing, not modern Play Store distribution requirements.

## Package

- Application ID: `com.messenger.little`
- App name: `little chatter`
- Minimum SDK: `19`
- Target SDK: `23`

