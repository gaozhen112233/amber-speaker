#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
给 Framework 的 OpenGL ES2 渲染器打补丁，并加上绘制诊断计数器。

背景
----
`CubismRenderer_OpenGLES2::DrawMeshOpenGL()` 里每画一个部件都要做一次

    glGetIntegerv(GL_CURRENT_PROGRAM, &currentProgram);
    if (currentProgram != 0) { ... glDrawElements ... }

这句在 ARM 的 GL 驱动上是**同步查询**：它会让驱动把已经排队的命令刷出去，
才能回答「当前 program 是哪个」。这台机器（Cortex-A7 + Adreno 304）每帧有
460 个主体部件 + 约 298 个 mask 几何要画，也就是每帧约 760 次这种白给的开销。

它想确认的事情是「上一行 glUseProgram 真的成功了」——失败概率为零，而这个信息
我们自己就有。同一函数末尾还有一句 `glUseProgram(0)`，导致下一次绘制要重新绑定
program，每帧又白做 760 次绑定。

去掉这两句是安全的：`CubismRenderer::DrawModel()` 是
`SaveProfile() -> DoDrawModel() -> RestoreProfile()`，而 `RestoreProfile()` 最后
一定会 `glUseProgram(_lastProgram)` 恢复到进 DrawModel 之前的状态，不会漏。

另外顺便加一组诊断计数器（/amber/ 前缀的全局变量），把「主体绘制」和「mask 预处理」
的耗时与 draw call 次数分开统计——合批能省的是**次数**，所以必须先知道这 760 次
在两边各占多少、每次是不是一样贵。

用法
----
    python tools/patch_renderer.py

