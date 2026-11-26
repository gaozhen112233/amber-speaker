#!/bin/sh
# 无线 adb 的接线工具。
#
# 为什么要这个：这台机器上 adb daemon 在每条 Bash 命令结束后都会被回收，
# 于是 `adb connect` 建立起来的设备状态活不过下一条命令——
# 表现为「明明连上了，下一条就 no devices found」。
# 所以把 connect 和真正要做的事放进同一个进程生命周期里。
#
#   tools/adbx.sh shell "getprop ro.build.version.sdk"
#   tools/adbx.sh install  amber-speaker.apk
#   tools/adbx.sh logcat  AmberGL AmberCue
#   tools/adbx.sh shot     out/phone.png
#
set -u

# adb 路径：优先环境变量，其次项目常用的绝对路径，最后 PATH。
if [ -n "${ADB:-}" ] && [ -x "${ADB}" ]; then
    :
elif [ -x "/c/android/sdk/platform-tools/adb.exe" ]; then
    ADB="/c/android/sdk/platform-tools/adb.exe"
elif command -v adb >/dev/null 2>&1; then
    ADB="adb"
else
    echo "找不到 adb。装好 Android platform-tools，或用 ADB=/path/to/adb 指定。" >&2
    exit 1
fi

# 无线 adb（设备上 persist.adb.tcp.port=5555）。
# 路由器里给设备 MAC 做 DHCP 静态绑定，IP 才不会漂。
# 下面的默认值只是占位，请 export AMBER_ADB=<音箱IP>:5555。
DEVICE="${AMBER_ADB:-192.168.1.100:5555}"

connect() {
    "$ADB" connect "$DEVICE" >/dev/null 2>&1
    # connect 立刻返回，但 adbd 握手要一会儿；不睡这一下后面就是 no devices
    sleep 2
}

case "${1:-}" in
shell)
    shift
    connect
    # 显式 -s：USB 和无线同时在时 adb 默认挑 USB，盖盖子前必须能只走无线
    "$ADB" -s "$DEVICE" shell "$@"
    ;;
install)
    connect
    "$ADB" -s "$DEVICE" install -r -t "$2"
    ;;
uninstall)
    connect
    "$ADB" -s "$DEVICE" uninstall "$2"
    ;;
logcat)
    shift
    connect
    "$ADB" -s "$DEVICE" logcat -d -v time "$@"
    ;;
shot)
    connect
    "$ADB" -s "$DEVICE" exec-out screencap -p > "$2"
    echo "截图 -> $2"
    ;;
props)
    connect
    "$ADB" -s "$DEVICE" shell "getprop ro.product.cpu.abilist; getprop ro.build.version.sdk; getprop ro.build.version.release; getprop ro.product.model; getprop ro.board.platform; getprop dalvik.vm.heapsize; wm size; wm density"
    ;;
*)
    echo "用法: $0 {shell|install|uninstall|logcat|shot|props} ..." >&2
    exit 2
    ;;
esac
