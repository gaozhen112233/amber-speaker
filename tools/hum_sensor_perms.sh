#!/system/bin/sh

# amber-speaker: open hum_sensor input node to 0666 so the app (non-root)

# can read proximity events directly. /dev is tmpfs, reset each boot.

# Match by device NAME, not hardcoded event5 (enum order may drift).

for i in 0 1 2 3 4 5 6 7 8 9; do

  node="/sys/class/input/event${i}/device/name"

  [ -r "$node" ] || continue

  name="$(cat "$node" 2>/dev/null)"

  if [ "$name" = "hum_sensor" ]; then

    chmod 666 "/dev/input/event${i}" 2>/dev/null

    chown root:input "/dev/input/event${i}" 2>/dev/null

    break

  fi

done
