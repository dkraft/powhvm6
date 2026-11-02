# Agent instructions

Read **[CONTRIBUTING.md](CONTRIBUTING.md)** first. It covers the architecture, the deploy and release steps, and the safety rules. [SETTINGS.md](SETTINGS.md) has the settings registry and register map.

The non-negotiables:

* This drives a real inverter and battery bank. Don't change a real inverter setting without the owner's explicit confirmation. To test, use `dry_run` or a same-value write.
* Only registry-validated writes (`SETTINGS` in `hdmi-inverter.py`). Never add a raw register or command passthrough.
* Keep `float ≤ bulk`. When lowering both, write float first; when raising both, write bulk first.
* Back up `/usr/local/sbin/hdmi-inverter.py` on the Pi before every deploy. Keep the write log `~pi/powhvm6-writes.log`.
* Never commit or print secrets (keystore or its passwords, the Pi password, keys, tokens). Sign releases with the same private key every time.
