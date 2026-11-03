#!/usr/bin/env python3
"""Live POW-HVM6 readout for the HDMI console, plus a small LAN API.

Monitoring is read-only Modbus. The only writes are the settings listed
in SETTINGS below, sent through POST /api/v1/set (see SETTINGS.md).

The official Wi-Fi dongle polls two holding-register blocks on slave 5
at 2400 8N1: 45 registers at 4501, then 16 registers at 4546.
Register words on the wire are little-endian.

The bottom graph is load percent on a fixed 0–100 scale. Its time axis
is logarithmic, from 24 h on the left to now on the right. Ticks are
green while the battery is charging, red while it is discharging, and
cyan while it is doing neither. Charge mins and disch mins count time
in those states since this screen started.
"""
import collections
import datetime
import json
import math
import os
import queue
import shutil
import socket
import sqlite3
import sys
import threading
import time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import serial

PORT = "/dev/ttyUSB0"
BAUD = 2400
SLAVE = 5
INTERVAL = 2.0
BLOCK1 = (4501, 45)
BLOCK2 = (4546, 16)
AGE_MIN = 2.0
AGE_MAX = 24 * 3600.0
HISTORY_KEEP = 50000
HTTP_PORT = 8088
DISCOVER_PORT = 28776
DISCOVER_MAGIC = b"powhvm6-discover"
MODEL = "POW-HVM6.2M-48V-N"
# Set to False to turn POST /api/v1/set off without touching the registry.
WRITES_ENABLED = True
API_VERSION = 3

# Linux console colors. Cyan is idle so green and red stay unambiguous.
CYAN = "\033[36m"
GREEN = "\033[32;1m"
RED = "\033[31;1m"
YELLOW = "\033[33m"
RESET = "\033[0m"


def crc(data: bytes) -> bytes:
    c = 0xFFFF
    for b in data:
        c ^= b
        for _ in range(8):
            c = (c >> 1) ^ 0xA001 if c & 1 else c >> 1
    return bytes((c & 0xFF, (c >> 8) & 0xFF))


def request(start: int, count: int) -> bytes:
    pdu = bytes(
        (
            SLAVE,
            3,
            (start >> 8) & 0xFF,
            start & 0xFF,
            (count >> 8) & 0xFF,
            count & 0xFF,
        )
    )
    return pdu + crc(pdu)


def read_regs(ser, start: int, count: int) -> list[int]:
    ser.reset_input_buffer()
    ser.write(request(start, count))
    ser.flush()
    buf = b""
    deadline = time.monotonic() + 1.5
    need = None
    while time.monotonic() < deadline:
        n = ser.in_waiting
        if n:
            buf += ser.read(n)
            if need is None and len(buf) >= 3 and buf[1] == 3:
                need = 5 + buf[2]
            if need is not None and len(buf) >= need:
                break
        else:
            time.sleep(0.02)
    if len(buf) < 5 or buf[0] != SLAVE or buf[1] != 3:
        raise TimeoutError("no reply")
    nbytes = buf[2]
    frame = buf[: 3 + nbytes]
    got = buf[3 + nbytes : 5 + nbytes]
    if len(got) < 2 or crc(frame) != got:
        raise TimeoutError("bad crc")
    data = buf[3 : 3 + nbytes]
    if len(data) < count * 2:
        raise TimeoutError("short reply")
    return [data[i] | (data[i + 1] << 8) for i in range(0, count * 2, 2)]


def await_reply(ser, need_fn, timeout=1.5) -> bytes:
    buf = b""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        n = ser.in_waiting
        if n:
            buf += ser.read(n)
            need = need_fn(buf)
            if need is not None and len(buf) >= need:
                break
        else:
            time.sleep(0.02)
    return buf


def write_reg(ser, register: int, value: int) -> dict:
    """Modbus function 06 (write single register). Returns ack/exception info.

    Settings registers (50xx) take a normal big-endian word, unlike the
    little-endian words the 45xx monitoring block returns.
    """
    if not (0 <= value <= 0xFFFF):
        raise ValueError("value out of 16-bit range")
    pdu = bytes(
        (
            SLAVE,
            6,
            (register >> 8) & 0xFF,
            register & 0xFF,
            (value >> 8) & 0xFF,
            value & 0xFF,
        )
    )
    frame = pdu + crc(pdu)
    ser.reset_input_buffer()
    ser.write(frame)
    ser.flush()

    def need(buf):
        if len(buf) >= 2 and buf[1] == 0x86:
            return 5
        if len(buf) >= 2:
            return 8
        return None

    buf = await_reply(ser, need)
    if len(buf) >= 5 and buf[0] == SLAVE and buf[1] == 0x86:
        if crc(buf[:3]) != buf[3:5]:
            return {"ack": False, "error": "bad crc on exception reply"}
        code = buf[2]
        names = {1: "illegal function", 2: "illegal address", 3: "illegal value", 4: "device failure"}
        return {"ack": False, "exception": code, "error": f"inverter NAK: {names.get(code, 'code ' + str(code))}"}
    if len(buf) < 8:
        return {"ack": False, "error": "no reply" if not buf else f"short reply {buf.hex()}"}
    if crc(buf[:6]) != buf[6:8]:
        return {"ack": False, "error": "bad crc"}
    if buf[:6] != pdu:
        return {"ack": False, "error": f"unexpected echo {buf[:8].hex()}"}
    return {"ack": True}


class Field:
    def __init__(self, label, block, fmt):
        self.label = label
        self.block = block
        self.fmt = fmt
        self.value = "—"
        self.number = None
        self.group = "other"
        self.fid = label.lower().replace(" ", "_")

    def cell(self, width: int) -> str:
        text = f"{self.label:<12} {self.value:>16}"
        if len(text) > width:
            text = text[:width]
        return text.ljust(width)


def volts(i):
    def fmt(regs):
        number = regs[i] / 10.0
        return f"{number:.1f} V", number

    return fmt


def hertz(i):
    def fmt(regs):
        number = regs[i] / 10.0
        return f"{number:.1f} Hz", number

    return fmt


def watts(i):
    def fmt(regs):
        number = float(regs[i])
        return f"{regs[i]} W", number

    return fmt


def amps(i):
    def fmt(regs):
        number = float(regs[i])
        return f"{regs[i]} A", number

    return fmt


def percent(i):
    def fmt(regs):
        number = float(regs[i])
        return f"{regs[i]} %", number

    return fmt


def fault(regs):
    return ("none" if regs[29] == 0 else str(regs[29]), regs[29] or None)


def output_mode(regs):
    return (
        {0: "Utility first", 1: "Solar first", 2: "SBU"}.get(regs[36], str(regs[36])),
        regs[36],
    )


def charge_mode(regs):
    return (
        {
            0: "Utility first",
            1: "Solar first",
            2: "Solar+utility",
            3: "Solar only",
        }.get(regs[35], str(regs[35])),
        regs[35],
    )


def ac_range(regs):
    return (
        {0: "Appliances", 1: "UPS"}.get(regs[37], str(regs[37])),
        regs[37],
    )


def target_hz(regs):
    value = regs[39]
    text = {0: "50 Hz", 1: "60 Hz"}.get(value, str(value))
    number = {0: 50.0, 1: 60.0}.get(value)
    return text, number


def heatsink(regs):
    v = regs[11]
    text = f"{v} C" if 0 <= v <= 125 else f"raw {v}"
    number = float(v) if 0 <= v <= 125 else None
    return text, number


def eq_minutes(i):
    def fmt(regs):
        number = float(regs[i])
        return f"{regs[i]} min", number

    return fmt


def eq_days(i):
    def fmt(regs):
        number = float(regs[i])
        return f"{regs[i]} days", number

    return fmt


