#!/usr/bin/env python3
"""Live POW-HVM6 readout for the HDMI console. Read-only Modbus.

The official Wi-Fi dongle polls two holding-register blocks on slave 5
at 2400 8N1: 45 registers at 4501, then 16 registers at 4546.
Register words on the wire are little-endian.

The bottom graph is load percent on a fixed 0–100 scale. Its time axis
is logarithmic, from 24 h on the left to now on the right. Ticks are
green while the battery is charging, red while it is discharging, and
cyan while it is doing neither. Charge mins and disch mins count time
in those states since this screen started.
"""
import math
import shutil
import sys
import time
from collections import deque

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


class Field:
    def __init__(self, label, block, fmt):
        self.label = label
        self.block = block
        self.fmt = fmt
        self.value = "—"

    def cell(self, width: int) -> str:
        text = f"{self.label:<12} {self.value:>16}"
        if len(text) > width:
            text = text[:width]
        return text.ljust(width)


def volts(i):
    return lambda regs: f"{regs[i] / 10:.1f} V"


def hertz(i):
    return lambda regs: f"{regs[i] / 10:.1f} Hz"


def watts(i):
    return lambda regs: f"{regs[i]} W"


def amps(i):
    return lambda regs: f"{regs[i]} A"


def percent(i):
    return lambda regs: f"{regs[i]} %"


def fault(regs):
    return "none" if regs[29] == 0 else str(regs[29])


def output_mode(regs):
    return {0: "Utility first", 1: "Solar first", 2: "SBU"}.get(regs[36], str(regs[36]))


def charge_mode(regs):
    return {
        0: "Utility first",
        1: "Solar first",
        2: "Solar+utility",
        3: "Solar only",
    }.get(regs[35], str(regs[35]))


def ac_range(regs):
    return {0: "Appliances", 1: "UPS"}.get(regs[37], str(regs[37]))


def target_hz(regs):
    return {0: "50 Hz", 1: "60 Hz"}.get(regs[39], str(regs[39]))


def heatsink(regs):
    v = regs[11]
    if 0 <= v <= 125:
        return f"{v} C"
    return f"raw {v}"


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
        Field("Target V", 0, lambda regs: f"{regs[41]} V"),
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
        Field("Eq time", 1, lambda regs: f"{regs[4]} min"),
        Field("Eq timeout", 1, lambda regs: f"{regs[5]} min"),
        Field("Eq every", 1, lambda regs: f"{regs[6]} days"),
    ],
]
FIELDS = [field for column in COLUMNS for field in column]


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
            field.value = field.fmt(regs)
        except Exception as exc:
            field.value = str(exc)


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


def remember(history, regs, activity: Activity) -> None:
    now = time.monotonic()
    activity.note(now, regs[7], regs[8])
    history.append((now, regs[13], regs[7], regs[8]))
    cutoff = now - AGE_MAX - 60
    while history and history[0][0] < cutoff:
        history.popleft()


def main() -> None:
    print("\033[2J\033[H\033[36;1mPOW-HVM6\033[0m\n\n  opening serial...", flush=True)
    ser = None
    err1 = "waiting"
    err2 = "waiting"
    lat1 = None
    lat2 = None
    drops1 = 0
    drops2 = 0
    history = deque(maxlen=HISTORY_KEEP)
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
                    lat2 = rtt
                    err2 = ""
                if dead:
                    ser = close_port(ser)
        except Exception as exc:
            err1 = err2 = f"{type(exc).__name__}: {exc}"
            drops1 += 1
            drops2 += 1
            ser = close_port(ser)
        charge_field.value = Activity.fmt(activity.charge_s)
        disch_field.value = Activity.fmt(activity.disch_s)
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
            time.sleep(delay)


if __name__ == "__main__":
    main()
