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
import json
import math
import os
import queue
import shutil
import socket
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
API_VERSION = 2

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
        res["message"] = f"{s['label']} is now {readback} {s.get('unit', '')}".strip()
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
                    ],
                },
            )
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self) -> None:
        path = self.path.split("?", 1)[0]
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
            if dead:
                drops2 += 1
                err2 = err1
                ser = close_port(ser)
            else:
                regs, rtt, err, dead = poll_block(ser, *BLOCK2)
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
