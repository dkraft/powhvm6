#!/usr/bin/env python3
"""Offline tests for charger labels and the logs endpoint (no serial, no inverter)."""
import os
import sys
import tempfile
import types
import unittest
from pathlib import Path

# hdmi-inverter.py imports serial at module level; stub it so we can import.
sys.modules.setdefault("serial", types.ModuleType("serial"))

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))


def load_script():
    import importlib.util
    path = ROOT / "hdmi-inverter.py"
    spec = importlib.util.spec_from_file_location("hdmi_inverter", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


inv = load_script()


class ChargeModeLabels(unittest.TestCase):
    def test_confirmed_mapping(self):
        self.assertEqual(inv.charge_mode([0] * 35 + [0])[0], "Solar first")
        self.assertEqual(inv.charge_mode([0] * 35 + [1])[0], "Solar + utility")
        self.assertEqual(inv.charge_mode([0] * 35 + [2])[0], "Only solar")

    def test_unknown_raw_falls_back_to_number(self):
        self.assertEqual(inv.charge_mode([0] * 35 + [3])[0], "3")

    def test_registry_options(self):
        s = inv.SETTINGS_BY_KEY["charger_source_priority"]
        opts = s["validation"]["options"]
        self.assertEqual(opts, {0: "Solar first", 1: "Solar + utility", 2: "Only solar"})
        self.assertNotIn(3, opts)


class LogsEndpoint(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.ctl = Path(self.tmp.name) / "chargectl.log"
        self.writes = Path(self.tmp.name) / "writes.log"
        inv.LOG_FILES = {"chargectl": str(self.ctl), "writes": str(self.writes)}

    def test_rejects_unknown_name(self):
        code, payload = inv.handle_logs_request("name=../../etc/passwd")
        self.assertEqual(code, 400)
        self.assertFalse(payload["ok"])

    def test_rejects_missing_name(self):
        code, payload = inv.handle_logs_request("")
        self.assertEqual(code, 400)

    def test_missing_file_is_404(self):
        code, payload = inv.handle_logs_request("name=chargectl")
        self.assertEqual(code, 404)
        self.assertEqual(payload["name"], "chargectl")

    def test_returns_last_n_lines(self):
        self.ctl.write_text("\n".join(f"line {i}" for i in range(10)) + "\n")
        code, payload = inv.handle_logs_request("name=chargectl&lines=3")
        self.assertEqual(code, 200)
        self.assertEqual(payload["lines"], ["line 7", "line 8", "line 9"])
        self.assertEqual(payload["returned"], 3)

    def test_caps_lines(self):
        self.ctl.write_text("x\n" * 50)
        code, payload = inv.handle_logs_request(f"name=chargectl&lines={inv.LOG_MAX_LINES + 50}")
        self.assertEqual(code, 200)
        self.assertLessEqual(payload["returned"], inv.LOG_MAX_LINES)

    def test_rejects_non_integer_lines(self):
        self.ctl.write_text("a\n")
        code, payload = inv.handle_logs_request("name=chargectl&lines=all")
        self.assertEqual(code, 400)

    def test_refuses_symlink(self):
        target = Path(self.tmp.name) / "secret"
        target.write_text("secret\n")
        self.ctl.symlink_to(target)
        code, payload = inv.handle_logs_request("name=chargectl")
        self.assertIn(code, (404, 500))
        if code == 200:
            self.fail("symlink was followed")


class HistoryCurrents(unittest.TestCase):
    def test_downsample_includes_currents_and_state(self):
        # history tuples: (stamp, pct, charge, discharge)
        now = 1000.0
        with inv.history_lock:
            inv.history.clear()
            inv.history.append((now - 10, 20.0, 5.0, 0.0))
            inv.history.append((now - 5, 10.0, 0.0, 3.0))
        # downsample_history uses time.monotonic() for age; just check keys
        out = inv.downsample_history(points=2)
        self.assertTrue(out)
        for pt in out:
            self.assertIn("charge_a", pt)
            self.assertIn("discharge_a", pt)
            self.assertIn("state", pt)
            self.assertIn(pt["state"], ("idle", "charging", "discharging"))


if __name__ == "__main__":
    unittest.main()
