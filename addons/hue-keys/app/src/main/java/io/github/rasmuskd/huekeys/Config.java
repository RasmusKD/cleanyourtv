package io.github.rasmuskd.huekeys;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The bridge address, the SHA-256 of its HTTPS certificate, the app key the bridge issued and the room (group id),
 * in the app's own storage (backups are off). They arrive together as config.json written through ConfigProvider,
 * and are replaced only together: an address or certificate cannot change without a key for it, so no write can
 * send the stored key to another host.
 */
final class Config {
    static final String PUSHED = "config.json";
    private static final String[] FIELDS = {"bridge", "pin", "key", "room"};

    final String bridge;
    final String pin;
    final String key;
    final String room;

    private Config(String bridge, String pin, String key, String room) {
        this.bridge = bridge;
        this.pin = pin;
        this.key = key;
        this.room = room;
    }

    boolean complete() {
        return !bridge.isEmpty() && Pin.valid(pin) && !key.isEmpty() && !room.isEmpty();
    }

    String url(String path) {
        return "https://" + bridge + "/api/" + key + path;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("bridge", Context.MODE_PRIVATE);
    }

    static Config load(Context c) {
        SharedPreferences p = prefs(c);
        return new Config(p.getString("bridge", ""), p.getString("pin", ""), p.getString("key", ""),
                p.getString("room", ""));
    }

    /**
     * The config in a pushed file ({"bridge", "pin", "key", "room"}), or null unless all four are there and the pin
     * is a SHA-256.
     */
    static Config parse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String[] v = new String[FIELDS.length];
            for (int i = 0; i < FIELDS.length; i++) v[i] = o.optString(FIELDS[i]).trim();
            Config c = new Config(v[0], v[1].toLowerCase(java.util.Locale.ROOT), v[2], v[3]);
            return c.complete() ? c : null;
        } catch (JSONException e) {
            return null;
        }
    }

    /** Takes the file ConfigProvider just closed, if it holds a whole config, and deletes it either way. */
    static void importPushed(Context c, File f) {
        try {
            Config pushed = parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (pushed == null) return;
            prefs(c).edit().putString("bridge", pushed.bridge).putString("pin", pushed.pin)
                    .putString("key", pushed.key).putString("room", pushed.room).apply();
        } catch (java.io.IOException ignored) {
            // an unreadable file changes nothing; install.sh pushes a fresh one
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
