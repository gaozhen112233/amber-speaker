#!/system/bin/sh

# amber-speaker: open LCD backlight node to 0666 so the app (non-root)

# can write 0/255 to really turn the screen off/on (sleep mode).

# The node owner is system with 0644; app uid (u0_a28) cannot write it.

# Match by NAME, not hardcoded path (layout may drift).

for n in /sys/class/leds/lcd-backlight /sys/class/backlight/*; do

  [ -e "$n/brightness" ] || continue

  chmod 666 "$n/brightness" 2>/dev/null

  chown system:system "$n/brightness" 2>/dev/null

  break

done
