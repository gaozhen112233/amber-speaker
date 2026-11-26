#!/system/bin/sh
# 琥珀音箱 · 进程看门狗（放 /data/adb/service.d/，开机由 Magisk 以 root 拉起）
#
# 为什么需要它：应用内部有看门狗（GL 线程卡死 / 画面全黑会自己重启），但
# 「进程整个消失」它救不了自己 —— 重新装 APK 会 force-stop、系统 OOM 会杀进程，
# 之后没有任何东西把大肥鱼拉起来，表现就是「8788 全无响应，但设备没重启」。
#
# 判据两条，任意一条成立就 am start：
#   1. com.amber.speaker 进程不在（pidof）
#   2. 心跳文件 /sdcard/amber/beat 超过 120 秒没更新（应用每 30 秒写一次）
#
# 注意（踩过的坑）：
#   * 脚本必须是 LF 换行 + 纯 ASCII，否则 5.1 的 sh 读不进来
#   * 必须用 busybox 的 pidof/stat/date/sleep；系统 toolbox 没有 stat
#     （Magisk 环境不完整时 busybox 会跑不起来，见 MEMORY 里那条 environment incomplete）
#   * beat 文件不存在时不判「超时」，只在进程消失时拉起，免得反复重启

BB=/data/adb/magisk/busybox
[ -x "$BB" ] || BB=/system/bin/busybox
BEAT=/sdcard/amber/beat
LOG=/sdcard/amber/keepalive.log
PKG=com.amber.speaker

while true; do
    sleep 30

    alive=0
    if [ -x "$BB" ]; then
        pids=$($BB pidof $PKG 2>/dev/null)
    else
        pids=$(ps | grep $PKG | grep -v grep)
    fi
    [ -n "$pids" ] && alive=1

    stale=0
    if [ -f "$BEAT" ] && [ -x "$BB" ]; then
        now=$($BB date +%s 2>/dev/null)
        mt=$($BB stat -c %Y "$BEAT" 2>/dev/null)
        if [ -n "$now" ] && [ -n "$mt" ]; then
            age=$((now - mt))
            if [ $age -ge 120 ]; then
                stale=1
            fi
        fi
    fi

    if [ $alive -eq 0 ] || [ $stale -eq 1 ]; then
        echo "$($BB date 2>/dev/null) restart alive=$alive stale=$stale" >> $LOG
        am start -n $PKG/.MainActivity >/dev/null 2>&1
        # 给应用 20 秒起来，别在同一个故障上连打
        sleep 20
    fi
done
