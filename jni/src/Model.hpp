// Android 端 Live2D 渲染器的核心。
//
// 和桌宠（renderer/main.cpp）的关系：那边的 Pet 类里有两坨东西，
//   ① 「身体怎么说」——脸追、扫视、切视线、走路晃动，这些在音箱上由 Java 的 Body 负责；
//   ② 「身体怎么动」——物理、眨眼、呼吸、表情权重、动作轨道、IDLE 循环，这些留在原生。
// 所以这里是 Pet 去掉 ① 之后的部分，加上从 AssetManager 读文件、用 stb 解贴图。
//
// 命令格式和桌宠一字不差（见 Sink.java），一条命令进来只改状态，真正算在 Tick 里做。
#pragma once

#include <deque>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "Platform.hpp"

#include <CubismFramework.hpp>
#include <CubismModelSettingJson.hpp>
#include <ICubismAllocator.hpp>
#include <Effect/CubismBreath.hpp>
#include <Effect/CubismEyeBlink.hpp>
#include <Math/CubismMatrix44.hpp>
#include <Model/CubismUserModel.hpp>
#include <Motion/CubismExpressionMotion.hpp>
#include <Motion/CubismMotionManager.hpp>

namespace amber {

/**
 * 模型资源的读音：读文件、列目录。
 *
 * 抽成接口是因为同一份渲染代码要在两个地方跑——音箱上从 APK 的 assets 读（{@code AssetPack}），
 * PC 上从磁盘目录读（{@code DirAssets}）。这样 GL 那一半代码可以完全不改就在桌面上验证，
 * 真机出问题时就能确定问题出在驱动或镜像上，而不是自己写错了。
 */
class Assets
{
public:
    virtual ~Assets() {}
    virtual bool Read(const std::string& path, std::vector<char>& out) const = 0;
    virtual std::vector<std::string> List(const std::string& directory) const = 0;
};

/**
 * 模型本体。所有 public 方法只在 GL 线程调用；
 * Command 是唯一的例外，它从主线程进来，进队列就算完事。
 */
class Pet : public Live2D::Cubism::Framework::CubismUserModel
{
public:
    Pet();
    ~Pet() override;

    /**
     * 只做 CPU 那部分：读 model3.json、moc3、物理、表情、动作。
     * **不碰 GL**，所以可以放在后台线程——不然解 19MB 资源会把 GL 线程堵住，
     * 这时候用户按 Home 会卡在 onPause 上，五秒就 ANR。
     *
     * <p>资源读取器用 shared_ptr 传进来而不是引用：CreateGl 建纹理时还要回头读一遍贴图，
     * 那已经是另一个线程、另一个时点了，靠调用方保证它活着太容易出事。
     */
    bool Prepare(std::shared_ptr<const Assets> assets, const std::string& modelDirectory);

    /** 建纹理和渲染器。必须在 GL 线程、上下文 current 的时候调。 */
    bool CreateGl(int width, int height);

    void Resize(int width, int height);
    void Tick(float dt);

    /**
     * 待机微动。在每帧的参数设置阶段叠加到 ParamAngleX/Z、ParamBodyAngleX 上。
     *
     * <p>为什么不用模型自带的 IDLE 动作：那一段是长循环，但幅度小、变化慢，
     * 实机上看着「一动不动」。这里用多套不同频率/幅度的低频正弦叠加，
     * 每 25~45 秒换一套、交叉淡化 2 秒，看起来永远不会重复同一个节奏。
     */
    void TickIdle(float dt);
    void Draw();

    /**
     * 画面左右镜像。默认开——这是给琥珀音箱那块「光路翻转」的屏用的。
     *
     * <p>真机上的链路是：应用内容 → 系统 rotation=2（整体转 180°）→ 光路再把画面上下翻。
     * 「转 180°」= 左右翻 + 上下翻，所以应用侧只要再左右翻一次，三者相乘正好是原样。
     * 关掉它就是普通手机上的样子（调试时用 --ez mirror false）。
     */
    void SetFlipX(bool flip) { flipX_ = flip; }

    /**
     * 模型在画面里的位置与大小（不改变模型本身，只改投影矩阵）。
     *
     * <p>为什么需要：三个模型的画布尺寸和「留白习惯」不一样。大肥鱼的顶点本来就
     * 铺满画布，miku 两个是标准全身立绘，canvas 中心偏上、脚还带一截空白 ——
     * 同一个投影打上去，她们在屏上的高度和上下位置差不少。这部分属于「摆台」而不是
     * 「模型数据」，所以放在投影矩阵里，切模型不影响，也不需要动 moc3。
     *
     * @param x     NDC 横向平移（-1 = 左边缘到右边缘一个屏宽）
     * @param y     NDC 纵向平移（+1 = 往上挪一个屏高的一半）
     * @param scale 在「铺满画布高度」基础上的倍率，1 = 原样
     */
    void SetViewFit(float x, float y, float scale);

