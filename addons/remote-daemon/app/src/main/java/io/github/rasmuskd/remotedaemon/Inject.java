package io.github.rasmuskd.remotedaemon;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Key injection for the daemon, run as the ADB shell user through app_process (the shell holds INJECT_EVENTS):
 * {@code CLASSPATH=<apk> app_process / io.github.rasmuskd.remotedaemon.Inject <device name part>}. Reads lines from
 * stdin: {@code p <keycode>} sends one whole press, {@code u <keycode>} one key up.
 *
 * <p>Events carry the remote's own device id: Android stops repeating a held key only on an up from the device
 * that holds it, so {@code u} ends the TV's own repeat of a key the remote still holds down.
 */
public final class Inject {
    public static void main(String[] args) throws Exception {
        int device = findDevice(args.length > 0 ? args[0] : "IR Receiver");
        Object manager;
        try {
            // Android 14 moved injection to InputManagerGlobal; earlier versions have it on InputManager
            manager = Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null);
        } catch (ClassNotFoundException e) {
            manager = Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null);
        }
        Method inject = manager.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
        System.out.println("ready device=" + device);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        for (String line; (line = in.readLine()) != null; ) {
            String[] f = line.trim().split(" ");
            if (f.length != 2) continue;
            int code = Integer.parseInt(f[1]);
            long now = SystemClock.uptimeMillis();
            if (f[0].equals("p")) {
                send(inject, manager, key(now, KeyEvent.ACTION_DOWN, code, device));
                send(inject, manager, key(now, KeyEvent.ACTION_UP, code, device));
            } else if (f[0].equals("u")) {
                send(inject, manager, key(now, KeyEvent.ACTION_UP, code, device));
            }
        }
    }

    private static KeyEvent key(long now, int action, int code, int device) {
        return new KeyEvent(now, now, action, code, 0, 0, device, 0, 0, InputDevice.SOURCE_KEYBOARD);
    }

    private static void send(Method inject, Object manager, KeyEvent event) throws Exception {
        // 0: INJECT_INPUT_EVENT_MODE_ASYNC; events stay in order, and the daemon never waits on an app
        if (!(Boolean) inject.invoke(manager, event, 0)) System.err.println("inject refused: " + event);
    }

    private static int findDevice(String want) {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d != null && d.getName().contains(want)) return id;
        }
        throw new IllegalStateException("no input device named like " + want);
    }
}
