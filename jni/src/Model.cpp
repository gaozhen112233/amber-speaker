#include "Model.hpp"

#include "Platform.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <chrono>
#include <vector>

#define STB_IMAGE_IMPLEMENTATION
#define STBI_NO_STDIO
#define STBI_ONLY_PNG
#include "stb_image.h"

#include <Effect/CubismBreath.hpp>
#include <Effect/CubismEyeBlink.hpp>
#include <Id/CubismIdManager.hpp>
#include <Math/CubismMatrix44.hpp>
#include <Math/CubismModelMatrix.hpp>
#include <Model/CubismUserModel.hpp>
#include <Motion/CubismBreathUpdater.hpp>
#include <Motion/CubismExpressionMotion.hpp>
#include <Motion/CubismEyeBlinkUpdater.hpp>
#include <Motion/CubismMotion.hpp>
#include <Motion/CubismMotionJson.hpp>
#include <Motion/CubismMotionManager.hpp>
#include <Motion/CubismPhysicsUpdater.hpp>
#include <Rendering/OpenGL/CubismOffscreenManager_OpenGLES2.hpp>
#include <Rendering/OpenGL/CubismRenderer_OpenGLES2.hpp>

using namespace Live2D::Cubism::Framework;
using Rendering::CubismRenderer_OpenGLES2;

// jni/local/CubismShader_OpenGLES2.cpp 里的诊断计数：实际编了多少个 program。
// 按需编译之后这个数字会从四百多掉到几十，启动日志里一眼能看出优化有没有生效。
// 那边用的是 C 链接，避开了 Cubism 的命名空间。
extern "C" int g_amberShaderProgramCount;

// jni/local/CubismRenderer_OpenGLES2.cpp 里的绘制诊断计数（每帧由 DoDrawModel 清零）。
//
// 为什么非要拆开量：Draw 的 57.8ms 是「主体 460 次」和「mask 约 298 次」混在一起的总和。
// 合批能省的只有「次数」，可如果 mask 那边每次都比主体贵一倍，或者 mask 的耗时其实
// 不在绘制而在 CPU 的布局计算（CalcClippedTotalBounds），那合批能拿到的收益就完全不同。
// 先看清再动手，比写完 600 行再发现省不下来强。
extern int g_amberBodyDrawCalls;
extern int g_amberMaskDrawCalls;
extern double g_amberMaskSetupMs;
extern double g_amberLoopMs;
/** 宽松合批（只并「相邻且贴图/mask buffer/混合模式全同」的部件）之后每帧的主体段数。 */
extern int g_amberBatchCount;
/** 严格合批（再加「同一 clip context」+「opacity 完全一致」）之后的段数——真正能做的。 */
extern int g_amberBatchStrict;
/** opacity < 0.999 的部件个数。它们不能用同一个 uniform 混批，除非把 opacity 烘进顶点。 */
extern int g_amberOpaNotOne;
// 诊断：按 drawable 编号隐藏（定义在 CubismRenderer_OpenGLES2.cpp）
extern bool g_amberHiddenDrawable[2048];
// 诊断：合批开关（定义在 CubismRenderer_OpenGLES2.cpp）。0 = 走原版逐部件路径。
extern int g_amberBatchUser;
extern int g_amberMaskUser;
extern bool g_amberNoMaskDrawable[2048];
// 诊断：只画指定的那一个 drawable（-1 = 正常）。查「这块白斑是谁画的」用。
extern int g_amberSoloDrawable;
// 下巴修正（定义在 CubismRenderer_OpenGLES2.cpp）。这块白色脸底的网格比脸型多出一截
// 下巴，渲染时把多出来的部分收回去，见那边的注释。
extern int g_amberChinFix;
extern int g_amberChinDrawable;
extern float g_amberChinFromY;
extern float g_amberChinLift;
extern float g_amberChinPinch;
extern float g_amberChinSpan;
// 绘制顺序改写（定义在 CubismRenderer_OpenGLES2.cpp）。index.txt 的 [order] 段填进来。
extern int g_amberMoveBeforeFrom[16];
extern int g_amberMoveBeforeTo[16];
extern int g_amberMoveBeforeCount;

// 实验用：树莓派版 Cubism Core 库自带的日志实现走 fprintf(stdout)，而 Android 的 libc
// 不导出 stdout 这个符号。我们自己提供这个入口（同名符号已定义，链接器就不会把库里那个
// PlatformLogger_Linux.c.o 拉进来），顺便把日志接到 logcat 上。
extern "C" void csmiPlatformDependentLogPrint(const char* message)
{
    if (message) amber::LogLine(message);
}


namespace amber {

namespace {

/** 桌宠那边是 _aligned_malloc，Android 上是 memalign：两边都收在 Platform 里。 */
class Allocator : public ICubismAllocator
{
    void* Allocate(const csmSizeType size) override { return malloc(size); }
    void Deallocate(void* memory) override { free(memory); }
    void* AllocateAligned(const csmSizeType size, const csmUint32 alignment) override
    {
        return AlignedAlloc(size, alignment);
    }
    void DeallocateAligned(void* memory) override { AlignedFree(memory); }
};

Allocator g_allocator;

std::string DirectoryOf(const std::string& path)
{
    const size_t slash = path.find_last_of('/');
    return slash == std::string::npos ? std::string() : path.substr(0, slash + 1);
}

std::string Stem(const std::string& name, const std::string& suffix)
{
    return name.size() > suffix.size() ? name.substr(0, name.size() - suffix.size()) : name;
}

/**
 * 她那副眼皮，按「眨眼之后还剩多少」的倍数来收：放在眨眼之后所以眨眼不能把它们重新撑开，
 * 又放在物理之前所以睫毛会跟着动。桌宠里叫 Lids，逻辑照抄。
 */
class Lids : public ICubismUpdater
{
public:
    explicit Lids(const float* open) : ICubismUpdater(CubismUpdateOrder_EyeBlink + 50), open_(open) {}

    void OnLateUpdate(CubismModel* model, csmFloat32) override
    {
        auto* ids = CubismFramework::GetIdManager();
        CubismIdHandle eyes[2] = { ids->GetId("ParamEyeLOpen"), ids->GetId("ParamEyeROpen") };
        for (int i = 0; i < 2; i++)
            if (open_[i] != 1)
                model->SetParameterValue(eyes[i], model->GetParameterValue(eyes[i]) * std::max(0.0f, open_[i]));
    }

private:
    const float* open_;
};

/** 单调毫秒，只用来量启动耗时。
 *
 *  用 chrono 而不是 clock_gettime：MSVC 上没有后者，而这份 Model.cpp 是
 *  Android 和桌面验证版共用的同一份源码——这是这个工程的核心约束，不能为了一边方便就破坏它。 */
double NowMs()
{
    using Clock = std::chrono::steady_clock;
    static const Clock::time_point origin = Clock::now();
    return std::chrono::duration<double, std::milli>(Clock::now() - origin).count();
}

/** 手动生成一级 mip（把 src 2×2 合 1 个像素，正确保留预乘 alpha 的颜色）。
 *
 *  为什么不能直接 glGenerateMipmap：贴图已经是**预乘 alpha**（RGB 都乘过 a），
 *  GPU 的 glGenerateMipmap 是拿预乘后的 RGBA 直接盒式平均。在半透明边缘（头发与脸的
 *  过渡处），它会把「深色高 alpha 像素」和「透明像素（RGB 因预乘归零）」平均，产出的
 *  边缘色比原图更暗。缩小显示（真机 1024×600 角色只有几百像素高）时采样到这些 mip，
 *  就在下巴/脸缘挤出一圈「胡子」似的深色灰边。
 *
 *  正确做法：先把预乘反回去（RGB/a 还原成直通色），在直通空间做 2×2 平均，再重新乘
 *  alpha 存回预乘。这样半透明边缘的平均色不会因预乘而偏黑。
 *
 *  宽高都是 2 的幂（贴图集都是 512/1024/2048），逐级减半到 1 为止。 */
void DownsamplePremultipliedMip(const unsigned char* src, int sw, int sh,
                                unsigned char* dst, int dw, int dh)
{
    for (int y = 0; y < dh; y++)
    {
        const int sy0 = y * 2;
        for (int x = 0; x < dw; x++)
        {
            const int sx0 = x * 2;
            float r = 0, g = 0, b = 0, a = 0;
            for (int dy = 0; dy < 2; dy++)
            {
                const unsigned char* row = src + static_cast<size_t>(sy0 + dy) * sw * 4;
                for (int dx = 0; dx < 2; dx++)
                {
                    const unsigned char* p = row + static_cast<size_t>(sx0 + dx) * 4;
                    const float alpha = p[3];
                    a += alpha;
                    if (alpha > 0)
                    {
                        const float inv = 255.0f / alpha;
                        r += p[0] * inv;
                        g += p[1] * inv;
                        b += p[2] * inv;
                    }
                }
            }
            r /= 4.0f; g /= 4.0f; b /= 4.0f; a /= 4.0f;
            unsigned char* o = dst + static_cast<size_t>(y) * dw * 4 + x * 4;
            const unsigned char oa = static_cast<unsigned char>(a < 0 ? 0 : (a > 255 ? 255 : a + 0.5f));
            const float m = oa / 255.0f;
            o[0] = static_cast<unsigned char>(r * m + 0.5f);
            o[1] = static_cast<unsigned char>(g * m + 0.5f);
            o[2] = static_cast<unsigned char>(b * m + 0.5f);
            o[3] = oa;
        }
    }
}

/** 把一张 PNG 变成 GL 纹理。贴图集大半是透明的，直接缩会把她脸边的颜色吸进来，所以先乘 alpha。 */
GLuint UploadTexture(const unsigned char* png, size_t size, int* outWidth, int* outHeight)
{
    const double atStart = NowMs();
    int width = 0, height = 0, channels = 0;
    unsigned char* rgba = stbi_load_from_memory(png, static_cast<int>(size), &width, &height, &channels, 4);
    if (!rgba) { LogLine("贴图解不开"); return 0; }
    const double atDecoded = NowMs();
    // 逐级 mip 的上一级缓冲（mip 生成循环里用）。level 0 就是 rgba 本身。
    std::vector<unsigned char> previous;

    // 2026-11-23：选择性调色。半反射膜会把画面压灰，但全局加饱和会把皮肤弄红。
    // 这里只给「蓝/青主导」的像素加饱和：头发、眼睛、尾巴、藏青衣服；
    // 皮肤（红主导）和白色（中性）不动，只做基础对比 + 提亮。
    // 2026-11-23 晚：舞台灯调暗后模型显得偏暗，基础亮度 1% → 5%，再加 0.94 中间调 gamma，
    // 让脸和衣服在暗舞台上先“亮起来”，而不是继续加对比。
    const float baseContrast = 1.12f;
    const float baseBrightness = 0.01f;
    const float blueSaturation = 0.18f;
    const float blueBrightness = 0.02f;
    const float highlightLift = 0.015f;
    for (int i = 0; i < width * height; i++)
    {
        unsigned char* pixel = rgba + i * 4;
        const unsigned int alpha = pixel[3];
        if (alpha == 0) continue;
        const float a = alpha / 255.0f;
        float r = pixel[0] / 255.0f;
        float g = pixel[1] / 255.0f;
        float b = pixel[2] / 255.0f;

        // 基础：对比 + 亮度（不加全局饱和）
        //
        // ★ 对比度必须在亮部衰减。皮肤贴图是 (255,238,236)，亮度 0.94，本身就贴着白；
        //   固定 1.12 的对比度会把它推成 (255,254,251) —— 肉眼看就是纯白。
        //   下巴那块「白」正是这么来的：那里没有被腮红和头发投影压住，只剩过曝的纯白，
        //   而旁边脸颊有腮红，一对比就显得下巴「没上色」。
        //   这里按亮度给对比度加权：中间调吃满，亮部不吃，暗部照常压。
        const float lum0 = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        const float protect = std::max(0.0f, std::min(1.0f, (0.88f - lum0) / 0.28f));
        const float contrast = 1.0f + (baseContrast - 1.0f) * protect;
        const float bright = baseBrightness * protect;
        r = (r - 0.5f) * contrast + 0.5f + bright;
        g = (g - 0.5f) * contrast + 0.5f + bright;
        b = (b - 0.5f) * contrast + 0.5f + bright;
        r = std::max(0.0f, std::min(1.0f, r));
        g = std::max(0.0f, std::min(1.0f, g));
        b = std::max(0.0f, std::min(1.0f, b));

        // 蓝/青权重：min(g,b) 明显高于 r 才算。皮肤 r 高 → 0；白色 r≈g≈b → 0。
        const float blueWeight = std::max(0.0f, std::min(1.0f, (std::min(g, b) - r) * 5.0f));
        if (blueWeight > 0.001f)
        {
            const float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            const float sat = 1.0f + blueSaturation * blueWeight;
            r = lum + (r - lum) * sat + blueBrightness * blueWeight;
            g = lum + (g - lum) * sat + blueBrightness * blueWeight;
            b = lum + (b - lum) * sat + blueBrightness * blueWeight;
        }

        // 高光轻提：让围裙/发丝的高光透一点，但不把整张图拉亮
        const float lum2 = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        const float hi = std::max(0.0f, std::min(1.0f, (lum2 - 0.72f) / 0.23f));
        const float lift = highlightLift * hi * hi * (3.0f - 2.0f * hi);
        r = std::max(0.0f, std::min(1.0f, r + lift));
        g = std::max(0.0f, std::min(1.0f, g + lift));
        b = std::max(0.0f, std::min(1.0f, b + lift));

        // 预乘 alpha。Cubism 的混合是预乘路径（glBlendFuncSeparate(SRC_ALPHA,...)
        // 配的是预乘色），实测把这里改成直通值整张脸立刻过曝发白 —— 所以必须乘。
        pixel[0] = static_cast<unsigned char>(r * a * 255.0f + 0.5f);
        pixel[1] = static_cast<unsigned char>(g * a * 255.0f + 0.5f);
        pixel[2] = static_cast<unsigned char>(b * a * 255.0f + 0.5f);
    }
    const double atPremultiplied = NowMs();

    // 第一张贴图的颜色统计。上传前先在 CPU 侧看一眼：如果这儿就是黑的，
    // 那后面再怎么调采样都没用，问题在解码或预乘。
    static bool reported = false;
    if (!reported)
    {
        reported = true;
        long visible = 0, solid = 0;
        double sumR = 0, sumG = 0, sumB = 0;
        for (int i = 0; i < width * height; i++)
        {
            const unsigned char* pixel = rgba + i * 4;
            if (pixel[3] == 0) continue;
            visible++;
            if (pixel[3] > 200) solid++;
            sumR += pixel[0]; sumG += pixel[1]; sumB += pixel[2];
        }
        char note[256];
        std::snprintf(note, sizeof(note),
            "贴图0 %dx%d 非透明=%ld 接近不透明=%ld 平均RGB=(%.0f,%.0f,%.0f) 首像素=(%d,%d,%d,%d)",
            width, height, visible, solid,
            visible ? sumR / visible : 0.0, visible ? sumG / visible : 0.0, visible ? sumB / visible : 0.0,
            rgba[0], rgba[1], rgba[2], rgba[3]);
        LogLine(note);
    }

    GLuint texture = 0;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);

