package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DeveloperOptionsRowTest {
    @Test public void shownOnceUnlockedWithTheSwitchOn() {
        assertTrue(DeveloperOptionsRow.shown(true, 1));
    }

    @Test public void notShownWhileTheTvHasNotUnlockedThem() {
        assertFalse(DeveloperOptionsRow.shown(true, 0));
    }

    @Test public void switchOffNeverShowsIt() {
        assertFalse(DeveloperOptionsRow.shown(false, 1));
    }
}
