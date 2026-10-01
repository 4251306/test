#!/bin/bash
# Install the mixer page so it starts on boot. Run this on the Pi.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
sudo mkdir -p /opt/p16mix /var/lib/p16mix
sudo rm -rf /opt/p16mix/p16mix
sudo cp -a "$ROOT/pi/p16mix" /opt/p16mix/p16mix
sudo cp "$ROOT/deploy/p16-mix.service" /etc/systemd/system/p16-mix.service
sudo sed -i "s/__USER__/${USER}/" /etc/systemd/system/p16-mix.service
sudo chown -R "$USER" /var/lib/p16mix
sudo usermod -aG dialout "$USER" || true
sudo systemctl daemon-reload
sudo systemctl enable --now p16-mix.service
echo "Mixer page: http://$(hostname -I | awk '{print $1}'):8080"