# block 0 indexes are relative to 4501. block 1 indexes are relative to 4546.
COLUMNS = [
    [
        Field("AC voltage", 0, volts(1)),
        Field("AC frequency", 0, hertz(2)),
        Field("Load power", 0, watts(11)),
        Field("Load percent", 0, percent(13)),
        Field("Load voltage", 0, volts(9)),
        Field("Load freq", 0, hertz(10)),
        Field("Battery", 0, volts(5)),
        Field("Battery SoC", 0, percent(6)),
        Field("PV voltage", 0, volts(3)),
        Field("PV power", 0, watts(4)),
    ],
    [
        Field("Charge", 0, amps(7)),
        Field("Discharge", 0, amps(8)),
        Field("Charging", -1, None),
        Field("Discharging", -1, None),
        Field("Fault", 0, fault),
        Field("Output mode", 0, output_mode),
        Field("Charge mode", 0, charge_mode),
        Field("AC range", 0, ac_range),
        Field("Target V", 0, lambda regs: (f"{regs[41]} V", float(regs[41]))),
        Field("Target Hz", 0, target_hz),
        Field("Max charge", 0, amps(40)),
        Field("AC chg max", 0, amps(42)),
    ],
    [
        Field("To grid", 0, volts(43)),
        Field("To battery", 0, volts(44)),
        Field("Heatsink", 1, heatsink),
        Field("Bulk", 1, volts(0)),
        Field("Float", 1, volts(1)),
        Field("Low cutoff", 1, volts(2)),
        Field("Equalize", 1, volts(3)),
        Field("Eq time", 1, eq_minutes(4)),
        Field("Eq timeout", 1, eq_minutes(5)),
        Field("Eq every", 1, eq_days(6)),
    ],
]
FIELDS = [field for column in COLUMNS for field in column]
FIELD_GROUPS = {
    "AC voltage": "live",
    "AC frequency": "live",
    "Load power": "live",
    "Load percent": "live",
    "Load voltage": "live",
    "Load freq": "live",
    "Battery": "battery",
    "Battery SoC": "battery",
    "Charge": "battery",
    "Discharge": "battery",
    "Charging": "battery",
    "Discharging": "battery",
    "PV voltage": "solar",
    "PV power": "solar",
    "Fault": "status",
    "Output mode": "status",
    "Charge mode": "status",
    "AC range": "status",
    "Heatsink": "status",
    "Target V": "setpoints",
    "Target Hz": "setpoints",
    "Max charge": "setpoints",
    "AC chg max": "setpoints",
    "To grid": "setpoints",
    "To battery": "setpoints",
    "Bulk": "setpoints",
    "Float": "setpoints",
    "Low cutoff": "setpoints",
    "Equalize": "setpoints",
    "Eq time": "setpoints",
    "Eq timeout": "setpoints",
    "Eq every": "setpoints",
}
for _field in FIELDS:
    _field.group = FIELD_GROUPS.get(_field.label, "other")


# ---------------------------------------------------------------------------
# Writable settings registry. Adding a setting is one entry here; the API and
# the Android app pick it up automatically. See SETTINGS.md.
#
#   key         stable id used by POST /api/v1/set
#   label/unit  shown in the app editor
#   field_id    dashboard field this setting edits (Field.fid), or None
#   menu        inverter LCD program number, for reference
#   write       Modbus write: function 6 to `register`, raw = value * scale
#   read        where the current value comes from: (block, index) in the
#               polled blocks, decoded as raw / scale
#   validation  exactly one of:
#                 {"type": "options", "values": [..]}        numeric list
#                 {"type": "range", "min": a, "max": b, "step": s}
#                 {"type": "enum", "options": {raw_int: "label", ..}}
#   enabled     False keeps it listed but refuses writes
#   note        optional hint shown in the editor
# Allowed values below are the LCD options in the POW-HVM3.6/4.2/6.2M-N
# manual (V1.3.0514) for the 6.2 kW 48 V model.
# ---------------------------------------------------------------------------
SETTINGS = [
    {
        "key": "max_charge_current",
        "label": "Max charge",
        "description": "Maximum total charging current (solar + utility)",
        "unit": "A",
        "field_id": "max_charge",
        "menu": 2,
        "write": {"function": 6, "register": 5022, "scale": 1},
        "read": {"block": 0, "index": 40, "scale": 1},
        "validation": {"type": "range", "min": 10, "max": 120, "step": 10},
        "enabled": True,
        "note": "Must be at least the AC max charge; set below your battery/BMS limit.",
    },
    {
        "key": "max_ac_charge_current",
        "label": "AC chg max",
        "description": "Maximum utility (AC) charging current",
        "unit": "A",
        "field_id": "ac_chg_max",
        "menu": 11,
        "write": {"function": 6, "register": 5024, "scale": 1},
        "read": {"block": 0, "index": 42, "scale": 1},
        "validation": {"type": "options", "values": [2, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100]},
        "enabled": True,
        "note": "If Max charge is lower, the inverter uses Max charge for AC charging.",
    },
    {
        # Program 26. Only used when battery type (program 05) is User-defined.
        # 5027 holds volts x10, big-endian; mirrored in 4546 (block 1, index 0).
        "key": "bulk_charge_voltage",
        "label": "Bulk (CV)",
        "description": "Bulk / constant-voltage charging target for the pack",
        "unit": "V",
        "field_id": "bulk",
        "menu": 26,
        "write": {"function": 6, "register": 5027, "scale": 10},
        "read": {"block": 1, "index": 0, "scale": 10},
        "validation": {"type": "range", "min": 48.0, "max": 60.0, "step": 0.1,
                       "min_of": "float_charge_voltage"},
        "enabled": True,
        "note": "Must be at least the float voltage. LiFePO4 16S: divide by 16 for volts per cell.",
    },
    {
        # Program 27. 5028 holds volts x10, big-endian; mirrored in 4547.
        "key": "float_charge_voltage",
        "label": "Float",
        "description": "Float (hold) charging voltage for the pack",
        "unit": "V",
        "field_id": "float",
        "menu": 27,
        "write": {"function": 6, "register": 5028, "scale": 10},
        "read": {"block": 1, "index": 1, "scale": 10},
        "validation": {"type": "range", "min": 48.0, "max": 60.0, "step": 0.1,
                       "max_of": "bulk_charge_voltage"},
        "enabled": True,
        "note": "Must not exceed the bulk (CV) voltage. Lower it first before lowering bulk.",
    },
    {
        # Program 12 (SBU / Solar first): battery voltage at which loads go
        # back to utility. 5025 holds volts x10, big-endian; mirrored in 4544
        # (block 0, index 43). 6.2 kW options: 42-51 V in 1 V steps.
        "key": "back_to_grid_voltage",
        "label": "To grid",
        "description": "Battery voltage where loads switch back to utility (SBU / Solar first)",
        "unit": "V",
        "field_id": "to_grid",
        "menu": 12,
        "write": {"function": 6, "register": 5025, "scale": 10},
        "read": {"block": 0, "index": 43, "scale": 10},
        "validation": {"type": "range", "min": 42, "max": 51, "step": 1,
                       "min_of": {"read": {"block": 1, "index": 2, "scale": 10},
                                  "label": "Low cutoff", "unit": "V", "margin": 1},
                       "max_of": {"key": "back_to_battery_voltage", "margin": 1}},
        "enabled": True,
        "note": "Must be at least 1 V above Low cutoff and 1 V below To battery.",
    },
    {
        # Program 13: battery voltage at which loads return to battery.
        # 5026 holds volts x10, big-endian; mirrored in 4545 (block 0, index 44).
        # 6.2 kW options: 48-58 V in 1 V steps. The LCD "FUL" (battery fully
        # charged) option is left out: its raw register value isn't documented.
        "key": "back_to_battery_voltage",
        "label": "To battery",
        "description": "Battery voltage where loads switch back to battery (SBU / Solar first)",
        "unit": "V",
        "field_id": "to_battery",
        "menu": 13,
        "write": {"function": 6, "register": 5026, "scale": 10},
        "read": {"block": 0, "index": 44, "scale": 10},
        "validation": {"type": "range", "min": 48, "max": 58, "step": 1,
                       "min_of": {"key": "back_to_grid_voltage", "margin": 1}},
        "enabled": True,
        "note": "Must be at least 1 V above To grid. Keep it at or below what charging actually reaches (float).",
    },
    {
        # Program 01. 5018, mirrored in 4537 (block 0, index 36). Raw values
        # from the odya ESPHome PowMr config (tested on HVM2.4H/10.2M);
        # 0 = Utility first matches this unit's live reading. MKS (in the
        # 6.2 kW manual) is left out: its raw value isn't documented.
        "key": "output_source_priority",
        "label": "Output mode",
        "description": "Output source priority: which source powers the loads first",
        "unit": "",
        "field_id": "output_mode",
        "menu": 1,
        "write": {"function": 6, "register": 5018, "scale": 1},
        "read": {"block": 0, "index": 36, "scale": 1},
        "validation": {"type": "enum", "options": {
            0: "Utility first (USB)",
            1: "Solar first (SUB)",
            2: "SBU priority (solar, battery, then utility)"}},
        "enabled": True,
        "note": "Changes where the house load comes from. SBU drains the battery to To grid before using utility.",
    },
    {
        # Program 16. 5017, mirrored in 4536 (block 0, index 35). Raw values
        # from the odya ESPHome PowMr config; 1 = Solar first matches this
        # unit's live reading. 0 (Utility first) is left out: the 6.2 kW
        # manual has no such charger option.
        "key": "charger_source_priority",
        "label": "Charge mode",
        "description": "Charger source priority: what may charge the battery",
        "unit": "",
        "field_id": "charge_mode",
        "menu": 16,
        "write": {"function": 6, "register": 5017, "scale": 1},
        "read": {"block": 0, "index": 35, "scale": 1},
        "validation": {"type": "enum", "options": {
            1: "Solar first (utility only when no solar)",
            2: "Solar + utility",
            3: "Only solar (never charge from utility)"}},
        "enabled": True,
        "note": "Only applies on utility/standby; on battery only solar charges.",
    },
    {
        # Program 03. 5019, mirrored in 4538 (block 0, index 37). Raw values
        # from odya docs/registers-map.md ("0 - 90-280VAC, 1 - 170-280VAC") and
        # src/modules/inverter.yaml (0 Appliances, 1 UPS); the 6.2 kW manual lists
        # APL (default) then UPS. Both seen on this unit (4538 read 0, then 1
        # after a UPS write on 2026-11-01).
        "key": "ac_input_range",
        "label": "AC range",
        "description": "AC input voltage range accepted from the grid",
        "unit": "",
        "field_id": "ac_range",
        "menu": 3,
        "write": {"function": 6, "register": 5019, "scale": 1},
        "read": {"block": 0, "index": 37, "scale": 1},
        "validation": {"type": "enum", "options": {
            0: "Appliances (90-280 V, wide)",
            1: "UPS (170-280 V, narrow, faster transfer)"}},
        "enabled": True,
        "note": "Appliances accepts 90-280 V input. UPS accepts 170-280 V and switches to battery faster.",
    },
]
SETTINGS_BY_KEY = {s["key"]: s for s in SETTINGS}

