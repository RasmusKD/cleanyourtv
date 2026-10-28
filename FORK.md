# TCL BeyondTV fork

The reference launcher with four switches for a TCL BeyondTV (RT2851, Android TV 9,
`V8-R851T02-LF1V606`), all off by default (this branch's Prosonic setup turns `hide_rows_until_focus` and
`developer_options_row` on in `config.xml`; each such value says so in its comment). Upstream took the Inputs fold
([#1964](https://github.com/Gathin23/cleanyourtv/pull/1964)), the device note
([#1967](https://github.com/Gathin23/cleanyourtv/pull/1967)) and the Danish string
([#1968](https://github.com/Gathin23/cleanyourtv/pull/1968)); the rest lives here.

| Key | What it does |
|---|---|
| `hide_rows_until_focus` | With `dock_row` and `rows_below_dock` 0, Home shows only the dock; the rows under it fade in when focus moves down to them. [Screenshot](docs/switches/hide_rows_until_focus.jpg) |
| `developer_options_row` | Once developer options are unlocked, the hold-OK Settings menu gets a Developer options row, so network debugging for `tools/tv.py` is two clicks away. [Screenshot](docs/switches/developer_options_row.jpg) |
| `tcl_t_key` | The T button runs its `app_keys` entry from any app, not only Home. TCL's key service starts `com.tcl.partnercustomizer.tkey` on every press; `TKeyActivity` answers that action. |
| `vendor_key_log` | Vendor buttons that reach only the focused window (the Netflix button, 4062) run their `app_keys` entry from any app. `InputKeyService` follows the window manager's key log, which needs `READ_LOGS` (see below). |

`tcl_t_key` and `vendor_key_log` have nothing on screen to photograph, so they are exempt from
`tests/test_switches.py` here instead of having a row in `docs/switches.md`.

## Set up

In a theme's `config.json`:

```json
{
  "dock_row": true,
  "rows_below_dock": 0,
  "hide_rows_until_focus": true,
  "developer_options_row": true,
  "tcl_t_key": true,
  "vendor_key_log": true,
  "key_filter": false,
  "app_keys": [
    "4020:input:com.tcl.tvpassthrough/.TvPassThroughService/HW1413744384",
    "4062:<package of the app the Netflix button should open>"
  ]
}
```

`HW1413744384` is HDMI 2 on this set (HDMI 1 ends in 744128, HDMI 3 in 744640, HDMI 4 in
745664). Install with the key helper and the log grant:

```bash
TV_HOST=<tv ip> INPUT_KEY_SERVICE=1 RESTORE_KEY_SERVICES=1 VENDOR_KEY_LOG=1 ./launcher/build-and-install.sh
```

On Windows: `-InputKeyService -RestoreKeyServices -VendorKeyLog`.

**What `READ_LOGS` means.** It lets the launcher read the system log, which can hold other apps'
messages. The launcher reads only the window manager's and activity manager's lines, acts only on
those the system itself wrote (uid 1000, so an app cannot fake a key press), keeps nothing and has
no network permission; if you do not want that grant, leave `vendor_key_log` off and the Netflix
button works on Home only. Upstream discussion:
[#1969](https://github.com/Gathin23/cleanyourtv/issues/1969).

**What `WRITE_SECURE_SETTINGS` means.** `RESTORE_KEY_SERVICES=1` and `BACKLIGHT_KEYS=1` grant it so
the launcher can switch its own key helpers back on and step the backlight, but Android grants it
whole: the launcher could then change any secure or global setting, accessibility services and
network debugging among them. It changes only those two.

Each grant follows its switch on every install: a run without `VENDOR_KEY_LOG=1` takes `READ_LOGS`
back, and one without either `RESTORE_KEY_SERVICES=1` or `BACKLIGHT_KEYS=1` takes
`WRITE_SECURE_SETTINGS` back.

`key_filter` false keeps the accessibility service from filtering keys, so the Input button stays
as quick as with no helper; the service then only hosts the log watcher.
