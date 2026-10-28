package com.gathin.tvhome;

import android.content.Context;
import android.provider.Settings;
import android.widget.Toast;

/**
 * The "brighter" and "dimmer" app_keys actions: step the panel backlight on MediaTek sets that keep it in
 * Settings.Global "picture_backlight" (0 to 100, the Backlight slider of the picture settings). The TV's own
 * settings service (com.mediatek.tv.service, TVMenuSettingsService) observes that key and hands every change to the
 * display driver, so a write here moves the real backlight, as the slider does (Vestel BODRUM, Android TV 14).
 * Writing Settings.Global needs WRITE_SECURE_SETTINGS, granted with BACKLIGHT_KEYS=1 in build-and-install. On a set
 * without the key, or without the grant, a press does nothing.
 */
final class Backlight {
    static final String KEY = "picture_backlight";
    static final int MAX = 100;
    static final int STEP = 5;

    private static Toast shown;

    private Backlight() {
    }

    /** The level one step from current, on the STEP grid: 2 goes up to 5 and down to 0. */
    static int next(int current, int direction) {
        int level = Math.max(0, Math.min(MAX, current));
        int stepped = direction > 0 ? (level / STEP + 1) * STEP : ((level + STEP - 1) / STEP - 1) * STEP;
        return Math.max(0, Math.min(MAX, stepped));
    }

    static void step(Context ctx, int direction) {
        int current = Settings.Global.getInt(ctx.getContentResolver(), KEY, -1);
        if (current < 0) return;
        int level = next(current, direction);
        try {
            Settings.Global.putInt(ctx.getContentResolver(), KEY, level);
        } catch (SecurityException e) {
            return;
        }
        // a sun and the level, which needs no translation; the previous one goes so held presses do not queue
        if (shown != null) shown.cancel();
        shown = Toast.makeText(ctx.getApplicationContext(), "☀ " + level, Toast.LENGTH_SHORT);
        shown.show();
    }
}
