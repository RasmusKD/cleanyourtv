package io.github.rasmuskd.remotedaemon;

import android.app.Activity;
import android.os.Bundle;

/**
 * Starts the daemon by hand, with no window. install.sh opens it once so the TV shows its "Allow debugging?"
 * prompt for this app's key while someone is there to tick "Always allow".
 */
public class StartActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StartJob.schedule(this, 120_000);
        finish();
    }
}
