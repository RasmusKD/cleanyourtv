package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RowsUntilFocusTest {
    @Test public void appliesToADockRowWithNothingBelowIt() {
        assertTrue(RowsUntilFocus.applies(true, true, false, 0));
    }

    @Test public void switchOffNeverApplies() {
        assertFalse(RowsUntilFocus.applies(false, true, false, 0));
    }

    @Test public void needsADockRow() {
        assertFalse(RowsUntilFocus.applies(true, false, false, 0));
    }

    @Test public void aSingleRowHasNoRowsToHide() {
        assertFalse(RowsUntilFocus.applies(true, true, true, 0));
    }

    @Test public void rowsBelowDockAsksForVisibleRows() {
        assertFalse(RowsUntilFocus.applies(true, true, false, 2));
    }
}
