package io.github.rasmuskd.remotedaemon;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;

/**
 * Runs Starter as a job: Android 14 freezes an app's process soon after its activity finishes or its receiver
 * returns, and a frozen thread would never finish the ADB handshake. A running job keeps the process thawed.
 */
public class StartJob extends JobService {
    private static final String AUTH_WAIT = "authWaitMs";

    static void schedule(Context ctx, int authWaitMs) {
        PersistableBundle extras = new PersistableBundle();
        extras.putInt(AUTH_WAIT, authWaitMs);
        JobInfo job = new JobInfo.Builder(1, new ComponentName(ctx, StartJob.class))
                .setOverrideDeadline(0)
                .setExtras(extras)
                .build();
        ctx.getSystemService(JobScheduler.class).schedule(job);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        int authWaitMs = params.getExtras().getInt(AUTH_WAIT, 15_000);
        new Thread(() -> {
            Starter.start(getApplicationContext(), authWaitMs);
            jobFinished(params, false);
        }, "remote-daemon-start").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
