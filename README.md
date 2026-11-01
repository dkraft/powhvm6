# powhvm6

HDMI console for a PowMr POW-HVM6.2M-48V-N. A Python script on the Raspberry Pi polls the inverter over RS-232 Modbus RTU and draws the readings on the local tty.

Every 2 seconds it sends the same two read-only holding-register requests the official Wi-Fi dongle sends: 45 registers at 4501, then 16 registers at 4546. The link is 2400 baud, 8N1, slave 5. Register words are little-endian. No settings are written.

The top of the screen is the live readings. Charging and discharging minutes are counted on the Pi from those samples. The bottom graph is load percent on a fixed 0–100 scale, with a 24-hour logarithmic time axis. Ticks are green while charging, red while discharging, and cyan while idle.

## Files

- `hdmi-inverter.py` is the display, installed at `/usr/local/sbin/hdmi-inverter.py`.
- `hdmi-htop.sh` starts a tmux session on tty1 with the display above a shell.
- `hdmi-tmux.conf` is the tmux config for that session.
- `hdmi-htop.service` runs that session on the HDMI console instead of getty.

The service name still says htop. Restarting it kills the tmux server, including the shell pane. To load a new copy of the script and leave the shell alone:

```
tmux -f /etc/hdmi-tmux.conf -L hdmi respawn-pane -k -c /home/pi -t main:0.0 /usr/local/sbin/hdmi-inverter.py
```

## Serial

The adapter is a Gearmo FTDI USB-RS232 on `/dev/ttyUSB0`. Use the inverter's Wi-Fi stick jack, not the battery BMS jack. Working cable on the male DB9: RJ45 pin 1 to DB9 pin 3, RJ45 pin 2 to DB9 pin 2, RJ45 pin 8 to DB9 pin 5. Leave RJ45 pin 4 open. It is +12 V.