    // 必须逐级生成 mipmap，否则整张图会是纯黑剪影。
    //
    // Cubism 的 SetupTexture 每次绘制都会把 MIN_FILTER 强制设成
    // GL_LINEAR_MIPMAP_LINEAR，这时候如果纹理只有 level 0，按 GL 规范它算「不完整」，
    // 采样一律返回 (0,0,0,1)——于是模型形状、位置、动作全都对，就是一个纯黑的剪影，
    // 而且不报任何错、不打任何日志。在 Adreno 上同样是黑屏。
    //
    // 但也不能直接 glGenerateMipmap：贴图是预乘 alpha，直接盒式平均会让半透明边缘
    // 变黑（下巴一圈「胡子」灰边）。这里改用 DownsamplePremultipliedMip 逐级在
    // 直通空间平均后再预乘，边缘色才不会偏黑。顺带的好处：缩小显示时没有摩尔纹。
    {
        int mw = width, mh = height;
        int level = 0;
        while (mw > 1 || mh > 1)
        {
            const int nw = mw > 1 ? mw / 2 : 1;
            const int nh = mh > 1 ? mh / 2 : 1;
            std::vector<unsigned char> next(static_cast<size_t>(nw) * nh * 4);
            DownsamplePremultipliedMip(
                level == 0 ? rgba : previous.data(),
                mw, mh, next.data(), nw, nh);
            glTexImage2D(GL_TEXTURE_2D, level + 1, GL_RGBA, nw, nh, 0, GL_RGBA, GL_UNSIGNED_BYTE, next.data());
            previous = std::move(next);
            mw = nw; mh = nh;
            level++;
        }
    }

    // glTexImage2D / glGenerateMipmap 都是**异步**的，不 glFinish 就量到的只是把命令塞进队列的时间。
    // 想量真实代价必须等它做完；这一下本身也要算进总时间里，所以两头各夹一次。
    glFinish();
    const double atUploaded = NowMs();

    glBindTexture(GL_TEXTURE_2D, 0);
    stbi_image_free(rgba);

    char note[192];
    std::snprintf(note, sizeof(note),
        "  分项 %dx%d 解码 %.0fms 预乘 %.0fms 上传+mip %.0fms",
        width, height, atDecoded - atStart, atPremultiplied - atDecoded, atUploaded - atPremultiplied);
    LogLine(note);

    *outWidth = width;
    *outHeight = height;
    return texture;
}

}  // namespace

// ------------------------------------------------------------------ Assets
// 两个平台各一份实现：Android 在 AssetsAndroid.cpp（读 APK 里的 assets），
// PC 在 win/src/DirAssets.hpp（读磁盘目录）。这里只管用，不管从哪来。

// --------------------------------------------------------------------- Pet

Pet::Pet()
{
}

Pet::~Pet()
{
    for (auto& entry : motions_) ACubismMotion::Delete(entry.second);
    delete gestures_;
    delete leftHand_;
    delete setting_;
    // 呼吸和物理由 _updateScheduler 持有的 updater 引用，析构顺序交给框架，这里不手动 delete
    // （它们的析构函数是私有的，本来就 delete 不了）。纹理由 DeleteRenderer 释放。
    DeleteRenderer();
}

bool Pet::Prepare(std::shared_ptr<const Assets> assets, const std::string& directory)
{
    directory_ = directory;
    if (!assets) return false;
    assets_ = std::move(assets);
    const Assets& files = *assets_;

    const std::string modelJson = directory + "model3.json";
    std::vector<char> json;
    if (!files.Read(modelJson, json)) { LogLine("读不到 model3.json"); return false; }
    setting_ = new CubismModelSettingJson(reinterpret_cast<csmByte*>(json.data()), static_cast<csmSizeInt>(json.size()));
    if (!setting_->GetModelFileName()) { LogLine("model3.json 解析失败"); return false; }

    std::vector<char> buffer;
    if (!files.Read(directory + setting_->GetModelFileName(), buffer)) { LogLine("读不到 moc3"); return false; }
    LoadModel(reinterpret_cast<csmByte*>(buffer.data()), static_cast<csmSizeInt>(buffer.size()));
    if (!_model) { LogLine("moc3 加载失败"); return false; }

    if (!files.Read(directory + setting_->GetPhysicsFileName(), buffer)) { LogLine("读不到物理"); return false; }
    LoadPhysics(reinterpret_cast<csmByte*>(buffer.data()), static_cast<csmSizeInt>(buffer.size()));
    if (_physics) _updateScheduler.AddUpdatableList(CSM_NEW CubismPhysicsUpdater(*_physics));

    _eyeBlink = CubismEyeBlink::Create(setting_);
    if (_eyeBlink)
    {
        _eyeBlink->SetBlinkingInterval(3.2f);   // 桌宠上定过：3.5 看着想睡
        _updateScheduler.AddUpdatableList(CSM_NEW CubismEyeBlinkUpdater(blinkBlocked_, *_eyeBlink));
    }
    _updateScheduler.AddUpdatableList(CSM_NEW Lids(lidOpen_));

    // 这个模型有 30 处物理读 ParamBreath，所以呼吸得摆满 0..1，不能只给一点点。
    _breath = CubismBreath::Create();
    if (_breath)
    {
        csmVector<CubismBreath::BreathParameterData> breath;
        breath.PushBack(CubismBreath::BreathParameterData(CubismFramework::GetIdManager()->GetId("ParamBreath"), 0.5f, 0.5f, 3.2345f, 1.0f));
        _breath->SetParameters(breath);
        _updateScheduler.AddUpdatableList(CSM_NEW CubismBreathUpdater(*_breath));
    }
    _updateScheduler.SortUpdatableList();

    _model->SaveParameters();
    LoadSettings(files, directory);

    std::string message = "解析完成：表情 " + std::to_string(expressions_.size())
        + " 个，动作 " + std::to_string(motions_.size()) + " 段"
        + "，骨架 " + profileName_ + (standard_ ? "（标准 Cubism 参数）" : "（自定义输入层）");
    LogLine(message.c_str());
    return true;
}

bool Pet::CreateGl(int width, int height)
{
    width_ = width;
    height_ = height;
    if (!_model || !setting_) return false;

    // 遮罩缓冲要几张，看的是「同一帧里同时要几个遮罩上下文」，不是某个 drawable 最多要几个。
    //
    // ★ 2026-11-24 踩过：原先这里按 csmGetDrawableMaskCounts 的最大值算（大肥鱼给 16），
    //   算出 1 张缓冲，于是日志里立刻出现 "not supported mask count : 13 / mask count : 49"，
    //   头发和眼白边缘画错。那个 API 给的是「单个部件最多引用几个遮罩」，而 SDK 是**按帧
    //   累计**分配 mask 缓冲槽位的（每个可见部件每引用一次就占一格），两个数完全不同。
    //   49 个同时可见的遮罩上下文 / 每张 32 格 = 至少 2 张，留 1 张余量就是 3 张。
    //   每张 1024² RGBA 约 4MB，多要一张不心疼，少要一张就是画面错。
    int maxMasks = 0;
    int totalMasks = 0;
    {
        const csmInt32* counts = csmGetDrawableMaskCounts(_model->GetModel());
        const int drawables = _model->GetDrawableCount();
        for (int i = 0; i < drawables && counts; i++)
        {
            maxMasks = std::max(maxMasks, static_cast<int>(counts[i]));
            totalMasks += static_cast<int>(counts[i]);
        }
    }
    const int maskBuffers = 3;
    {
        char note[128];
        std::snprintf(note, sizeof(note),
            "遮罩：单个部件最多 %d 个，全模型共 %d 个，%d 张缓冲（%d 格）",
            maxMasks, totalMasks, maskBuffers, maskBuffers * 32);
        LogLine(note);
    }

    const double atCreateGl = NowMs();
    CreateRenderer(static_cast<csmUint32>(width), static_cast<csmUint32>(height), maskBuffers);
    renderer_ = GetRenderer<CubismRenderer_OpenGLES2>();
    if (!renderer_) { LogLine("渲染器建不起来"); return false; }
    {
        glFinish();
        char note[160];
        std::snprintf(note, sizeof(note), "渲染器就绪（含着色器编译）%.0fms，着色器 program %d 个",
            NowMs() - atCreateGl, g_amberShaderProgramCount);
        LogLine(note);
    }

    const double atTextures = NowMs();
    LoadTextures(*assets_, directory_);
    {
        glFinish();
        char note[128];
        std::snprintf(note, sizeof(note), "贴图就绪 %.0fms", NowMs() - atTextures);
        LogLine(note);
    }

    // 这个模型用了哪几种混合模式。数值是 csmBlendMode 的位打包值：
    // 0 = Normal/Over，1 = AddCompatible，2 = MultiplyCompatible —— 三种全落在固定槽位上，
    // 所以按需编译之后一个额外 program 都不用编（着色器 474 → 8）。
    // 留着这行是因为换模型时它能立刻告诉你「这个模型会不会踩到 474 个组合那条路」。
    {
        const int count = _model->GetDrawableCount();
        const int* modes = csmGetDrawableBlendModes(_model->GetModel());
        bool seen[32] = { false };
        int kinds = 0;
        for (int i = 0; i < count && modes; i++)
        {
            const int mode = modes[i];
            if (mode < 0 || mode >= 32) continue;
            if (!seen[mode]) { seen[mode] = true; kinds++; }
        }
        std::string list;
        for (int i = 0; i < 32; i++)
        {
            if (!seen[i]) continue;
            if (!list.empty()) list += ",";
            list += std::to_string(i);
        }
        std::string message = "混合模式：drawable " + std::to_string(count) + " 个，用到 " + std::to_string(kinds)
            + " 种（" + list + "）";
        LogLine(message.c_str());
    }

    auto* renderer = static_cast<CubismRenderer_OpenGLES2*>(renderer_);
    // 2026-11-23：试过 2048，下巴灰带没变（不是边缘分辨率问题），回到 1024 省显存。
    renderer->SetDrawableClippingMaskBufferSize(1024, 1024);

    // 2026-11-23 注意：不要对脸部皮肤 ArtMesh109 关 mask——灰边会没，但脸皮会盖住
    // 脖子和嘴（用户当场发现「脖子异常大」）。灰边是 mask 软边缘在脸下缘和深色
    // 后发之间过渡出来的；mask 过滤改 NEAREST 也试过，无效（139.3 vs 139.7）。
    renderer->IsPremultipliedAlpha(true);

    std::string message = "渲染就绪：" + std::to_string(width) + "x" + std::to_string(height)
        + "，贴图 " + std::to_string(textures_.size()) + " 张";
    LogLine(message.c_str());
    return true;
}

