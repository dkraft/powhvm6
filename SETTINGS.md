# Writable inverter settings

The Pi script (`hdmi-inverter.py`) has a small **settings registry**. The app has no
hard-coded settings: it reads `GET /api/v1/settings` and builds its editor from that.
To expose another setting, add an entry to `SETTINGS` in `hdmi-inverter.py`, deploy
the script, and it shows up in the app (Settings → Inverter settings, and as a
tappable tile on the dashboard if `field_id` matches a dashboard field).

Enabled today:

| Key | Label | Program / reg | Allowed values | Cross-limits |
|---|---|---|---|---|
| `max_charge_current` | Max charge | 02 / 5022 | 10–120 A, 10 A steps | – |
| `max_ac_charge_current` | AC chg max | 11 / 5024 | 2, 10, 20 … 100 A | – |
| `bulk_charge_voltage` | Bulk (CV) | 26 / 5027 | 48.0–60.0 V, 0.1 V | ≥ Float |
| `float_charge_voltage` | Float | 27 / 5028 | 48.0–60.0 V, 0.1 V | ≤ Bulk |
| `back_to_grid_voltage` | To grid | 12 / 5025 | 42–51 V, 1 V | ≥ Low cutoff (5029) + 1 V, ≤ To battery − 1 V |
| `back_to_battery_voltage` | To battery | 13 / 5026 | 48–58 V, 1 V | ≥ To grid + 1 V |
| `output_source_priority` | Output mode | 01 / 5018 | enum 0 Utility first, 1 Solar first, 2 SBU | – |
| `charger_source_priority` | Charge mode | 16 / 5017 | enum 1 Solar first, 2 Solar + utility, 3 Only solar | – |
| `ac_input_range` | AC range | 03 / 5019 | enum 0 Appliances (90–280 V), 1 UPS (170–280 V) | – |

* Bulk/Float need program 05 = User-defined (reg 5020 = 2). The API rejects Float above Bulk, so to lower both, write Float first; to raise both, write Bulk first.
* Program 13's LCD option "FUL" (battery fully charged) is **not** offered: its raw register value isn't documented.
* **Enum raw values.** Confirmed on this unit by reading: 5018 = 0 shows Utility first and 5017 = 1 shows Solar first, and same-value writes of both were acked and read back. The other values (5018: 1 Solar first, 2 SBU; 5017: 2 Solar + utility, 3 Only solar) come from the odya ESPHome PowMr config, which was tested on the HVM2.4H/10.2M, not on this unit. The option order matches the 6.2M manual. Verify on the LCD after the first real change.
* **AC range (5019).** 0 = Appliances (90–280 V) and 1 = UPS (170–280 V, faster transfer to battery). Both values have been read back on this unit (4538 = 0, then 1 after a UPS write on 2026-11-01), matching odya `docs/registers-map.md`, `inverter.yaml` and the 6.2M manual order. The grid here reads about 243 V / 60 Hz, so either range accepts it.
* Left out on purpose: output mode **MKS** (in the 6.2M manual, raw value unknown) and charger **0 = Utility first** (not an option in the 6.2M manual).

## Protocol facts (POW-HVM6.2M-48V-N, Modbus RTU)

* Slave 5, 2400 8N1, RS-232. Reads: function 03. Writes: function 06 (write single register).
* Status blocks 4501×45 and 4546×16 return **little-endian** words.
* Settings registers 5017–5033 are **big-endian** (confirmed on this unit by reading
  5022/5024 and comparing with 4541/4543).
* 45xx setting values are read-only mirrors; writes go to the 50xx register.
* Register map source: odya/esphome-powmr-hybrid-inverter `docs/registers-map.md`.
  Allowed values: the 6.2M-N user manual, section 5.4.

| Register | Setting (LCD program) | Status mirror |
|---|---|---|
| 5017 | Charger source priority (16) | 4536 |
| 5018 | Output source priority (01) | 4537 |
| 5019 | AC input voltage range (03): 0 Appliances, 1 UPS | 4538 |
| 5020 | Battery type (05): 0 AGM, 1 Flooded, 2 User-defined, 3 LIB, 4 LIB-485 | – |
| 5022 | Max total charge current (02) | 4541 |
| 5024 | Max utility charge current (11) | 4543 |
| 5025 / 5026 | Back to utility / back to battery voltage ×10 (12/13) | 4544 / 4545 |
| 5027 | Bulk (CV) voltage ×10 (26) | 4546 |
| 5028 | Float voltage ×10 (27) | 4547 |
| 5029 | Low DC cut-off ×10 (29) | 4548 |
| 5030–5033 | Equalization voltage/time/timeout/interval (31/33/34/35) | 4549–4552 |

