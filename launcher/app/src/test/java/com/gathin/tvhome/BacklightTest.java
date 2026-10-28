package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class BacklightTest {
    @Test public void stepsOnTheGrid() {
        assertEquals(5, Backlight.next(2, 1));
        assertEquals(0, Backlight.next(2, -1));
        assertEquals(35, Backlight.next(30, 1));
        assertEquals(25, Backlight.next(30, -1));
        assertEquals(30, Backlight.next(33, -1));
    }

    @Test public void staysInRange() {
        assertEquals(100, Backlight.next(100, 1));
        assertEquals(100, Backlight.next(250, 1));
        assertEquals(0, Backlight.next(0, -1));
        assertEquals(5, Backlight.next(-3, 1));
    }
}