# Latest raw register blocks, kept for settings reads.
last_blocks = {0: None, 1: None}


def allowed_values(setting) -> list:
    v = setting["validation"]
    if v["type"] == "options":
        return list(v["values"])
    if v["type"] == "range":
        n = int(round((v["max"] - v["min"]) / v["step"]))
        return [round(v["min"] + i * v["step"], 4) for i in range(n + 1)]
    if v["type"] == "enum":
        return [int(k) for k in v["options"]]
    raise ValueError("bad validation type")


def option_list(setting) -> list[dict]:
    v = setting["validation"]
    unit = setting.get("unit", "")
    if v["type"] == "enum":
        return [{"value": int(k), "label": str(lbl)} for k, lbl in v["options"].items()]
    return [{"value": x, "label": f"{x:g} {unit}".strip()} for x in allowed_values(setting)]


def setting_current(setting):
    r = setting["read"]
    regs = last_blocks.get(r["block"])
    if regs is None or r["index"] >= len(regs):
        return None
    value = regs[r["index"]] / r.get("scale", 1)
    return int(value) if float(value).is_integer() else value


def validate_value(setting, value):
    """Return the raw register word for value, or raise ValueError."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError("value must be a number")
    allowed = allowed_values(setting)
    match = next((a for a in allowed if abs(a - value) < 1e-6), None)
    if match is None:
        raise ValueError(f"{value} is not an allowed value for {setting['key']}")
    raw = int(round(match * setting["write"].get("scale", 1)))
    if not (0 <= raw <= 0xFFFF):
        raise ValueError("raw value out of range")
    return match, raw


def _relation_ref(spec):
    """A relation target: "<key>" or {"key": k, "margin": m} or
    {"read": {...}, "label": l, "unit": u, "margin": m}. Returns (label, unit, current, margin)."""
    if isinstance(spec, str):
        spec = {"key": spec}
    margin = float(spec.get("margin", 0))
    if "key" in spec:
        other = SETTINGS_BY_KEY[spec["key"]]
        return other["label"], other.get("unit", ""), setting_current(other), margin
    return spec.get("label", "?"), spec.get("unit", ""), setting_current({"read": spec["read"]}), margin


def check_relations(setting, value) -> None:
    """Cross-setting limits (e.g. float <= bulk), against current readings."""
    v = setting["validation"]
    for rel in ("min_of", "max_of"):
        spec = v.get(rel)
        if not spec:
            continue
        label, unit, cur, margin = _relation_ref(spec)
        if cur is None:
            raise ValueError(f"{label} hasn't been read yet; try again")
        if rel == "min_of":
            ok, limit, word = value >= cur + margin - 1e-6, cur + margin, "below"
        else:
            ok, limit, word = value <= cur - margin + 1e-6, cur - margin, "above"
        if not ok:
            extra = f" {'+' if rel == 'min_of' else '-'} {margin:g}" if margin else ""
            raise ValueError(f"{setting['label']} can't be {word} {limit:g} {unit} "
                             f"({label} {cur:g} {unit}{extra})".replace("  ", " "))


def settings_payload() -> dict:
    with api_lock:
        out = []
        for s in SETTINGS:
            cur = setting_current(s)
            out.append(
                {
                    "key": s["key"],
                    "label": s["label"],
                    "description": s.get("description", ""),
                    "unit": s.get("unit", ""),
                    "field_id": s.get("field_id"),
                    "menu": s.get("menu"),
                    "type": s["validation"]["type"],
                    "options": option_list(s),
                    "current": cur,
                    "enabled": bool(s.get("enabled", True)) and WRITES_ENABLED,
                    "note": s.get("note", ""),
                }
            )
        diag = api_state.get("probe_50xx")
        block5017 = api_state.get("probe_5017_block")
        probe_time = api_state.get("probe_time")
    b0, b1 = last_blocks.get(0), last_blocks.get(1)
    status_raw = {}
    if b0 and len(b0) >= 45:
        status_raw.update({str(4501 + i): b0[i] for i in (15, 29, 34, 35, 36, 37)})
    if b1 and len(b1) >= 16:
        status_raw.update({str(4546 + i): b1[i] for i in range(7, 16)})
    return {"api_version": API_VERSION, "model": MODEL, "writes_enabled": WRITES_ENABLED,
            "settings": out,
            "diagnostics": {"settings_registers_raw": diag,
                            "settings_block_5017": block5017,
                            "settings_probe_time": probe_time,
                            "status_raw": status_raw}}


# Write jobs are run by the poll thread, which owns the serial port.
write_jobs: "queue.Queue" = queue.Queue(maxsize=4)
write_lock = threading.Lock()  # one write request in flight at a time
wake = threading.Event()


class WriteJob:
    def __init__(self, setting, value, raw):
        self.setting = setting
        self.value = value
        self.raw = raw
        self.done = threading.Event()
        self.result = None
        self.cancelled = False


def run_write_job(ser, job: WriteJob) -> dict:
    s = job.setting
    old = setting_current(s)
    reg = s["write"]["register"]
    res = {"key": s["key"], "requested": job.value, "old": old, "register": reg}
    try:
        check_relations(s, job.value)  # re-check against the freshest readings
    except ValueError as exc:
        res.update({"ack": False, "error": str(exc), "readback": old, "ok": False,
                    "message": str(exc)})
        return res
    try:
        ack = write_reg(ser, reg, job.raw)
    except Exception as exc:
        ack = {"ack": False, "error": f"{type(exc).__name__}: {exc}"}
    res.update(ack)
    # Re-read the block the setting is shown in, so the reply has the value
    # the inverter now reports.
    readback = None
    read_err = ""
    if ack.get("ack"):
        time.sleep(0.5)
        start, count = BLOCK1 if s["read"]["block"] == 0 else BLOCK2
        for _ in range(3):
            try:
                regs = read_regs(ser, start, count)
                with api_lock:
                    last_blocks[s["read"]["block"]] = regs
                apply_block(s["read"]["block"], regs)
                readback = setting_current(s)
                if readback is not None and abs(readback - job.value) < 1e-6:
                    break
            except Exception as exc:
                read_err = f"{type(exc).__name__}: {exc}"
            time.sleep(0.7)
    res["readback"] = readback
    res["ok"] = bool(ack.get("ack")) and readback is not None and abs(readback - job.value) < 1e-6
    if ack.get("ack"):
        api_state["probe_due"] = 0  # refresh the 50xx diagnostics snapshot next loop
    if res["ok"]:
        shown = s["validation"].get("options", {}).get(int(readback)) if s["validation"]["type"] == "enum" else None
        res["message"] = (f"{s['label']} is now {shown}" if shown else
                          f"{s['label']} is now {readback} {s.get('unit', '')}".strip())
    elif ack.get("ack"):
        res["message"] = f"Inverter acknowledged, but reads back {readback}" + (f" ({read_err})" if read_err else "")
    else:
        res["message"] = res.get("error", "write failed")
    return res


WRITE_LOG = os.path.expanduser("~/powhvm6-writes.log")


def log_write(key, result) -> None:
    try:
        with open(WRITE_LOG, "a") as f:
            f.write(json.dumps({"t": time.strftime("%Y-%m-%d %H:%M:%S"), "key": key, **result}) + "\n")
    except Exception:
        pass


PROBE_INTERVAL_S = 600


def probe_settings_regs(ser) -> None:
    """Read-only look at the 50xx settings registers, for diagnostics.

    Runs at startup, every PROBE_INTERVAL_S, and right after a successful
    write (run_write_job clears probe_due)."""
    if time.monotonic() < api_state.get("probe_due", 0):
        return
    api_state["probe_due"] = time.monotonic() + PROBE_INTERVAL_S
    out = {}
    for s in SETTINGS:
        reg = s["write"]["register"]
        try:
            le = read_regs(ser, reg, 1)[0]
            out[str(reg)] = {"le": le, "be": ((le & 0xFF) << 8) | (le >> 8)}
        except Exception as exc:
            out[str(reg)] = {"error": f"{type(exc).__name__}: {exc}"}
    # Read-only snapshot, one register at a time, of 5017-5033 settings block (battery type,
    # charger priority, bulk/float/cut-off etc.) so they can be inspected.
    # These registers are big-endian, unlike the 45xx status blocks.
    block = {}
    for reg in range(5017, 5034):
        try:
            w = read_regs(ser, reg, 1)[0]
            block[str(reg)] = ((w & 0xFF) << 8) | (w >> 8)
        except Exception as exc:
            block[str(reg)] = f"{type(exc).__name__}: {exc}"
    with api_lock:
        api_state["probe_50xx"] = out
        api_state["probe_5017_block"] = block
        api_state["probe_time"] = time.strftime("%Y-%m-%d %H:%M:%S")


def process_write_jobs(ser) -> None:
    while True:
        try:
            job = write_jobs.get_nowait()
        except queue.Empty:
            return
        if job.cancelled:
            job.done.set()
            continue
        try:
            job.result = run_write_job(ser, job)
        finally:
            job.done.set()


def column_for_age(age: float, n: int):
    """Map an age in seconds onto a log-time column. Right edge is now."""
    if n <= 0 or age > AGE_MAX:
        return None
    if n == 1:
        return 0
    age = AGE_MIN if age < AGE_MIN else age
    if age >= AGE_MAX:
        return 0
    frac = math.log(age / AGE_MIN) / math.log(AGE_MAX / AGE_MIN)
    x = int((1.0 - frac) * (n - 1) + 1e-9)
    if x < 0:
        return 0
    if x > n - 1:
        return n - 1
    return x


def tick_color(charge: float, discharge: float) -> str:
    if charge > discharge and charge > 0:
        return GREEN
    if discharge > charge and discharge > 0:
        return RED
    if charge > 0 and discharge > 0:
        return YELLOW
    return CYAN


def bins_for(history, now: float, n: int):
    """Average load percent in each log-time column, plus charge totals."""
    total_pct = [0.0] * n
    total_c = [0.0] * n
    total_d = [0.0] * n
    count = [0] * n
    for stamp, pct, charge, discharge in history:
        age = now - stamp
        col = column_for_age(age, n)
        if col is None:
            continue
        total_pct[col] += pct
        total_c[col] += charge
        total_d[col] += discharge
        count[col] += 1
    return total_pct, total_c, total_d, count


def time_axis(n: int) -> str:
    marks = (
        (AGE_MAX, "24h"),
        (6 * 3600, "6h"),
        (3600, "1h"),
        (600, "10m"),
        (60, "1m"),
        (0, "now"),
    )
    chars = [" "] * n
    occupied = [False] * n
    for age, text in reversed(marks):
        col = n - 1 if age <= AGE_MIN else column_for_age(age, n)
        if col is None:
            continue
        start = col - (len(text) // 2)
        start = max(0, min(start, n - len(text)))
        span = range(max(0, start - 1), min(n, start + len(text) + 1))
        if any(occupied[i] for i in span):
            continue
        for i, ch in enumerate(text):
            chars[start + i] = ch
            occupied[start + i] = True
    return "".join(chars)


def graph_lines(history, now: float, cols: int, graph_h: int) -> list[str]:
    gutter_w = 6
    plot_w = max(10, cols - 1 - gutter_w)
    total_pct, total_c, total_d, count = bins_for(history, now, plot_w)
    filled = []
    colors = []
    for i, n in enumerate(count):
        if not n:
            filled.append(0)
            colors.append(CYAN)
            continue
        avg = total_pct[i] / n
        cells = int(round(avg / 100.0 * graph_h))
        if avg > 0 and cells < 1:
            cells = 1
        if cells > graph_h:
            cells = graph_h
        filled.append(cells)
        colors.append(tick_color(total_c[i], total_d[i]))
    lines = []
    for row in range(graph_h):
        above = graph_h - 1 - row
        if row == 0:
            label = f"{100:5.0f}"
        elif row == graph_h // 2:
            label = f"{50:5.0f}"
        elif row == graph_h - 1:
            label = f"{0:5.0f}"
        else:
            label = "     "
        parts = [f"{label}|"]
        for x in range(plot_w):
            if filled[x] > above:
                parts.append(f"{colors[x]}|{RESET}")
            else:
                parts.append(" ")
        lines.append("".join(parts))
    axis = f"{'load%':>5} {time_axis(plot_w)}"
    lines.append(axis)
    return lines


def frame_summary(lat1, drops1, err1, lat2, drops2, err2) -> str:
    def one(n, lat, drops):
        lat_s = "  — ms" if lat is None else f"{lat:4.0f} ms"
        drop_s = f"{drops}"
        if drops:
            drop_s = f"{RED}{drops}{RESET}"
        return f"frame {n}  {lat_s}  drops {drop_s}"

    if err1 and err2:
        state = f"{RED}no reply{RESET}"
    elif err1 or err2:
        state = f"{YELLOW}{err1 or err2}{RESET}"
    else:
        state = f"{GREEN}link ok{RESET}"
    legend = (
        f"{CYAN}|{RESET} idle   {GREEN}|{RESET} charging   {RED}|{RESET} discharging"
    )
    return f"{one(1, lat1, drops1)}    {one(2, lat2, drops2)}    {state}    {legend}"


def render(history, now, lat1, drops1, err1, lat2, drops2, err2, cols, rows) -> str:
    clock = time.strftime("%H:%M:%S")
    title_vis = "POW-HVM6  6.2 kW"
    pad = cols - 1 - len(title_vis) - len(clock)
    if pad < 1:
        pad = 1
    lines = [f"{CYAN}\033[1mPOW-HVM6{RESET}  6.2 kW{' ' * pad}{clock}"]
    value_rows = max(len(column) for column in COLUMNS)
    col_w = max(20, (cols - 1) // 3)
    for i in range(value_rows):
        parts = []
        for column in COLUMNS:
            field = column[i] if i < len(column) else None
            parts.append(field.cell(col_w) if field else " " * col_w)
        lines.append("".join(parts))
    lines.append(frame_summary(lat1, drops1, err1, lat2, drops2, err2))
    # title + values + frame line + time axis, the rest is the plot
    graph_h = rows - len(lines) - 1
    if graph_h < 4:
        graph_h = 4
    lines.extend(graph_lines(history, now, cols, graph_h))
    if len(lines) > rows:
        lines = lines[:rows]
    return "\033[H" + "\n".join(line + "\033[K" for line in lines) + "\033[J"


def open_port():
    return serial.Serial(
        PORT,
        BAUD,
        timeout=0.05,
        write_timeout=1,
        rtscts=False,
        dsrdtr=False,
        exclusive=True,
    )


def poll_block(ser, start, count):
    """Return (regs, rtt_ms, error, port_dead)."""
    t0 = time.monotonic()
    try:
        regs = read_regs(ser, start, count)
    except Exception as exc:
        return None, None, f"{type(exc).__name__}: {exc}", isinstance(exc, OSError)
    return regs, (time.monotonic() - t0) * 1000.0, "", False


def apply_block(block: int, regs) -> None:
    for field in FIELDS:
        if field.block != block:
            continue
        try:
            field.value, field.number = field.fmt(regs)
        except Exception as exc:
            field.value = str(exc)
            field.number = None


def close_port(ser):
    if ser is None:
        return None
    try:
        ser.close()
    except Exception:
        pass
    return None


class Activity:
    """Seconds spent charging or discharging since this screen started."""

    def __init__(self):
        self.charge_s = 0.0
        self.disch_s = 0.0
        self.last_t = None
        self.last_c = 0
        self.last_d = 0

    def note(self, stamp: float, charge: float, discharge: float) -> None:
        if self.last_t is not None:
            dt = stamp - self.last_t
            # Skip outages so a dead link is not counted as charge time.
            if 0 < dt <= 15:
                if self.last_c > self.last_d and self.last_c > 0:
                    self.charge_s += dt
                elif self.last_d > self.last_c and self.last_d > 0:
                    self.disch_s += dt
        self.last_t = stamp
        self.last_c = charge
        self.last_d = discharge

    @staticmethod
    def fmt(seconds: float) -> str:
        minutes = seconds / 60.0
        if minutes < 100:
            return f"{minutes:.1f} min"
        return f"{minutes:.0f} min"


# Shared API state. The poll thread writes, HTTP threads read.
history: deque = deque(maxlen=HISTORY_KEEP)
history_lock = threading.Lock()
FEED_FILE = os.path.expanduser("~/powhvm6-feed.json")
FEED_MIN_V = 80.0      # AC input below this (or below FEED_MIN_HZ) counts as no grid
FEED_MIN_HZ = 40.0
FEED_DEBOUNCE = 3      # consecutive good block-0 polls (~6 s at INTERVAL 2 s) to change state
FEED_SAG_V = 200.0     # blip: any good poll below this (or FEED_MIN_HZ) that doesn't become an outage
BLIP_WINDOW_S = 24 * 3600
BLIP_KEEP = 500        # cap on stored blip timestamps


def iso_local(ts):
    """Epoch seconds to ISO 8601 with the Pi's UTC offset, or None."""
    if ts is None:
        return None
    return datetime.datetime.fromtimestamp(ts).astimezone().isoformat(timespec="seconds")


