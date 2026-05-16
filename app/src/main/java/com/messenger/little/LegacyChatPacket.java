package com.messenger.little;

import org.json.JSONException;
import org.json.JSONObject;

final class LegacyChatPacket {
    static final int CURRENT_VERSION = 1;

    final String id;
    final String sender;
    final String text;
    final long createdAtMillis;
    final int hops;

    LegacyChatPacket(String id, String sender, String text, long createdAtMillis, int hops) {
        this.id = id;
        this.sender = sender;
        this.text = text;
        this.createdAtMillis = createdAtMillis;
        this.hops = hops;
    }

    String toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("v", CURRENT_VERSION);
        json.put("id", id);
        json.put("sender", sender);
        json.put("text", text);
        json.put("createdAtMillis", createdAtMillis);
        json.put("hops", hops);
        return json.toString();
    }

    LegacyChatPacket withHopIncremented() {
        return new LegacyChatPacket(id, sender, text, createdAtMillis, hops + 1);
    }

    static LegacyChatPacket fromJson(String rawJson) throws JSONException {
        JSONObject json = new JSONObject(rawJson);
        return new LegacyChatPacket(
                json.getString("id"),
                json.optString("sender", "Unknown"),
                json.optString("text", ""),
                json.optLong("createdAtMillis", System.currentTimeMillis()),
                json.optInt("hops", 0)
        );
    }
}
