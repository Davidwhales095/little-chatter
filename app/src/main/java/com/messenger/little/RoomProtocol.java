package com.messenger.little;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * Wire protocol for little chatter rooms (protocol version 2).
 *
 * Topology: one host, up to {@link #MAX_MEMBERS} members, each connected to the host
 * over Bluetooth Classic RFCOMM. The host relays messages.
 *
 * Frames: [4-byte length][UTF-8 JSON]. Every packet has "t" (type) and "v" (version).
 *
 * Every connection starts with the member sending {@code hello}:
 * <pre>
 * hello {probe: true}                -> host answers "room" {hostNick, people, max, endsInMs} and closes
 *                                       (used to list nearby rooms)
 * hello {nick, model, token, lastSeq} with the token of a member who dropped out
 *                                    -> host answers "welcome" right away (reconnect, no approval)
 * hello {nick, model}                -> host answers "waiting", shows a join request to the host user;
 *                                       on accept: "welcome" {token, ...}; on decline/timeout: "reject"
 * </pre>
 * The token is a private random value that keeps the member's seat across reconnects.
 */
final class RoomProtocol {
    static final int VERSION = 2;
    static final String SERVICE_NAME = "LittleChatterRoom";
    // New UUID for protocol v2 so older 1:1 builds cannot connect by mistake.
    static final UUID SERVICE_UUID = UUID.fromString("5c1a2f8e-7d3b-4e61-9a0c-2b8f4d6e1a73");
    static final Charset UTF_8 = Charset.forName("UTF-8");

    static final int MAX_FRAME_BYTES = 64 * 1024;
    static final int MAX_TEXT_CHARS = 2000;
    static final int MAX_NICKNAME_CHARS = 32;
    static final int MAX_MODEL_CHARS = 40;
    /** Members besides the host: rooms hold up to 6 people. */
    static final int MAX_MEMBERS = 5;

    static final long RECONNECT_GRACE_MS = 45000L;
    static final long RECONNECT_RETRY_MS = 3000L;
    static final long HEARTBEAT_MS = 15000L;
    static final long SILENCE_TIMEOUT_MS = 40000L;
    static final long HANDSHAKE_TIMEOUT_MS = 15000L;
    /** A join request the host does not answer within this time is declined automatically. */
    static final long APPROVAL_TIMEOUT_MS = 60000L;
    static final long PROBE_TIMEOUT_MS = 5000L;
    static final int HISTORY_FOR_REJOIN = 100;

    static final String HELLO = "hello";
    static final String ROOM = "room";
    static final String WAITING = "waiting";
    static final String WELCOME = "welcome";
    static final String REJECT = "reject";
    static final String MSG = "msg";
    static final String NOTICE = "notice";
    static final String INFO = "info";
    static final String PING = "ping";
    static final String LEAVE = "leave";
    static final String CLOSED = "closed";

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private RoomProtocol() {
    }

    static String newToken(SecureRandom random) {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0x0f];
            out[i * 2 + 1] = HEX[bytes[i] & 0x0f];
        }
        return new String(out);
    }

    /** Compares without leaking where the strings differ. */
    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] x = a.getBytes(UTF_8);
        byte[] y = b.getBytes(UTF_8);
        int diff = x.length ^ y.length;
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            diff |= x[i] ^ y[i];
        }
        return diff == 0;
    }

    /** Builds a packet: packet("msg", "text", "hi", "seq", 3). */
    static JSONObject packet(String type, Object... keyValues) {
        try {
            JSONObject json = new JSONObject();
            json.put("t", type);
            json.put("v", VERSION);
            for (int i = 0; i + 1 < keyValues.length; i += 2) {
                json.put((String) keyValues[i], keyValues[i + 1]);
            }
            return json;
        } catch (JSONException error) {
            throw new IllegalArgumentException(error);
        }
    }

    static String newMessageId() {
        return UUID.randomUUID().toString();
    }

    static String cleanNickname(String nickname) {
        String value = limit(nickname, MAX_NICKNAME_CHARS);
        return value.length() == 0 ? "Guest" : value;
    }

    static String cleanModel(String model) {
        String value = limit(model, MAX_MODEL_CHARS);
        return value.length() == 0 ? "unknown model" : value;
    }

    /** Last four hex digits of a Bluetooth address: "AA:BB:CC:DD:3F:A2" -> "3F:A2". */
    static String addressTail(String address) {
        if (address == null || address.length() < 5) {
            return "??:??";
        }
        return address.substring(address.length() - 5).toUpperCase(java.util.Locale.US);
    }

    private static String limit(String value, int max) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
