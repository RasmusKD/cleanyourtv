package com.gathin.tvhome;

/** When the Settings menu offers Developer options (developer_options_row). */
final class DeveloperOptionsRow {
    private DeveloperOptionsRow() {}

    /** developmentSettingsEnabled is Settings.Global.DEVELOPMENT_SETTINGS_ENABLED: 1 once the TV has unlocked them. */
    static boolean shown(boolean on, int developmentSettingsEnabled) {
        return on && developmentSettingsEnabled == 1;
    }
}
