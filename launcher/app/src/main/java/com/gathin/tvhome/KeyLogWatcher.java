package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
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
 *
 * It also follows the activity manager's start lines for "launch:" entries. On MediaTek/Vestel firmware the Netflix
 * and Prime buttons are global buttons: the TV's own service receives them and starts Netflix or Prime Video by
 * package or component, and no key service ever sees the key. With that app disabled the start fails ("START u0
 * {... pkg=com.netflix.ninja ...} result code=-91"), and "launch:com.netflix.ninja:com.viu.viuapp" opens Viu in its
 * place. No key filtering is involved, so a held volume key keeps its auto-repeat.
 *
 * Android 13 and later also ask the viewer before an app with READ_LOGS reads other apps' lines, and only while that
 * app is in front: from the background the read is declined without a word, logcat runs on with this app's lines
 * only, and the system keeps that "no" for later reads too (on a Vestel BODRUM, Android 14, until the next boot). So
 * there logcat starts only while Home is in front, which puts the "Allow access to all device logs?" dialog over it;
 * a start from the background waits for Home. An allowed read lasts as long as that logcat does, so in practice the
 * dialog comes once per boot.
 */
final class KeyLogWatcher {
    private static final String TAG = "KeyLogWatcher";
    private static final long RESTART_MS = 2000;
    /** Keycodes from here up are vendor buttons: Android's own KeyEvent codes end below it. */
    static final int FIRST_VENDOR_KEYCODE = 1000;
    private static final Pattern KEY_UP = Pattern.compile("interceptKeyTi keyCode=(\\d+) down=false");
    /** A start the activity manager refused (a negative result code), and the package it was aimed at. */
    private static final Pattern START_FAILED =
            Pattern.compile("START u\\d+ \\{.*?\\b(?:pkg|cmp)=([A-Za-z][A-Za-z0-9_.]*)[/ }].*result code=-\\d+");
    private static final String LAUNCH_PREFIX = "launch:";

    /** The keys a running watcher opens; Home leaves these to it so a press on Home opens the app once. */
    private static volatile Map<Integer, String> covered = Collections.emptyMap();
    /** The watcher of the bound InputKeyService, started or waiting for Home; main thread only. */
    private static KeyLogWatcher current;
    /** Home is resumed; main thread only. */
    private static boolean homeInFront;

    private final Context ctx;
    private final Map<Integer, String> keys;
    private final Map<String, String> launches;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Process logcat;
    private Thread reader;

    /** keys: the app_keys entries to follow, as {@link #followed} picks them; launches: {@link #launches}. */
    KeyLogWatcher(Context ctx, Map<Integer, String> keys, Map<String, String> launches) {
        this.ctx = ctx.getApplicationContext();
        this.keys = keys;
        this.launches = launches;
    }

    /** missing package -> target from the "launch:" app_keys entries ("launch:com.netflix.ninja:com.viu.viuapp"). */
    static Map<String, String> launches(Iterable<String> entries) {
        Map<String, String> out = new HashMap<>();
        for (String entry : entries) {
            if (entry == null || !entry.trim().startsWith(LAUNCH_PREFIX)) continue;
            String[] parts = entry.trim().substring(LAUNCH_PREFIX.length()).split(":", 2);
            if (parts.length != 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()) continue;
            out.put(parts[0].trim(), parts[1].trim());
        }
        return out;
    }

    /** The package of a refused activity start in the activity manager's log, or null for any other line. */
    static String failedStart(String line) {
        Matcher m = START_FAILED.matcher(line);
        return m.find() ? m.group(1) : null;
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

    /** Home is in front: a watcher that waited for it starts now. */
    static void homeShown() {
        homeInFront = true;
        if (current != null) current.start();
    }

    static void homeHidden() {
        homeInFront = false;
    }

    void start() {
        if ((keys.isEmpty() && launches.isEmpty()) || reader != null) return;
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "READ_LOGS not granted, vendor keys are handled on Home only");
            return;
        }
        current = this;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !homeInFront) return;
        Process process;
        try {
            // -T 1: from now on plus one backlog line, which follow() skips, so an old press is not replayed
            process = new ProcessBuilder("logcat", "-v", "brief", "-T", "1", "WindowManager:D",
                    "ActivityTaskManager:I", "*:S")
                    .redirectErrorStream(true).start();
        } catch (IOException e) {
            Log.w(TAG, "logcat did not start", e);
            return;
        }
        logcat = process;
        covered = keys;
        reader = new Thread(() -> follow(process), TAG);
        reader.setDaemon(true);
        reader.start();
    }

    private void follow(Process process) {
        boolean backlog = true;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            for (String line; (line = in.readLine()) != null; ) {
                // the one backlog line -T 1 prints happened before this read: acting on it would replay a press
                if (backlog) {
                    backlog = false;
                    continue;
                }
                int keyCode = keyUp(line);
                String target = keyCode < 0 ? null : keys.get(keyCode);
                if (target == null) {
                    String missing = launches.isEmpty() ? null : failedStart(line);
                    target = missing == null ? null : launches.get(missing);
                }
                if (target != null) {
                    String open = target;
                    main.post(() -> RemoteKeys.launch(ctx, open));
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "logcat ended", e);
        } finally {
            // a logcat that stop() replaced leaves the keys to its successor
            if (logcat == process) covered = Collections.emptyMap();
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
        if (current == this) current = null;
        Process process = logcat;
        logcat = null;
        reader = null;
        if (process != null) process.destroy();
    }
}
