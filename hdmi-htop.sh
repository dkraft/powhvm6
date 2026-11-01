#!/bin/bash
export TERM="${TERM:-linux}"
if ! tmux -f /etc/hdmi-tmux.conf -L hdmi has-session -t main 2>/dev/null; then
  tmux -f /etc/hdmi-tmux.conf -L hdmi new-session -d -s main -c /home/pi /usr/local/sbin/hdmi-inverter.py
  tmux -f /etc/hdmi-tmux.conf -L hdmi select-pane -t main:0.0 -T inverter
  tmux -f /etc/hdmi-tmux.conf -L hdmi split-window -v -t main:0.0 -p 35 -c /home/pi
  tmux -f /etc/hdmi-tmux.conf -L hdmi select-pane -t main:0.1 -T shell
fi
exec tmux -f /etc/hdmi-tmux.conf -L hdmi attach-session -t main
