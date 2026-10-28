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
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs app_keys for vendor buttons that reach only the focused window and that no service sees: the Netflix button on
 * TCL remotes goes to the app in front and, past that, only to Netflix's own signature-guarded broadcast, so inside
 * another app or on an HDMI input Home never hears it. The system window manager logs every key it intercepts
 * ("interceptKeyTi keyCode=N down=true|false") whatever has focus, so this follows the log and runs the button's tap
 * or hold entry from the press and release lines, timed by their log timestamps. Any app can write a line with the
 * window manager's tag, so only lines whose writer is the system (uid 1000, which logd records and the app cannot
 * set) count. Only while vendor_key_log is set: one blocking logcat read on a daemon thread, run from
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
    /** A restarted logcat reads on from the last line its predecessor gave, but never further back than this. */
    static final long CATCH_UP_MS = 5000;
    /** Keycodes from here up are vendor buttons: Android's own KeyEvent codes end below it. */
    static final int FIRST_VENDOR_KEYCODE = 1000;
    /** "-v time -v uid -v epoch": "1730000000.123 D/WindowManager( 1000:  680): text"; the uid is the writer's. */
    private static final Pattern SYSTEM_LINE =
            Pattern.compile("^\\s*(\\d{1,12})\\.(\\d{3}) [VDIWEF]/[^(]*\\(\\s*1000:\\s*\\d+\\): (.*)$");
    private static final Pattern KEY = Pattern.compile("interceptKeyTi keyCode=(\\d{1,9}) down=(true|false)\\b");
    /**
     * A start the activity manager refused (a negative result code), and the package it was aimed at. The system
     * writes the line, but its intent text is the starting app's own, and a newline in it prints as a line of its
     * own under the system's prefix: any app can still make such a line appear. The most it gets is the owner's
     * configured follow-up app opening.
     */
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
    private final Map<Integer, String> holds;
    private final Map<Integer, String> both;
    private final Map<String, String> launches;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Hold timers run on {@link #main}, so stop() cancels a pending hold with everything else. Main thread only. */
    private final RemoteKeys.Presses presses = new RemoteKeys.Presses(new RemoteKeys.Presses.Timer() {
        @Override public void post(Runnable hold, long delayMs) { main.postDelayed(hold, delayMs); }
        @Override public void cancel(Runnable hold) { main.removeCallbacks(hold); }
    });
    private volatile Process logcat;
    /** Epoch ms just past the last line read, where a restarted logcat goes on; 0 before the first line. */
    private volatile long resumeFromMs;
    private Thread reader;

    /**
     * keys and holds: the app_keys and hold entries to follow, as {@link #followed} picks them; launches:
     * {@link #launches}.
     */
    KeyLogWatcher(Context ctx, Map<Integer, String> keys, Map<Integer, String> holds, Map<String, String> launches) {
        this.ctx = ctx.getApplicationContext();
        this.keys = keys;
        this.holds = holds;
        this.launches = launches;
        Map<Integer, String> union = new HashMap<>(holds);
        union.putAll(keys);
        both = Collections.unmodifiableMap(union);
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

    /** A log line the system wrote: its epoch time and its text. */
    static final class SystemLine {
        final long ms;
        final String text;

        SystemLine(long ms, String text) {
            this.ms = ms;
            this.text = text;
        }
    }

    /** The time and text of a line the system (uid 1000) wrote, or null for any other writer or line. */
    static SystemLine systemLine(String line) {
        Matcher m = SYSTEM_LINE.matcher(line);
        if (!m.matches()) return null;
        return new SystemLine(Long.parseLong(m.group(1)) * 1000 + Integer.parseInt(m.group(2)), m.group(3));
    }

    /** The keycode of a key press or release line of the window manager's log, or -1 for any other line. */
    static int keyCode(String text) {
        Matcher m = KEY.matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** Whether a line {@link #keyCode} read is a press rather than a release. */
    static boolean keyDown(String text) {
        Matcher m = KEY.matcher(text);
        return m.find() && m.group(2).equals("true");
    }

    /** logcat's -T argument: where a (re)started read begins, never earlier than CATCH_UP_MS ago nor after now. */
    static String readFrom(long resumeFromMs, long nowMs) {
        long from = resumeFromMs == 0 ? nowMs : Math.min(nowMs, Math.max(resumeFromMs, nowMs - CATCH_UP_MS));
        return String.format(Locale.ROOT, "%d.%03d", from / 1000, from % 1000);
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
        if ((both.isEmpty() && launches.isEmpty()) || reader != null) return;
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "READ_LOGS not granted, vendor keys are handled on Home only");
            return;
        }
        current = this;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !homeInFront) return;
        Process process;
        try {
            // From now on, or from where the last logcat stopped, so a press in the restart gap is still read and
            // one from before is never replayed. logcat's own errors carry no system prefix, so the parser drops them.
            process = new ProcessBuilder("logcat", "-v", "time", "-v", "uid", "-v", "epoch",
                    "-T", readFrom(resumeFromMs, System.currentTimeMillis()),
                    "WindowManager:D", "ActivityTaskManager:I", "*:S")
                    .redirectErrorStream(true).start();
        } catch (IOException e) {
            Log.w(TAG, "logcat did not start", e);
            return;
        }
        logcat = process;
        covered = both;
        reader = new Thread(() -> follow(process), TAG);
        reader.setDaemon(true);
        reader.start();
    }

    private void follow(Process process) {
        // followed keys whose press line came without a release yet
        Set<Integer> down = new HashSet<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            for (String raw; (raw = in.readLine()) != null; ) {
                SystemLine line = systemLine(raw);
                if (line == null) continue;
                resumeFromMs = line.ms + 1;
                int keyCode = keyCode(line.text);
                if (keyCode >= 0) {
                    if (both.containsKey(keyCode)) key(process, down, keyCode, keyDown(line.text), line.ms);
                    continue;
                }
                String missing = launches.isEmpty() ? null : failedStart(line.text);
                String target = missing == null ? null : launches.get(missing);
                if (target != null) onMain(process, () -> RemoteKeys.launch(ctx, target));
            }
        } catch (IOException e) {
            Log.w(TAG, "logcat ended", e);
        } finally {
            // a logcat that stop() replaced leaves the keys to its successor
            if (logcat == process) covered = Collections.emptyMap();
            process.destroy();
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // logcat exits when logd restarts or drops a slow reader; follow again unless stop() ended it
        main.postDelayed(() -> {
            if (logcat != process) return;
            reader = null;
            start();
        }, RESTART_MS);
    }

    /**
     * A press or release of a followed key, through the same tap/hold rules as the key services. A key logs a press
     * line for every auto-repeat, so only the first press line since its release starts a press.
     */
    private void key(Process process, Set<Integer> down, int keyCode, boolean isDown, long ms) {
        String button = "log:" + keyCode;
        String app = keys.get(keyCode), holdApp = holds.get(keyCode);
        if (isDown) {
            if (!down.add(keyCode)) return;
            onMain(process, () -> presses.down(button, ms, 0, holdApp, target -> RemoteKeys.launch(ctx, target)));
        } else {
            down.remove(keyCode);
            onMain(process, () -> presses.up(button, ms, ms, false, app, holdApp,
                    target -> RemoteKeys.launch(ctx, target)));
        }
    }

    /** Runs a line's action on the main thread, unless stop() ended that line's logcat before it got there. */
    private void onMain(Process process, Runnable action) {
        main.post(() -> {
            if (logcat == process) action.run();
        });
    }

    void stop() {
        // also cancels a pending hold: its timer runs on main
        main.removeCallbacksAndMessages(null);
        covered = Collections.emptyMap();
        if (current == this) current = null;
        Process process = logcat;
        logcat = null;
        reader = null;
        if (process != null) process.destroy();
    }
}