class FeedTracker:
    """Grid (AC input) presence with debounce and a persisted last-outage record.

    Only fed from good block-0 reads, so a Modbus link failure is "unknown",
    never an outage. The record survives restarts via FEED_FILE.
    """

    def __init__(self, path=FEED_FILE):
        self.path = path
        self.ok = None          # None until the first debounced decision
        self.start = None       # epoch of the last outage start
        self.end = None         # epoch of its end, None while ongoing
        self.end_approx = False  # end time is when the script saw the grid again after a restart
        self._run_state = None
        self._run = 0
        self._first_seen = None
        self.blips = []           # epoch starts of blips (pruned to BLIP_WINDOW_S)
        self.last_blip = None     # epoch start of the most recent blip (kept past 24 h)
        self.last_blip_min_v = None
        self._sag_start = None    # current run of sagging polls
        self._sag_min = None
        self._sag_outage = False  # this run turned into a full outage
        try:
            with open(path) as f:
                d = json.load(f)
            self.start = d.get("start")
            self.end = d.get("end")
            self.end_approx = bool(d.get("end_approx"))
            self.blips = [float(t) for t in d.get("blips") or []]
            self.last_blip = d.get("last_blip")
            self.last_blip_min_v = d.get("last_blip_min_v")
        except (OSError, ValueError, TypeError):
            pass

    def _save(self):
        tmp = self.path + ".tmp"
        try:
            with open(tmp, "w") as f:
                json.dump({"start": self.start, "end": self.end, "end_approx": self.end_approx,
                           "blips": self.blips, "last_blip": self.last_blip,
                           "last_blip_min_v": self.last_blip_min_v}, f)
            os.replace(tmp, self.path)
        except OSError:
            pass

    @staticmethod
    def grid_up(regs):
        return regs[1] / 10.0 >= FEED_MIN_V and regs[2] / 10.0 >= FEED_MIN_HZ

    def _prune(self, now):
        self.blips = [t for t in self.blips if now - t < BLIP_WINDOW_S][-BLIP_KEEP:]

    def _note_sag(self, regs, now):
        v, hz = regs[1] / 10.0, regs[2] / 10.0
        if v < FEED_SAG_V or hz < FEED_MIN_HZ:
            if self._sag_start is None:
                self._sag_start, self._sag_min, self._sag_outage = now, v, False
            else:
                self._sag_min = min(self._sag_min, v)
            return
        if self._sag_start is not None:
            if not self._sag_outage:
                self._prune(now)
                self.blips.append(self._sag_start)
                self.last_blip, self.last_blip_min_v = self._sag_start, round(self._sag_min, 1)
                self._save()
            self._sag_start = None

    def note(self, regs, now=None):
        now = time.time() if now is None else now
        self._note_sag(regs, now)
        up = self.grid_up(regs)
        if up == self._run_state:
            self._run += 1
        else:
            self._run_state, self._run = up, 1
            self._first_seen = now
        if self._run < FEED_DEBOUNCE or up == self.ok:
            return
        prev, self.ok = self.ok, up
        if not up:
            if self._sag_start is not None:
                self._sag_outage = True  # a real outage, not a blip
            if prev is None and self.start is not None and self.end is None:
                return  # restarted during an outage that's already recorded
            self.start, self.end, self.end_approx = self._first_seen, None, False
            self._save()
        elif self.start is not None and self.end is None:
            # Grid back. If we only learn this right after startup, the real
            # return time is unknown: use now and flag it.
            self.end = self._first_seen
            self.end_approx = prev is None
            self._save()

    def api(self):
        secs = None
        if self.start is not None:
            secs = int(round((self.end if self.end is not None else time.time()) - self.start))
        return {
            "feed_ok": self.ok,
            "last_outage_start": iso_local(self.start),
            "last_outage_end": iso_local(self.end),
            "last_outage_seconds": secs,
            "last_outage_end_approx": self.end_approx if self.end is not None else None,
            "last_blip_time": iso_local(self.last_blip),
            "last_blip_min_voltage": self.last_blip_min_v,
            "blips_24h": sum(1 for t in self.blips if time.time() - t < BLIP_WINDOW_S),
        }