/** 表情和动作的名字里有中文，走 ASCII 文件名 + index.txt，免得 zip 里转码出岔子。 */
void Pet::LoadSettings(const Assets& assets, const std::string& directory)
{
    std::vector<char> index;
    if (assets.Read(directory + "index.txt", index))
    {
        std::string text(index.begin(), index.end());
        std::string section;
        size_t start = 0;
        while (start <= text.size())
        {
            size_t end = text.find('\n', start);
            if (end == std::string::npos) end = text.size();
            std::string line = text.substr(start, end - start);
            start = end + 1;
            if (!line.empty() && line.back() == '\r') line.pop_back();
            if (line.empty()) continue;
            if (line[0] == '#') continue;   // 注释。【hide】段里整行都是名字，不挡住的话注释会被当成部件名。
            if (line.size() > 2 && line[0] == '[' && line.back() == ']')
            {
                section = line.substr(1, line.size() - 2);
                continue;
            }
            if (section == "hide")
            {
                // 两种写法：
                //   Part18     —— 按 part 藏（连它直属的 drawable 一起关，适合整块图层）
                //   draw 202   —— 按 drawable 编号直接关（适合「藏在一大块 part 里的
                //                 一行小字」，那种零件用 part 关会连角色一起关掉）
                if (line.size() > 6 && line.compare(0, 6, "drawid") == 0)
                {
                    // 按名字藏：编号会随作者重导模型整体错位，名字不会。
                    // 「drawid」后面跟空格还是 tab 都行，参数本身也可能带尾随空白。
                    std::string id = line.substr(6);
                    const size_t first = id.find_first_not_of(" \t");
                    id = (first == std::string::npos) ? std::string() : id.substr(first);
                    while (!id.empty() && (id.back() == ' ' || id.back() == '\t' || id.back() == '\r')) id.pop_back();
                    if (!id.empty()) hiddenDrawableIds_.push_back(id);
                }
                else if (line.size() > 5 && line.compare(0, 5, "draw ") == 0)
                {
                    const int index = std::atoi(line.c_str() + 5);
                    if (index >= 0 && index < 2048) hiddenDrawables_.push_back(index);
                    else LogLine(("要隐藏的 drawable 编号越界：" + line).c_str());
                }
                else hiddenParts_.push_back(line);   // 整行都是部件名：源文件里带空格（「水印 (1).psd」）
                continue;
            }
            if (section == "fix")
            {
                // 渲染侧几何修正开关，一行「键<TAB>值」。目前只有大肥鱼那块白色蛋形脸底需要用：
                //
                // 它的网格比真正的脸型多出一小截下巴（最低点 y≈0.514，脸型只到 0.488），
                // 多出来的那一圈没有任何东西盖着，就是下巴下面那块灰白。moc3 是二进制、
                // 也没有 .cmo3，改不了网格，所以在渲染时把这截收回去（见渲染器里的 chin 修正）。
                //
                //   chin     1        开关
                //   chinfrom 0.560    从这条模型空间 y 开始收
                //   chinlift 0.024    最下点往上抬多少
                //   chinpinch 0.12    最下点横向收多少（比例）
                //   chinspan 0.050    过渡带宽度，越小越生硬
                const size_t tab = line.find_first_not_of(" \t");
                if (tab == std::string::npos) continue;
                const size_t sp = line.find_first_of(" \t", tab);
                std::string key = line.substr(tab, sp == std::string::npos ? std::string::npos : sp - tab);
                const float value = (sp == std::string::npos) ? 0.0f : std::atof(line.c_str() + sp + 1);
                if (key == "chin") g_amberChinFix = static_cast<int>(value);
                else if (key == "chinfrom") g_amberChinFromY = value;
                else if (key == "chinlift") g_amberChinLift = value;
                else if (key == "chinpinch") g_amberChinPinch = value;
                else if (key == "chinspan") g_amberChinSpan = value;
                else LogLine(("认不出的 [fix] 键：" + key).c_str());
                LogLine(("[fix] " + key + " = " + std::to_string(value)).c_str());
                continue;
            }
            if (section == "order")
            {
                // 「要挪的<TAB>参照物」：前者挪到后者之前画。
                const size_t tab = line.find('\t');
                if (tab == std::string::npos)
                {
                    LogLine(("order 行少一个 tab：" + line).c_str());
                    continue;
                }
                std::string mover = line.substr(0, tab), anchor = line.substr(tab + 1);
                auto trim = [](std::string& s)
                {
                    const size_t first = s.find_first_not_of(" \t\r");
                    s = (first == std::string::npos) ? std::string() : s.substr(first);
                    while (!s.empty() && (s.back() == ' ' || s.back() == '\t' || s.back() == '\r')) s.pop_back();
                };
                trim(mover);
                trim(anchor);
                if (!mover.empty() && !anchor.empty()) moveBefore_.push_back(std::make_pair(mover, anchor));
                continue;
            }
            const size_t tab = line.find('\t');
            if (tab == std::string::npos) continue;
            const std::string name = line.substr(0, tab), file = line.substr(tab + 1);
            if (section == "exp") expressionFiles_[name] = file;
            else if (section == "motion") motionFiles_[name] = file;
            else if (section == "meta" && name == "profile")
            {
                profileName_ = file;
                standard_ = file != "dafeiyu";
            }
        }
    }

    // 部件名 -> 编号 -> 它**直属**的 drawable。
    //
    // 为什么按 drawable 关而不是关整个 part 的不透明度：这两个 miku 模型的 part 是分层的，
    // part 的不透明度会乘到它下面所有子部件上 —— 实测对 0~3 号 part 逐个 hidepart，
    // 屏上直接全黑（3 号是「最终版.psd」，角色本体）。水印那个 part 正好有 3 个直属
    // drawable（作者、使用须知、版本号三行字），点名关掉最干净。
    //
    // 数组是全局的、跨模型活着，所以切模型时必须先清零：否则上一个模型关掉的编号
    // 会把新模型的部件一起藏掉，表现是「换了模型缺一块」。
    std::fill(g_amberHiddenDrawable, g_amberHiddenDrawable + 2048, false);
    // 绘制顺序改写也一样是全局的，先清空再按新模型填。
    g_amberMoveBeforeCount = 0;
    // 点名关掉的 drawable 先落下来，再按 part 批量补。
    for (int drawable : hiddenDrawables_)
    {
        g_amberHiddenDrawable[drawable] = true;
        LogLine(("默认隐藏 drawable " + std::to_string(drawable)).c_str());
    }
    // 按名字关的（drawid）。名字查不到就只打一行日志：宁可看得见，也不要悄悄失效。
    if (!hiddenDrawableIds_.empty())
    {
        auto* idManager = CubismFramework::GetIdManager();
        for (const auto& want : hiddenDrawableIds_)
        {
            const int index = _model->GetDrawableIndex(idManager->GetId(want.c_str()));
            if (index < 0 || index >= 2048)
            {
                LogLine(("要隐藏的 drawable 找不到：" + want).c_str());
                continue;
            }
            g_amberHiddenDrawable[index] = true;
            LogLine(("默认隐藏 drawable " + want + "（" + std::to_string(index) + "）").c_str());
        }
    }
    // 绘制顺序改写（[order] 段）。名字解析不到就只打日志，不要悄悄不生效。
    if (!moveBefore_.empty())
    {
        auto* idManager = CubismFramework::GetIdManager();
        for (const auto& pair : moveBefore_)
        {
            const int from = _model->GetDrawableIndex(idManager->GetId(pair.first.c_str()));
            const int to = _model->GetDrawableIndex(idManager->GetId(pair.second.c_str()));
            if (from < 0 || to < 0)
            {
                LogLine(("要挪绘制顺序的 drawable 找不到：" + pair.first + " / " + pair.second).c_str());
                continue;
            }
            if (g_amberMoveBeforeCount >= 16)
            {
                LogLine("order 条目太多（上限 16）");
                break;
            }
            g_amberMoveBeforeFrom[g_amberMoveBeforeCount] = from;
            g_amberMoveBeforeTo[g_amberMoveBeforeCount] = to;
            g_amberMoveBeforeCount++;
            LogLine(("绘制顺序：" + pair.first + "(" + std::to_string(from) + ") 挪到 "
                     + pair.second + "(" + std::to_string(to) + ") 之前").c_str());
        }
    }
    if (!hiddenParts_.empty())
    {
        csmVector<CubismModelPartInfo> hierarchy = _model->GetPartsHierarchy();        auto* idManager = CubismFramework::GetIdManager();
        for (const auto& partName : hiddenParts_)
        {
            const int partIndex = _model->GetPartIndex(idManager->GetId(partName.c_str()));
            if (partIndex < 0 || partIndex >= static_cast<int>(hierarchy.GetSize()))
            {
                LogLine(("要隐藏的部件找不到：" + partName).c_str());
                continue;
            }
            PartChildDrawObjects& child = hierarchy.At(static_cast<csmUint32>(partIndex)).ChildDrawObjects;
            int hidden = 0;
            for (csmUint32 k = 0; k < child.DrawableIndices.GetSize(); k++)
            {
                const csmUint32 drawable = child.DrawableIndices.At(k);
                if (drawable < 2048) { g_amberHiddenDrawable[drawable] = true; hidden++; }
            }
            // part 自己的不透明度也要压 0：有子 part 的图层（Part17「水印 (1).psd」）
            // 自己一个 drawable 都不挂，光隐藏直属 drawable 等于没动，那行小字还留在屏上。
            // 实测 hidepart 1 之后小字消失、角色完好，所以这条路是通的。
            hiddenPartIndex_.push_back(partIndex);
            LogLine(("默认隐藏部件 " + partName + "（" + std::to_string(partIndex) + "）的 "
                     + std::to_string(hidden) + " 个 drawable").c_str());
        }
    }

    for (const auto& entry : expressionFiles_)
    {
        std::vector<char> buffer;
        if (!assets.Read(directory + entry.second, buffer)) continue;
        CubismExpressionMotion* motion = CubismExpressionMotion::Create(
            reinterpret_cast<csmByte*>(buffer.data()), static_cast<csmSizeInt>(buffer.size()));
        if (!motion) continue;
        Expression expression;
        expression.parameters = motion->GetExpressionParameters();
        expressions_[entry.first] = expression;
        ACubismMotion::Delete(motion);
    }

    gestures_ = CSM_NEW CubismMotionManager();
    leftHand_ = CSM_NEW CubismMotionManager();
    for (const auto& entry : motionFiles_)
    {
        std::vector<char> buffer;
        if (!assets.Read(directory + entry.second, buffer))
        {
            LogLine((std::string("动作读不到：") + directory + entry.second).c_str());
            continue;
        }
        auto* motion = static_cast<CubismMotion*>(
            LoadMotion(reinterpret_cast<csmByte*>(buffer.data()), static_cast<csmSizeInt>(buffer.size()), entry.first.c_str()));
        if (!motion)
        {
            // 先说清楚是「JSON 根本解析不了」还是「统计值对不上」：CubismMotion::Parse
            // 里这两道关都会静默 return，外面只看到 nullptr。
            CubismMotionJson probe(reinterpret_cast<const csmByte*>(buffer.data()),
                                   static_cast<csmSizeInt>(buffer.size()));
            std::string message = std::string("动作解析失败：") + entry.first
                + "（" + std::to_string(buffer.size()) + " 字节，"
                + (probe.IsValid() ? "JSON 有效" : "JSON 无效")
                + "，一致性 " + (probe.IsValid() && probe.HasConsistency() ? "OK" : "失败") + "）";
            LogLine(message.c_str());
            continue;
        }
        const bool idle = entry.first == "IDLE";
        motion->SetLoop(idle);
        motion->SetLoopFadeIn(false);
        if (!idle) { motion->SetFadeInTime(0.2f); motion->SetFadeOutTime(0.0f); }
        motions_[entry.first] = motion;
    }
}

void Pet::LoadTextures(const Assets& assets, const std::string& directory)
{
    auto* renderer = static_cast<CubismRenderer_OpenGLES2*>(renderer_);
    const int count = setting_->GetTextureCount();
    for (int i = 0; i < count; i++)
    {
        const std::string name = setting_->GetTextureFileName(i);
        std::vector<char> png;
        const double atRead = NowMs();
        if (!assets.Read(directory + name, png))
        {
            std::string message = "读不到贴图 " + name;
            LogLine(message.c_str());
            continue;
        }
        const double readMs = NowMs() - atRead;
        int width = 0, height = 0;
        const GLuint texture = UploadTexture(reinterpret_cast<const unsigned char*>(png.data()), png.size(), &width, &height);
        if (!texture) continue;
        if (renderer) renderer->BindTexture(static_cast<csmUint32>(i), texture);
        GLint limit = 0;
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, &limit);
        std::string message = "贴图 " + std::to_string(i) + " " + std::to_string(width) + "x" + std::to_string(height)
            + "（上限 " + std::to_string(limit) + "）读文件 " + std::to_string(static_cast<int>(readMs)) + "ms";
        LogLine(message.c_str());
        textures_.push_back(name);
    }
}

// ------------------------------------------------------------------ 命令

void Pet::Command(const std::string& line)
{
    std::lock_guard<std::mutex> guard(lock_);
    commands_.push_back(line);
}

// ------------------------------------------------------------------ 参数名映射

/**
 * 桌宠的命令词汇用的是<b>它自己模型</b>的参数名，而这个模型的名字是自定义的，对不上三处。
 *
 * <p>对不上的后果特别隐蔽：{@code SetParameterValue} 遇到不存在的 id 不报错、不打日志，
 * 只是没反应。于是「转头」「点头」「歪头」「摇头」「晃头」这一批词全部变成空操作，
 * 而她本来也在呼吸眨眼，看画面根本看不出来。
 *
 * <p>为什么映射写在渲染器、不去改词表：词表要和桌宠**逐字一致**，那是验收标准
 * （98 行命令对照），改了词表就失去了这个锚点。名字差异属于「模型适配」，属于这一层。
 *
 * <p>符号也要翻。实测这个骨架 {@code ParamAngleX} 正数把脸转向画面右侧、
 * {@code ParamAngleY} 正数是低头，而桌宠的约定是「正数转屏幕左边、正数抬头」。
 * 三个轴一起反，说明它是个镜像过的骨架——不翻的话转头会左右颠倒，
 * 而且 {@code 看左边} 会和 {@code 看右边} 说反。
 *
 * <p><b>关键坑（2026-11-21 用 --sweep 参数扫描坐实）</b>：这个模型里有两套角度参数，
 * 名字极易混淆：
 * <ul>
 *   <li>{@code ParamAngleX/Y/Z}（Name 就是 AngleX/Y/Z）——<b>真正驱动头部旋转</b>的参数，
 *       --sweep 实测 moved 327~331 像素。桌宠渲染器上设 ParamAngleZ=30 有 12446 像素变化。</li>
 *   <li>{@code AngleX/Y/Z}（Name 是 OX/OY/OZ）——<b>死参数</b>（原点偏移），--sweep 实测
 *       moved=0，设了完全没反应。</li>
 * </ul>
 * 早期的注释写反了（以为 ParamAngleX/Z 是死的、AngleX/Y/Z 才有效），导致映射到了
 * {@code AngleX/Y/Z}，头部旋转全部空转。正确映射是 {@code FaceAngleX/Y/Z → ParamAngleX/Y/Z}。
 *
 * <p>另外 {@code ParamAngleX/Y/Z} 还是这个模型的物理<b>输出</b>（physics3.json 里
 * PhysicsSetting 的 Output），物理模拟每帧按 {@code AngleX/Y/Z} 输入重算它们。所以必须在
 * 物理更新（OnLateUpdate）<b>之后</b>再设置，否则刚设上去就被物理覆盖回 0——见 Tick 里的
 * headTurns / headMoves 分离逻辑。
 */
struct ParamAlias
{
    const char* from;
    const char* to;
    bool negate;
};

static const ParamAlias kAliases[] = {
    { "FaceAngleX", "ParamAngleX", true },
    { "FaceAngleY", "ParamAngleY", true },
    { "FaceAngleZ", "ParamAngleZ", true },
};

/** 把桌宠的词换成这个模型的词。改了就返回 true，并且告诉调用方要不要把值反号。 */
bool Pet::MapParam(const std::string& in, std::string* out, bool* negate) const
{
    for (const auto& alias : kAliases)
    {
        if (in == alias.from)
        {
            *out = alias.to;
            // 大肥鱼的骨架是镜像过的（ParamAngleX 正数把脸转向画面右侧），要反号；
            // 标准 Cubism 骨架不镜像，反了就成了「说左看右」。
            *negate = standard_ ? false : alias.negate;
            return true;
        }
    }
    return false;
}

