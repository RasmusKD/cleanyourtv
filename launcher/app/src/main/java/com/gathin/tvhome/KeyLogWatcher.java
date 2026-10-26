package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs app_keys for vendor buttons that reach only the focused window and that no service sees: the Netflix button on
 * TCL remotes goes to the app in front and, past that, only to Netflix's own signature-guarded broadcast, so inside
 * another app or on an HDMI input Home never hears it. The system window manager logs every key it intercepts
 * ("interceptKeyTi keyCode=N down=false") whatever has focus, so this follows the log and opens the button's app on
 * the release line. Only while vendor_key_log is set: one blocking logcat read on a daemon thread, run from
 * InputKeyService, which the system keeps bound. Needs READ_LOGS, granted over adb (pm grant com.gathin.tvhome
 * android.permission.READ_LOGS); without it logcat shows only this app's own lines and the watcher does not start.
 */
final class KeyLogWatcher {
    private static final String TAG = "KeyLogWatcher";
    private static final long RESTART_MS = 2000;
    /** Keycodes from here up are vendor buttons: Android's own KeyEvent codes end below it. */
    static final int FIRST_VENDOR_KEYCODE = 1000;
    private static final Pattern KEY_UP = Pattern.compile("interceptKeyTi keyCode=(\\d+) down=false");

    /** The keys a running watcher opens; Home leaves these to it so a press on Home opens the app once. */
    private static volatile Map<Integer, String> covered = Collections.emptyMap();

    private final Context ctx;
    private final Map<Integer, String> keys;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Process logcat;
    private Thread reader;

    /** keys: the app_keys entries to follow, as {@link #followed} picks them. */
    KeyLogWatcher(Context ctx, Map<Integer, String> keys) {
        this.ctx = ctx.getApplicationContext();
        this.keys = keys;
    }

    /** The app_keys entries a watcher follows: the vendor buttons, keycode 1000 and up. */
    static Map<Integer, String> followed(Map<Integer, String> appKeys) {
        Map<Integer, String> out = new HashMap<>();
        for (Map.Entry<Integer, String> e : appKeys.entrySet()) {
            if (e.getKey() >= FIRST_VENDOR_KEYCODE) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** The keycode of a key-release line of the window manager's log, or -1 for any other line. */
    static int keyUp(String line) {
        Matcher m = KEY_UP.matcher(line);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    static boolean covers(int keyCode) {
        return covered.containsKey(keyCode);
    }

    void start() {
        if (keys.isEmpty() || reader != null) return;
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "READ_LOGS not granted, vendor keys are handled on Home only");
            return;
        }
        try {
            // -T 1: from now on, never the backlog, so an old press is not replayed
            logcat = new ProcessBuilder("logcat", "-v", "brief", "-T", "1", "WindowManager:D", "*:S")
                    .redirectErrorStream(true).start();
        } catch (IOException e) {
            Log.w(TAG, "logcat did not start", e);
            return;
        }
        covered = keys;
        Process process = logcat;
        reader = new Thread(() -> follow(process), TAG);
        reader.setDaemon(true);
        reader.start();
    }

    private void follow(Process process) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            for (String line; (line = in.readLine()) != null; ) {
                int keyCode = keyUp(line);
                String target = keyCode < 0 ? null : keys.get(keyCode);
                if (target != null) main.post(() -> RemoteKeys.launch(ctx, target));
            }
        } catch (IOException e) {
            Log.w(TAG, "logcat ended", e);
        } finally {
            covered = Collections.emptyMap();
        }
        // logcat exits when logd restarts or drops a slow reader; follow again unless stop() ended it
        main.postDelayed(() -> {
            if (logcat != process) return;
            reader = null;
            start();
        }, RESTART_MS);
    }

    void stop() {
        main.removeCallbacksAndMessages(null);
        covered = Collections.emptyMap();
        if (logcat != null) logcat.destroy();
        logcat = null;
        reader = null;
    }
}
