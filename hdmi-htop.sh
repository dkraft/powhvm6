#!/bin/bash
export TERM="${TERM:-linux}"
if ! tmux -f /etc/hdmi-tmux.conf -L hdmi has-session -t main 2>/dev/null; then
  tmux -f /etc/hdmi-tmux.conf -L hdmi new-session -d -s main -c /home/pi /usr/local/sbin/hdmi-inverter.py
  tmux -f /etc/hdmi-tmux.conf -L hdmi select-pane -t main:0.0 -T inverter
  tmux -f /etc/hdmi-tmux.conf -L hdmi split-window -v -t main:0.0 -p 35 -c /home/pi
  tmux -f /etc/hdmi-tmux.conf -L hdmi select-pane -t main:0.1 -T shell
fi
# Re-attach loop: if the attach client dies, wait briefly and re-attach so the
# HDMI never goes blank while the tmux server (and the inverter) keep running.
while true; do
  tmux -f /etc/hdmi-tmux.conf -L hdmi attach-session -t main
  sleep 1
done
