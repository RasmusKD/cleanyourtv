package com.gathin.tvhome;

/** When hide_rows_until_focus takes effect. */
final class RowsUntilFocus {
    private RowsUntilFocus() {}

    /**
     * Only for a dock row with nothing meant to show under it: rows_below_dock asks for visible rows, and a single
     * row has no rows below it.
     */
    static boolean applies(boolean on, boolean dockRow, boolean singleRow, int rowsBelowDock) {
        return on && dockRow && !singleRow && rowsBelowDock == 0;
    }
}
