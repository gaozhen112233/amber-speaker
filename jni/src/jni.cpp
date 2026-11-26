// JNI 这一层只做三件事：把 AssetManager 递进去、把 Java 的字符串转成命令、算每帧的 dt。
// 所有 GL 调用都发生在 GLSurfaceView 的 GL 线程上，也就是 onSurfaceCreated / onDrawFrame 里，
// 所以这里不加任何线程切换——一旦在别的线程碰 GL，Adreno 上是直接黑屏，不报错。
#include <jni.h>
#include <android/log.h>
#include <time.h>

#include <fcntl.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "AssetsAndroid.hpp"
#include "Model.hpp"

#include <CubismFramework.hpp>
#include <ICubismAllocator.hpp>

using namespace Live2D::Cubism::Framework;

namespace {

const char* kTag = "AmberGL";

void Log(const char* message)
{
    __android_log_write(ANDROID_LOG_INFO, kTag, message);
}

class Allocator : public ICubismAllocator
{
    void* Allocate(const csmSizeType size) override { return malloc(size); }
    void Deallocate(void* memory) override { free(memory); }
    void* AllocateAligned(const csmSizeType size, const csmUint32 alignment) override
    {
        return amber::AlignedAlloc(size, alignment);
    }
    void DeallocateAligned(void* memory) override { amber::AlignedFree(memory); }
};

Allocator g_allocator;
bool g_started = false;
double g_lastSeconds = 0;

/**
 * Cubism 5-r.5 把 GLSL 全部改成从文件加载了，程序里**没有内嵌源码**。
 * 不实现这个函数，渲染会在 InitializeShaders 里静默失败，最后就是一片黑，
 * 日志只会说 "Failed to load vertex shader"，看着像编译错误，其实是读不到文件。
 *
 * Android 分支传进来的路径是纯文件名（"VertShaderSrc.vert"），
 * 所以这里补上 APK 里的目录前缀。桌面上 Cubism 传的是 "FrameworkShaders/..."，
 * 两边不一样是 SDK 自己的平台分支决定的，不是我们拼错。
 */
amber::AssetPack* g_assets = nullptr;

csmByte* LoadShaderFile(const std::string path, csmSizeInt* size)
{
    if (size) *size = 0;
    if (!g_assets) return nullptr;
    std::vector<char> bytes;
    if (!g_assets->Read("model/shaders/" + path, bytes) || bytes.empty()) return nullptr;
    auto* out = static_cast<csmByte*>(malloc(bytes.size()));
    if (!out) return nullptr;
    memcpy(out, bytes.data(), bytes.size());
    if (size) *size = static_cast<csmSizeInt>(bytes.size());
    return out;
}

void ReleaseBytesStub(csmByte* bytes)
{
    free(bytes);
}

double NowSeconds()
{
    timespec spec;
    clock_gettime(CLOCK_MONOTONIC, &spec);
    return static_cast<double>(spec.tv_sec) + spec.tv_nsec / 1e9;
}

void StartOnce()
{
    if (g_started) return;
    // 必须是 static：CubismFramework::StartUp 只做 `s_option = option`，**不拷贝内容**。
    // 这里如果是个局部变量，函数一返回 s_option 就指向一帧死掉的栈，
    // 之后 GetLoadFileFunction() 读 s_option->LoadFileFunction 就是读野指针。
    //
    // 桌面端用局部变量能跑，纯粹是因为那块栈刚好没被复用——这个 bug 在 PC 上永远不会暴露。
    // 真机（arm32 / Android 14）上是 CreateGl → GenerateShaders 的第一条着色器就 SIGSEGV，
    // 而 GetLoadFileFunction 偏偏没写判空，连 "File loader is not set" 都打不出来。
    static CubismFramework::Option option;
    option.LogFunction = Log;
    option.LoggingLevel = CubismFramework::Option::LogLevel_Warning;
    option.LoadFileFunction = LoadShaderFile;
    option.ReleaseBytesFunction = ReleaseBytesStub;
    CubismFramework::StartUp(&g_allocator, &option);
    CubismFramework::Initialize();
    g_started = true;
}

std::string ToString(JNIEnv* env, jstring text)
{
    if (!text) return std::string();
    const char* chars = env->GetStringUTFChars(text, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(text, chars);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_amber_speaker_gl_Native_prepare(JNIEnv* env, jclass, jobject manager, jstring directory)
{
    StartOnce();
    auto assets = std::make_shared<amber::AssetPack>();
    assets->Open(env, manager);
    g_assets = assets.get();   // shader 从文件加载要用，Pet 持有 shared_ptr 所以一直有效
    auto* pet = new amber::Pet();
    // shared_ptr 交给 Pet 保管：CreateGl 建纹理时还要回头读贴图，那是另一个线程另一个时点了。
    const bool ok = pet->Prepare(assets, ToString(env, directory));
    if (!ok)
    {
        Log("模型解析失败");
        delete pet;
        return 0;
    }
    return reinterpret_cast<jlong>(pet);
}

JNIEXPORT jboolean JNICALL
Java_com_amber_speaker_gl_Native_createGl(JNIEnv*, jclass, jlong handle, jint width, jint height)
{
    auto* pet = reinterpret_cast<amber::Pet*>(handle);
    if (!pet) return JNI_FALSE;
    const bool ok = pet->CreateGl(width, height);
    g_lastSeconds = NowSeconds();
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_amber_speaker_gl_Native_resize(JNIEnv*, jclass, jlong handle, jint width, jint height)
{
    auto* pet = reinterpret_cast<amber::Pet*>(handle);
    if (pet) pet->Resize(width, height);
}

JNIEXPORT void JNICALL
Java_com_amber_speaker_gl_Native_setFlipX(JNIEnv*, jclass, jlong handle, jboolean flip)
{
    auto* pet = reinterpret_cast<amber::Pet*>(handle);
    if (pet) pet->SetFlipX(flip == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_amber_speaker_gl_Native_drawFrame(JNIEnv*, jclass, jlong handle)
{
    auto* pet = reinterpret_cast<amber::Pet*>(handle);
    if (!pet) return;
    const double now = NowSeconds();
    float dt = static_cast<float>(now - g_lastSeconds);
    g_lastSeconds = now;
    // 卡了一下（比如刚切回来）别让她一帧跳出老远。
    // ★ 这里曾经写成 `dt > 0.1f → 1/60`，后果是「慢动作」：帧率低时每帧真实间隔
    //   0.15~0.6 秒，却被压成 0.0167 秒，动作只推进真实时间的 1/6~1/10，看起来像慢放。
    // 现在封顶到 0.2 秒：这台机器换上硬件浮点库后稳定在 7~8fps（每帧 130ms），
    // dt 能如实反映真实间隔，动作速度才是对的；再慢到 5fps 以下才会轻微拖慢。
    if (dt <= 0) dt = 1.0f / 60;
    else if (dt > 0.2f) dt = 0.2f;
    pet->Tick(dt);
    pet->Draw();
}

JNIEXPORT void JNICALL
Java_com_amber_speaker_gl_Native_command(JNIEnv* env, jclass, jlong handle, jstring command)
{
    auto* pet = reinterpret_cast<amber::Pet*>(handle);
    if (!pet) return;
    pet->Command(ToString(env, command));
}

JNIEXPORT void JNICALL
Java_com_amber_speaker_gl_Native_destroy(JNIEnv*, jclass, jlong handle)
{
    auto* pet = reinterpret_cast<amber::Pet*>(handle);
    if (!pet) return;
    delete pet;
    g_lastSeconds = 0;
}

// ---------------------------------------------------------------- 舞台灯带

/*
 * 灯带是 SN3218（18 路 PWM），厂商驱动给了两条路：
 *
 *   /sys/class/sn3218/reg_value   一次写一路，「寄存器号 亮度」两个整数。
 *                                 好处是自带「上电 + 使能」，坏处是每次 store 都往串口打一行
 *                                 printk，实测单路 ≈5.5ms —— 18 路就是 100ms，动画没戏。
 *   /dev/sn3218  ioctl + write      ioctl 管上电/使能，write 一次灌 18 路（下面这个）。
 *
 * write 的 ABI 有个坑，必须按它写：驱动里 count 被当成【32 位字数】用（先 lsl #2 再
 * copy_from_user），所以 18 条记录（144 字节）要传 count=36。传 144 会让内核从数组后面
 * 多读 432 字节野内存当寄存器号，寄存器号跑飞 → oops → 整机重启（踩过一次）。
 * 每条记录 8 字节：{u32 寄存器号; u8 亮度; 3 字节填充}，寄存器 1..18 就是 18 路。
 */
namespace {
constexpr int kLedOn = 0x4C01;    // 反汇编 led1_ioctl 得来：上电 + reg0=1 + 18 路使能 + update
constexpr int kLedOff = 0x4C04;   // 断电：使能清零 + reg0=0 + 关电源脚
}  // namespace

JNIEXPORT jint JNICALL
Java_com_amber_speaker_led_NativeLed_open(JNIEnv*, jclass)
{
    return ::open("/dev/sn3218", O_WRONLY);
}

JNIEXPORT void JNICALL
Java_com_amber_speaker_led_NativeLed_powerOn(JNIEnv*, jclass, jint fd)
{
    // 只 write 不管上电的话，芯片还在 shutdown 状态，18 路亮度写了也不亮。
    if (fd >= 0) ::ioctl(fd, kLedOn, 0);
}

JNIEXPORT jint JNICALL
Java_com_amber_speaker_led_NativeLed_write(JNIEnv* env, jclass, jint fd, jbyteArray data, jint words)
{
    if (fd < 0 || data == nullptr) return -1;
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) return -1;
    // 注意这里传的是 words（36），而缓冲区有 144 字节 —— 正是驱动期望的错位契约，不能"修正"
    const ssize_t written = ::write(fd, bytes, static_cast<size_t>(words));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return static_cast<jint>(written);
}

JNIEXPORT void JNICALL
Java_com_amber_speaker_led_NativeLed_powerOff(JNIEnv*, jclass, jint fd)
{
    if (fd >= 0) ::ioctl(fd, kLedOff, 0);
}

JNIEXPORT void JNICALL
Java_com_amber_speaker_led_NativeLed_close(JNIEnv*, jclass, jint fd)
{
    if (fd >= 0) ::close(fd);
}

// ---------------------------------------------------------------- 人体感应独占

/*
 * system_server 的 InputManager 会把 hum_sensor（/dev/input/event5）注册成输入设备，
 * 它的 InputReader 持续读这个节点，把真实硬件上报的 KEY_F6（有人靠近/人离开）事件抢走。
 * 结果应用侧直接 read event5 永远等不到真实事件（走近唤醒没反应），而 sendevent 注入却能
 * 收到（注入走内核 soft path，会广播给所有 reader，真实硬件事件却先被 InputReader 消费）。
 *
 * 修法：EVIOCGRAB 让本进程独占这个 input 设备，别的 reader（system_server）拿不到事件。
 * EVIOCGRAB = _IOW('E', 0x90, int) = 0x40044590，参数传 1 表示独占。
 */
namespace {
constexpr int kEvIocGrab = 0x40044590;
}  // namespace

JNIEXPORT jint JNICALL
Java_com_amber_speaker_sensor_NativeHum_grab(JNIEnv*, jclass, jint fd)
{
    if (fd < 0) return -1;
    return ::ioctl(fd, kEvIocGrab, 1);
}

}  // extern "C"
