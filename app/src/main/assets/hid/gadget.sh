# Copyright (C) 2026 Daryan Kamaran Karim
# SPDX-License-Identifier: GPL-3.0-or-later
# Adds a HID mouse and keyboard to Android's own USB gadget, so the phone is a real mouse and
# keyboard to whatever it is plugged into, and takes them away again. Run as root:
#   sh gadget.sh up <mouse-descriptor-file> <keyboard-descriptor-file>
#   sh gadget.sh down
#
# The one thing that matters here: the kernel refuses to change a gadget that is bound to a
# controller, and Android's USB HAL re-binds it the moment it notices one unbound. So everything
# between unbinding and re-binding happens with no command in between that could block or sleep —
# a one-second gap is enough to lose the race, and the symlink then fails with EINVAL.
#
# adb is left alone: the HID functions join the config beside it rather than replacing it.

G=/config/usb_gadget/g1
CFG="$G/configs/b.1"
ACTION="$1"

[ -d "$G" ] || { echo "no USB gadget at $G"; exit 1; }

# Tessera's own functions, named so nothing else is mistaken for them.
MOUSE_FN="$G/functions/hid.0"
KBD_FN="$G/functions/hid.1"

case "$ACTION" in
  up)
    MOUSE_DESC="$2"
    KBD_DESC="$3"
    [ -f "$MOUSE_DESC" ] && [ -f "$KBD_DESC" ] || { echo "descriptor files missing"; exit 1; }

    # Already in place: say so and leave the gadget alone.
    if [ -e "$CFG/hid.0" ] && [ -e "$CFG/hid.1" ] && [ -e /dev/hidg0 ] && [ -e /dev/hidg1 ]; then
      echo "ready"
      exit 0
    fi

    mkdir -p "$MOUSE_FN" || { echo "this kernel has no HID gadget function"; exit 1; }
    echo 2 > "$MOUSE_FN/protocol"    # 2 = mouse
    echo 1 > "$MOUSE_FN/subclass"    # 1 = boot interface, the one every host knows
    echo 4 > "$MOUSE_FN/report_length"
    cat "$MOUSE_DESC" > "$MOUSE_FN/report_desc" || { echo "mouse descriptor rejected"; exit 1; }

    mkdir -p "$KBD_FN" || { echo "could not make the keyboard function"; exit 1; }
    echo 1 > "$KBD_FN/protocol"      # 1 = keyboard
    echo 1 > "$KBD_FN/subclass"
    echo 8 > "$KBD_FN/report_length"
    cat "$KBD_DESC" > "$KBD_FN/report_desc" || { echo "keyboard descriptor rejected"; exit 1; }

    UDC=$(cat "$G/UDC")
    [ -n "$UDC" ] || UDC=$(ls /sys/class/udc | head -n 1)
    [ -n "$UDC" ] || { echo "no USB controller to bind to"; exit 1; }

    # The window with no gap in it. USB, adb included, drops for the moment this takes.
    echo "" > "$G/UDC"
    ln -s "$MOUSE_FN" "$CFG/hid.0" 2>/dev/null
    ln -s "$KBD_FN" "$CFG/hid.1" 2>/dev/null
    if ! echo "$UDC" > "$G/UDC"; then
      # Put it back the way Android had it rather than leave USB dead.
      rm -f "$CFG/hid.0" "$CFG/hid.1"
      echo "$UDC" > "$G/UDC"
      echo "the gadget would not take the HID functions"
      exit 1
    fi

    # The nodes appear with the host's first configuration of the new interfaces.
    i=0
    while [ $i -lt 50 ]; do
      [ -e /dev/hidg0 ] && [ -e /dev/hidg1 ] && break
      sleep 0.1
      i=$((i + 1))
    done
    [ -e /dev/hidg0 ] && [ -e /dev/hidg1 ] || { echo "no /dev/hidg nodes appeared"; exit 1; }

    # And wait for the host to configure the interfaces, which is a separate thing: the node
    # exists as soon as the function does, but writing to it before the PC has set up the
    # endpoint fails, and whoever holds it open for us would die on its first report.
    i=0
    while [ $i -lt 100 ]; do
      case "$(cat /sys/class/udc/*/state 2>/dev/null)" in
        *configured*) break ;;
      esac
      sleep 0.1
      i=$((i + 1))
    done
    case "$(cat /sys/class/udc/*/state 2>/dev/null)" in
      *configured*) echo "ready" ;;
      *) echo "the PC has not taken the mouse and keyboard; is the cable a data cable?"; exit 1 ;;
    esac
    ;;

  down)
    # First, whatever still holds an endpoint open goes (our writers, if the app couldn't stop
    # them): unbinding a HID function while a writer sits in it, blocked on a host that's gone,
    # is what the kernel's HID gadget handles worst; it can hang the whole phone.
    for p in /proc/[0-9]*; do
      ls -l "$p/fd" 2>/dev/null | grep -q /dev/hidg && kill -9 "${p#/proc/}" 2>/dev/null
    done
    sleep 0.2
    UDC=$(cat "$G/UDC")
    [ -n "$UDC" ] || UDC=$(ls /sys/class/udc | head -n 1)
    echo "" > "$G/UDC"
    rm -f "$CFG/hid.0" "$CFG/hid.1"
    echo "$UDC" > "$G/UDC"
    # The functions themselves only go once nothing links them.
    rmdir "$MOUSE_FN" 2>/dev/null
    rmdir "$KBD_FN" 2>/dev/null
    # Check, rather than take it on trust: this said "stopped" once while leaving both functions
    # linked, and a teardown that only claims to have worked leaves the phone pretending to be a
    # mouse to everything it is plugged into until it is rebooted.
    if [ -e "$CFG/hid.0" ] || [ -e "$CFG/hid.1" ]; then
      echo "the HID functions would not come off; rebooting clears them"
      exit 1
    fi
    echo "stopped"
    ;;

  state)
    if [ -e "$CFG/hid.0" ] && [ -e /dev/hidg0 ]; then echo "ready"; else echo "absent"; fi
    ;;

  *)
    echo "usage: gadget.sh up <mouse-desc> <kbd-desc> | down | state"
    exit 1
    ;;
esac