feed = FeedTracker()


class LinkStats:
    """Pi -> inverter Modbus RTU frame stats. One frame = one block read
    (2 per cycle). Dropped = timeout, bad CRC, short/garbled reply, or a
    serial error. Counts are since script start plus a rolling hour."""

    AVG_N = 100  # rolling average over the last N good frames

    def __init__(self):
        self.ok = 0
        self.dropped = 0
        self.last_ms = None
        self._rtts = collections.deque(maxlen=self.AVG_N)
        self._hour = collections.deque()  # (monotonic ts, ok?)

    def _push(self, ok):
        now = time.monotonic()
        self._hour.append((now, ok))
        while self._hour and now - self._hour[0][0] > 3600:
            self._hour.popleft()

    def good(self, rtt_ms):
        self.ok += 1
        self.last_ms = rtt_ms
        self._rtts.append(rtt_ms)
        self._push(True)

    def drop(self):
        self.dropped += 1
        self._push(False)

    def api(self):
        total = self.ok + self.dropped
        h_total = len(self._hour)
        h_drop = sum(1 for _, ok in self._hour if not ok)
        avg = sum(self._rtts) / len(self._rtts) if self._rtts else None
        return {
            "link_ms_last": None if self.last_ms is None else round(self.last_ms, 1),
            "link_ms_avg": None if avg is None else round(avg, 1),
            "frames_ok": self.ok,
            "frames_dropped": self.dropped,
            "frames_1h": h_total,
            "frames_dropped_1h": h_drop,
            "link_success_pct": None if total == 0 else round(100.0 * self.ok / total, 2),
        }


link_stats = LinkStats()


api_state = {
    "started": time.time(),
    "lat1": None,
    "lat2": None,
    "drops1": 0,
    "drops2": 0,
    "err1": "",
    "err2": "",
    "last_ok": None,
    "charge_s": 0.0,
    "disch_s": 0.0,
}
api_lock = threading.Lock()


def remember(history, regs, activity: Activity) -> None:
    now = time.monotonic()
    activity.note(now, regs[7], regs[8])
    history.append((now, regs[13], regs[7], regs[8]))
    cutoff = now - AGE_MAX - 60
    while history and history[0][0] < cutoff:
        history.popleft()


def snapshot_now() -> dict:
    with api_lock:
        lat1, drops1, err1 = api_state["lat1"], api_state["drops1"], api_state["err1"]
        lat2, drops2, err2 = api_state["lat2"], api_state["drops2"], api_state["err2"]
        last_ok = api_state["last_ok"]
        charge_s = api_state["charge_s"]
        disch_s = api_state["disch_s"]
        feed_api = feed.api()
        link_api = link_stats.api()
    link = last_ok is not None and (time.time() - last_ok) <= 10.0
    fields = []
    for field in FIELDS:
        if field.label == "Charging":
            text = Activity.fmt(charge_s)
            number = round(charge_s / 60.0, 1)
        elif field.label == "Discharging":
            text = Activity.fmt(disch_s)
            number = round(disch_s / 60.0, 1)
        else:
            text = field.value
            number = field.number
        fields.append(
            {
                "id": field.fid,
                "label": field.label,
                "group": field.group,
                "text": text,
                "number": number,
            }
        )
    return {
        "name": "POW-HVM6",
        "model": "POW-HVM6.2M-48V-N",
        "up_since": api_state["started"],
        "link_ok": link,
        "frames": [
            {"latency_ms": lat1, "drops": drops1, "error": err1},
            {"latency_ms": lat2, "drops": drops2, "error": err2},
        ],
        "fields": fields,
        **feed_api,
        **link_api,
        "history": downsample_history(),
    }


def downsample_history(points: int = 360) -> list[dict]:
    """Average history into at most `points` buckets for the API."""
    with history_lock:
        now = time.monotonic()
        items = list(history)
    if not items:
        return []
    buckets = points
    out = []
    for i in range(buckets):
        start = i * len(items) // buckets
        end = (i + 1) * len(items) // buckets
        if end <= start:
            continue
        part = items[start:end]
        n = len(part)
        stamp = part[-1][0]
        pct = sum(p for _, p, _, _ in part) / n
        charge = sum(c for _, _, c, _ in part) / n
        discharge = sum(d for _, _, _, d in part) / n
        state = "idle"
        if charge > discharge and charge > 0:
            state = "charging"
        elif discharge > charge and discharge > 0:
            state = "discharging"
        out.append(
            {
                "age_s": round(now - stamp, 1),
                "pct": round(pct, 1),
                "state": state,
            }
        )
    return out


# ---------------------------------------------------------------------------
# Power event trackers (read-only with respect to the inverter).
#
# A tracker is a generic rule on one reading. An event starts when the start
# condition is true, ends once the end condition has held for end_hold_s, and
# counts only if it lasted at least min_s. While it runs the engine integrates
# the reading over time (W -> kWh for watt metrics) and keeps the peak.
# Trackers, finished events and per-tracker totals live in SQLite
# (EVENTS_DB). Reset clears count/kWh/time totals, never the history.
# The engine is fed by the poll loop, so it runs with no app connected.
# ---------------------------------------------------------------------------
EVENTS_DB = os.path.expanduser("~/powhvm6-events.db")
EVENT_KEEP_DAYS = 90
EVENT_MAX_GAP_S = 15.0      # longer gaps between samples aren't integrated
EVENT_STALE_S = 120.0       # no sample for this long while active: end the event at the last sample
MAX_TRACKERS = 50
EVENT_OPS = (">", "<")

