package com.gathin.tvhome;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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

    @Test public void readsTheKeycodeOfPressAndReleaseLines() {
        assertEquals(4062, KeyLogWatcher.keyCode("interceptKeyTi keyCode=4062 down=false repeatCount=0"));
        assertFalse(KeyLogWatcher.keyDown("interceptKeyTi keyCode=4062 down=false repeatCount=0"));
        assertEquals(4062, KeyLogWatcher.keyCode("interceptKeyTi keyCode=4062 down=true"));
        assertTrue(KeyLogWatcher.keyDown("interceptKeyTi keyCode=4062 down=true"));
    }

    @Test public void otherLinesAreIgnored() {
        assertEquals(-1, KeyLogWatcher.keyCode("Unhandled key: keyCode=4062"));
        assertEquals(-1, KeyLogWatcher.keyCode("interceptKeyTi keyCode=4062 down=trueish"));
        assertEquals(-1, KeyLogWatcher.keyCode(""));
        // a keycode past int range would end the reader thread if it reached parseInt
        assertEquals(-1, KeyLogWatcher.keyCode("interceptKeyTi keyCode=99999999999 down=false"));
    }

    @Test public void onlyTheSystemsLinesCount() {
        KeyLogWatcher.SystemLine line = KeyLogWatcher.systemLine(
                "1730000000.123 D/WindowManager( 1000:  680): interceptKeyTi keyCode=4062 down=false");
        assertEquals(1730000000123L, line.ms);
        assertEquals("interceptKeyTi keyCode=4062 down=false", line.text);
        // the same text from an app that logs under the window manager's tag
        assertNull(KeyLogWatcher.systemLine(
                "1730000000.123 D/WindowManager(10123: 4410): interceptKeyTi keyCode=4062 down=false"));
        // a message cannot carry a forged prefix: logcat prints each line of it after the writer's own
        assertNull(KeyLogWatcher.systemLine(
                "1730000000.123 I/WindowManager(10123: 4410): 1730000000.124 D/WindowManager( 1000:  680): x"));
        assertNull(KeyLogWatcher.systemLine("D/WindowManager( 1000:  680): interceptKeyTi keyCode=4062 down=false"));
        assertNull(KeyLogWatcher.systemLine("logcat: Unknown option"));
    }

    @Test public void aRestartReadsOnWhereTheLastLogcatStopped() {
        long now = 1730000010000L;
        assertEquals("1730000010.000", KeyLogWatcher.readFrom(0, now));            // the first start: from now
        assertEquals("1730000008.501", KeyLogWatcher.readFrom(now - 1499, now));   // the 2 s restart gap is read
        assertEquals("1730000005.000", KeyLogWatcher.readFrom(now - 60000, now));  // never more than CATCH_UP_MS back
        assertEquals("1730000010.000", KeyLogWatcher.readFrom(now + 60000, now));  // a clock set back
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
