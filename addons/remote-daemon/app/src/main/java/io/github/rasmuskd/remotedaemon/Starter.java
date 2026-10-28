package io.github.rasmuskd.remotedaemon;

import android.content.Context;
import android.util.Log;

/**
 * Starts the daemon as the ADB shell user through the TV's own ADB port, unless it is already running. The daemon
 * and its config sit in /data/local/tmp, where install.sh put them; the -mode comes from remote-daemon.args there.
 */
final class Starter {
    private static final String TAG = "RemoteDaemon";
    static final String DIR = "/data/local/tmp";
    // adbd ends the shell's whole process group when the command returns: the sleep lets setsid move the daemon into
    // a session of its own first, and the daemon must be found running after it.
    static final String COMMAND = "cd " + DIR + " && if pidof remote-daemon >/dev/null; then echo running; "
            + "else setsid -d ./remote-daemon $(cat remote-daemon.args 2>/dev/null) remote-daemon.conf "
            + "> remote-daemon.log 2>&1 < /dev/null & sleep 2; "
            + "if pidof remote-daemon >/dev/null; then echo started; else echo failed; cat remote-daemon.log; fi; fi";
    private static final int PORT = 5555;
    /** adbd comes up after BOOT_COMPLETED on some boots: try for this long. */
    private static final long BOOT_RETRY_MS = 120_000;

    private Starter() {
    }

    /** Blocks until the daemon runs or the retry time is over; call off the main thread. */
    static void start(Context ctx, int authWaitMs) {
        long until = System.currentTimeMillis() + BOOT_RETRY_MS;
        while (true) {
            try {
                AdbKey key = AdbKey.loadOrCreate(ctx.getFilesDir());
                Log.i(TAG, Adb.shell("127.0.0.1", PORT, key, COMMAND, authWaitMs).trim());
                return;
            } catch (Exception e) {
                if (System.currentTimeMillis() > until) {
                    Log.w(TAG, "daemon not started: " + e);
                    return;
                }
                Log.i(TAG, "ADB not ready (" + e + "), retrying");
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException stop) {
                    return;
                }
            }
        }
    }
}