## Registry entry schema

```python
{
    "key": "max_charge_current",          # API key, stable, snake_case
    "label": "Max charge",                # shown in the app
    "description": "...",                 # one line, shown in the editor
    "unit": "A",
    "field_id": "max_charge",             # dashboard field to make tappable (or None)
    "menu": 2,                            # LCD program number, for reference
    "write": {"function": 6, "register": 5022, "scale": 1},
    "read": {"block": 0, "index": 40, "scale": 1},   # where the current value is read back
    "validation": {"type": "range", "min": 10, "max": 120, "step": 10},
    #   or {"type": "options", "values": [2, 10, 20]}
    #   or {"type": "enum", "options": {0: "Utility first", 1: "Solar first"}}
    #   optional in validation (cross-setting limits), each one of:
    #     "min_of": "<key>"                              value >= other
    #     "min_of": {"key": "<key>", "margin": 1}        value >= other + 1
    #     "min_of": {"read": {"block": 1, "index": 2, "scale": 10},
    #                "label": "Low cutoff", "unit": "V", "margin": 1}   # a non-registry reading
    #     "max_of": ... same forms, value <= other - margin
    "enabled": True,                      # False = listed but read-only
    "note": "...",                        # optional warning shown on the confirm screen
}
```

`scale` converts between the user's value and the raw register value
(raw = value × scale). For voltages use `scale: 10` (54.6 V → 546).

## Adding a setting: checklist

1. Find the 50xx register and LCD program above, and the allowed values in the manual.
   Use a list of the exact allowed values. Don't guess ranges.
2. Find where the current value appears in the 45xx status blocks (`read.block`/`index`).
3. Add the entry with `"enabled": False` first and deploy. Check that
   `GET /api/v1/settings` shows the right `current` value.
4. Dry-run: `curl -X POST -H 'Content-Type: application/json'
   -d '{"key":"...","value":<current>,"dry_run":true}' http://<pi>:8088/api/v1/set`
   and check that `raw` is correct.
5. Set `"enabled": True`, deploy, and do a same-value write (write the current value).
   Expect `ack: true` and a matching `readback`.
6. Only then use the app to change it for real.

## API

`GET /api/v1/settings` returns `{api_version, model, writes_enabled, settings: [...], diagnostics}`.
Each setting has `key, label, description, unit, field_id, menu, type, options[{value,label}], current, enabled, note`.
`diagnostics` holds a read-only snapshot of 5017–5033 (`settings_block_5017`, big-endian decoded) with its time (`settings_probe_time`, Pi local time), plus some raw status words. The snapshot is taken at startup, every 10 minutes (`PROBE_INTERVAL_S`), and on the next poll loop after any acknowledged write.

`POST /api/v1/set`, body `{"key": str, "value": number, "dry_run"?: bool}`. Content-Type must be `application/json`.

| Status | Meaning |
|---|---|
| 200 | Written and read back, `ok: true` |
| 400 / 415 | Bad JSON or body size (max 1024 bytes) / wrong content type |
| 403 | Setting disabled in the registry |
| 404 | Unknown key |
| 422 | Value not allowed, or breaks a `min_of`/`max_of` limit such as float ≤ bulk (response includes `options`) |
| 502 | Inverter NAK, no reply, or readback mismatch (`ok: false`, `message`) |
| 503 | Another write is in progress or the queue is full |
| 504 | Timed out waiting for the poll thread (job cancelled) |

Every write the poll thread runs (success or failure) is appended to `~pi/powhvm6-writes.log` as one JSON line. Requests rejected before queuing (4xx) and 504 timeouts are not logged.

`GET /api/v1/now` also carries read-only grid feed state (v1.7+): `feed_ok`, `last_outage_start`, `last_outage_end`, `last_outage_seconds`, `last_outage_end_approx`, and blips (v1.8+): `last_blip_time`, `last_blip_min_voltage`, `blips_24h`. Link stats (v1.8+): `link_ms_last`, `link_ms_avg`, `frames_ok`, `frames_dropped`, `frames_1h`, `frames_dropped_1h`, `link_success_pct`. These come from AC input voltage/frequency readings, not a setting; see CONTRIBUTING.md (Feed tracker).

## Safety

* Only registry keys can be written: no raw register or command passthrough.
* Values are checked against the allowed list before anything is sent.
* Writes are serialized through the poll thread that owns the serial port.
* An inverter exception reply (0x86) is reported, not retried.
* `WRITES_ENABLED = False` at the top of the script turns every write off.
* There's no CORS preflight handler, so a web page can't POST JSON to the Pi.
