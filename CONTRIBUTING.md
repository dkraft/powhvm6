# Contributing to powhvm6

This guide is for anyone, human or coding agent, picking this project up cold. Read it before you change code. Read [Safety rules](#safety-rules) before anything that writes to the inverter.

## 1. What this is

There are two parts:

* **Pi script**: `hdmi-inverter.py`, a single Python 3 file that runs on a Raspberry Pi. It talks Modbus RTU over RS-232 to a **PowMr POW-HVM6.2M-48V-N** inverter on `/dev/ttyUSB0`. It draws live readings on the Pi's HDMI console and serves a small LAN JSON API on port **8088**, plus UDP discovery on port **28776**.
* **Android app**: `app/`, Kotlin + Jetpack Compose. It finds the Pi on the LAN, shows a dashboard, and can change a small set of inverter settings through the Pi's validated API.

The real system is a 48 V bank of 4× 12.8 V LiFePO4 batteries in series (16S). Settings changes affect real hardware. Treat every write as consequential.

## 2. Repo layout

| Path | What it is |
|---|---|
| `hdmi-inverter.py` | Pi script: Modbus poller, HDMI renderer, HTTP API, UDP discovery, settings registry. Deployed to `/usr/local/sbin/hdmi-inverter.py`. |
| `hdmi-htop.sh` | Starts tmux session `main` (socket `-L hdmi`) on tty1. Pane `main:0.0` runs the script and pane `main:0.1` is a shell. Installed at `/usr/local/sbin/hdmi-htop.sh`. |
| `hdmi-tmux.conf` | tmux config for that session, installed at `/etc/hdmi-tmux.conf`. |
| `hdmi-htop.service` | systemd unit that runs the session on tty1 instead of getty. The name still says "htop" for history; it runs the inverter display. |
| `SETTINGS.md` | Settings registry schema, register map, and the checklist for adding a writable setting. |
| `README.md` | User-facing overview: hardware, cable pinout, API table, app screens. |
| `app/src/main/java/com/dkraft/powhvm6/` | App sources (table below). |
| `build.gradle.kts`, `settings.gradle.kts`, `app/build.gradle.kts`, `gradle/` | Gradle build: AGP 8.5.2, Kotlin 1.9.24, Compose BOM 2024.06.00, compileSdk/targetSdk 34, minSdk 26, Java 17. |

App sources:

| File | Role |
|---|---|
| `Main.kt` | Activity and navigation (Dashboard / Settings / Search). |
| `MainViewModel.kt` | Polls `/api/v1/now` every 2 s, loads the settings registry, runs the edit flow (`EditStage`: LOADING → PICK → CONFIRM → WRITING → DONE/ERROR). |
| `ApiModels.kt` | JSON parsing and HTTP for `/now`, `/identity`, `/settings`, `/set` (`org.json` + `HttpURLConnection`, no third-party network libraries). |
| `Discover.kt` | UDP broadcast discovery, then a parallel /24 scan as fallback. |
| `HostAddr.kt` | Normalizes user input to `host:port` (default port 8088). |
| `Settings.kt` | SharedPreferences: host, theme mode, temperature unit (°F default / °C; the Pi API stays °C and `Field.displayText` in `ApiModels.kt` converts), hidden fields. |
| `Theme.kt` | Material 3 theme (System/Dark/Light). |
| `ui/DashboardScreen.kt` | Grouped field cards and load graph. A tile is tappable when its field id matches an enabled registry setting's `field_id`. |
| `ui/SettingsScreen.kt` | Field visibility, theme, temperature unit, host entry, and the "Inverter settings" list from the registry. |
| `ui/SettingEditor.kt` | Edit dialog: pick from the Pi-supplied options, confirm, write, show the readback. |
| `ui/SearchScreen.kt` | LAN search results. |

## 3. Architecture and data flow

```
Inverter (slave 5) ⇄ RS-232 2400 8N1 ⇄ FTDI /dev/ttyUSB0 ⇄ hdmi-inverter.py (one process)
                                                     ├─ main loop: poll every 2 s, render HDMI, run queued writes
                                                     ├─ HTTP :8088  (ThreadingHTTPServer)
                                                     └─ UDP  :28776 discovery
Android app ── HTTP GET /api/v1/now every 2 s, /settings on demand, POST /set on confirm
```

* **One process owns the serial port.** The Pi can't share a serial line between processes, so the API, discovery and writes all live in the same script. HTTP handler threads never touch the port. A `POST /set` becomes a `WriteJob` on `write_jobs`, the handler sets `wake`, and the **main poll loop** runs the job (`process_write_jobs`) between polls. `write_lock` allows one write request at a time.
* **Polling:** each cycle reads `BLOCK1 = (4501, 45)` and then `BLOCK2 = (4546, 16)` with function 03, the same requests the official Wi-Fi dongle sends. Results land in `last_blocks[0]` / `last_blocks[1]` and are formatted into `FIELDS` (`COLUMNS` defines them). A field's id is its label lower-cased with spaces turned into `_`, e.g. "AC chg max" → `ac_chg_max`.
* **50xx probe:** `probe_settings_regs` reads 5017–5033 one register at a time and stores the result for `GET /api/v1/settings` → `diagnostics` (with `settings_probe_time`). It runs after the first good poll, then every `PROBE_INTERVAL_S` (600 s), and again right after any acknowledged write (`run_write_job` clears `probe_due`). The live values come from the 45xx mirrors (`current`, `/now`).

### API (all JSON, CORS `*` on responses)

| Endpoint | Notes |
|---|---|
| `GET /api/v1/now` | `name, model, up_since, link_ok` (last good poll within 10 s), `frames[2]` (latency/drops/error), `fields[] {id,label,group,text,number}`, `history[] {age_s,pct,state}` (360 points). Feed (v1.7+): `feed_ok` (bool, null for ~6 s after start), `last_outage_start`, `last_outage_end` (null while ongoing), `last_outage_seconds`, `last_outage_end_approx` (ISO 8601 with offset; all null if no outage seen). |
| `GET /api/v1/identity` | `{name, model, http_port}`. |
| `GET /api/v1/settings` | `{api_version, model, writes_enabled, settings[], diagnostics}`. Each setting has `key,label,description,unit,field_id,menu,type,options[{value,label}],current,enabled,note`. |
| `POST /api/v1/set` | Body `{"key": str, "value": number, "dry_run"?: bool}`, `Content-Type: application/json` required, body ≤ 1024 bytes. Status codes are listed in SETTINGS.md. |
| UDP 28776 | Send bytes starting with `powhvm6-discover`. The reply is `{name, model, http_port, api_version}`. |
| `GET /` | Endpoint list. |

**Feed tracker** (`FeedTracker`): on each good block-0 read, grid is "up" when AC input ≥ `FEED_MIN_V` (80 V) and frequency ≥ `FEED_MIN_HZ` (40 Hz). A state change needs `FEED_DEBOUNCE` (3) consecutive polls (~6 s); the outage start/end is the first poll of the new state. Failed reads never count, so an RS-232 link drop is not an outage. The last outage is persisted atomically to `~pi/powhvm6-feed.json`. After a restart during an outage, the stored start is kept; if the grid is already back when the script first sees it, the end is the first poll after restart and `last_outage_end_approx` is true. The app's Feed tile (Status card) shows green OK, red "Down since…" while down, red "Last out … · duration" for 24 h after, then green with a "Last out…" subtext; no tile if the fields are missing (older script).

`API_VERSION = 2`. If you change the API shape in a breaking way, bump it and keep the app tolerant: the app uses `opt*` getters and treats a 404 on `/settings` as an old Pi script.

## 4. Modbus and register map highlights

* Slave **5**, **2400 8N1**, CRC-16/Modbus (poly 0xA001). Reads use **function 03**; writes use **function 06** (write single register). There's no multi-register write.
* **Byte order differs by range.** The 45xx status words come back **little-endian**. The 50xx settings registers are normal **big-endian**, both for reads and the value in a function 06 write. This was confirmed on this unit (5022/5024 read as BE 30/2, matching 4541/4543).
* Voltages are **×10** (54.0 V ↔ 540) and currents are ×1 A. `volts(i)` in the script divides by 10.
* 45xx setting values are **read-only mirrors**. Writes go to the 50xx register, and readback comes from the mirror.

| Register | Setting (LCD program) | Mirror (block, index) |
|---|---|---|
| 5017 | Charger source priority (16) | 4536 |
| 5018 | Output source priority (01) | 4537 |
| 5020 | Battery type (05): 2 = User-defined (required for 26/27) | – |
| 5022 | Max total charge current A (02), **editable** | 4541 (0, 40) |
| 5024 | Max utility charge current A (11), **editable** | 4543 (0, 42) |
| 5025 / 5026 | Back to utility / back to battery V×10 (12/13) | 4544 / 4545 |
| 5027 | Bulk (CV) V×10 (26), **editable** | 4546 (1, 0) |
| 5028 | Float V×10 (27), **editable** | 4547 (1, 1) |
| 5029 | Low DC cut-off V×10 (29) | 4548 |
| 5030–5033 | Equalization V/time/timeout/interval (31/33/34/35) | 4549–4552 |

The full table and its sources are in [SETTINGS.md](SETTINGS.md).

## 5. Adding an editable setting

The app has **no hard-coded settings**. It builds its editor from `GET /api/v1/settings`, and a dashboard tile becomes tappable when its field id equals a setting's `field_id`. So adding a setting is **one entry in `SETTINGS`** in `hdmi-inverter.py`, followed by a Pi deploy. No app release is needed.

Follow the checklist in [SETTINGS.md → Adding a setting](SETTINGS.md#adding-a-setting-checklist). In short:

1. Take allowed values from the manual, never guesses. `validation` is one of `{"type":"options","values":[...]}`, `{"type":"range","min","max","step"}` (expanded with integer stepping, so 0.1 V steps are exact), or `{"type":"enum","options":{raw:"label"}}`.
2. Add cross-setting limits with `"min_of"` / `"max_of"` inside `validation`: a key string, `{"key": k, "margin": m}`, or `{"read": {...}, "label", "unit", "margin"}` for a reading that isn't in the registry (see SETTINGS.md). They're checked by `check_relations()` against the latest readings, both in the HTTP handler and again in the poll thread just before writing.
3. Ship it with `"enabled": False`, deploy, and check that `current` decodes correctly. Then dry-run (`"dry_run": true`) and check `raw`.
4. Enable it and do a **same-value write** (write the current value), expecting `ack: true` and a matching `readback`. Only change it for real when the owner has confirmed.

Offline testing: the script imports `serial` at the top, so to exercise validation without hardware, put a stub `serial` module on `sys.path` and import the file with `importlib`. Then call `validate_value` / `check_relations`, filling `last_blocks` with fake register lists.

## Safety rules

These are hard rules for any change.

1. **Validated writes only.** Only keys in `SETTINGS` can be written. Every value must be in the setting's allowed list (`validate_value`). **Never add a raw register or raw command passthrough**, to the API or anywhere else.
2. **Cross-setting invariants are enforced in software:** `float ≤ bulk` (`float_charge_voltage.max_of = bulk_charge_voltage`, `bulk.min_of = float`); `To grid ≥ Low cutoff + 1 V` and `To grid ≤ To battery − 1 V`; `To battery ≥ To grid + 1 V`. Enum mode switches (output/charger priority) only offer raw values from a documented source; see SETTINGS.md for which are confirmed on this unit. Max charge should stay ≥ AC max charge (the inverter falls back to Max charge; it's in the note).
3. **Write ordering follows from the invariants.** When **lowering** both bulk and float, write **float first**, then bulk. When **raising** both, write **bulk first**, then float. The API refuses the wrong order with 422.
4. **The app confirms every write.** The editor's PICK → CONFIRM → Apply flow must stay. "Next" is disabled when the value didn't change, and the dialog can't be dismissed while WRITING. Don't add one-tap writes.
5. **Read back after every write.** `run_write_job` re-reads the mirror block and reports `ok` only if the readback matches. An inverter exception reply (0x86) is reported and **never retried**. Timed-out jobs are cancelled, never run late.
6. **Write log:** completed write requests (success or failure from the poll thread) are appended as JSON lines to `~pi/powhvm6-writes.log` (`/home/pi/powhvm6-writes.log`). Keep this.
7. **Kill switch:** `WRITES_ENABLED = False` near the top of the script disables every write. Per setting, use `"enabled": False`.
8. **No CORS preflight handler**, and POST requires `application/json`, so a browser page on the LAN can't send writes. Don't add an `OPTIONS` handler.
9. **Real settings change only with the owner's explicit confirmation.** For testing, use `dry_run` or same-value writes.
10. **Before every deploy, back up** the running script on the Pi (below). Before every real write, record the old value so you can roll it back.

**Rolling back a setting:** POST the old value to `/api/v1/set`, following the ordering rule (e.g. to go back from bulk 54.0/float 53.4 to 54.6/54.0, write bulk 54.6 first, then float 54.0). You can also set the LCD program on the inverter by hand. The write log has every old value (`old` field).

## 6. Deploying the Pi script

The Pi is reachable over SSH with key auth as host alias `pi-inverter`. Credentials aren't in this repo and must never be added. Runtime dependency: `python3-serial` (pyserial).

```sh
# 1. Back up the running copy
ssh pi-inverter 'sudo cp -p /usr/local/sbin/hdmi-inverter.py /usr/local/sbin/hdmi-inverter.py.bak-$(date +%Y%m%d-%H%M%S)'

# 2. Copy, syntax-check, install, and respawn only the display pane (the shell pane survives)
scp -q hdmi-inverter.py pi-inverter:/tmp/hdmi-inverter.py.new && ssh pi-inverter '
  python3 -m py_compile /tmp/hdmi-inverter.py.new &&
  sudo install -m 755 -o root -g root /tmp/hdmi-inverter.py.new /usr/local/sbin/hdmi-inverter.py &&
  tmux -f /etc/hdmi-tmux.conf -L hdmi respawn-pane -k -c /home/pi -t main:0.0 /usr/local/sbin/hdmi-inverter.py'

# 3. Verify (wait ~30 s for the first polls and the startup probe)
curl -s http://<pi>:8088/api/v1/now | head -c 300
curl -s http://<pi>:8088/api/v1/settings
ssh pi-inverter 'sha256sum /usr/local/sbin/hdmi-inverter.py'   # should match the committed file
```

Don't `systemctl restart hdmi-htop`: it kills the whole tmux server, including the shell pane. Respawn the pane instead.

**Rollback:** `sudo cp -p /usr/local/sbin/hdmi-inverter.py.bak-<stamp> /usr/local/sbin/hdmi-inverter.py`, then the same `respawn-pane` command. Restoring the script doesn't change any inverter setting.

Keep the deployed file identical to `main`: commit what you deploy.

## 7. Building, signing, releasing the app

Toolchain on the build box: JDK 17 at `/opt/jdk17`, Android SDK at `/opt/android-sdk` (build-tools 34.0.0). `local.properties` is git-ignored.

```sh
export JAVA_HOME=/opt/jdk17 ANDROID_HOME=/opt/android-sdk
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/app-release-unsigned.apk
```

There's no `signingConfig` in Gradle, on purpose: no signing material lives in the repo. Sign the release APK outside Gradle with build-tools:

```sh
BT=/opt/android-sdk/build-tools/34.0.0; export PATH=$JAVA_HOME/bin:$PATH
$BT/zipalign -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk /tmp/aligned.apk
$BT/apksigner sign --ks <private keystore> --ks-key-alias powhvm6 --out powhvm6-<version>.apk /tmp/aligned.apk
$BT/apksigner verify --print-certs powhvm6-<version>.apk
```

* **The release keystore is kept privately, outside this repo.** Its passwords are never committed, logged, pasted into issues or PRs, or passed on a command line that gets recorded. apksigner prompts for them.
* **Use the same key forever.** Android refuses to install an update signed with a different key. The expected signer certificate is `CN=David Kraft, O=dkraft, C=US`, SHA-256 `4639c500ec3caac9f472f3f48508d8d7171c889c1aabca83cfc8d096d49b327d`. If `verify --print-certs` shows anything else, stop.
* **Before a release:** bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`. The dashboard shows `BuildConfig.VERSION_NAME`.
* **Release:** tag `vX.Y`, GitHub release on `dkraft/powhvm6` with the signed APK attached as `powhvm6-X.Y.apk`.
* A Pi-only change (e.g. a new registry setting) needs **no** app release.

## 8. Secrets and hygiene

* Never commit or print the keystore, its passwords, the Pi's password, SSH keys, tokens or any other secret. The repo contains none; keep it that way (`git grep -i pass` should stay clean).
* `build/`, `.gradle/`, `local.properties`, `__pycache__/` and IDE files are git-ignored.
* Commits are authored as `dkraft <23946+dkraft@users.noreply.github.com>`. Keep messages short and say what changed on the Pi vs. the app.

## 9. Known quirks

* A multi-register read of 5017×17 returned bad CRCs on this unit, so the probe reads 50xx one register at a time.
* `diagnostics` in `/settings` refreshes every 10 min and after writes (see §3); check `settings_probe_time`.
* Before the registry loads, `MainViewModel.editableFieldIds()` falls back to `max_charge` and `ac_chg_max`, so those tiles give an "update the Pi" message on old scripts.
* Program 13 (back to battery, 5026) isn't editable yet. In SBU mode it interacts with float: if float is below it, the pack may never rise back to it.
