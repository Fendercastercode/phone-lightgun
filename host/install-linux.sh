#!/usr/bin/env bash
# Run once after extracting the RetroSight-linux-*.tar.gz release (or just double-click
# RetroSight/RetroSight after this has run once). Unlike start-host.sh, this does NOT
# build a Python venv or compile anything - RetroSight is already a frozen binary here.
# This script only does the one-time system setup: /dev/uinput access, and a couple of tips.
set -e
cd "$(dirname "$0")"

if [ ! -x RetroSight/RetroSight ]; then
  echo "RetroSight/RetroSight not found - run this script from inside the extracted release folder."
  exit 1
fi

# The window toolkit (Qt) needs this library on many distros
if command -v ldconfig >/dev/null 2>&1 && ! ldconfig -p | grep -q "libxcb-cursor.so.0"; then
  echo "Note: if the window doesn't open, install libxcb-cursor:"
  echo "  Debian/Ubuntu/Raspberry Pi OS: sudo apt install libxcb-cursor0    Fedora: sudo dnf install xcb-util-cursor"
fi

# The phone's aim reaches games through a virtual light gun device (/dev/uinput)
if [ ! -w /dev/uinput ]; then
  echo
  echo "RetroSight needs permission to create its virtual light gun (/dev/uinput)."
  read -r -p "Fix it now? It asks for your password once. [Y/n] " ans
  case "$ans" in
    [nN]*) echo "Skipped - the gun won't be able to aim until this is fixed." ;;
    *)
      echo 'KERNEL=="uinput", MODE="0660", GROUP="input", OPTIONS+="static_node=uinput"' \
        | sudo tee /etc/udev/rules.d/60-RetroSight-uinput.rules >/dev/null
      echo uinput | sudo tee /etc/modules-load.d/RetroSight-uinput.conf >/dev/null
      sudo modprobe uinput
      sudo udevadm control --reload-rules
      sudo udevadm trigger --subsystem-match=misc --action=change
      sudo usermod -aG input "$USER"
      echo "Done. (After your next log-in this is permanent - log out/in, or reboot, then run RetroSight/RetroSight.)"
      ;;
  esac
fi

# Nice to have: lets RetroSight see which emulator is in front and give it focus back after lock-on (X11)
if ! command -v xdotool >/dev/null 2>&1 && [ -z "$WAYLAND_DISPLAY" ]; then
  echo "Tip: install xdotool for automatic emulator presets (sudo apt install xdotool)."
fi

# Firewalls block the phone by default
if command -v ufw >/dev/null 2>&1 && sudo -n ufw status 2>/dev/null | grep -q "Status: active"; then
  echo "Firewall (ufw) is on - let the phone in once with: sudo ufw allow 47800/udp"
elif command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
  echo "Firewall is on - let the phone in once with:"
  echo "  sudo firewall-cmd --permanent --add-port=47800/udp && sudo firewall-cmd --reload"
fi

echo
echo "Setup done. Start RetroSight with: ./RetroSight/RetroSight"
