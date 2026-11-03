# powhvm6

HDMI console and Android dashboard for a PowMr POW-HVM6.2M-48V-N. A Python script on the Raspberry Pi polls the inverter over RS-232 Modbus RTU, draws the readings on the local tty, and serves a LAN JSON API for the phone app.

Every 2 seconds it sends the same two read-only holding-register requests the official Wi-Fi dongle sends: 45 registers at 4501, then 16 registers at 4546. The link is 2400 baud, 8N1, slave 5. Register words are little-endian. The only writes are the validated settings in the registry (see [SETTINGS.md](SETTINGS.md)).

Developers and coding agents: start with [CONTRIBUTING.md](CONTRIBUTING.md).

## HDMI console

The top of the screen is the live readings. Charging and discharging minutes are counted on the Pi from those samples. The bottom graph is load percent on a fixed 0–100 scale, with a 24-hour logarithmic time axis. Ticks are green while charging, red while discharging, and cyan while idle.

## Files

- `hdmi-inverter.py` is the display + API, installed at `/usr/local/sbin/hdmi-inverter.py`.
- `hdmi-htop.sh` starts a tmux session on tty1 with the display above a shell.
- `hdmi-tmux.conf` is the tmux config for that session.
- `hdmi-htop.service` runs that session on the HDMI console instead of getty.

The service name still says htop. Restarting it kills the tmux server, including the shell pane. To load a new copy of the script and leave the shell alone:

```
tmux -f /etc/hdmi-tmux.conf -L hdmi respawn-pane -k -c /home/pi -t main:0.0 /usr/local/sbin/hdmi-inverter.py
```

## Serial

The adapter is a Gearmo FTDI USB-RS232 on `/dev/ttyUSB0`. Use the inverter's Wi-Fi stick jack, not the battery BMS jack. Working cable on the male DB9: RJ45 pin 1 to DB9 pin 3, RJ45 pin 2 to DB9 pin 2, RJ45 pin 8 to DB9 pin 5. Leave RJ45 pin 4 open. It is +12 V.

## LAN API

The same process that owns the serial port also runs an HTTP server and a UDP discovery responder (the Pi cannot run two processes on one serial line).

| Endpoint / port | Purpose |
|---|---|
| `GET http://<ip>:8088/api/v1/now` | Live snapshot: all 32 fields (id, label, group, text, number), frame latency/drops, link status, 360-point load-% history |
| `GET http://<ip>:8088/api/v1/identity` | Name, model, HTTP port |
| `GET http://<ip>:8088/api/v1/settings` | Writable settings registry: definitions, current values, allowed options, read-only diagnostics |
| `POST http://<ip>:8088/api/v1/set` | Change one registry setting: `{"key": "...", "value": N}` (JSON only). Validated, written, read back. See [SETTINGS.md](SETTINGS.md) |
| `GET http://<ip>:8088/api/v1/events` | Event trackers: rules, count/kWh since reset, live "running now" state, last event, metric list, form defaults |
| `GET http://<ip>:8088/api/v1/events/<id>/history?limit=N` | One tracker's recorded events (start, duration, peak, avg, kWh), newest first; kept 90 days |
| `POST http://<ip>:8088/api/v1/events` | Create a tracker (JSON). `POST .../events/<id>/update\|pause\|resume\|reset\|delete` to change one. Never touches the inverter |
| UDP port 28776 | Discovery. Send `powhvm6-discover`, get back JSON with the HTTP port |

All endpoints are CORS-enabled (`Access-Control-Allow-Origin: *`) so a browser or web app can read them directly.

## Android app

Kotlin + Jetpack Compose, minSdk 26, targetSdk 34. No third-party network libraries — `org.json` + `HttpURLConnection` + `DatagramSocket`.

| Screen | What it does |
|---|---|
| Dashboard (Live tab) | Grouped field cards (live / battery / solar / status / setpoints), a live load-% graph, and a green/red status dot |
| Settings | Per-field visibility toggles, night-mode switch, IP address input with Connect, and a "Search LAN" button |
| Events (tab) | Generic power-event trackers held on the Pi (e.g. Well pump: output > 800 W for 30 s, ends < 600 W held 10 s). Cards show the rule, count, kWh since reset and a live "running now" timer; tap for history, + to add, long-press to edit/pause/reset/delete |
| Search | UDP broadcast first, then parallel /24 subnet scan; tap "Use" on a found device |

### Build

Requires JDK 17 and Android SDK 34. The Gradle wrapper is included.

```
./gradlew :app:assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

### Install

```
adb install app/build/outputs/apk/debug/app-debug.apk
```

Open the app, tap the search icon, and tap "Use" on your Pi. The app stores the IP in `SharedPreferences` and polls `/api/v1/now` every 2 seconds.

### Cleartext HTTP

The manifest sets `android:usesCleartextTraffic="true"` so the app can talk to the Pi over plain HTTP on the LAN.
