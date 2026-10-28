# Remote Daemon

Remote buttons that work in every app, without an accessibility key filter and without root. A small daemon runs
as the ADB shell user, whose `input` group lets it read the remote's own input device, and runs a shell command
for each bound button: a backlight step, an add-on's activity, an app. Unbound buttons, and the bound ones too,
keep reaching Android as before.

Why not `app_keys`: an accessibility service that filters keys breaks held volume on some TVs (Vestel), and
without one the launcher sees buttons only on Home. The daemon reads below Android, so neither applies.

```
# <key> <kind> [command]; key is a name below or the Linux key code from `getevent -l`
sh bl() { ... }                 # run once in the action shell: helper functions
VOLUMEUP    smooth              # held: one step per press instead of the TV's accelerated repeat
CHANNELUP   repeat bl 1         # runs on the press and again while held
CHANNELDOWN tap    bl -1        # runs on the release when the key also has a hold command
CHANNELDOWN hold   app io.github.luyzfernando08.sleeptimer/.BedtimeActivity
GREEN       tap    app io.github.rasmuskd.huekeys/.Low
PAGEUP      tap    app com.viu.viuapp/.ui.MainActivity
```

Names: `VOLUMEUP`, `VOLUMEDOWN`, `MUTE`, `CHANNELUP`, `CHANNELDOWN`, `RED`, `GREEN`, `YELLOW`, `BLUE`, `PAGEUP`,
`PAGEDOWN`, `HOME`, `BACK`, `MENU`, `INFO`, `NUMERIC_0` to `NUMERIC_9`. Vendor buttons have plain codes: find them
with `tools/tv.py 'getevent -l'` while pressing (the Prosonic's Netflix button is `PAGEUP`, Prime Video 504).
[`daemon/prosonic.conf`](daemon/prosonic.conf) is the full Prosonic BODRUM setup.

The TV still does what a button normally does as well: P+ also changes the channel in Live TV, and a Netflix
button still tries Netflix, which fails quietly when Netflix is disabled.

`smooth` (volume, channel and arrow keys) fixes held volume on Vestel, whose `AudioService` moves a repeated volume
key by `sys.repeated_key_vol_steps` (3, a system property the shell cannot set). The press itself reaches Android as
always; after 250 ms the daemon sends an up for the key from the remote's own device id, which ends Android's repeat
before it starts, and from 300 ms adds single presses every 60 ms. It presses through the starter app's `Inject`
class, run as the shell user with `app_process` (the shell may inject input).

## Install

Network debugging on, then

```bash
TV_HOST=<tv ip> addons/remote-daemon/install.sh addons/remote-daemon/daemon/prosonic.conf
```

It builds the daemon (Go, for 32-bit ARM) and the starter app, puts the daemon and the config in
`/data/local/tmp`, and starts it. The first time, the TV asks to allow debugging for `remote-daemon@tv`: tick
**Always allow**. A reboot ends every shell-user process, so the starter app starts the daemon again after each
boot through the TV's own ADB port (127.0.0.1:5555) with that key. To change the buttons, edit the config and
run the install again.

## Full mode

`install.sh <config> full` makes the daemon take the remote over and pass its buttons on through a `uinput`
copy, so bound buttons stop doing their own thing. Try it only on a TV you can restart: creating the copy crashed
and rebooted the Prosonic BODRUM (MediaTek, Android 14) every time, so it stays in the default `watch` mode.

## Permissions

| What | Why |
|---|---|
| ADB shell user (the daemon) | Reads the remote's input device, runs the bound commands and injects smooth keys' presses. Started over ADB; it can do what `adb shell` can. |
| `INTERNET` (the starter) | Only 127.0.0.1:5555, the TV's own ADB port, to start the daemon. |
| `RECEIVE_BOOT_COMPLETED` | Starting the daemon after a reboot. |

The starter's ADB key is a shell-level key to the TV, kept in the app's private storage. Uninstalling the app and
**Revoke USB debugging authorizations** in the developer options take that access away; with network debugging
off, the daemon simply does not start after the next reboot.

## Tested on

Prosonic Android TV (Vestel BODRUM, MediaTek, Android 14): watch mode with backlight, Hue Keys, Sleep Timer and
app buttons, through standby and on with the TV off (its IR receiver still reports buttons in standby).