    /** 主线程调用；内部只加锁入队。 */
    void Command(const std::string& line);

    bool ready() const { return renderer_ != nullptr; }

private:
    struct Expression
    {
        Live2D::Cubism::Framework::csmVector<Live2D::Cubism::Framework::CubismExpressionMotion::ExpressionParameter> parameters;
        float weight = 0, fade = 0.5f;
        bool on = false;
    };

    struct Hold
    {
        float value = 0, target = 0, weight = 0;
        bool on = true;
    };

    struct Track
    {
        std::string id;
        std::vector<std::pair<float, float>> keys;   // 秒 + 值；首尾都是 0，所以永远不跳
        float time = 0;
        bool loop = false;
    };

    void LoadSettings(const Assets& assets, const std::string& directory);
    void LoadTextures(const Assets& assets, const std::string& directory);
    void DrainCommands();

    /** 把桌宠的词换成这个模型的词（只对 FaceAngleX/Y/Z 生效）。改了就返回 true。 */
    bool MapParam(const std::string& in, std::string* out, bool* negate) const;

    /**
     * 这是不是「标准 Cubism 骨架」。
     *
     * <p>false = 大肥鱼：物理的输入是 AngleX / AngleY / AngleZ / PositionX / LeanInput
     * 这类不在参数表界面露面的死参数，ParamAngleX/Y/Z 反而是物理的**输出**，
     * 所以「转头」必须写成 AngleX，而且要在物理之后再覆盖 ParamAngle 才看得见。
     *
     * <p>true = miku 那两个：全套标准参数，ParamAngleX/Y/Z 就是物理的**输入**，
     * 直接写它、由物理带出头发和身体，写完不能反过来覆盖（否则头动了头发不动）。
     *
     * <p>由 index.txt 的 {@code [meta] profile} 决定。不写这一段的老模型按大肥鱼处理，
     * 因为它是第一个、也是唯一一个「非标准骨架」的模型。
     */
    bool standard_ = false;
    std::string profileName_ = "dafeiyu";

    void SetExpression(const std::string& name, bool on);
    void PlayMotion(const std::string& name);

    std::string directory_;
    std::shared_ptr<const Assets> assets_;   // Prepare 时留一份，CreateGl 建纹理还要读
    void* renderer_ = nullptr;   // CubismRenderer_OpenGLES2*，避免头文件污染
    Live2D::Cubism::Framework::CubismMotionManager* gestures_ = nullptr;
    Live2D::Cubism::Framework::CubismMotionManager* leftHand_ = nullptr;
    Live2D::Cubism::Framework::CubismModelSettingJson* setting_ = nullptr;

    std::map<std::string, Expression> expressions_;
    std::map<std::string, Live2D::Cubism::Framework::ACubismMotion*> motions_;
    std::map<std::string, std::string> expressionFiles_;   // 名字 -> 文件
    std::map<std::string, std::string> motionFiles_;
    /**
     * index.txt {@code [hide]} 段里的部件 Id（{@code Part18} 这种），加载完把它们的
     * **直属 drawable** 全部从画面上去掉。
     *
     * <p>为什么需要：这两个 miku 模型的源文件里带着作者水印和一张使用须知
     * （Part18「水印.psd」等），在 VTube Studio 里由按键手动关，默认是开着的。
     * 放到音箱上，她脸上就顶着一行「禁止商用 二传二改」。
     */
    std::vector<std::string> hiddenParts_;
    /** index.txt {@code [hide]} 里写成 {@code draw N} 的条目：直接按 drawable 编号关。 */
    std::vector<int> hiddenDrawables_;
    /**
     * index.txt {@code [hide]} 里写成 {@code drawid ArtMesh245} 的条目：按 drawable Id 关。
     *
     * <p>比编号稳：编号是 moc3 内部的顺序，作者重导一次模型就会整体错位，而 Id 是
     * 美术在 Cubism 里给网格起的名字，重导也还在。给「这块多余的白斑」这种只有一两个
     * 网格的场合用。
     */
    std::vector<std::string> hiddenDrawableIds_;
    /**
     * index.txt {@code [order]} 段：每行「要挪的 drawable Id<TAB>参照 Id」，表示前者要
     * 挪到后者**之前**画（也就是被后者盖住）。
     *
     * <p>用来修「侧发透过脸颊」：moc3 里这些发片排在脸之后，改不了模型，但渲染器里的
     * 绘制顺序就是一张列表，排完再搬位置就行。
     */
    std::vector<std::pair<std::string, std::string>> moveBefore_;
    /** 解析出来的部件编号（找不到的不进表），Tick 里每帧把不透明度压回 0。 */
    std::vector<int> hiddenPartIndex_;