# Metric id -> (label, unit, extractor on block-0 registers).
EVENT_METRICS = {
    "load_power": ("Output power", "W", lambda r: float(r[11])),
    "load_percent": ("Load percent", "%", lambda r: float(r[13])),
    "pv_power": ("PV power", "W", lambda r: float(r[4])),
    # V x (charge - discharge): + while charging, - while discharging.
    "battery_power": ("Battery power (+chg/-dis)", "W", lambda r: round(r[5] / 10.0 * (float(r[7]) - float(r[8])), 1)),
    "battery": ("Battery voltage", "V", lambda r: r[5] / 10.0),
    "battery_soc": ("Battery SoC", "%", lambda r: float(r[6])),
    "charge": ("Charge current", "A", lambda r: float(r[7])),
    "discharge": ("Discharge current", "A", lambda r: float(r[8])),
    "ac_voltage": ("AC input voltage", "V", lambda r: r[1] / 10.0),
    "pv_voltage": ("PV voltage", "V", lambda r: r[3] / 10.0),
}

TRACKER_COLS = ("name", "metric", "start_op", "start_value", "min_s", "end_op", "end_value",
                "end_hold_s", "max_s", "enabled")

SEED_TRACKERS = [
    {"name": "Well pump", "metric": "load_power", "start_op": ">", "start_value": 800.0,
     "min_s": 30.0, "end_op": "<", "end_value": 600.0, "end_hold_s": 10.0, "max_s": 3600.0,
     "enabled": True},
]


def metric_values(regs) -> dict:
    out = {}
    for mid, (_, _, fn) in EVENT_METRICS.items():
        try:
            out[mid] = fn(regs)
        except Exception:
            pass
    return out


def _cmp(op, a, b) -> bool:
    return a > b if op == ">" else a < b


def _num(body, key, lo, hi, default=None, allow_none=False):
    v = body.get(key, default)
    if v is None and allow_none:
        return None
    if isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v):
        raise ValueError(f"{key} must be a number")
    if not (lo <= v <= hi):
        raise ValueError(f"{key} must be between {lo:g} and {hi:g}")
    return float(v)


def validate_tracker(body, base=None) -> dict:
    """Return a clean tracker dict from a JSON body (missing keys from base), or raise ValueError."""
    if not isinstance(body, dict):
        raise ValueError("body must be a JSON object")
    src = dict(base or {})
    src.update({k: v for k, v in body.items() if k in TRACKER_COLS})
    name = src.get("name")
    if not isinstance(name, str) or not name.strip() or len(name.strip()) > 40:
        raise ValueError("name must be 1-40 characters")
    metric = src.get("metric", "load_power")
    if metric not in EVENT_METRICS:
        raise ValueError(f"metric must be one of {', '.join(EVENT_METRICS)}")
    out = {"name": name.strip(), "metric": metric}
    for k in ("start_op", "end_op"):
        op = src.get(k, ">" if k == "start_op" else "<")
        if op not in EVENT_OPS:
            raise ValueError(f"{k} must be > or <")
        out[k] = op
    out["start_value"] = _num(src, "start_value", -1e6, 1e6)
    out["end_value"] = _num(src, "end_value", -1e6, 1e6)
    out["min_s"] = _num(src, "min_s", 0, 86400, 0)
    out["end_hold_s"] = _num(src, "end_hold_s", 0, 3600, 0)
    max_s = src.get("max_s")
    out["max_s"] = None if max_s in (None, 0) else _num(src, "max_s", 1, 7 * 86400)
    if out["max_s"] is not None and out["max_s"] < out["min_s"]:
        raise ValueError("max_s must be at least min_s")
    en = src.get("enabled", True)
    if not isinstance(en, bool):
        raise ValueError("enabled must be true or false")
    out["enabled"] = en
    # An end condition that is already true at the start threshold would end
    # every event on its first sample; require some hysteresis.
    if _cmp(out["end_op"], out["start_value"], out["end_value"]):
        raise ValueError("the end condition can't already be true at the start threshold")
    return out


def tracker_summary(t) -> str:
    unit = EVENT_METRICS.get(t["metric"], ("", "", None))[1]
    label = EVENT_METRICS.get(t["metric"], (t["metric"], "", None))[0]

    def n(v):
        return f"{v:g}"

    s = (f"{label} {t['start_op']} {n(t['start_value'])} {unit} for {n(t['min_s'])} s; "
         f"ends {t['end_op']} {n(t['end_value'])} {unit} for {n(t['end_hold_s'])} s")
    if t.get("max_s"):
        s += f"; max {n(t['max_s'] / 60.0)} min"
    return s


class _Live:
    """In-memory state of one tracker's current candidate event."""

    def __init__(self):
        self.active = False
        self.latched = False   # max duration hit: wait for the end condition before re-arming
        self.start = None
        self.last_t = None
        self.last_v = None
        self.integral = 0.0    # value x seconds
        self.peak = None
        self.end_mark = None   # (t, integral, peak) when the end condition first became true


