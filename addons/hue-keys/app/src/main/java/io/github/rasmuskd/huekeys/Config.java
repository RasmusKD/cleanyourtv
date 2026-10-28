package io.github.rasmuskd.huekeys;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The bridge address, the app key the bridge issued and the room (group id), in the app's own storage (backups
 * are off). They arrive as config.json pushed by install.sh, which is read once and deleted.
 */
final class Config {
    static final String PUSHED = "config.json";

    final String bridge;
    final String key;
    final String room;

    private Config(String bridge, String key, String room) {
        this.bridge = bridge;
        this.key = key;
        this.room = room;
    }

    boolean complete() {
        return !bridge.isEmpty() && !key.isEmpty() && !room.isEmpty();
    }

    String url(String path) {
        return "http://" + bridge + "/api/" + key + path;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("bridge", Context.MODE_PRIVATE);
    }

    static Config load(Context c) {
        importPushed(c);
        SharedPreferences p = prefs(c);
        return new Config(p.getString("bridge", ""), p.getString("key", ""), p.getString("room", ""));
    }

    /** Takes a pushed config.json ({"bridge": ..., "key": ..., "room": ...}) if there is one, and deletes it. */
    static void importPushed(Context c) {
        File dir = c.getExternalFilesDir(null);
        File f = dir == null ? null : new File(dir, PUSHED);
        if (f == null || !f.isFile()) return;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            SharedPreferences.Editor edit = prefs(c).edit();
            for (String name : new String[] {"bridge", "key", "room"}) {
                if (o.has(name)) edit.putString(name, o.optString(name).trim());
            }
            edit.apply();
        } catch (Exception ignored) {
            // an unreadable file changes nothing; install.sh pushes a fresh one
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
