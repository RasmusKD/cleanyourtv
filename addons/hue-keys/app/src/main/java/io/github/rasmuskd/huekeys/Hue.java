package io.github.rasmuskd.huekeys;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The requests behind each button, against the bridge's local API (v1, /api/&lt;key&gt;/...), and the watchpost
 * check that comes before every one of them.
 *
 * A surveillance rig on the same bridge (watchpost) owns the lights while it is armed: it keeps a CLIP generic
 * flag sensor with unique id {@link #WATCHPOST_ID} set and refreshes it every few minutes. While that flag is set
 * and fresh, no button changes a light. A flag older than {@link #WATCHPOST_FRESH_MS} is a rig that stopped
 * without clearing it, and no longer counts. When the sensors cannot be read, nothing is changed either.
 */
final class Hue {
    enum Action { TOGGLE, BRIGHTER, DIMMER, LOW, HALF, FULL }

    static final String WATCHPOST_ID = "watchpost-armed";
    static final long WATCHPOST_FRESH_MS = 20 * 60 * 1000L;
    static final int BRI_STEP = 32;
    /** Hue brightness runs 1 to 254: 25, 50 and 100 percent. */
    static final int LOW_BRI = 64;
    static final int HALF_BRI = 127;
    static final int FULL_BRI = 254;

    private Hue() {
    }

    /** The action an alias stands for ("io.github.rasmuskd.huekeys.Toggle"), or null for any other name. */
    static Action action(String className) {
        if (className == null) return null;
        switch (className.substring(className.lastIndexOf('.') + 1)) {
            case "Toggle": return Action.TOGGLE;
            case "Brighter": return Action.BRIGHTER;
            case "Dimmer": return Action.DIMMER;
            case "Low": return Action.LOW;
            case "Half": return Action.HALF;
            case "Full": return Action.FULL;
            default: return null;
        }
    }

    /** The body of PUT /groups/&lt;room&gt;/action; anyOn is the room's state.any_on, read only for TOGGLE. */
    static String body(Action action, boolean anyOn) {
        switch (action) {
            case TOGGLE: return "{\"on\":" + !anyOn + "}";
            case BRIGHTER: return "{\"on\":true,\"bri_inc\":" + BRI_STEP + "}";
            case DIMMER: return "{\"bri_inc\":-" + BRI_STEP + "}";
            case LOW: return level(LOW_BRI);
            case HALF: return level(HALF_BRI);
            case FULL: return level(FULL_BRI);
            default: throw new IllegalArgumentException(String.valueOf(action));
        }
    }

    private static String level(int bri) {
        return "{\"on\":true,\"bri\":" + bri + "}";
    }

    /** Whether GET /sensors shows watchpost armed now: its flag set and updated within WATCHPOST_FRESH_MS. */
    static boolean watchpostArmed(String sensorsJson, long nowMs) throws JSONException {
        JSONObject sensors = new JSONObject(sensorsJson);
        for (Iterator<String> ids = sensors.keys(); ids.hasNext(); ) {
            JSONObject sensor = sensors.optJSONObject(ids.next());
            if (sensor == null || !WATCHPOST_ID.equals(sensor.optString("uniqueid"))) continue;
            JSONObject state = sensor.optJSONObject("state");
            if (state == null || !state.optBoolean("flag")) return false;
            long updated = utcMillis(state.optString("lastupdated"));
            return updated > 0 && nowMs - updated < WATCHPOST_FRESH_MS;
        }
        return false;
    }

    /** The bridge reports a refused request (revoked key, unknown room) as HTTP 200 with an error array. */
    static boolean refused(String response) {
        String text = response.trim();
        if (!text.startsWith("[")) return false;
        try {
            JSONArray items = new JSONArray(text);
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null && item.has("error")) return true;
            }
            return false;
        } catch (JSONException e) {
            return true;
        }
    }

    /** The bridge's "2026-10-28T10:15:00" (UTC), or 0 for "none" or anything unreadable. */
    static long utcMillis(String stamp) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            return format.parse(stamp).getTime();
        } catch (ParseException | NullPointerException e) {
            return 0;
        }
    }
}