class EventEngine:
    def __init__(self, path=EVENTS_DB):
        self.path = path
        self.lock = threading.Lock()
        self.db = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=NORMAL")
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS trackers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL, metric TEXT NOT NULL,
                start_op TEXT NOT NULL, start_value REAL NOT NULL, min_s REAL NOT NULL,
                end_op TEXT NOT NULL, end_value REAL NOT NULL, end_hold_s REAL NOT NULL,
                max_s REAL, enabled INTEGER NOT NULL DEFAULT 1,
                created REAL NOT NULL,
                count INTEGER NOT NULL DEFAULT 0, kwh REAL NOT NULL DEFAULT 0,
                total_s REAL NOT NULL DEFAULT 0, since REAL NOT NULL);
            CREATE TABLE IF NOT EXISTS events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                tracker_id INTEGER NOT NULL,
                start REAL NOT NULL, end REAL NOT NULL, duration_s REAL NOT NULL,
                peak REAL, avg REAL, kwh REAL, truncated INTEGER NOT NULL DEFAULT 0);
            CREATE INDEX IF NOT EXISTS events_tracker ON events(tracker_id, start);
            CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT);
        """)
        self.live = {}
        self.last_sample = None
        self.next_prune = 0.0
        if self.db.execute("SELECT v FROM meta WHERE k='seeded'").fetchone() is None:
            for t in SEED_TRACKERS:
                self._insert(validate_tracker(t), time.time())
            self.db.execute("INSERT OR REPLACE INTO meta VALUES ('seeded', '1')")
        self.trackers = self._load()

    # -- storage -----------------------------------------------------------
    def _load(self) -> dict:
        return {r["id"]: dict(r) for r in self.db.execute("SELECT * FROM trackers ORDER BY id")}

    def _insert(self, t, now) -> int:
        cur = self.db.execute(
            "INSERT INTO trackers (name, metric, start_op, start_value, min_s, end_op, end_value,"
            " end_hold_s, max_s, enabled, created, since) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            (t["name"], t["metric"], t["start_op"], t["start_value"], t["min_s"], t["end_op"],
             t["end_value"], t["end_hold_s"], t["max_s"], int(t["enabled"]), now, now))
        return cur.lastrowid

    def _record(self, t, start, end, integral, peak, truncated) -> None:
        dur = max(0.0, end - start)
        if dur < t["min_s"]:
            return
        unit = EVENT_METRICS[t["metric"]][1]
        kwh = integral / 3.6e6 if unit == "W" else None
        avg = integral / dur if dur > 0 else peak
        self.db.execute(
            "INSERT INTO events (tracker_id, start, end, duration_s, peak, avg, kwh, truncated)"
            " VALUES (?,?,?,?,?,?,?,?)",
            (t["id"], start, end, dur, peak, avg, kwh, int(truncated)))
        self.db.execute("UPDATE trackers SET count = count + 1, kwh = kwh + ?, total_s = total_s + ?"
                        " WHERE id = ?", (kwh or 0.0, dur, t["id"]))
        row = self.db.execute("SELECT count, kwh, total_s FROM trackers WHERE id=?", (t["id"],)).fetchone()
        if row and t["id"] in self.trackers:
            self.trackers[t["id"]].update(dict(row))

    def _prune(self, now) -> None:
        if now < self.next_prune:
            return
        self.next_prune = now + 3600
        self.db.execute("DELETE FROM events WHERE end < ?", (now - EVENT_KEEP_DAYS * 86400,))

    # -- engine ------------------------------------------------------------
    def _finish(self, t, lv, end, integral, peak, truncated=False) -> None:
        self._record(t, lv.start, end, integral, peak, truncated)
        lv.active = False
        lv.start = None
        lv.integral = 0.0
        lv.peak = None
        lv.end_mark = None

    def feed(self, now, values) -> None:
        """One good sample: epoch seconds and {metric: value}. Called from the poll loop."""
        with self.lock:
            gap = None if self.last_sample is None else now - self.last_sample
            for tid, t in self.trackers.items():
                lv = self.live.setdefault(tid, _Live())
                if not t["enabled"]:
                    continue
                v = values.get(t["metric"])
                if v is None:
                    continue
                if lv.active and gap is not None and gap > EVENT_STALE_S:
                    # Link was down for a long time: close the event at the last sample.
                    end, integ, pk = lv.end_mark or (lv.last_t, lv.integral, lv.peak)
                    self._finish(t, lv, end, integ, pk)
                if lv.active:
                    dt = now - lv.last_t
                    if 0 < dt <= EVENT_MAX_GAP_S:
                        lv.integral += (lv.last_v + v) / 2.0 * dt
                    lv.peak = v if lv.peak is None else (max(lv.peak, v) if t["start_op"] == ">" else min(lv.peak, v))
                    lv.last_t, lv.last_v = now, v
                    if _cmp(t["end_op"], v, t["end_value"]):
                        if lv.end_mark is None:
                            lv.end_mark = (now, lv.integral, lv.peak)
                        if now - lv.end_mark[0] >= t["end_hold_s"]:
                            self._finish(t, lv, *lv.end_mark)
                            continue
                    else:
                        lv.end_mark = None
                    if t["max_s"] and now - lv.start >= t["max_s"]:
                        self._finish(t, lv, now, lv.integral, lv.peak, truncated=True)
                        lv.latched = True
                    continue
                if lv.latched:
                    if _cmp(t["end_op"], v, t["end_value"]):
                        lv.latched = False
                    else:
                        continue
                if _cmp(t["start_op"], v, t["start_value"]):
                    lv.active = True
                    lv.start = now
                    lv.last_t, lv.last_v = now, v
                    lv.integral = 0.0
                    lv.peak = v
                    lv.end_mark = None
            self.last_sample = now
            self._prune(now)

    # -- API ---------------------------------------------------------------
    def _tracker_api(self, t, now) -> dict:
        lv = self.live.get(t["id"]) or _Live()
        unit = EVENT_METRICS[t["metric"]][1]
        running = bool(lv.active and t["enabled"])
        # Through the last sample (at most one poll behind); the app ticks a local timer on top.
        elapsed = (lv.last_t - lv.start) if running else None
        last = self.db.execute("SELECT * FROM events WHERE tracker_id=? ORDER BY start DESC LIMIT 1",
                               (t["id"],)).fetchone()
        return {
            "id": t["id"], "name": t["name"], "metric": t["metric"],
            "metric_label": EVENT_METRICS[t["metric"]][0], "unit": unit,
            "start_op": t["start_op"], "start_value": t["start_value"], "min_s": t["min_s"],
            "end_op": t["end_op"], "end_value": t["end_value"], "end_hold_s": t["end_hold_s"],
            "max_s": t["max_s"], "enabled": bool(t["enabled"]),
            "summary": tracker_summary(t),
            "count": t["count"], "kwh": round(t["kwh"], 4), "total_s": round(t["total_s"], 1),
            "since": iso_local(t["since"]), "since_epoch": t["since"],
            # candidate = start condition seen but not yet min_s long; running = counts already
            "candidate": running and elapsed < t["min_s"],
            "running": running and elapsed >= t["min_s"],
            "ending": running and lv.end_mark is not None,
            "elapsed_s": None if elapsed is None else round(elapsed, 1),
            "live_peak": lv.peak if running else None,
            "live_kwh": round(lv.integral / 3.6e6, 4) if running and unit == "W" else None,
            "live_value": lv.last_v if running else None,
            "last_event": self._event_api(last) if last else None,
        }

    @staticmethod
    def _event_api(r) -> dict:
        return {"id": r["id"], "tracker_id": r["tracker_id"],
                "start": iso_local(r["start"]), "end": iso_local(r["end"]),
                "start_epoch": r["start"], "end_epoch": r["end"],
                "duration_s": round(r["duration_s"], 1),
                "peak": r["peak"], "avg": None if r["avg"] is None else round(r["avg"], 1),
                "kwh": None if r["kwh"] is None else round(r["kwh"], 4),
                "truncated": bool(r["truncated"])}

    def list_api(self) -> dict:
        with self.lock:
            now = time.time()
            return {
                "api_version": API_VERSION,
                "now": iso_local(now),
                "last_sample": iso_local(self.last_sample),
                "keep_days": EVENT_KEEP_DAYS,
                "metrics": [{"id": k, "label": v[0], "unit": v[1]} for k, v in EVENT_METRICS.items()],
                "defaults": dict(SEED_TRACKERS[0], name="New tracker"),
                "trackers": [self._tracker_api(t, now) for t in self.trackers.values()],
            }

    def get_api(self, tid) -> dict:
        with self.lock:
            t = self.trackers.get(tid)
            if t is None:
                raise KeyError(tid)
            return self._tracker_api(t, time.time())

    def history_api(self, tid, limit=50) -> dict:
        with self.lock:
            t = self.trackers.get(tid)
            if t is None:
                raise KeyError(tid)
            rows = self.db.execute("SELECT * FROM events WHERE tracker_id=? ORDER BY start DESC LIMIT ?",
                                   (tid, max(1, min(int(limit), 1000)))).fetchall()
            total = self.db.execute("SELECT COUNT(*) FROM events WHERE tracker_id=?", (tid,)).fetchone()[0]
            return {"tracker": self._tracker_api(t, time.time()), "total_events": total,
                    "events": [self._event_api(r) for r in rows]}

    def create(self, body) -> dict:
        t = validate_tracker(body)
        with self.lock:
            if len(self.trackers) >= MAX_TRACKERS:
                raise ValueError(f"at most {MAX_TRACKERS} trackers")
            tid = self._insert(t, time.time())
            self.trackers = self._load()
        return self.get_api(tid)

    def update(self, tid, body) -> dict:
        with self.lock:
            old = self.trackers.get(tid)
            if old is None:
                raise KeyError(tid)
            t = validate_tracker(body, base={k: old[k] for k in TRACKER_COLS} | {"enabled": bool(old["enabled"])})
            rule_changed = any(t[k] != old[k] for k in TRACKER_COLS if k not in ("name", "enabled"))
            self.db.execute(
                "UPDATE trackers SET name=?, metric=?, start_op=?, start_value=?, min_s=?, end_op=?,"
                " end_value=?, end_hold_s=?, max_s=?, enabled=? WHERE id=?",
                (t["name"], t["metric"], t["start_op"], t["start_value"], t["min_s"], t["end_op"],
                 t["end_value"], t["end_hold_s"], t["max_s"], int(t["enabled"]), tid))
            lv = self.live.get(tid)
            if lv and lv.active and (rule_changed or not t["enabled"]):
                # Close the running event under the old rule (counted only if long enough).
                end, integ, pk = lv.end_mark or (lv.last_t, lv.integral, lv.peak)
                self._finish(old, lv, end, integ, pk)
            if lv and (rule_changed or not t["enabled"]):
                lv.latched = False
            self.trackers = self._load()
        return self.get_api(tid)

    def set_enabled(self, tid, enabled) -> dict:
        return self.update(tid, {"enabled": bool(enabled)})

    def reset(self, tid) -> dict:
        with self.lock:
            if tid not in self.trackers:
                raise KeyError(tid)
            self.db.execute("UPDATE trackers SET count=0, kwh=0, total_s=0, since=? WHERE id=?",
                            (time.time(), tid))
            self.trackers = self._load()
        return self.get_api(tid)

    def delete(self, tid) -> dict:
        with self.lock:
            if tid not in self.trackers:
                raise KeyError(tid)
            self.db.execute("DELETE FROM events WHERE tracker_id=?", (tid,))
            self.db.execute("DELETE FROM trackers WHERE id=?", (tid,))
            self.live.pop(tid, None)
            self.trackers = self._load()
        return {"ok": True, "deleted": tid}


events_engine = None  # created in main(); None when the database can't be opened


def handle_events_request(method, path, query, body):
    """Route /api/v1/events... Returns (code, payload)."""
    eng = events_engine
    if eng is None:
        return 503, {"ok": False, "message": "event tracker database unavailable"}
    parts = [p for p in path.split("/") if p][3:]  # after api, v1, events
    try:
        if method == "GET":
            if not parts:
                return 200, eng.list_api()
            tid = int(parts[0])
            if len(parts) == 1:
                return 200, eng.get_api(tid)
            if len(parts) == 2 and parts[1] == "history":
                limit = 50
                for kv in query.split("&"):
                    if kv.startswith("limit="):
                        limit = int(kv[6:])
                return 200, eng.history_api(tid, limit)
            return 404, {"ok": False, "message": "not found"}
        # POST
        if not parts:
            return 201, eng.create(body)
        tid = int(parts[0])
        action = parts[1] if len(parts) > 1 else "update"
        if len(parts) > 2:
            return 404, {"ok": False, "message": "not found"}
        if action == "update":
            return 200, eng.update(tid, body)
        if action == "pause":
            return 200, eng.set_enabled(tid, False)
        if action == "resume":
            return 200, eng.set_enabled(tid, True)
        if action == "reset":
            return 200, eng.reset(tid)
        if action == "delete":
            return 200, eng.delete(tid)
        return 404, {"ok": False, "message": f"unknown action {action!r}"}
    except KeyError:
        return 404, {"ok": False, "message": "no such tracker"}
    except ValueError as exc:
        return 422, {"ok": False, "message": str(exc)}


class ApiHandler(BaseHTTPRequestHandler):
    server_version = "PowHVM6/1.0"

    def log_message(self, fmt, *args):
        pass

    def _json(self, code: int, payload) -> None:
        data = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self) -> None:
        path = self.path.split("?", 1)[0]
        if path == "/api/v1/now":
            self._json(200, snapshot_now())
        elif path == "/api/v1/identity":
            self._json(
                200,
                {
                    "name": "POW-HVM6",
                    "model": "POW-HVM6.2M-48V-N",
                    "http_port": HTTP_PORT,
                },
            )
        elif path == "/api/v1/settings":
            self._json(200, settings_payload())
        elif path == "/api/v1/events" or path.startswith("/api/v1/events/"):
            query = self.path.split("?", 1)[1] if "?" in self.path else ""
            try:
                code, payload = handle_events_request("GET", path, query, None)
            except Exception as exc:
                code, payload = 500, {"ok": False, "message": f"{type(exc).__name__}: {exc}"}
            self._json(code, payload)
        elif path == "/":
            self._json(
                200,
                {
                    "name": "POW-HVM6",
                    "endpoints": [
                        "GET /api/v1/now",
                        "GET /api/v1/identity",
                        "GET /api/v1/settings",
                        "POST /api/v1/set",
                        "GET /api/v1/events",
                        "POST /api/v1/events",
                        "GET /api/v1/events/<id>",
                        "GET /api/v1/events/<id>/history?limit=N",
                        "POST /api/v1/events/<id>/update|pause|resume|reset|delete",
                    ],
                },
            )
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self) -> None:
        path = self.path.split("?", 1)[0]
        if path == "/api/v1/events" or path.startswith("/api/v1/events/"):
            self._post_events(path)
            return
        if path != "/api/v1/set":
            self._json(404, {"error": "not found"})
            return
        # Requiring a JSON content type means a browser page on the LAN can't
        # send this cross-origin without a preflight, which we never approve.
        ctype = (self.headers.get("Content-Type") or "").split(";")[0].strip().lower()
        if ctype != "application/json":
            self._json(415, {"ok": False, "message": "Content-Type must be application/json"})
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        if length <= 0 or length > 1024:
            self._json(400, {"ok": False, "message": "bad body length"})
            return
        try:
            body = json.loads(self.rfile.read(length))
            key = body["key"]
            value = body["value"]
        except Exception:
            self._json(400, {"ok": False, "message": 'body must be {"key": ..., "value": ...}'})
            return
        setting = SETTINGS_BY_KEY.get(key) if isinstance(key, str) else None
        if setting is None:
            self._json(404, {"ok": False, "message": f"unknown setting {key!r}"})
            return
        if not WRITES_ENABLED or not setting.get("enabled", True):
            self._json(403, {"ok": False, "message": "writes are disabled for this setting"})
            return
        try:
            value, raw = validate_value(setting, value)
            with api_lock:
                check_relations(setting, value)
        except ValueError as exc:
            self._json(422, {"ok": False, "message": str(exc), "options": option_list(setting)})
            return
        if body.get("dry_run"):
            self._json(200, {"ok": True, "dry_run": True, "key": key, "value": value,
                             "register": setting["write"]["register"], "raw": raw,
                             "old": setting_current(setting)})
            return
        if not write_lock.acquire(timeout=10):
            self._json(503, {"ok": False, "message": "another write is in progress"})
            return
        try:
            job = WriteJob(setting, value, raw)
            try:
                write_jobs.put_nowait(job)
            except queue.Full:
                self._json(503, {"ok": False, "message": "write queue full"})
                return
            wake.set()
            if not job.done.wait(15):
                job.cancelled = True  # never run a write the client gave up on
                self._json(504, {"ok": False, "message": "timed out waiting for the inverter (serial link down?)"})
                return
            result = job.result or {"ok": False, "message": "no result"}
            log_write(key, result)
            self._json(200 if result.get("ok") else 502, result)
        finally:
            write_lock.release()


    def _post_events(self, path) -> None:
        """Event trackers only touch the Pi's own database, never the inverter."""
        ctype = (self.headers.get("Content-Type") or "").split(";")[0].strip().lower()
        if ctype != "application/json":
            self._json(415, {"ok": False, "message": "Content-Type must be application/json"})
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        if length < 0 or length > 4096:
            self._json(400, {"ok": False, "message": "bad body length"})
            return
        try:
            body = json.loads(self.rfile.read(length)) if length else {}
        except Exception:
            self._json(400, {"ok": False, "message": "body must be JSON"})
            return
        try:
            code, payload = handle_events_request("POST", path, "", body)
        except Exception as exc:
            code, payload = 500, {"ok": False, "message": f"{type(exc).__name__}: {exc}"}
        self._json(code, payload)


