package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import java.util.Map;

/**
 * Answers com.tcl.partnercustomizer.tkey, the activity TCL's key service (com.tcl.keycustomfunctionservice) starts on
 * every press of the remote's T button whichever app is in front, and runs the app_keys entry for that button. The key
 * itself only reaches the focused window, so Home's own app_keys handling never sees it inside another app; this is
 * the one path that does. The component is disabled in the manifest and MainActivity switches it on only while
 * tcl_t_key is set, so a TCL set that still has its own handler is never offered a chooser. While it is on, Home
 * leaves the T keycode to this activity, so a press on Home opens the app once. No UI of its own (Theme.NoDisplay).
 */
public class TKeyActivity extends Activity {
    /** The T button's keycode on TCL remotes. */
    static final int KEYCODE_T = 4020;

    /** Whether Home leaves this key to TKeyActivity: only for the T button, with the switch on and an entry to run. */
    static boolean answers(boolean tclTKey, int keyCode, Map<Integer, String> appKeys) {
        return tclTKey && keyCode == KEYCODE_T && appKeys.containsKey(KEYCODE_T);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Config cfg = Config.load(this);
        String target = cfg.tclTKey ? RemoteKeys.parseAppKeys(cfg.appKeys).get(KEYCODE_T) : null;
        if (target != null) RemoteKeys.launch(this, target);
        finish();
    }
}
