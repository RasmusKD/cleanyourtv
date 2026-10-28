package io.github.rasmuskd.huekeys;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Started by a remote button through one of its aliases (.Toggle, .Brighter, .Dimmer, .Low, .Half, .Full): finishes at once,
 * with no window (Theme.NoDisplay), and sends the request on a background thread. One thread, so presses in a
 * quick row reach the bridge in order.
 */
public class HueKeyActivity extends Activity {
    private static final String TAG = "HueKeys";
    private static final int TIMEOUT_MS = 3000;
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Hue.Action action = Hue.action(getComponentName().getClassName());
        Context app = getApplicationContext();
        finish();
        if (action == null) return;
        WORK.execute(() -> run(app, action));
    }

    private static void run(Context app, Hue.Action action) {
        Config cfg = Config.load(app);
        if (!cfg.complete()) {
            Log.w(TAG, "no bridge configured; push config.json with install.sh");
            return;
        }
        try {
            if (Hue.watchpostArmed(request("GET", cfg.url("/sensors"), null), System.currentTimeMillis())) {
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(app, "Watchpost is on: the lights are its", Toast.LENGTH_SHORT).show());
                return;
            }
            boolean anyOn = false;
            if (action == Hue.Action.TOGGLE) {
                JSONObject room = new JSONObject(request("GET", cfg.url("/groups/" + cfg.room), null));
                JSONObject state = room.optJSONObject("state");
                anyOn = state != null && state.optBoolean("any_on");
            }
            String answer = request("PUT", cfg.url("/groups/" + cfg.room + "/action"), Hue.body(action, anyOn));
            if (Hue.refused(answer)) Log.w(TAG, "bridge refused " + action);
        } catch (Exception e) {
            // the sensors could not be read or the bridge is unreachable: no light changes
            Log.w(TAG, action + " not sent: " + e);
        }
    }

    private static String request(String method, String url, String body) throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
        try {
            http.setRequestMethod(method);
            http.setConnectTimeout(TIMEOUT_MS);
            http.setReadTimeout(TIMEOUT_MS);
            if (body != null) {
                http.setDoOutput(true);
                http.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = http.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            if (http.getResponseCode() != 200) throw new IOException("HTTP " + http.getResponseCode());
            try (InputStream in = http.getInputStream()) {
                ByteArrayOutputStream text = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) text.write(buf, 0, n);
                return text.toString("UTF-8");
            }
        } finally {
            http.disconnect();
        }
    }
}