def start_api() -> None:
    server = ThreadingHTTPServer(("0.0.0.0", HTTP_PORT), ApiHandler)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    print(f"  api on :{HTTP_PORT}, discovery on udp :{DISCOVER_PORT}", flush=True)


def discover_reply() -> bytes:
    return json.dumps(
        {"name": "POW-HVM6", "model": MODEL, "http_port": HTTP_PORT, "api_version": API_VERSION}
    ).encode()


def discovery_thread() -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", DISCOVER_PORT))
    while True:
        data, addr = sock.recvfrom(2048)
        if data.startswith(DISCOVER_MAGIC):
            sock.sendto(discover_reply(), addr)


def main() -> None:
    global events_engine
    try:
        events_engine = EventEngine()
    except Exception as exc:
        print(f"  event trackers disabled: {type(exc).__name__}: {exc}", flush=True)
    start_api()
    threading.Thread(target=discovery_thread, daemon=True).start()
    print("\033[2J\033[H\033[36;1mPOW-HVM6\033[0m\n\n  opening serial...", flush=True)
    ser = None
    err1 = "waiting"
    err2 = "waiting"
    lat1 = None
    lat2 = None
    drops1 = 0
    drops2 = 0
    activity = Activity()
    charge_field = next(field for field in FIELDS if field.label == "Charging")
    disch_field = next(field for field in FIELDS if field.label == "Discharging")
    while True:
        t0 = time.monotonic()
        try:
            if ser is None or not ser.is_open:
                ser = open_port()
            regs, rtt, err, dead = poll_block(ser, *BLOCK1)
            with api_lock:
                link_stats.drop() if err else link_stats.good(rtt)
            if err:
                drops1 += 1
                err1 = err
            else:
                apply_block(0, regs)
                with api_lock:
                    last_blocks[0] = regs
                lat1 = rtt
                err1 = ""
                remember(history, regs, activity)
                with api_lock:
                    feed.note(regs)
                if events_engine is not None:
                    try:
                        events_engine.feed(time.time(), metric_values(regs))
                    except Exception:
                        pass  # trackers must never stop the poll loop
            if dead:
                drops2 += 1
                err2 = err1
                with api_lock:
                    link_stats.drop()
                ser = close_port(ser)
            else:
                regs, rtt, err, dead = poll_block(ser, *BLOCK2)
                with api_lock:
                    link_stats.drop() if err else link_stats.good(rtt)
                if err:
                    drops2 += 1
                    err2 = err
                else:
                    apply_block(1, regs)
                    with api_lock:
                        last_blocks[1] = regs
                    lat2 = rtt
                    err2 = ""
                if dead:
                    ser = close_port(ser)
            if ser is not None and not err1:
                probe_settings_regs(ser)
                process_write_jobs(ser)
        except Exception as exc:
            err1 = err2 = f"{type(exc).__name__}: {exc}"
            drops1 += 1
            drops2 += 1
            with api_lock:
                link_stats.drop()
                link_stats.drop()
            ser = close_port(ser)
        charge_field.value = Activity.fmt(activity.charge_s)
        disch_field.value = Activity.fmt(activity.disch_s)
        with api_lock:
            api_state["lat1"], api_state["drops1"], api_state["err1"] = lat1, drops1, err1
            api_state["lat2"], api_state["drops2"], api_state["err2"] = lat2, drops2, err2
            api_state["charge_s"] = activity.charge_s
            api_state["disch_s"] = activity.disch_s
            if not err1:
                api_state["last_ok"] = time.time()
        size = shutil.get_terminal_size((126, 34))
        # No trailing newline: a full pane plus print()'s newline scrolls
        # the title off the top of the HDMI console.
        sys.stdout.write(
            render(
                history,
                time.monotonic(),
                lat1,
                drops1,
                err1,
                lat2,
                drops2,
                err2,
                size.columns,
                size.lines,
            )
        )
        sys.stdout.flush()
        delay = INTERVAL - (time.monotonic() - t0)
        if delay > 0:
            wake.wait(delay)
            wake.clear()


if __name__ == "__main__":
    main()
