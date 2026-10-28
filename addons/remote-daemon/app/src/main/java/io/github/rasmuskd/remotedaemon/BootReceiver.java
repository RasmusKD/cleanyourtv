package io.github.rasmuskd.remotedaemon;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the daemon after every boot: it runs as the shell user, which a reboot ends. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        // the key was allowed at install; a refused key gives up after 15 s rather than waiting on a prompt
        StartJob.schedule(context, 15_000);
    }
}