    std::map<std::string, Hold> holds_;
    std::vector<Track> tracks_;
    std::vector<std::string> textures_;   // 只用来在析构时记住有哪些，纹理本身由渲染器持有

    std::mutex lock_;
    std::deque<std::string> commands_;

    /** 说话时的嘴开合（0~1），由 {@code mouth} 命令推。和 set 出来的嘴型叠加，不是替换。 */
    float mouthLevel_ = 0;

    /** 待机编排的运行时状态：当前风格、上一套（用于交叉淡化）、累计时间。 */
    float idleTime_ = 0;
    float idleFade_ = 1;        // 0 = 全在上一套，1 = 全是当前套
    float idleSwitchIn_ = 30;   // 还有多少秒换下一套
    int idleCur_ = 0;
    int idlePrev_ = 0;
    bool idleOn_ = true;
    /** 偶发小动作（点头 / 歪头 / 瞟一眼）：下一次还有多久、当前进度、是哪一种。 */
    float idlePulseIn_ = 4;
    float idlePulseT_ = -1;     // <0 表示没有在播
    int idlePulseKind_ = 0;
    /** 待机肢体动作（打哈欠 / 伸懒腰 / 环视伸展）：下一次还有多久。低频触发，10~24 秒。 */
    float idleGestureIn_ = 15;
    /**
     * 待机摆幅的缩放。动作在播时压到 0.25，动作结束回到 1。
     *
     * <p>待机「环视」风格的转头主分量 8.5 + 次分量 2.0 = 10.5 度，而动作曲线本身只有
     * 6~12 度，两者同量级 —— 不压下去，动作在持续晃动里根本看不出来，用户的原话是
     * 「动作里面很多没有用」。动作结束后平滑恢复，不能硬跳。
     */
    float idleScale_ = 1.0f;
    /**
     * 待机时的眼神扫视：每隔 1.5~6 秒换一个新的注视目标，平滑追过去。
     *
     * <p>只有 {@link SampleIdle} 里那条慢正弦的话，眼球像被钉在头上慢慢漂；
     * 真人待机时眼睛会「自己找东西看」——快速跳到某个点、停一下、再跳。这一层
     * 幅度很小（±0.35），但正是「活着」和「上发条」的区别。
     */
    float idleGazeX_ = 0;
    float idleGazeY_ = 0;
    float idleGazeTargetX_ = 0;
    float idleGazeTargetY_ = 0;
    float idleGazeIn_ = 2;
    float lidOpen_[2] = { 1, 1 };
    bool blinkBlocked_ = false;
    /** 应用侧左右镜像（见 SetFlipX 的注释）。真机屏默认要开。 */
    bool flipX_ = true;
    /** 摆台：NDC 平移 + 缩放倍率（见 SetViewFit）。0,0,1 = 原样。 */
    float viewX_ = 0, viewY_ = 0, viewScale_ = 1;
    int width_ = 0, height_ = 0;

    /** 渲染耗时画像的累加器：60 帧打一行，用来分清 CPU 提交与 GPU 填充谁是瓶颈。 */
    double drawAccumMs_ = 0;
    int drawFrames_ = 0;
    /** 逻辑（参数更新 + 物理 + 顶点重算）耗时累加器，以及四段拆分。 */
    double tickAccumMs_ = 0;
    double profAccumMs_[5] = { 0, 0, 0, 0, 0 };
    int tickFrames_ = 0;
    /** GPU 侧探针（glFinish 计时）。只为诊断开着，会影响吞吐，定位完就关。 */
    double gpuAccumMs_ = 0;
    int gpuFrames_ = 0;
    bool gpuProbe_ = false;  // 诊断完已关：实测 GPU 20ms，不是瓶颈

    /**
     * 舞台背景。琥珀音箱是「实体 LED 灯带 + 反射虚像」，立体感来自光学结构而非画面，
     * 所以背景不能画纯黑去挡光，而要让真实舞台光透过来。
     *
     * <p>{@code stageGlow_} 是底部光晕的强度（0~1）。默认 0.5：底部给一束淡淡的暖光，
     * 让虚像「踩」在光里，又不会盖过实体 LED 的蓝。设为 0 就是纯透明，完全交给灯带。
     */
    float stageGlow_ = 0.5f;
};

}  // namespace amber
