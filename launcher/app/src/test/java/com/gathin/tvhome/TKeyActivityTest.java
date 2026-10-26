package com.gathin.tvhome;

import java.util.Collections;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TKeyActivityTest {
    private static final Map<Integer, String> T_ENTRY = Collections.singletonMap(4020, "com.example.app");

    @Test public void homeLeavesTheTButtonToTheActivityWhileTheSwitchIsOn() {
        assertTrue(TKeyActivity.answers(true, 4020, T_ENTRY));
    }

    @Test public void switchOffLeavesTheKeyToHome() {
        assertFalse(TKeyActivity.answers(false, 4020, T_ENTRY));
    }

    @Test public void withoutAnEntryTheKeyStaysWithHome() {
        assertFalse(TKeyActivity.answers(true, 4020, Collections.emptyMap()));
    }

    @Test public void otherKeysAreNeverLeftToTheActivity() {
        assertFalse(TKeyActivity.answers(true, 4062, Collections.singletonMap(4062, "com.example.app")));
    }
}
