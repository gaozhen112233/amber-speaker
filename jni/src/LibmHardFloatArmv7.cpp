//
// LibmHardFloatArmv7.cpp —— armv7 专用。其它 ABI 下整个文件是空的。
//
// 这个文件是为了填一个非常隐蔽的坑，坑的形状是「头和手和尾巴整片消失，身体完好」。
//
// ── 起因 ────────────────────────────────────────────────────────────────
// Cubism Core 官方不给 Android 的 armeabi-v7a（android/ 目录只有 arm64-v8a /
// x86 / x86_64）。32 位 ARM 只有两份能用：
//
//   harmonyos/armeabi-v7a  纯软件浮点，能跑，但每帧 csmUpdateModel 要 590ms（1.5fps）
//   experimental/rpi       ARMv6 + VFPv2，硬件浮点，同样的模型只要 52ms（7.6fps）
//
// 我们选了 rpi 那份。它是用 -mfloat-abi=hard 编的（ELF 属性 Tag_ABI_VFP_args =
// AAPCS VFP），而 Android NDK 的 armeabi-v7a 平台库（含 libm）是 softfp。
//
// ── 症状 ────────────────────────────────────────────────────────────────
// 库内部调 libm 时按硬浮点约定把 float 放进 s0，而 softfp 的 libm 按 r0 去读
// —— 等于把「指针的位模式」当成弧度值。cmath 返回垃圾，角度全是乱的。
//
// 后果的分布很能说明问题：只有依赖 libm 的代码坏掉。
//   DeformerTransform.c.o → sincosf   ⇒ 旋转变形器（头、手臂、尾巴）
//   MathUtils.c.o         → atan2f    ⇒ 变形器角度
//   UpdateParameters.c.o  → floorf
//   InitializeModelRuntime.c.o → powf
// 弯曲变形器（身体、腿、裙子）是纯乘加，不碰 libm，所以一点事没有。真机上看到的
// 就是「头、手、尾巴整片消失，身体、裙子、腿完好」，同时 logcat 每帧刷
//   [CSM] [W]RotationDeformer: Not found transformed Direction.
// —— 那是 Core 发现方向向量归一化不了（因为角度是垃圾），子部件就塌成一个点。
//
// ── 修法 ────────────────────────────────────────────────────────────────
// 给这四个符号各写一个「硬浮点 ABI 的外壳」：pcs("aapcs-vfp") 只改**这个函数入口**
// 的传参约定，函数体仍按本编译单元的 softfp 去调 double 版 libm，所以壳里转一道
// double 就拿到正确结果，不需要自己实现三角函数。
//
// ── 为什么名字带 __amber_hf_ 前缀 ──────────────────────────────────────
// Framework 自己也在用这三个名字（CubismMath::DirectionToAngle 用 atan2f、
// CubismMath::Mod 用 floorf、CubismVector2::Normalize 用 powf），而 Framework
// 是整个用 softfp 编的。如果我们直接顶掉系统符号，Framework 那边会当场坏掉。
// 所以用 tools/patch_core_armv7.py 只把**静态库里**这几个引用改名成 __amber_hf_*，
// 库走我们的硬浮点壳，Framework 继续用系统 softfp 版，井水不犯河水。
//
// ★ 这四个函数只能被 Cubism Core 那个库调用，**绝不能在工程自己的代码里调**：
//   我们这边是 softfp，调用它们会按 r0 传参，同样踩回原来那个坑。
//

#if defined(__arm__) && !defined(__aarch64__)

#include <cmath>

// aapcs-vfp = 硬浮点（VFP 寄存器传 float 参数），和 rpi 版 Core 的约定一致。
#define AMBER_HARDFP __attribute__((pcs("aapcs-vfp")))

extern "C" {

AMBER_HARDFP float __amber_hf_atan2f(float y, float x)
{
    return static_cast<float>(::atan2(static_cast<double>(y), static_cast<double>(x)));
}

AMBER_HARDFP float __amber_hf_floorf(float x)
{
    return static_cast<float>(::floor(static_cast<double>(x)));
}

AMBER_HARDFP float __amber_hf_powf(float x, float y)
{
    return static_cast<float>(::pow(static_cast<double>(x), static_cast<double>(y)));
}

// glibc 的 sincosf(float x, float* sinOut, float* cosOut)：一次调用同时算出 sin 和 cos。
// 参数顺序不能错——库里是 r0 = &sin、r1 = &cos（从反汇编里核对过）。
AMBER_HARDFP void __amber_hf_sincosf(float x, float* sinOut, float* cosOut)
{
    const double d = static_cast<double>(x);
    *sinOut = static_cast<float>(::sin(d));
    *cosOut = static_cast<float>(::cos(d));
}

}  // extern "C"

#endif  // __arm__ && !__aarch64__