void Pet::DrainCommands()

{
    std::deque<std::string> pending;
    {
        std::lock_guard<std::mutex> guard(lock_);
        pending.swap(commands_);
    }
    for (const std::string& line : pending)
    {
        // 一行一条：命令 + 最多四个参数，和桌宠的 Exec 同样的解析方式
        std::string word[5];
        int count = 0;
        size_t i = 0;
        while (i < line.size() && count < 5)
        {
            while (i < line.size() && line[i] == ' ') i++;
            if (i >= line.size()) break;
            size_t start = i;
            while (i < line.size() && line[i] != ' ') i++;
            word[count++] = line.substr(start, i - start);
        }
        if (count == 0) continue;
        const std::string& command = word[0];
        const std::string a = count > 1 ? word[1] : std::string();
        const std::string b = count > 2 ? word[2] : std::string();

        if (command == "exp") SetExpression(a, b != "0");
        else if (command == "motion") PlayMotion(a);
        // 诊断：全部部件藏起来，看 Update 的耗时是否随部件数线性下降。
        // 这决定了「简化模型」是不是一条活路。
        else if (command == "hideall" || command == "showall")
        {
            const float opacity = command == "hideall" ? 0.0f : 1.0f;
            for (csmInt32 i = 0; i < _model->GetPartCount(); i++)
                _model->SetPartOpacity(i, opacity);
            LogLine(command == "hideall" ? "诊断：已隐藏全部部件" : "诊断：已恢复全部部件");
        }
        // 诊断：把整张参数表吐到日志（id / 最小 / 默认 / 最大 / 当前）。
        // 这台模型的自定义输入层（AngleX、PositionX、ParamBreath）和物理输出
        // （ParamAngleX、LEar1、ShortPonytailX1 …）光靠名字猜不出范围，
        // 而待机编排必须落在正确的参数上、用正确的量程。
        else if (command == "params")
        {
            std::string block;
            char one[160];
            const csmInt32 total = _model->GetParameterCount();
            int inBlock = 0;
            for (csmInt32 i = 0; i < total; i++)
            {
                const csmChar* name = _model->GetParameterId(static_cast<csmUint32>(i))->GetString().GetRawString();
                std::snprintf(one, sizeof(one), "%s[%.2f,%.2f,%.2f]=%.2f ",
                              name,
                              _model->GetParameterMinimumValue(static_cast<csmUint32>(i)),
                              _model->GetParameterDefaultValue(static_cast<csmUint32>(i)),
                              _model->GetParameterMaximumValue(static_cast<csmUint32>(i)),
                              _model->GetParameterValue(static_cast<csmUint32>(i)));
                block += one;
                // 一条日志别太长：logcat 单行有上限，而且 509 个参数挤成一行也不好读
                if (++inBlock == 10) { LogLine(block.c_str()); block.clear(); inBlock = 0; }
            }
            if (!block.empty()) LogLine(block.c_str());
            LogLine("参数表结束");
        }
        else if (command == "parts")
        {
            // 诊断：列出所有「部件」（part）的编号 / 名字 / 当前不透明度。
            // 排查「多出来的一块阴影」时，用 hidepart <编号> 逐块藏，最快能定位到是哪一块。
            const csmInt32 total = _model->GetPartCount();
            LogLine(("部件表：共 " + std::to_string(total) + " 个").c_str());
            char one[192];
            for (csmInt32 i = 0; i < total; i++)
            {
                const csmChar* name = _model->GetPartId(static_cast<csmUint32>(i))->GetString().GetRawString();
                std::snprintf(one, sizeof(one), "part %d %s op=%.2f", i, name,
                              _model->GetPartOpacity(static_cast<csmUint32>(i)));
                LogLine(one);
            }
            LogLine("部件表结束");
        }
        else if (command == "hidepart" || command == "showpart")
        {
            const int index = std::atoi(a.c_str());
            if (index >= 0 && index < _model->GetPartCount())
            {
                _model->SetPartOpacity(static_cast<csmUint32>(index),
                                       command == "hidepart" ? 0.0f : 1.0f);
                LogLine((std::string("诊断：") + command + " " + std::to_string(index)).c_str());
            }
        }
        else if (command == "hidtex" || command == "showtex")
        {
            // 诊断：按贴图隐藏所有使用它的 drawable。先定位「多出来的一块」在哪张图集，
            // 再在那一张里二分，比 715 个逐个试快得多。
            const int tex = std::atoi(a.c_str());
            int hidden = 0;
            for (csmInt32 i = 0; i < _model->GetDrawableCount(); i++)
            {
                if (_model->GetDrawableTextureIndex(i) == tex)
                {
                    g_amberHiddenDrawable[i] = (command == "hidtex");
                    hidden++;
                }
            }
            LogLine((std::string("诊断：") + command + " " + std::to_string(tex)
                     + "，涉及 " + std::to_string(hidden) + " 个 drawable").c_str());
        }
        else if (command == "hidedraw" || command == "showdraw")
        {
            // 诊断：按 drawable 编号隐藏。比 part 更细——排查「某一块阴影」时用。
            const int index = std::atoi(a.c_str());
            if (index >= 0 && index < 2048)
            {
                g_amberHiddenDrawable[index] = (command == "hidedraw");
                LogLine((std::string("诊断：") + command + " " + std::to_string(index)).c_str());
            }
        }
        else if (command == "partdraws")
        {
            // 诊断：列出一个 part 下的所有 drawable（编号 / 名字 / 贴图 / 不透明度）
            const int part = std::atoi(a.c_str());
            csmVector<CubismModelPartInfo> hierarchy = _model->GetPartsHierarchy();
            if (part >= 0 && part < static_cast<int>(hierarchy.GetSize()))
            {
                PartChildDrawObjects& child = hierarchy.At(static_cast<csmUint32>(part)).ChildDrawObjects;
                const int total = static_cast<int>(child.DrawableIndices.GetSize());
                LogLine(("part " + std::to_string(part) + " 的 drawable：" + std::to_string(total) + " 个").c_str());
                char one[224];
                for (int k = 0; k < total; k++)
                {
                    const csmUint32 d = child.DrawableIndices.At(static_cast<csmUint32>(k));
                    const csmChar* id = _model->GetDrawableId(d)->GetString().GetRawString();
                    std::snprintf(one, sizeof(one), "  draw %u %s tex=%d op=%.2f",
                                  d, id, _model->GetDrawableTextureIndex(d),
                                  _model->GetDrawableOpacity(d));
                    LogLine(one);
                }
            }
        }
        else if (command == "drawbounds")
        {
            // 诊断：列出某张贴图下所有 drawable 的模型空间包围盒。
            // 排查「多出来的阴影」时先按位置筛出脸/下巴附近的候选，再逐个 hidedraw。
            const int tex = std::atoi(a.c_str());
            char one[256];
            for (csmInt32 i = 0; i < _model->GetDrawableCount(); i++)
            {
                if (_model->GetDrawableTextureIndex(i) != tex)
                {
                    continue;
                }
                const csmInt32 vc = _model->GetDrawableVertexCount(i);
                const csmFloat32* v = _model->GetDrawableVertices(i);
                if (vc <= 0 || v == NULL)
                {
                    continue;
                }
                float minX = v[0], maxX = v[0], minY = v[1], maxY = v[1];
                for (csmInt32 k = 1; k < vc; k++)
                {
                    const float x = v[k * 2 + 0];
                    const float y = v[k * 2 + 1];
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
                const csmChar* id = _model->GetDrawableId(static_cast<csmUint32>(i))->GetString().GetRawString();
                std::snprintf(one, sizeof(one), "draw %d %s bbox=(%.3f,%.3f)-(%.3f,%.3f) op=%.2f",
                              i, id, minX, minY, maxX, maxY,
                              _model->GetDrawableOpacity(static_cast<csmUint32>(i)));
                LogLine(one);
            }
        }
        else if (command == "batch")
        {
            // 诊断：合批开关。0 = 走原版逐部件绘制，用来判断「多出来的一块」是不是
            // 合批路径特有的 blend/mask 状态问题。
            g_amberBatchUser = std::atoi(a.c_str()) != 0 ? 1 : 0;
            LogLine((std::string("诊断：batch = ") + std::to_string(g_amberBatchUser)).c_str());
        }
        else if (command == "masks")
        {
            // 诊断：关掉所有 clipping mask（0）。用来判断「多出来的一块」是不是 mask 边缘造成的。
            g_amberMaskUser = std::atoi(a.c_str()) != 0 ? 1 : 0;
            LogLine((std::string("诊断：masks = ") + std::to_string(g_amberMaskUser)).c_str());
        }
        else if (command == "maskeddraws")
        {
            // 诊断：列出带 clipping mask 的 drawable 及包围盒（不依赖渲染器内部成员）。
            const int tex = std::atoi(a.c_str());
            const csmInt32* maskCounts = _model->GetDrawableMaskCounts();
            char one[256];
            int total = 0;
            for (csmInt32 i = 0; i < _model->GetDrawableCount(); i++)
            {
                if (tex >= 0 && _model->GetDrawableTextureIndex(i) != tex) continue;
                if (maskCounts == NULL || maskCounts[i] <= 0) continue;
                const csmInt32 vc = _model->GetDrawableVertexCount(i);
                const csmFloat32* v = _model->GetDrawableVertices(i);
                if (vc <= 0 || v == NULL) continue;
                float minX = v[0], maxX = v[0], minY = v[1], maxY = v[1];
                for (csmInt32 k = 1; k < vc; k++)
                {
                    const float x = v[k * 2 + 0];
                    const float y = v[k * 2 + 1];
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
                const csmChar* id = _model->GetDrawableId(static_cast<csmUint32>(i))->GetString().GetRawString();
                std::snprintf(one, sizeof(one), "masked draw %d %s bbox=(%.3f,%.3f)-(%.3f,%.3f) op=%.2f maskN=%d",
                              i, id, minX, minY, maxX, maxY,
                              _model->GetDrawableOpacity(static_cast<csmUint32>(i)), maskCounts[i]);
                LogLine(one);
                total++;
            }
            LogLine(("maskeddraws 结束：共 " + std::to_string(total) + " 个").c_str());
        }
        else if (command == "maskinfo")
        {
            // 诊断：查某个 drawable 的遮罩关系——它自己用了哪几个遮罩，反过来又有谁拿它当遮罩。
            //
            // 排查下巴白块用。已知白底是 ArtMesh106（t0 上一块近白椭圆），又是
            // 「masks 0 关掉全部遮罩后白块消退、脸变完整」——说明本该盖住它的那层被
            // 遮罩裁掉了。但逐个 nomaskdraw 扫了三十多个相交部件，没有一个能让白像素
            // 掉一半，所以裁它的大概率是多层遮罩叠加（或者遮罩源本身就是错的）。
            // 这条命令把「谁裁谁」这张图直接打出来，不用再盲试。
            const int target = std::atoi(a.c_str());
            if (target >= 0 && target < _model->GetDrawableCount())
            {
                const csmInt32* counts = _model->GetDrawableMaskCounts();
                const csmInt32** masks = csmGetDrawableMasks(_model->GetModel());

                // 包围盒：判断这块到底盖不盖得住白块所在的区域。
                struct Box { float x0, y0, x1, y1; };
                auto bounds = [&](int idx, Box* box) -> bool {
                    const csmInt32 vc = _model->GetDrawableVertexCount(idx);
                    const csmFloat32* v = _model->GetDrawableVertices(idx);
                    if (vc <= 0 || v == NULL) return false;
                    box->x0 = box->x1 = v[0];
                    box->y0 = box->y1 = v[1];
                    for (csmInt32 k = 1; k < vc; k++)
                    {
                        const float x = v[k * 2 + 0];
                        const float y = v[k * 2 + 1];
                        if (x < box->x0) box->x0 = x;
                        if (x > box->x1) box->x1 = x;
                        if (y < box->y0) box->y0 = y;
                        if (y > box->y1) box->y1 = y;
                    }
                    return true;
                };
                auto idOf = [&](int idx) -> const char* {
                    return _model->GetDrawableId(static_cast<csmUint32>(idx))->GetString().GetRawString();
                };
                auto line = [&](const char* tag, int idx) {
                    Box box;
                    char one[288];
                    if (!bounds(idx, &box)) return;
                    std::snprintf(one, sizeof(one),
                                  "maskinfo %s %d <- %d %s bbox=(%.3f,%.3f)-(%.3f,%.3f) op=%.2f maskN=%d",
                                  tag, target, idx, idOf(idx),
                                  box.x0, box.y0, box.x1, box.y1,
                                  _model->GetDrawableOpacity(static_cast<csmUint32>(idx)),
                                  counts ? counts[idx] : 0);
                    LogLine(one);
                };

                char head[192];
                std::snprintf(head, sizeof(head), "maskinfo %d %s 自用遮罩 %d 个",
                              target, idOf(target), counts ? counts[target] : 0);
                LogLine(head);

                if (masks && counts)
                {
                    for (int k = 0; k < counts[target]; k++) line("own", masks[target][k]);
                }
                for (csmInt32 i = 0; i < _model->GetDrawableCount(); i++)
                {
                    if (i == target || counts == NULL || counts[i] <= 0 || masks == NULL) continue;
                    for (int k = 0; k < counts[i]; k++)
                    {
                        if (masks[i][k] == target) { line("user", i); break; }
                    }
                }
                LogLine("maskinfo 结束");
            }
        }
        else if (command == "chin")
        {
            // 下巴修正的开关与参数（实现见 CubismRenderer_OpenGLES2.cpp 的顶点拷贝处）。
            // 用法：chin 0 关；chin 1 开；chin lift pinch 直接调两个强度。
            if (a == "0" || a == "off") g_amberChinFix = 0;
            else if (a == "1" || a == "on") g_amberChinFix = 1;
            if (!a.empty() && a != "0" && a != "1" && a != "on" && a != "off")
            {
                g_amberChinLift = std::strtof(a.c_str(), nullptr);
                g_amberChinFix = 1;
            }
            if (!b.empty())
            {
                g_amberChinPinch = std::strtof(b.c_str(), nullptr);
            }
            char one[192];
            std::snprintf(one, sizeof(one), "诊断：chin fix=%d draw=%d fromY=%.3f lift=%.3f pinch=%.3f",
                          g_amberChinFix, g_amberChinDrawable, g_amberChinFromY,
                          g_amberChinLift, g_amberChinPinch);
            LogLine(one);
        }
        else if (command == "verts")
        {
            // 诊断：把某个 drawable 的顶点（模型坐标 + UV）全打出来。
            // 下巴白块定位到 ArtMesh245（脸皮）之后，要靠顶点分布判断它是「本来就短」
            // 还是「被变形器拉短」，以及网格是几行几列。
            const int index = std::atoi(a.c_str());
            if (index >= 0 && index < _model->GetDrawableCount())
            {
                const csmInt32 vc = _model->GetDrawableVertexCount(index);
                const csmFloat32* v = _model->GetDrawableVertices(index);
                const csmFloat32* uv = reinterpret_cast<const csmFloat32*>(_model->GetDrawableVertexUvs(index));
                char one[192];
                std::snprintf(one, sizeof(one), "verts draw %d 共 %d 个顶点", index, vc);
                LogLine(one);
                for (csmInt32 k = 0; k < vc && v != NULL; k++)
                {
                    std::snprintf(one, sizeof(one), "vert %d %.4f %.4f %.4f %.4f",
                                  k, v[k * 2], v[k * 2 + 1],
                                  uv ? uv[k * 2] : -1.0f, uv ? uv[k * 2 + 1] : -1.0f);
                    LogLine(one);
                }
            }
        }
        else if (command == "opzero")
        {
            // 诊断：列出所有「当前透明度为 0」的 drawable 及其父 part 的名字。
            //
            // 下巴那一整块发灰白，逐块 hidedraw 谁都不是元凶——因为真正的问题是
            // 「少画了一层」。少的那层此刻 op 就是 0，而 0 在画面上等于不存在，
            // 用肉眼或 hide 实验都找不着。把 op=0 的层连同它属于哪个 part 一起列出来，
            // 就能看出是哪一层该出现却没出现（part 名通常就是 PSD 里的图层名）。
            using namespace Live2D::Cubism::Core;
            const csmInt32* parents = csmGetDrawableParentPartIndices(_model->GetModel());
            char one[288];
            int total = 0;
            for (csmInt32 i = 0; i < _model->GetDrawableCount(); i++)
            {
                const float op = _model->GetDrawableOpacity(static_cast<csmUint32>(i));
                if (op > 0.01f) continue;
                const int part = parents ? parents[i] : -1;
                const char* partId = "(无)";
                if (part >= 0 && part < _model->GetPartCount())
                {
                    partId = _model->GetPartId(static_cast<csmUint32>(part))->GetString().GetRawString();
                }
                std::snprintf(one, sizeof(one), "opzero draw %d %s tex=%d part=%d(%s) partOp=%.2f",
                              i, _model->GetDrawableId(static_cast<csmUint32>(i))->GetString().GetRawString(),
                              _model->GetDrawableTextureIndex(i),
                              part, partId,
                              (part >= 0 && part < _model->GetPartCount()) ? _model->GetPartOpacity(part) : -1.0f);
                LogLine(one);
                total++;
            }
            LogLine(("opzero 结束：共 " + std::to_string(total) + " 个").c_str());
        }
        else if (command == "idle")
        {
            // 诊断：冻结待机（0 = 冻结，1 = 正常）。待机会让头一直轻微摆动，
            // 而像素比对必须停在同一个姿势上，不然额头和下巴在采样点里来回换，
            // 基准色一会儿是肤色一会儿是头发，什么结论都得不出来。
            idleOn_ = std::atoi(a.c_str()) != 0;
            if (!idleOn_)
            {
                for (auto& hold : holds_) hold.second.on = false;
            }
            LogLine(idleOn_ ? "诊断：待机恢复" : "诊断：待机已冻结");
        }
        else if (command == "blenddraws")
        {
            // 诊断：列出所有「不是普通混合」的 drawable（加算 / 正片叠底等）。
            //
            // 下巴白块的排查走到这一步：下半张脸整块发灰白，但逐块 hidedraw 谁都不是
            // 元凶——因为问题不是「多画了一块」，而是「少画了一层」。而少的那层几乎
            // 一定是正片叠底（Multiply）的阴影：它的贴图偏暗，叠在黑背景上看不出来，
            // 所以在 solo 图里也是黑的，肉眼根本找不到。只能按混合模式列出来。
            // 传 "all" 就列全部 drawable（不带混合过滤）。换模型后要「这块是谁画的」
            // 只能靠 bbox：不同模型的 ArtMesh 编号完全不通，名字也没语义。
            const bool all = a == "all";
            const int want = (a.empty() || a == "-1" || all) ? -1 : std::atoi(a.c_str());
            const csmInt32* blends = csmGetDrawableBlendModes(_model->GetModel());
            const csmInt32* maskCounts = csmGetDrawableMaskCounts(_model->GetModel());
            char one[288];
            int total = 0;
            for (csmInt32 i = 0; i < _model->GetDrawableCount(); i++)
            {
                if (!all && (blends == NULL || blends[i] == 0)) continue;
                if (!all && want >= 0 && blends[i] != want) continue;
                const csmInt32 vc = _model->GetDrawableVertexCount(i);
                const csmFloat32* v = _model->GetDrawableVertices(i);
                if (vc <= 0 || v == NULL) continue;
                float x0 = v[0], x1 = v[0], y0 = v[1], y1 = v[1];
                for (csmInt32 k = 1; k < vc; k++)
                {
                    const float x = v[k * 2 + 0];
                    const float y = v[k * 2 + 1];
                    if (x < x0) x0 = x;
                    if (x > x1) x1 = x;
                    if (y < y0) y0 = y;
                    if (y > y1) y1 = y;
                }
                std::snprintf(one, sizeof(one),
                              "blenddraw %d %s tex=%d blend=%d bbox=(%.3f,%.3f)-(%.3f,%.3f) op=%.2f maskN=%d",
                              i, _model->GetDrawableId(static_cast<csmUint32>(i))->GetString().GetRawString(),
                              _model->GetDrawableTextureIndex(i), blends[i],
                              x0, y0, x1, y1,
                              _model->GetDrawableOpacity(static_cast<csmUint32>(i)),
                              maskCounts ? maskCounts[i] : -1);
                LogLine(one);
                total++;
            }
            LogLine(("blenddraws 结束：共 " + std::to_string(total) + " 个").c_str());
        }
        else if (command == "drawinfo")
        {
            // 诊断：一个 drawable 的底细——贴图、透明度、混合模式、是否反向遮罩、
            // 乘算/加算颜色。查「这块白圆为什么这么亮」时，混合模式比什么都说明问题。
            const int index = std::atoi(a.c_str());
            if (index >= 0 && index < _model->GetDrawableCount())
            {
                using namespace Live2D::Cubism::Core;
                const csmModel* raw = _model->GetModel();
                const csmFlags* flags = csmGetDrawableConstantFlags(raw);
                const csmInt32* blends = csmGetDrawableBlendModes(raw);
                const csmVector4* mul = csmGetDrawableMultiplyColors(raw);
                const csmVector4* scr = csmGetDrawableScreenColors(raw);
                const csmInt32* maskCounts = csmGetDrawableMaskCounts(raw);
                const csmChar* id = _model->GetDrawableId(static_cast<csmUint32>(index))->GetString().GetRawString();
                char one[320];
                std::snprintf(one, sizeof(one),
                              "drawinfo %d %s tex=%d op=%.2f blend=0x%02x invMask=%d visFlag=%d maskN=%d",
                              index, id, _model->GetDrawableTextureIndex(index),
                              _model->GetDrawableOpacity(static_cast<csmUint32>(index)),
                              blends ? blends[index] : -1,
                              (flags && (flags[index] & csmIsInvertedMask)) ? 1 : 0,
                              _model->GetDrawableDynamicFlagIsVisible(index) ? 1 : 0,
                              maskCounts ? maskCounts[index] : -1);
                LogLine(one);
                std::snprintf(one, sizeof(one), "drawinfo %d mul=(%.2f,%.2f,%.2f,%.2f) screen=(%.2f,%.2f,%.2f,%.2f)",
                              index,
                              mul ? mul[index].X : -1.0f, mul ? mul[index].Y : -1.0f,
                              mul ? mul[index].Z : -1.0f, mul ? mul[index].W : -1.0f,
                              scr ? scr[index].X : -1.0f, scr ? scr[index].Y : -1.0f,
                              scr ? scr[index].Z : -1.0f, scr ? scr[index].W : -1.0f);
                LogLine(one);
            }
        }
        else if (command == "solo")
        {
            // 诊断：只画指定的那一个部件，其余全跳过（-1 恢复正常）。
            //
            // 排查下巴白块用。hide 的做法只能看到「少了什么」，而且这角色白衣服白脸皮
            // 到处都是纯白，按像素数统计噪声比信号大；solo 反过来，屏幕上出现什么就是
            // 那一块的真身，一眼就能认出「哦这块就是那个白圆」。
            const int index = std::atoi(a.c_str());
            g_amberSoloDrawable = (index >= 0 && index < _model->GetDrawableCount()) ? index : -1;
            LogLine((std::string("诊断：solo = ") + std::to_string(g_amberSoloDrawable)).c_str());
        }
        else if (command == "nomaskdraw" || command == "maskdraw")
        {
            // 诊断：只对某个 drawable 关掉 mask，用来定位「只有脸/下巴这一块被 mask 画脏」。
            const int index = std::atoi(a.c_str());
            if (index >= 0 && index < 2048)
            {
                g_amberNoMaskDrawable[index] = (command == "nomaskdraw");
                LogLine((std::string("诊断：") + command + " " + std::to_string(index)).c_str());
            }
        }
        else if (command == "order")
        {
            // 诊断：列出每个 drawable 的绘制顺序（小的先画）。定位「谁画在脸上面」用。
            const csmInt32* orders = csmGetRenderOrders(_model->GetModel());
            if (orders != NULL)
            {
                char one[192];
                for (csmInt32 i = 0; i < _model->GetDrawableCount(); i++)
                {
                    std::snprintf(one, sizeof(one), "order %d %d %s", i, orders[i],
                                  _model->GetDrawableId(static_cast<csmUint32>(i))->GetString().GetRawString());
                    LogLine(one);
                }
            }
        }
        else if (command == "uvbounds")
        {
            // 诊断：列出某个 drawable 的 UV 包围盒，用来在贴图里定位它。
            const int index = std::atoi(a.c_str());
            if (index >= 0 && index < _model->GetDrawableCount())
            {
                const csmInt32 vc = _model->GetDrawableVertexCount(index);
                const csmFloat32* uv = reinterpret_cast<const csmFloat32*>(_model->GetDrawableVertexUvs(index));
                if (vc > 0 && uv != NULL)
                {
                    float minU = uv[0], maxU = uv[0], minV = uv[1], maxV = uv[1];
                    for (csmInt32 k = 1; k < vc; k++)
                    {
                        const float u = uv[k * 2 + 0];
                        const float v = uv[k * 2 + 1];
                        if (u < minU) minU = u;
                        if (u > maxU) maxU = u;
                        if (v < minV) minV = v;
                        if (v > maxV) maxV = v;
                    }
                    const csmChar* id = _model->GetDrawableId(static_cast<csmUint32>(index))->GetString().GetRawString();
                    char one[224];
                    std::snprintf(one, sizeof(one), "uvbounds draw %d %s tex=%d uv=(%.4f,%.4f)-(%.4f,%.4f)",
                                  index, id, _model->GetDrawableTextureIndex(index),
                                  minU, minV, maxU, maxV);
                    LogLine(one);
                }
            }
        }
        else if (command == "set")
        {
            std::string name = a;
            bool negate = false;
            MapParam(a, &name, &negate);
            float value = std::strtof(b.c_str(), nullptr);
            if (negate) value = -value;
            const bool shown = holds_.count(name) && holds_[name].weight >= 0.01f;
            Hold& hold = holds_[name];
            hold.target = value;
            if (!shown) hold.value = hold.target;
            hold.on = true;
        }
        else if (command == "clear")
        {
            std::string name = a;
            bool negate = false;
            MapParam(a, &name, &negate);
            auto hold = holds_.find(name);
            if (hold != holds_.end()) hold->second.on = false;
        }
        else if (command == "clearall")
        {
            for (auto& hold : holds_) hold.second.on = false;
            for (auto& e : expressions_) e.second.on = false;
            mouthLevel_ = 0;
        }
        // 说话时的嘴。单独一条命令而不是 set ParamMouthOpenY，因为它必须**叠在**嘴型通道上：
        // 「大笑」把 ParamMouthOpenY 按住 0.6，这时她开口说话，嘴还得在 0.6 的基础上继续动。
        else if (command == "mouth") mouthLevel_ = std::min(1.0f, std::max(0.0f, std::strtof(a.c_str(), nullptr)));
        // 舞台光晕强度（0~1）。0 = 纯透明，完全交给实体 LED 灯带；1 = 底部最亮。
        // 默认 0.5，让虚像「踩」在一束暖光里，又不会盖过实体蓝光。
        else if (command == "stage") stageGlow_ = std::min(1.0f, std::max(0.0f, std::strtof(a.c_str(), nullptr)));
        // 摆台：fit <x> <y> <scale>，单位见 SetViewFit。不传参数就打印当前值，
        // 调参台/脚本回读时用得上（日志前缀统一成「摆放」方便 grep）。
        else if (command == "fit")
        {
            if (count >= 4)
            {
                SetViewFit(std::strtof(a.c_str(), nullptr),
                           std::strtof(b.c_str(), nullptr),
                           std::strtof(word[3].c_str(), nullptr));
            }
            char note[128];
            std::snprintf(note, sizeof(note), "摆放：x=%.3f y=%.3f scale=%.3f", viewX_, viewY_, viewScale_);
            LogLine(note);
        }
        else if (command == "anim" || command == "animloop")
        {
            Track track;
            bool negate = false;
            track.id = a;
            MapParam(a, &track.id, &negate);
            track.loop = command == "animloop";
            for (int k = 2; k < count; k++)
            {
                const std::string& key = word[k];
                const size_t split = key.find(':');
                if (split == std::string::npos) continue;
                float value = std::strtof(key.c_str() + split + 1, nullptr);
                // 名字换了轴的，整条轨迹的值都要跟着反号，否则摇头会变成「越摇越偏」
                if (negate) value = -value;
                track.keys.push_back({ std::strtof(key.c_str(), nullptr), value });
            }
            if (track.keys.size() >= 2) tracks_.push_back(track);
        }
        else if (command == "animstop") { for (auto& track : tracks_) track.loop = false; }
    }
}

void Pet::SetExpression(const std::string& name, bool on)
{
    auto entry = expressions_.find(name);
    if (entry == expressions_.end())
    {
        std::string message = "没有这个表情：" + name;
        LogLine(message.c_str());
        return;
    }
    entry->second.on = on;
    std::string message = std::string("表情 ") + name + (on ? " 开" : " 关")
        + "，参数 " + std::to_string(entry->second.parameters.GetSize()) + " 个";
    LogLine(message.c_str());
}

void Pet::PlayMotion(const std::string& name)
{
    auto entry = motions_.find(name);
    if (entry == motions_.end())
    {
        std::string message = std::string("没有这个动作：") + name;
        LogLine(message.c_str());
        return;
    }
    if (name == "IDLE")
    {
        _motionManager->StartMotionPriority(entry->second, false, 1);
        LogLine("动作 IDLE（待机特效层，只驱动 Shock/SweatBurst 等，不碰肢体）");
        return;
    }
    // 抚胸（左手）和竖手指、狗尾草（右手）各走各的队列：同一个队列里新动作一来旧的当场掐断，
    // 刚扬起来的左手就会卡在半空。桌宠 2026-11-08 修过一次，这里保持一致。
    CubismMotionManager* queue =
        (name == "默认手切举手" || name == "举手切默认手") ? leftHand_ : gestures_;

    // 关键：CubismMotionManager 里优先级不高于当前动作时，新动作会被**静默丢弃**。
    // 之前这里没管，于是「上一个动作还没播完就点下一个」＝点了没反应；
    // 待机自己随机播动作时点「打喷嚏」也是同样下场。手动下发一律抢占。
    const bool busy = queue && !queue->IsFinished();
    if (busy) queue->StopAllMotions();
    queue->StartMotionPriority(entry->second, false, 3);

    auto* motion = static_cast<CubismMotion*>(entry->second);
    std::string duration = std::to_string(motion->GetDuration());
    duration = duration.substr(0, duration.find('.') + 3);
    std::string message = std::string("动作 ") + name + "（" + duration + "s）"
        + (busy ? " 抢占上一个" : "");
    LogLine(message.c_str());
}

// ------------------------------------------------------------------ 待机编排
//
// 这台模型自带的「待机」素材不够用：
//   · motion/m0（index.txt 里的 IDLE）只有 21 条曲线，驱动的是 Shock / SweatBurst /
//     Param27~58 这类**特效**参数，头、身体、尾巴一条都没碰 —— 所以她站着是不动的。
//   · 物理设定有 131 组，其中二十多组（前发、侧发、马尾、耳鳍、胸口、裙摆、袖子、缎带、
//     身体重心）的输入就是 AngleX / AngleY / AngleZ / PositionX / PositionY / ParamBreath。
//
// 所以待机的正确做法不是「再写几段关键帧」，而是**去驱动这些上游输入**：滞后、回弹、
// 跟随全交给物理算，头发和尾巴自己就会甩。早先那版直接叠加 ParamAngleX（物理的**输出**）
// 看着僵，就是因为绕过物理硬掰。
//
// 顺带把一个错误的结论纠正在这里：当时「头、手、尾巴整片消失」不是待机代码干的，
// 而是 Cubism Core 的浮点 ABI 不匹配（见 src/LibmHardFloatArmv7.cpp）。待机是清白的。

namespace {

/**
 * 一套待机风格。
 *
 * <p>全部是低频正弦：不用关键帧，是因为正弦的导数连续，在 8fps 这种低帧率下也不会看出
 * 「一跳一跳」。主分量 + 不同频率的次分量叠加，避免明显的机械周期感。
 *
 * <p>AngleX/Y/Z 与 PositionX/Y 的量程都是 ±30，但看起来舒服的转头只有 ±10 度以内 ——
 * 再大就成了「摇头」，不像待机。
 */
struct IdleStyle
{
    const char* name;                  // 只给日志看，方便对真机上的表现
    float axAmp1, axFreq1, axPhase1;   // 转头 · 主分量（度）
    float axAmp2, axFreq2, axPhase2;   // 转头 · 次分量（打破周期性）
    float ayAmp, ayFreq;               // 点头 / 抬头（度）
    float azAmp, azFreq;               // 歪头（度）
    float pxAmp, pxFreq;               // 重心左右（PositionX）
    float leanAmp, leanFreq;           // 前倾后仰（LeanInput）
    float breathCycle;                 // 呼吸周期（秒）
    float breathLo, breathHi;          // 呼吸的谷 / 峰（ParamBreath 是 0~1）
    float gaze;                        // 眼神跟随转头的强度 0~1
};

const IdleStyle kIdleStyles[] = {
    { "安静",   2.2f, 0.055f, 0.0f,  0.8f, 0.170f, 1.1f,  0.9f, 0.040f,  1.2f, 0.033f,
      0.7f, 0.037f,  0.4f, 0.021f,  4.6f, 0.05f, 0.55f,  0.25f },
    // 环视四周：幅度最大，最像「在打量你」
    { "环视",   8.5f, 0.075f, 0.4f,  2.0f, 0.230f, 2.0f,  1.4f, 0.050f,  2.4f, 0.045f,
      1.1f, 0.040f,  0.7f, 0.028f,  4.1f, 0.03f, 0.62f,  0.75f },
    // 歪头发呆：头长期偏向一边，只有很慢的呼吸
    { "歪头",   2.6f, 0.045f, 1.1f,  0.9f, 0.160f, 0.3f,  0.6f, 0.035f,  5.5f, 0.028f,
      0.5f, 0.030f,  0.3f, 0.017f,  5.2f, 0.06f, 0.50f,  0.35f },
    // 换重心：身体慢慢左右挪，头轻微跟着摆
    { "换重心", 3.4f, 0.038f, 2.4f,  1.2f, 0.190f, 1.7f,  1.0f, 0.042f,  2.0f, 0.070f,
      3.0f, 0.048f,  1.2f, 0.026f,  4.3f, 0.05f, 0.58f,  0.30f },
    // 犯困：头慢慢往下沉，呼吸最长最深
    { "犯困",   1.6f, 0.035f, 3.1f,  0.6f, 0.130f, 2.6f,  2.6f, 0.025f,  1.6f, 0.031f,
      0.4f, 0.026f,  0.5f, 0.014f,  6.4f, 0.02f, 0.70f,  0.10f },
    // 好奇：头转得更勤、眼睛睁着四处看，像在打量新东西
    { "好奇",   6.0f, 0.100f, 0.2f,  2.2f, 0.260f, 1.3f,  2.4f, 0.060f,  4.8f, 0.055f,
      2.0f, 0.050f,  1.0f, 0.030f,  3.6f, 0.10f, 0.60f,  0.90f },
    // 活泼：幅度最大、呼吸略快，尾巴和裙摆一直在动
    { "活泼",   7.0f, 0.085f, 0.7f,  3.0f, 0.240f, 0.8f,  2.8f, 0.070f,  3.6f, 0.062f,
      3.4f, 0.058f,  1.6f, 0.034f,  3.2f, 0.12f, 0.62f,  0.85f },
};

const int kIdleStyleCount = static_cast<int>(sizeof(kIdleStyles) / sizeof(kIdleStyles[0]));


/** 一套风格在某一时刻的姿态，单位全是模型参数单位。 */
struct IdlePose
{
    float ax, ay, az, px, lean, breath, gazeX, gazeY;
};

/** 频率单位是「圈/秒」，乘 2π 得角速度。 */
IdlePose SampleIdle(const IdleStyle& s, float t)
{
    const float twoPi = 6.2831853f;
    const auto clampUnit = [](float v) { return std::max(-1.0f, std::min(1.0f, v)); };
    IdlePose p;
    p.ax = s.axAmp1 * std::sin(t * s.axFreq1 * twoPi + s.axPhase1)
         + s.axAmp2 * std::sin(t * s.axFreq2 * twoPi + s.axPhase2);
    p.ay = s.ayAmp * std::sin(t * s.ayFreq * twoPi + 0.7f);
    p.az = s.azAmp * std::sin(t * s.azFreq * twoPi + 1.9f);
    p.px = s.pxAmp * std::sin(t * s.pxFreq * twoPi + 0.3f);
    p.lean = s.leanAmp * std::sin(t * s.leanFreq * twoPi + 2.7f);
    // 呼吸用 1-cos 而不是 sin：谷底停得久一点，像人呼气之后的那一下停顿
    p.breath = s.breathLo + (s.breathHi - s.breathLo)
        * (0.5f - 0.5f * std::cos(twoPi * t / s.breathCycle));
    // 眼神跟着转头走，再叠一点更慢的漂移 —— 只有跟随的话，眼睛会像钉在头上
    p.gazeX = s.gaze * clampUnit(p.ax / 12.0f) + 0.10f * std::sin(t * 0.041f * twoPi + 1.2f);
    p.gazeY = s.gaze * clampUnit(p.ay / 12.0f) + 0.06f * std::sin(t * 0.033f * twoPi + 2.2f);
    return p;
}

}  // namespace

void Pet::TickIdle(float dt)
{
    if (!idleOn_) return;
    idleTime_ += dt;

    // 换状态：每个状态有自己的持续时间（活动 22~50 秒，情绪 18~34 秒）。
    // +1 再取模保证一定换到**另一个**，不会连着抽中同一个。
    idleSwitchIn_ -= dt;
    if (idleSwitchIn_ <= 0)
    {
        idlePrev_ = idleCur_;
        idleCur_ = (idleCur_ + 1 + (std::rand() % (kIdleStyleCount - 1))) % kIdleStyleCount;
        idleFade_ = 0;
        idleSwitchIn_ = 18.0f + (std::rand() % 160) * 0.1f;
        std::string message = std::string("待机切到：") + kIdleStyles[idleCur_].name;
        LogLine(message.c_str());
    }
    // 交叉淡化 2 秒：低帧率下这个时长刚好，短了会看到姿态跳变
    if (idleFade_ < 1.0f) idleFade_ = std::min(1.0f, idleFade_ + dt / 2.0f);

    const IdlePose cur = SampleIdle(kIdleStyles[idleCur_], idleTime_);
    const IdlePose prev = SampleIdle(kIdleStyles[idlePrev_], idleTime_);
    const float w = idleFade_;
    IdlePose p;
    p.ax = prev.ax + (cur.ax - prev.ax) * w;
    p.ay = prev.ay + (cur.ay - prev.ay) * w;
    p.az = prev.az + (cur.az - prev.az) * w;
    p.px = prev.px + (cur.px - prev.px) * w;
    p.lean = prev.lean + (cur.lean - prev.lean) * w;
    p.breath = prev.breath + (cur.breath - prev.breath) * w;
    p.gazeX = prev.gazeX + (cur.gazeX - prev.gazeX) * w;
    p.gazeY = prev.gazeY + (cur.gazeY - prev.gazeY) * w;

    // 偶发小动作：4~11 秒一次。只有正弦漂移的话像钟摆，加上这种「想起来做点什么」
    // 的脉冲才有活气。种类从 3 种扩到 6 种：低头 / 歪头 / 瞟一眼 / 换重心 /
    // 快速左右看 / 前倾。
    idlePulseIn_ -= dt;
    if (idlePulseT_ < 0 && idlePulseIn_ <= 0)
    {
        idlePulseT_ = 0;
        idlePulseKind_ = std::rand() % 6;
        idlePulseIn_ = 4.0f + (std::rand() % 70) * 0.1f;   // 4~11 秒
    }
    if (idlePulseT_ >= 0)
    {
        const float kPulseLength = (idlePulseKind_ == 4) ? 2.2f : 1.6f;
        idlePulseT_ += dt;
        if (idlePulseT_ >= kPulseLength) idlePulseT_ = -1;
        else
        {
            // 半个正弦包络：起落都是平的，不会「啪」地弹一下
            const float e = std::sin(3.14159265f * idlePulseT_ / kPulseLength);
            if (idlePulseKind_ == 0) p.ay -= 3.2f * e;              // 轻轻低头
            else if (idlePulseKind_ == 1) p.az += 4.5f * e;         // 歪一下头
            else if (idlePulseKind_ == 2) p.ax += 5.0f * e;         // 瞟一眼
            else if (idlePulseKind_ == 3) p.px += 3.0f * e;         // 重心挪一下
            else if (idlePulseKind_ == 4)
            {
                // 快速左右看：包络 × 两次正弦，起落仍然归零
                const float wobble = std::sin(4.0f * 3.14159265f * idlePulseT_ / kPulseLength);
                p.ax += 6.0f * e * wobble;
            }
            else p.lean += 2.5f * e;                                // 前倾一点
        }
    }

    // 眼神扫视：每 1.5~6 秒换一个目标，快速但不瞬移。幅度 ±0.35，够看出「眼睛在动」，
    // 又不会像在瞪人。和 SampleIdle 的慢漂移叠加，再用 ±1 夹住。
    idleGazeIn_ -= dt;
    if (idleGazeIn_ <= 0)
    {
        idleGazeTargetX_ = ((std::rand() % 200) - 100) * 0.0035f;   // -0.35~+0.35
        idleGazeTargetY_ = ((std::rand() % 120) - 60) * 0.0035f;    // -0.21~+0.21
        idleGazeIn_ = 1.5f + (std::rand() % 45) * 0.1f;
    }
    const float gazeK = std::min(1.0f, dt * 6.0f);
    idleGazeX_ += (idleGazeTargetX_ - idleGazeX_) * gazeK;
    idleGazeY_ += (idleGazeTargetY_ - idleGazeY_) * gazeK;
    p.gazeX = std::max(-1.0f, std::min(1.0f, p.gazeX + idleGazeX_));
    p.gazeY = std::max(-1.0f, std::min(1.0f, p.gazeY + idleGazeY_));

    // 待机肢体动作。走 gestures_ 队列（真正的关键帧动作，驱动物理输入 + 表情/嘴型）。
    // 只在「没在说话」且「手势队列空闲」时触发 —— 正在说话时不能让她打哈欠抢戏。
    //
    // 2026-11-24 换掉一批：用户反馈 m8~m25 里「打哈欠/伸懒腰/揉眼睛/拨头发看不出，
    // 哼歌摇头只是摇一下就没了，踮脚雀跃/伸懒打挺衣服抖一下，开心摇摆幅度太小」。
    // 量过才知道：那批曲线幅度 6~19，而 Angle 系列量程是 ±30，且只驱单参数做慢正弦，
    // 撞上「待机自己就在晃 ±10 度」= 等于没动。
    // 现在主力换成 m30~m36（tools/gen_big_motions.py 生成）：幅度打满、多参数叠加、
    // 节奏「快起-停住-收回」，实测画面变化 15%+（老的是 2~5%）。
    // 老的那批留在 assets 里给调参台看，不再进随机池 —— 池里只留下确实有帧差的几个。
    idleGestureIn_ -= dt;
    if (idleGestureIn_ <= 0 && mouthLevel_ <= 0.001f && gestures_ && gestures_->IsFinished())
    {
        static const char* kGestures[] = {
            // 大幅度（新增，权重占多数）
            "蹲下看看", "蹦两下", "大伸懒腰", "左右大张望", "歪头杀", "鞠躬行礼", "转个圈",
            // 老动作里帧差够大的几个
            "环视伸展", "打喷嚏", "甩甩头发", "困困点头", "闻一闻", "好奇歪头", "看窗外",
        };
        const int total = static_cast<int>(sizeof(kGestures) / sizeof(kGestures[0]));
        // 换模型时动作库跟着换：miku 那两个包只有一段 IDLE，没有成套动作。
        // 不过滤的话每 12~28 秒就往日志里刷一条「没有这个动作」，
        // 而且真机上「她一直不动」会被误当成动作播了但没效果。
        const char* name = nullptr;
        for (int attempt = 0; attempt < 6 && !name; attempt++)
        {
            const char* candidate = kGestures[std::rand() % total];
            if (motions_.find(candidate) != motions_.end()) name = candidate;
        }
        if (name)
        {
            PlayMotion(name);
            std::string message = std::string("待机肢体动作：") + name;
            LogLine(message.c_str());
            idleGestureIn_ = 12.0f + (std::rand() % 160) * 0.1f;   // 12~28 秒
        }
        else idleGestureIn_ = 30.0f;   // 这个模型没有动作库，别每帧都重试
    }

    // 这些是**物理的输入**（PhysicsSetting1~9、50~62 等都在消费它们），物理再把
    // ParamAngle* / 头发 / 耳鳍 / 尾巴算出来。所以：必须写在 OnLateUpdate 之前，
    // 而且用 SetParameterValue —— 这几个上游参数是我们完整拥有的，不该和谁叠加。
    // 动作在播时把待机摆幅压到 25%。
    // 待机自己「环视」风格的转头是 8.5+2.0=10.5 度，而动作曲线（打喷嚏 AngleY 19、
    // 甩头发 AngleX 6~12）都差不多这个量级 —— 不压下去，动作和背景晃动分不开，
    // 看着就是「点了没反应」。呼吸和眼神不压：它们是底噪，压了反而假。
    const bool gesturing = gestures_ && !gestures_->IsFinished();
    idleScale_ += ((gesturing ? 0.25f : 1.0f) - idleScale_) * std::min(1.0f, dt * 6.0f);
    p.ax *= idleScale_;
    p.ay *= idleScale_;
    p.az *= idleScale_;
    p.px *= idleScale_;
    p.lean *= idleScale_;

    auto* ids = CubismFramework::GetIdManager();
    if (standard_)
    {
        // 标准骨架：ParamAngleX/Y/Z 本身就是物理的**输入**，写完由物理带出脖子、
        // 头发、身体。所以这里直接写它，而且值不用反号。
        // 身体摆动按转头的 35% 跟着走 —— 头单独转 10 度像「脖子是根轴」，
        // 加上这点身体随动才像整个人在看。
        _model->SetParameterValue(ids->GetId("ParamAngleX"), p.ax);
        _model->SetParameterValue(ids->GetId("ParamAngleY"), p.ay);
        _model->SetParameterValue(ids->GetId("ParamAngleZ"), p.az);
        _model->SetParameterValue(ids->GetId("ParamBodyAngleX"), p.ax * 0.35f);
        _model->SetParameterValue(ids->GetId("ParamBreath"), p.breath);
        _model->SetParameterValue(ids->GetId("ParamEyeBallX"), p.gazeX);
        _model->SetParameterValue(ids->GetId("ParamEyeBallY"), p.gazeY);
        return;
    }

    _model->SetParameterValue(ids->GetId("AngleX"), p.ax);
    _model->SetParameterValue(ids->GetId("AngleY"), p.ay);
    _model->SetParameterValue(ids->GetId("AngleZ"), p.az);
    _model->SetParameterValue(ids->GetId("PositionX"), p.px);
    _model->SetParameterValue(ids->GetId("LeanInput"), p.lean);
    _model->SetParameterValue(ids->GetId("ParamBreath"), p.breath);
    _model->SetParameterValue(ids->GetId("ParamEyeBallX"), p.gazeX);
    _model->SetParameterValue(ids->GetId("ParamEyeBallY"), p.gazeY);
}

// ------------------------------------------------------------------ 每帧

void Pet::Resize(int width, int height)
{
    width_ = width;
    height_ = height;
    if (ready()) SetRenderTargetSize(static_cast<csmUint32>(width), static_cast<csmUint32>(height));
}

void Pet::Tick(float dt)
{
    if (!ready()) return;
    // 逻辑耗时画像。Cubism 的 Update() 要重算 58967 个顶点，物理模拟还要按 217KB 的
    // 物理设定做多轮解算 —— 这两块都是纯 CPU，和渲染分辨率完全无关。
    // 真机上「整帧 700ms 但渲染只要 70ms」的差值，最大嫌疑就在这里。
    const auto tickStart = std::chrono::steady_clock::now();
    const auto now = []() { return std::chrono::steady_clock::now(); };
    const auto dur = [](const std::chrono::steady_clock::time_point& a,
                        const std::chrono::steady_clock::time_point& b) {
        return std::chrono::duration<double, std::milli>(b - a).count();
    };
    DrainCommands();

    _model->LoadParameters();
    // 待机编排放在参数阶段的最前面（物理之前）：它写的是 AngleX/Y/Z、PositionX、
    // LeanInput、ParamBreath 这些**物理输入**，往后放的每一样东西（动作、表情、按住、
    // 说话口型）都能压在它上面，优先级天然正确。
    TickIdle(dt);
    auto idle = motions_.find("IDLE");
    if (_motionManager->IsFinished())
    {
        if (idle != motions_.end()) _motionManager->StartMotionPriority(idle->second, false, 1);
    }
    else _motionManager->UpdateMotion(_model, dt);
    if (gestures_) gestures_->UpdateMotion(_model, dt);
    if (leftHand_) leftHand_->UpdateMotion(_model, dt);
    _model->SaveParameters();
    const auto tA = now();
    profAccumMs_[0] += dur(tickStart, tA);

    for (auto& entry : expressions_)
    {
        Expression& expression = entry.second;
        const float step = dt / expression.fade;
        expression.weight = expression.on ? std::min(1.0f, expression.weight + step)
                                        : std::max(0.0f, expression.weight - step);
        if (expression.weight <= 0) continue;
        for (csmUint32 i = 0; i < expression.parameters.GetSize(); i++)
        {
            const auto& parameter = expression.parameters[i];
            if (parameter.BlendType == CubismExpressionMotion::Additive)
                _model->AddParameterValue(parameter.ParameterId, parameter.Value, expression.weight);
            else if (parameter.BlendType == CubismExpressionMotion::Multiply)
                _model->MultiplyParameterValue(parameter.ParameterId, parameter.Value, expression.weight);
            else _model->SetParameterValue(parameter.ParameterId, parameter.Value, expression.weight);
        }
    }

    const auto tB = now();
    profAccumMs_[1] += dur(tA, tB);

    // EyeOpen 是桌宠那边的约定，指两只眼一起；单独的参数名照写。
    lidOpen_[0] = lidOpen_[1] = 1;
    auto lid = [&](const std::string& id, float open) {
        const bool both = id == "EyeOpen", left = id == "ParamEyeLOpen", right = id == "ParamEyeROpen";
        if (both || left) lidOpen_[0] *= std::max(0.0f, open);
        if (both || right) lidOpen_[1] *= std::max(0.0f, open);
        return both || left || right;
    };

    // 按住的值像表情一样淡入淡出，否则情绪会生硬地跳上去。
    std::vector<std::pair<std::string, Hold*>> held;
    for (auto hold = holds_.begin(); hold != holds_.end();)
    {
        Hold& entry = hold->second;
        entry.weight += ((entry.on ? 1.0f : 0.0f) - entry.weight) * (1 - std::exp(-dt / 0.15f));
        entry.value += (entry.target - entry.value) * (1 - std::exp(-dt / 0.15f));
        if (!entry.on && entry.weight < 0.01f) { hold = holds_.erase(hold); continue; }
        if (!lid(hold->first, 1 + (entry.value - 1) * entry.weight)) held.push_back({ hold->first, &entry });
        ++hold;
    }

    // 动作轨道互相叠加：新来的从不把旧的打断。
    std::vector<std::pair<std::string, float>> moved;
    bool eyeAnim = false;
    for (auto track = tracks_.begin(); track != tracks_.end();)
    {
        track->time += dt;
        const float end = track->keys.back().first;
        if (track->time >= end)
        {
            if (!track->loop) { track = tracks_.erase(track); continue; }
            track->time = std::fmod(track->time, end);
        }
        size_t next = 1;
        while (next + 1 < track->keys.size() && track->keys[next].first < track->time) next++;
        const auto& from = track->keys[next - 1];
        const auto& to = track->keys[next];
        float part = std::min(1.0f, std::max(0.0f, (track->time - from.first) / std::max(0.0001f, to.first - from.first)));
        part = part * part * (3 - 2 * part);
        const float value = from.second + (to.second - from.second) * part;
        if (!lid(track->id, 1 + value)) moved.push_back({ track->id, value });
        if (track->id == "ParamEyeLOpen" || track->id == "ParamEyeROpen") eyeAnim = true;
        ++track;
    }
    blinkBlocked_ = eyeAnim;

    auto* ids = CubismFramework::GetIdManager();
    // 头部旋转参数（ParamAngleX/Y/Z）是大肥鱼这个模型的物理**输出**，物理模拟会在 OnLateUpdate
    // 里按 AngleX/Y/Z 输入重新计算它们。这里先把它们从 held / moved 里挑出来，留到物理之后再设置，
    // 否则刚设上去就被物理覆盖回 0，歪头/转头/低头全都不动。
    //
    // 标准骨架正好相反：ParamAngle 是物理的**输入**，必须在物理之前写，物理才会把
    // 头发、耳鳍、身体一起算出来。所以这一段只对大肥鱼生效 —— 对 miku 也延后的话，
    // 会变成「头转了，头发还留在原地」。
    const bool deferHead = !standard_;
    std::vector<std::pair<std::string, Hold*>> headTurns;
    std::vector<std::pair<std::string, Hold*>> heldBody;
    for (const auto& entry : held)
    {
        const std::string& id = entry.first;
        if (deferHead && (id == "ParamAngleX" || id == "ParamAngleY" || id == "ParamAngleZ"))
            headTurns.push_back(entry);
        else
            heldBody.push_back(entry);
    }
    std::vector<std::pair<std::string, float>> headMoves;
    std::vector<std::pair<std::string, float>> movedBody;
    for (const auto& entry : moved)
    {
        const std::string& id = entry.first;
        if (deferHead && (id == "ParamAngleX" || id == "ParamAngleY" || id == "ParamAngleZ"))
            headMoves.push_back(entry);
        else
            movedBody.push_back(entry);
    }
    for (const auto& entry : heldBody) _model->SetParameterValue(ids->GetId(entry.first.c_str()), entry.second->value, entry.second->weight);
    for (const auto& entry : movedBody) _model->AddParameterValue(ids->GetId(entry.first.c_str()), entry.second);

    // 说话的嘴最后叠上去：这样「按住大笑的嘴」和「正在说话」两种事能同时成立。
    // JawOpen 跟着走 0.55——和「张嘴」里 ParamMouthOpenY .7 / JawOpen .4 的比例一致，
    // 只张嘴不落颌会像贴纸。
    if (mouthLevel_ > 0.001f)
    {
        _model->AddParameterValue(ids->GetId("ParamMouthOpenY"), mouthLevel_);
        _model->AddParameterValue(ids->GetId("JawOpen"), mouthLevel_ * 0.55f);
    }

    const auto tC = now();
    profAccumMs_[2] += dur(tB, tC);

    _updateScheduler.OnLateUpdate(_model, dt);
    const auto tD = now();
    // 头部旋转在物理之后设置：直接覆盖物理对 ParamAngleX/Y/Z 的输出，让歪头/转头真正生效。
    for (const auto& entry : headTurns) _model->SetParameterValue(ids->GetId(entry.first.c_str()), entry.second->value, entry.second->weight);
    for (const auto& entry : headMoves) _model->AddParameterValue(ids->GetId(entry.first.c_str()), entry.second);

    // 待机（TickIdle）不在这里：它驱动的是物理**输入**，已经在参数阶段最前面调过了。
    // 这里只剩「外部显式指定头部朝向」这一种情况需要在物理之后覆盖。
    const auto tE = now();
    // 默认隐藏的部件（作者水印 / 使用须知 / 参考图）在这里压回 0 ——
    // 必须是**所有动作、表情、物理都跑完之后、Update 之前**的最后一步。
    //
    // ★ 2026-11-24 踩过：一开始放在 LoadParameters 之后（参数阶段最前面），
    //   part 的 op=0 设上去了（diag parts 里能看到 0.00），可屏上那行小字还在。
    //   原因是 IDLE 动作（Scene1）里带 part 不透明度曲线，UpdateMotion 每帧又把它写回 1。
    //   放最后才压得住。
    for (int index : hiddenPartIndex_) _model->SetPartOpacity(static_cast<csmUint32>(index), 0.0f);
    _model->Update();
    const auto tF = now();
    profAccumMs_[3] += dur(tC, tD);
    profAccumMs_[4] += dur(tE, tF);

    tickAccumMs_ += dur(tickStart, now());
    if (++tickFrames_ >= 30)
    {
        char text[320];
        std::snprintf(text, sizeof(text),
                "Tick 分段 平均 %.1fms = 参数动作 %.1f + 表情 %.1f + 按键轨道 %.1f"
                " + 物理 %.1f + 顶点Update %.1f",
                tickAccumMs_ / tickFrames_,
                profAccumMs_[0] / tickFrames_, profAccumMs_[1] / tickFrames_,
                profAccumMs_[2] / tickFrames_, profAccumMs_[3] / tickFrames_,
                profAccumMs_[4] / tickFrames_);
        LogLine(text);
        tickAccumMs_ = 0;
        for (int i = 0; i < 5; i++) profAccumMs_[i] = 0;
        tickFrames_ = 0;
    }
}

void Pet::SetViewFit(float x, float y, float scale)
{
    // 夹一下：x/y 超过 ±1.5 就已经整只移出画面了，scale 小于 0.2 会缩成一个点，
    // 大于 4 则必然糊成一片（贴图本来就是按画布高度铺的）。夹住比让用户自己撞墙好。
    const auto clamp = [](float v, float lo, float hi) { return std::min(hi, std::max(lo, v)); };
    viewX_ = clamp(x, -1.5f, 1.5f);
    viewY_ = clamp(y, -1.5f, 1.5f);
    viewScale_ = clamp(scale, 0.2f, 4.0f);
}

void Pet::Draw()
{
    if (!ready()) return;
    auto* renderer = static_cast<CubismRenderer_OpenGLES2*>(renderer_);

    Rendering::CubismOffscreenManager_OpenGLES2::GetInstance()->BeginFrameProcess();

    glViewport(0, 0, width_, height_);
    // 清屏之前必须把 scissor 关掉，否则 glClear 只清被裁的那一小块。
    //
    // ★ 2026-11-24 定位到的一个真 bug：换模型之后，屏上会残留上一个模型的一块像素
    //   （实测是 miku 那张「雪初音miku 2022」的小字）。诡异之处是 hideall 把所有部件
    //   都藏了、那行字还在，说明它不是画出来的，而是**从来没被清掉**。
    //   Cubism 的遮罩绘制会开 GL_SCISSOR_TEST，恢复靠 PreDraw/Restore 这对函数；
    //   一旦某帧的「保存时状态」已经是开的，就会被一直传下去，之后每帧的 glClear
    //   都只清那一小块，模型画不到的地方全是上一帧（甚至上一个模型）的像素。
    //   清屏是唯一必须覆盖全屏的操作，所以在这里无条件关掉。
    glDisable(GL_SCISSOR_TEST);
    // 背景不能画纯黑。琥珀音箱是「实体 LED 灯带 + 反射虚像」，立体感靠光学结构，
    // 纯黑会像一块幕布把真实的舞台光挡死。这里清成透明，让灯带的光透过来，
    // 角色像浮在发光舞台上，而不是贴在黑板上。
    glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT);
    // 投影矩阵自己搭，不走「_modelMatrix + projection.Scale」那套。
    //
    // 两个坑都在这里踩过：
    //   ① CubismModelMatrix 的构造函数只做 SetHeight(2.0)，纯缩放不带平移，
    //      照抄官方 demo 会让人以为要自己补居中；
    //   ② 但实测这个模型的顶点本来就以中心为原点（扫描出来是 -0.51..0.51 × -0.93..0.88），
    //      再加 0.5 画布的平移反而把她推出画面外。
    // 所以这里只缩放、不平移，让顶点原样落到 NDC。
    const float canvasHeight = _model->GetCanvasHeight();
    const float zoom = 2.0f / (canvasHeight > 0.0f ? canvasHeight : 1.0f) * viewScale_;
    // 左右镜像给到投影矩阵的第一列：x 取负，几何一起翻，不用碰顶点，也不用再走一遍 CPU。
    // 负行列式只影响三角面朝向，Cubism 的 PreDraw 里关掉了背面剔除，所以不会缺面。
    const float signX = flipX_ ? -1.0f : 1.0f;
    const float scaleX = signX * zoom * static_cast<float>(height_) / static_cast<float>(width_);
    const float scaleY = zoom;

    // 摆台（SetViewFit）：平移写在第四列，缩放并进上面的缩放里。
    // 注意平移是**在 NDC 里**加的，也就是「屏幕空间」偏移：左右镜像只翻几何，
    // 不翻这个值 —— 调参台上往右拖，实体屏上就是往右动，不存在方向反转。
    // （镜像一次只翻 x 的符号会把「右」变成「左」，那样调起来非常反直觉。）
    const float offsetX = flipX_ ? -viewX_ : viewX_;

    csmFloat32 matrix[16] = {
        scaleX, 0, 0, 0,
        0, scaleY, 0, 0,
        0, 0, 1, 0,
        offsetX, viewY_, 0, 1,
    };

    CubismMatrix44 projection;
    projection.SetMatrix(matrix);
    renderer->SetMvpMatrix(&projection);

    // 渲染耗时画像。CPU 端（驱动提交 draw call）如果就吃掉几百毫秒，瓶颈是部件数；
    // 如果 CPU 只花几十毫秒但帧率还是个位数，瓶颈在 GPU 填充/带宽。
    // 两者要走的优化路线完全相反，所以先分开量。
    const auto drawStart = std::chrono::steady_clock::now();
    renderer->DrawModel();
    const double drawMs = std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - drawStart).count();
    drawAccumMs_ += drawMs;
    // 顺便按 60 帧累加拆分数据。g_amber* 是每帧清零的瞬时值，单帧采样抖动大。
    static int accBodyCalls = 0;
    static int accMaskCalls = 0;
    static double accMaskMs = 0;
    static double accLoopMs = 0;
    static int accBatch = 0;
    static int accStrict = 0;
    static int accOpa = 0;
    accBodyCalls += g_amberBodyDrawCalls;
    accMaskCalls += g_amberMaskDrawCalls;
    accMaskMs += g_amberMaskSetupMs;
    accLoopMs += g_amberLoopMs;
    accBatch += g_amberBatchCount;
    accStrict += g_amberBatchStrict;
    accOpa += g_amberOpaNotOne;

    if (++drawFrames_ >= 60)
    {
        char text[256];
        std::snprintf(text, sizeof(text), "渲染 CPU 平均 %.1f ms/帧（60 帧样本）",
                drawAccumMs_ / drawFrames_);
        LogLine(text);

        // 主体 = DrawObjectLoop（460 次 glDrawElements + uniform 提交）
        // mask = SetupClippingContext（把所有 mask 几何画进 mask buffer，含 CPU 布局计算）
        const double bodyPer = accBodyCalls > 0 ? accLoopMs * 1000.0 / accBodyCalls : 0;
        const double maskPer = accMaskCalls > 0 ? accMaskMs * 1000.0 / accMaskCalls : 0;
        std::snprintf(text, sizeof(text),
                "绘制拆分 主体 %d 次 %.1fms（%.0fus/次） · mask %d 次 %.1fms（%.0fus/次）",
                accBodyCalls / drawFrames_, accLoopMs / drawFrames_, bodyPer,
                accMaskCalls / drawFrames_, accMaskMs / drawFrames_, maskPer);
        LogLine(text);

        // 合批值不值得写：把 key 相同的相邻部件并成一段，段数就是合批后的 draw call 数。
        // 能省的时间 = 减少的次数 × 每次的开销。拿这个和「写 600 行渲染路径」的成本比。
        const int bodyNow = accBodyCalls / drawFrames_;
        const int batchNow = accBatch / drawFrames_;
        const int strictNow = accStrict / drawFrames_;
        const double saveMs = (bodyNow - batchNow) * bodyPer / 1000.0;
        const double saveStrictMs = (bodyNow - strictNow) * bodyPer / 1000.0;
        std::snprintf(text, sizeof(text),
                "合批宽松 主体 %d 次可并为 %d 段（省 %d 次 ≈ %.1fms）",
                bodyNow, batchNow, bodyNow - batchNow, saveMs);
        LogLine(text);
        // 严格版才是真能做的：mask 必须是同一个 context（各自的贴图矩阵不同），
        // opacity 必须一模一样（u_baseColor 是 uniform）。这两个条件会把段数打散。
        std::snprintf(text, sizeof(text),
                "合批严格 可并为 %d 段（省 %d 次 ≈ %.1fms）· opacity<1 的部件 %d 个/帧",
                strictNow, bodyNow - strictNow, saveStrictMs, accOpa / drawFrames_);
        LogLine(text);

        drawAccumMs_ = 0;
        drawFrames_ = 0;
        accBodyCalls = 0;
        accMaskCalls = 0;
        accMaskMs = 0;
        accLoopMs = 0;
        accBatch = 0;
        accStrict = 0;
        accOpa = 0;
    }

    // 一次性自检：真机第一次起来时，这三行能直接判读「资源在不在、算没算坏」。
    // 只在第一帧打一次，Android 上就是三行 logcat。
    static bool diagnosed = false;
    if (!diagnosed)
    {
        diagnosed = true;
        char text[512];
        const int drawables = _model ? _model->GetDrawableCount() : 0;

        std::snprintf(text, sizeof(text),
            "自检 drawable=%d canvas=%.2fx%.2f 贴图=%d glError=0x%04X",
            drawables,
            _model ? _model->GetCanvasWidth() : 0.0f,
            _model ? _model->GetCanvasHeight() : 0.0f,
            static_cast<int>(renderer->GetBindedTextures().GetSize()),
            glGetError());
        LogLine(text);

        // 顶点带 NaN 会整块消失；opacity 为 0 是正常的表情隐藏；包围盒能看出比例对不对。
        int broken = 0, invisible = 0, total = 0;
        float minX = 1e9f, maxX = -1e9f, minY = 1e9f, maxY = -1e9f;
        for (int i = 0; i < drawables; i++)
        {
            const int count = _model->GetDrawableVertexCount(i);
            const float* points = _model->GetDrawableVertices(i);
            total += count;
            bool bad = false;
            for (int k = 0; k < count; k++)
            {
                const float x = points[k * 2], y = points[k * 2 + 1];
                if (x != x || y != y) { bad = true; break; }
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
            if (bad) broken++;
            if (_model->GetDrawableOpacity(i) <= 0.001f) invisible++;
        }
        std::snprintf(text, sizeof(text),
            "自检 顶点%d个 NaN部件=%d 隐藏部件=%d 包围盒=(%.2f,%.2f)-(%.2f,%.2f)",
            total, broken, invisible, minX, minY, maxX, maxY);
        LogLine(text);

        std::snprintf(text, sizeof(text),
            "自检 参数%d个，表情%d个，动作%d段",
            _model ? _model->GetParameterCount() : 0,
            static_cast<int>(expressions_.size()),
            static_cast<int>(motions_.size()));
        LogLine(text);

        // 性能画像：这三行决定「为什么只有 1.5 fps」。
        //   可见部件数 = 实际 draw call 数（渲染器会跳过不可见的）
        //   offscreen 组数 = 每帧额外的 FBO 切换 + 重绘次数（这个是低端 GPU 的头号杀手）
        //   带 mask 的部件数 = 每帧额外的 mask 预渲染次数
        int visible = 0;
        for (int i = 0; i < drawables; i++)
        {
            if (_model->GetDrawableDynamicFlagIsVisible(i)) visible++;
        }
        int maskParts = 0, maskTotal = 0;
        if (_model)
        {
            const int* maskCounts = csmGetDrawableMaskCounts(_model->GetModel());
            if (maskCounts)
            {
                for (int i = 0; i < drawables; i++)
                {
                    if (maskCounts[i] > 0) { maskParts++; maskTotal += maskCounts[i]; }
                }
            }
        }
        std::snprintf(text, sizeof(text),
            "画像 可见部件=%d/%d offscreen组=%d 带mask部件=%d 总mask=%d",
            visible, drawables,
            _model ? _model->GetOffscreenCount() : 0,
            maskParts, maskTotal);
        LogLine(text);
    }

    Rendering::CubismOffscreenManager_OpenGLES2::GetInstance()->EndFrameProcess();

    // GPU 侧耗时。glFinish 会等 GPU 真的画完，所以它测的是「显卡干活的时间」；
    // 如果这个数字很小而帧率还是很低，说明时间花在 eglSwapBuffers（合成/显示侧），
    // 那就不是渲染代码能救的了，得从窗口/compositing 那边想办法。
    if (gpuProbe_)
    {
        const auto finishStart = std::chrono::steady_clock::now();
        glFinish();
        gpuAccumMs_ += std::chrono::duration<double, std::milli>(
                std::chrono::steady_clock::now() - finishStart).count();
        if (++gpuFrames_ >= 20)
        {
            char text[256];
            std::snprintf(text, sizeof(text), "GPU glFinish 平均 %.1f ms/帧（20 帧样本）",
                    gpuAccumMs_ / gpuFrames_);
            LogLine(text);
            gpuAccumMs_ = 0;
            gpuFrames_ = 0;
        }
    }
}

}  // namespace amber
