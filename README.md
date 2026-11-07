# little chatter

A short-range messenger for older Android smartphones.

`little chatter` is a lightweight Bluetooth Classic chat app designed for legacy Android devices. It runs on Android 4.4.x (KitKat) only and focuses on direct, nearby messaging without internet access, servers, or modern Android-only frameworks.

## Features

- Time-limited group rooms for up to 6 people nearby (1 host + 5 members), over Bluetooth Classic RFCOMM
- The host picks a time limit (10 / 30 / 60 minutes); the room deletes itself when time is up
- Nearby rooms are listed automatically; tapping one sends a join request
- The host accepts or declines each request, seeing the requester's nickname, Bluetooth name, last four address digits and phone model
- Rooms are strict: if anyone leaves, the room is deleted for everyone, and everyone is told who left
- Unexpected disconnects get a 45-second reconnect window; if the person does not come back in time, the room is deleted
- Messages are kept in memory only and disappear with the room
- Keeps running while the app is minimized or the screen is off (foreground service with an ongoing notification)
- Android 4.4.x (KitKat, API 19) only; other versions show an "unsupported" screen

## How To Use

1. Install the app on every device and enable Bluetooth.
2. **Host:** pick a time limit and tap `Create`. Allow the "make discoverable" prompt.
3. **Members:** tap `Find rooms`; rooms appear as they are found (the scan runs up to 30 seconds). Tap a room.
4. The member's screen shows their device name, address ending and model. The host gets a `Join request` popup with the same details, compares them, and taps `Accept` or `Decline`.
5. Chat. The room screen shows the time left; tap `People` to open the side panel with everyone in the room (host, you, and anyone reconnecting).
6. `Leave room` is in the side panel and asks for confirmation, because leaving deletes the room for everyone. Back closes the panel, otherwise it only minimizes the app.

## How Rooms Work

- **Topology:** every member connects to the host; the host relays messages and sets each sender's name (members cannot impersonate each other).
- **Finding rooms:** after discovery, the app keeps only smartphones and tablets (by Bluetooth device class; tablets that report as handheld/tablet computers count as tablets) and skips headsets, speakers, cars, laptops and other devices. Each phone or tablet is checked as soon as it is found: discovery pauses, the app briefly connects on the room service UUID, then discovery resumes, so rooms appear while the scan is still running. Paired phones/tablets are checked after the first full discovery round, and the whole scan stops after 30 seconds. A log under the room list shows the result for each device (room found, no room, no answer, or skipped with its device class). Devices that are not hosting fail within a few seconds; hosts reply with the host's nickname, head count and time left.
- **Join approval:** the requester's Bluetooth name is read by the host's own Bluetooth stack (the requester's app cannot fake it, though the phone's owner can rename the phone); the address ending cannot be changed on ordinary phones. Requests are shown one at a time. A declined device is blocked for the rest of the room; a request with no answer for 60 seconds is declined automatically, without blocking.
- **Reconnecting:** when the host accepts, it gives the member a private token. After an unexpected disconnect, the member reconnects with that token (only from the same device, no new approval) within 45 seconds and receives the messages it missed.
- **Timers:** the host's clock is the only clock. It sends the remaining time to members every 15 seconds; the same messages act as heartbeats, and a link silent for 40 seconds counts as disconnected.
- **Room ends when:** time is up, anyone taps `Leave`, or someone does not reconnect within 45 seconds.

## Notes

- Messages use Bluetooth Classic, not BLE.
- Messages are not encrypted at the app level; host approval controls who can join.
- Rooms use protocol version 2 and a new service UUID, so they do not talk to older 1:1 builds.
- Android 4.4 does not support BLE advertising/peripheral APIs, so BLE mesh messaging is not implemented.
- Bluetooth pairing prompts may appear depending on the device and manufacturer.
- `maxSdkVersion` only filters app stores; Android itself does not block sideloaded installs on newer versions, so the app also checks the version at startup.
- This project is intended for direct APK installation and legacy device testing, not modern Play Store distribution requirements.

## Package

- Application ID: `com.messenger.little`
- App name: `little chatter`
- Minimum SDK: `19`
- Target SDK: `19`
- Maximum SDK: `19`

