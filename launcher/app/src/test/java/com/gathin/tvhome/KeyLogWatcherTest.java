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

    @Test public void readsOnlyLaunchEntries() {
        Map<String, String> launches = KeyLogWatcher.launches(java.util.Arrays.asList(
                "launch:com.netflix.ninja:com.viu.viuapp", " launch:com.amazon.amazonvideo.livingroom:com.crunchyroll.crunchyroid",
                "4062:com.example.a", "scan:104:com.example.b", "launch::com.example.c", "launch:com.example.d:", null));
        assertEquals(2, launches.size());
        assertEquals("com.viu.viuapp", launches.get("com.netflix.ninja"));
        assertEquals("com.crunchyroll.crunchyroid", launches.get("com.amazon.amazonvideo.livingroom"));
    }

    // Both lines as a Vestel BODRUM (Android TV 14) logs the Netflix and Prime buttons with those apps disabled.
    @Test public void readsThePackageOfARefusedStart() {
        assertEquals("com.netflix.ninja", KeyLogWatcher.failedStart("I/ActivityTaskManager(  831): START u0 "
                + "{act=com.netflix.action.NETFLIX_KEY_START flg=0x10020000 pkg=com.netflix.ninja (has extras)} "
                + "with LAUNCH_MULTIPLE from uid 1000 result code=-91"));
        assertEquals("com.amazon.amazonvideo.livingroom", KeyLogWatcher.failedStart("I/ActivityTaskManager(  831): "
                + "START u0 {act=com.amazon.amazonvideo.livingroom.AMAZON_BUTTON cat=[android.intent.category.LAUNCHER] "
                + "flg=0x10000000 pkg= cmp=com.amazon.amazonvideo.livingroom/com.amazon.ignition.IgnitionActivity} "
                + "with LAUNCH_MULTIPLE from uid 1000 result code=-92"));
    }

    @Test public void aStartThatWentThroughIsIgnored() {
        assertEquals(null, KeyLogWatcher.failedStart("I/ActivityTaskManager(  831): START u0 {act=android.intent.action.MAIN "
                + "pkg=com.viu.viuapp cmp=com.viu.viuapp/.ui.MainActivity} with LAUNCH_MULTIPLE from uid 10003 result code=0"));
        assertEquals(null, KeyLogWatcher.failedStart("I/ActivityTaskManager(  831): Displayed com.viu.viuapp/.ui.MainActivity"));
    }
}