幂等：已经打过就直接退出。SDK 升级时重新拷一份上游文件再跑一遍即可。
"""

import io
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
TARGET = os.path.join(PROJECT, 'jni', 'local', 'CubismRenderer_OpenGLES2.cpp')
SDK_SRC = 'E:/toolchain/sdk/Framework/src/Rendering/OpenGL/CubismRenderer_OpenGLES2.cpp'

MARK = '[amber]'


def sub(text, old, new, note):
    """只替换一次，找不到就报错。宁可失败也不要用模糊匹配改错地方。"""
    if text.count(old) != 1:
        sys.exit('补丁失败（%s）：期望恰好 1 处匹配，实际 %d 处。\n'
                 'SDK 可能升级了，请对照新版本重做这段。' % (note, text.count(old)))
    return text.replace(old, new, 1)


def main():
    if not os.path.exists(TARGET):
        if not os.path.exists(SDK_SRC):
            sys.exit('找不到 %s，也找不到 SDK 原件。' % TARGET)
        with io.open(SDK_SRC, encoding='utf-8-sig') as f:
            src = f.read()
        with io.open(TARGET, 'w', encoding='utf-8-sig', newline='') as f:
            f.write(src)
        print('已从 SDK 拷贝一份到 jni/local/')

    with io.open(TARGET, encoding='utf-8-sig') as f:
        s = f.read()

    if MARK in s:
        print('已经打过补丁，跳过。')
        return

    # ---------- [1] 文件头说明 ----------
    header = '''/**
 * ============================================================================
 *  这是 Live2D Cubism SDK 里 Framework/src/Rendering/OpenGL/
 *  CubismRenderer_OpenGLES2.cpp 的一份拷贝，改动只有两类（grep "amber" 全都能找到）：
 *
 *  [1] 去掉 DrawMeshOpenGL() 里每部件一次的 glGetIntegerv(GL_CURRENT_PROGRAM)
 *      和末尾的 glUseProgram(0)。
 *
 *      glGet* 在 ARM 驱动上会强制刷命令队列（同步点），这台机器每帧 460 个主体
 *      部件 + 约 298 个 mask 几何 = 约 760 次白给。它想确认的是「上一行
 *      glUseProgram 成功了」，这个信息我们自己就有，失败概率为零。
 *      末尾的 glUseProgram(0) 去掉同样安全：CubismRenderer::DrawModel() 是
 *      SaveProfile -> DoDrawModel -> RestoreProfile，RestoreProfile() 最后一定
 *      glUseProgram(_lastProgram)，状态不会漏到外面。
 *
 *  [2] 加绘制诊断计数器，把「主体绘制」和「mask 预处理」分开计时/计数。
 *      合批能省的是 draw call「次数」，所以先要知道两边各占多少、每次是不是
 *      一样贵，再决定值不值得重写渲染路径。计数器由 Model.cpp 每 60 帧读一次。
 *
 *  不改 SDK 目录（E:/toolchain/sdk），也不改这个文件的任何绘制语义。
 * ============================================================================
 */
'''
    s = header + s

    # ---------- [2] include + 诊断计数器 ----------
    s = sub(
        s,
        '#include "CubismRenderer_OpenGLES2.hpp"\n',
        '#include "CubismRenderer_OpenGLES2.hpp"\n'
        '\n'
        '// [amber] 绘制诊断计数器。每帧由 DoDrawModel() 清零，Model.cpp 每 60 帧读一次。\n'
        '// 用全局变量而不是成员，是为了不去动 SDK 的 .hpp（类布局一改，所有目标文件\n'
        '// 都得重编，还容易出现 ODR 问题）。\n'
        'int    g_amberBodyDrawCalls = 0;   // 主体 draw call 次数\n'
        'int    g_amberMaskDrawCalls = 0;   // mask 几何 draw call 次数\n'
        'double g_amberMaskSetupMs   = 0.0; // SetupClippingContext 总耗时（含 mask 几何绘制）\n'
        'double g_amberLoopMs        = 0.0; // DrawObjectLoop 总耗时（主体绘制）\n'
        'int    g_amberDrawFrames    = 0;   // DoDrawModel 被调用次数\n'
        '\n'
        '#include <chrono>\n',
        '[2] include 与计数器')

    # ---------- [3] DoDrawModel 开头清零 ----------
    s = sub(
        s,
        'void CubismRenderer_OpenGLES2::DoDrawModel()\n'
        '{\n'
        '    GLint lastFBO;\n'
        '    GLint lastViewport[4];\n',
        'void CubismRenderer_OpenGLES2::DoDrawModel()\n'
        '{\n'
        '    GLint lastFBO;\n'
        '    GLint lastViewport[4];\n'
        '\n'
        '    // [amber] 每帧清零诊断计数\n'
        '    g_amberBodyDrawCalls = 0;\n'
        '    g_amberMaskDrawCalls = 0;\n'
        '    g_amberMaskSetupMs = 0.0;\n'
        '    g_amberLoopMs = 0.0;\n'
        '    ++g_amberDrawFrames;\n',
        '[3] DoDrawModel 清零')

    # ---------- [4] mask 预处理计时 ----------
    s = sub(
        s,
        '        else\n'
        '        {\n'
        '            _drawableClippingManager->SetupClippingContext(*GetModel(), this, lastFBO, lastViewport, DrawableObjectType_Drawable);\n'
        '        }\n',
        '        else\n'
        '        {\n'
        '            // [amber] 这一段每帧把所有 mask 几何画进 mask buffer，是「主体绘制」\n'
        '            // 之外的第二块开销，单独计时。\n'
        '            const auto amberMaskStart = std::chrono::steady_clock::now();\n'
        '            _drawableClippingManager->SetupClippingContext(*GetModel(), this, lastFBO, lastViewport, DrawableObjectType_Drawable);\n'
        '            g_amberMaskSetupMs += std::chrono::duration<double, std::milli>(\n'
        '                    std::chrono::steady_clock::now() - amberMaskStart).count();\n'
        '        }\n',
        '[4] mask 计时')

    # ---------- [5] 主体循环计时 ----------
    s = sub(
        s,
        '    // モデルの描画順に従って描画する\n'
        '    DrawObjectLoop(lastFBO, lastViewport);\n',
        '    // モデルの描画順に従って描画する\n'
        '    // [amber] 主体绘制计时（460 个部件的 glDrawElements + uniform 提交）\n'
        '    const auto amberLoopStart = std::chrono::steady_clock::now();\n'
        '    DrawObjectLoop(lastFBO, lastViewport);\n'
        '    g_amberLoopMs += std::chrono::duration<double, std::milli>(\n'
        '            std::chrono::steady_clock::now() - amberLoopStart).count();\n',
        '[5] 主体循环计时')

    # ---------- [6] DrawMeshOpenGL：去掉同步查询与多余的 program 解绑 ----------
    s = sub(
        s,
        '    // ポリゴンメッシュを描画する\n'
        '    GLint currentProgram = 0;\n'
        '    glGetIntegerv(GL_CURRENT_PROGRAM, &currentProgram);\n'
        '    if(currentProgram != 0)\n'
        '    {\n'
        '        csmInt32 indexCount = model.GetDrawableVertexIndexCount(index);\n'
        '        csmUint16* indexArray = const_cast<csmUint16*>(model.GetDrawableVertexIndices(index));\n'
        '        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_SHORT, indexArray);\n'
        '    }\n'
        '\n'
        '    // 後処理\n'
        '    glUseProgram(0);\n'
        '    SetClippingContextBufferForDrawable(NULL);\n'
        '    SetClippingContextBufferForMask(NULL);\n',
        '    // ポリゴンメッシュを描画する\n'
        '    // [amber] 原版在这里做 glGetIntegerv(GL_CURRENT_PROGRAM, ...) 当保护，\n'
        '    // 每画一个部件一次同步查询；末尾还有 glUseProgram(0) 把刚绑的 program 解掉，\n'
        '    // 下一个部件又得重绑。两句都是纯开销，见文件头说明。\n'
        '    {\n'
        '        csmInt32 indexCount = model.GetDrawableVertexIndexCount(index);\n'
        '        csmUint16* indexArray = const_cast<csmUint16*>(model.GetDrawableVertexIndices(index));\n'
        '        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_SHORT, indexArray);\n'
        '    }\n'
        '    if (IsGeneratingMask()) { ++g_amberMaskDrawCalls; } else { ++g_amberBodyDrawCalls; }\n'
        '\n'
        '    // 後処理\n'
        '    SetClippingContextBufferForDrawable(NULL);\n'
        '    SetClippingContextBufferForMask(NULL);\n',
        '[6] DrawMeshOpenGL')

    with io.open(TARGET, 'w', encoding='utf-8-sig', newline='') as f:
        f.write(s)

    print('补丁完成：%s' % TARGET)
    print('  去掉 glGetIntegerv(GL_CURRENT_PROGRAM) + glUseProgram(0)：每帧约 760 次同步查询')
    print('  新增诊断计数器：g_amberBodyDrawCalls / g_amberMaskDrawCalls / g_amberMaskSetupMs / g_amberLoopMs')


if __name__ == '__main__':
    main()
