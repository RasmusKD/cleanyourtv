package io.github.rasmuskd.huekeys;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HueTest {
    private static final long NOW = Hue.utcMillis("2026-10-28T20:00:00");

    private static String sensors(String flag, String updated) {
        return "{\"1\":{\"type\":\"Daylight\",\"name\":\"Daylight\",\"state\":{\"daylight\":false}},"
                + "\"7\":{\"type\":\"CLIPGenericFlag\",\"uniqueid\":\"watchpost-armed\",\"state\":{\"flag\":" + flag
                + ",\"lastupdated\":\"" + updated + "\"}}}";
    }

    @Test public void aliasesNameTheirAction() {
        assertEquals(Hue.Action.TOGGLE, Hue.action("io.github.rasmuskd.huekeys.Toggle"));
        assertEquals(Hue.Action.HALF, Hue.action("io.github.rasmuskd.huekeys.Half"));
        assertNull(Hue.action("io.github.rasmuskd.huekeys.HueKeyActivity"));
    }

    @Test public void toggleFlipsTheRoom() {
        assertEquals("{\"on\":false}", Hue.body(Hue.Action.TOGGLE, true));
        assertEquals("{\"on\":true}", Hue.body(Hue.Action.TOGGLE, false));
        assertEquals("{\"bri_inc\":-32}", Hue.body(Hue.Action.DIMMER, true));
        assertEquals("{\"on\":true,\"bri\":64}", Hue.body(Hue.Action.LOW, false));
        assertEquals("{\"on\":true,\"bri\":254}", Hue.body(Hue.Action.FULL, true));
    }

    @Test public void aFreshFlagIsArmed() throws Exception {
        assertTrue(Hue.watchpostArmed(sensors("true", "2026-10-28T19:55:00"), NOW));
    }

    @Test public void aClearedOrStaleFlagIsNot() throws Exception {
        assertFalse(Hue.watchpostArmed(sensors("false", "2026-10-28T19:59:00"), NOW));
        assertFalse(Hue.watchpostArmed(sensors("true", "2026-10-28T19:30:00"), NOW));
    }

    /** A set flag whose age cannot be read, or that lies ahead of the TV's clock, cannot be proven stale. */
    @Test public void aSetFlagOfUnknownAgeIsArmed() throws Exception {
        assertTrue(Hue.watchpostArmed(sensors("true", "none"), NOW));
        assertTrue(Hue.watchpostArmed(sensors("true", "2026-13-45T99:00:00"), NOW));
        assertTrue(Hue.watchpostArmed(sensors("true", "2026-10-29T08:00:00"), NOW));
    }

    @Test public void noWatchpostSensorIsNotArmed() throws Exception {
        assertFalse(Hue.watchpostArmed("{\"1\":{\"type\":\"Daylight\",\"state\":{}}}", NOW));
    }

    @Test(expected = org.json.JSONException.class)
    public void anErrorAnswerIsNotReadAsUnarmed() throws Exception {
        Hue.watchpostArmed("[{\"error\":{\"type\":1,\"description\":\"unauthorized user\"}}]", NOW);
    }

    @Test public void errorArraysAreRefusals() {
        assertTrue(Hue.refused("[{\"error\":{\"type\":1,\"description\":\"unauthorized user\"}}]"));
        assertFalse(Hue.refused("[{\"success\":{\"/groups/1/action/on\":true}}]"));
    }
}
