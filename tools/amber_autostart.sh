#!/system/bin/sh
# Magisk late_start: launch amber speaker app after boot (BootReceiver fallback)
sleep 45
am start -n com.amber.speaker/.MainActivity >/dev/null 2>&1
