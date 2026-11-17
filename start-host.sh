#!/usr/bin/env bash
# Start the RetroSight PC host on Linux. The first run sets everything up.
#   chmod +x start-host.sh   (once)   then   ./start-host.sh
SCRIPT="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"   # absolute: the cd below moves us into host/
cd "$(dirname "$0")/host" || exit 1

if ! command -v python3 >/dev/null 2>&1; then
  echo "Python 3.10 or newer is needed:"
  echo "  Ubuntu/Debian/Mint: sudo apt install python3 python3-venv"
  echo "  Fedora:             sudo dnf install python3"
  echo "  Arch/SteamOS:       sudo pacman -S python"
  exit 1
fi

if [ ! -x .venv/bin/python ]; then
  echo "First run: setting up RetroSight (a minute or two)..."
  if ! python3 -m venv .venv; then
    echo "Python's venv module is missing. Ubuntu/Debian: sudo apt install python3-venv"
    exit 1
  fi
  .venv/bin/python -m pip install --upgrade pip >/dev/null
  if ! .venv/bin/python -m pip install -r requirements.txt; then
    rm -rf .venv
    echo
    echo "Installing failed. Usually a compiler is missing for the 'evdev' part:"
    echo "  Ubuntu/Debian: sudo apt install build-essential python3-dev linux-libc-dev"
    echo "  Fedora:        sudo dnf install gcc python3-devel kernel-headers"
    echo "then run ./start-host.sh again."
    exit 1
  fi
fi

# The window toolkit (Qt) needs this library on many distros. ldconfig usually lives in
# /sbin or /usr/sbin, which often isn't on a regular (non-root) user's PATH, so fall back
# to the usual absolute paths when `command -v` can't find it.
LDCONFIG=$(command -v ldconfig || true)
[ -z "$LDCONFIG" ] && [ -x /sbin/ldconfig ] && LDCONFIG=/sbin/ldconfig
[ -z "$LDCONFIG" ] && [ -x /usr/sbin/ldconfig ] && LDCONFIG=/usr/sbin/ldconfig
if [ -n "$LDCONFIG" ] && ! "$LDCONFIG" -p | grep -q "libxcb-cursor.so.0"; then
  echo "Note: if the window doesn't open, install libxcb-cursor:"
  echo "  Ubuntu/Debian: sudo apt install libxcb-cursor0    Fedora: sudo dnf install xcb-util-cursor"
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
      echo "Done. (After your next log-in this is permanent.)"
      # this session doesn't have the new group yet: carry on inside it
      if [ ! -w /dev/uinput ] && command -v sg >/dev/null 2>&1; then
        exec sg input -c "$(printf '%q ' "$SCRIPT" "$@")"
      fi
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

exec .venv/bin/python -m lightgun_host "$@"
