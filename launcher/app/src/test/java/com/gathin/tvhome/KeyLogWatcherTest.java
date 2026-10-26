package com.gathin.tvhome;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KeyLogWatcherTest {
    @Test public void followsOnlyVendorKeycodes() {
        Map<Integer, String> appKeys = new HashMap<>();
        appKeys.put(4062, "com.example.a");
        appKeys.put(4020, "com.example.b");
        appKeys.put(999, "com.example.c");
        appKeys.put(172, "com.example.d");
        Map<Integer, String> followed = KeyLogWatcher.followed(appKeys);
        assertEquals(2, followed.size());
        assertTrue(followed.containsKey(4062));
        assertTrue(followed.containsKey(4020));
        assertFalse(followed.containsKey(999));
    }

    @Test public void readsTheKeycodeOfAReleaseLine() {
        assertEquals(4062, KeyLogWatcher.keyUp("D/WindowManager( 1234): interceptKeyTi keyCode=4062 down=false"));
    }

    @Test public void aPressLineIsNotARelease() {
        assertEquals(-1, KeyLogWatcher.keyUp("D/WindowManager( 1234): interceptKeyTi keyCode=4062 down=true"));
    }

    @Test public void otherLinesAreIgnored() {
        assertEquals(-1, KeyLogWatcher.keyUp("D/WindowManager( 1234): Unhandled key: keyCode=4062"));
        assertEquals(-1, KeyLogWatcher.keyUp(""));
    }
}
