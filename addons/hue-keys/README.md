# Hue Keys

Remote buttons for one Philips Hue room: on/off, brighter, dimmer and fixed levels of 25, 50 and 100 %, sent straight to the
Hue bridge on the home network. No screen and no launcher tile: each action is an activity alias that a remote
button's `app_keys` entry starts, which sends one request and finishes.

| Alias | Does |
|---|---|
| `io.github.rasmuskd.huekeys/.Toggle` | The room on if every light is off, otherwise off |
| `io.github.rasmuskd.huekeys/.Brighter` | On, and brightness up by 32 of 254 |
| `io.github.rasmuskd.huekeys/.Dimmer` | Brightness down by 32 |
| `io.github.rasmuskd.huekeys/.Low` | On at 25 % |
| `io.github.rasmuskd.huekeys/.Half` | On at 50 % |
| `io.github.rasmuskd.huekeys/.Full` | On at 100 % |

```xml
<item>KEYCODE_PROG_RED:io.github.rasmuskd.huekeys/.Toggle</item>
```

In the launcher's `app_keys` (config.xml or a theme). Like every `app_keys` entry, the buttons work on Home, and
everywhere only where the launcher's key services filter keys.

## Setup

The bridge issues an app key once its link button has been pressed: press it, then within 30 seconds

```bash
curl -s -X POST http://<bridge ip>/api -d '{"devicetype":"hue-keys#tv"}'
```

and keep the `username` it answers. The room is the group id from `GET /api/<key>/groups`. Put the three in a
file outside the repo and install:

```bash
echo '{"bridge": "192.168.1.19", "key": "...", "room": "1"}' > backup/hue-keys.json
TV_HOST=<tv ip> addons/hue-keys/install.sh backup/hue-keys.json
```

The app takes the file into its own storage and deletes it.

## Sharing the lights with an alarm

A surveillance or presence-simulation script on the same bridge can take the lights over: while a CLIP generic
flag sensor with unique id `watchpost-armed` is set, and was updated in the last 20 minutes, no button changes a
light (the TV shows a short note instead). The script sets the flag when it arms, refreshes it on every run and
clears it when it stops; a script that stops without clearing it loses the lights after 20 minutes. When the
sensors cannot be read, the buttons change nothing either.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | The Hue bridge's local API over plain HTTP on the home network, and nothing else. |

`ConfigProvider` takes the pushed file and only the ADB shell can use it (it requires `WRITE_SECURE_SETTINGS`).
Backups are off. No background service, accounts or libraries.

## Tested on

Prosonic Android TV (Vestel BODRUM, Android 14) with a Hue bridge (API v1) and one dimmable white lamp.
