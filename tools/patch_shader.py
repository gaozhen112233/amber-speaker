# -*- coding: utf-8 -*-
"""给 jni/local/CubismShader_OpenGLES2.cpp 打补丁：把 474 个组合的穷举编译改成按需编译。

这个脚本只跑一次（生成出来的 .cpp 直接进了版本管理），留着是为了说明改动到底是哪几处、
以及 SDK 升级时能一眼看出需要重新套哪些改动。
"""
import io
import re
import sys

PATH = 'jni/local/CubismShader_OpenGLES2.cpp'

src = open(PATH, encoding='utf-8').read()

BOM = '\ufeff'
has_bom = src.startswith(BOM)
if has_bom:
    src = src[len(BOM):]


# ---------------------------------------------------------------- 1. 文件头说明
HEADER = '''/**
 * ============================================================================
 *  这是 Live2D Cubism SDK 5-r.5 里 Framework/src/Rendering/OpenGL/
 *  CubismShader_OpenGLES2.cpp 的一份拷贝，只为了让着色器「按需编译」。
 *
 *  原版 GenerateShaders() 会把 16 种颜色混合 × 5 种 alpha 混合 × 6 种遮罩形态
 *  全部穷举编译一遍 —— 实测 474 个 program、骁龙 888 上 5.7 秒，而模型通常只用
 *  两三种混合模式（阿梓这个模型 715 个 drawable 只用到 Normal/Add/Mult）。
 *
 *  改动一共四处，都在这个文件里，SDK 目录本身一个字没改：
 *    [1] GenerateShaders()：三处「穷举混合模式组合」的循环清空，只留固定槽位
 *        （Copy / SetupMask / Normal / Add / Mult / NormalAtop… 共 32 个）。
 *    [2] SetupShaderProgramForDrawable()：真正画到某个组合而它还没编过时，就地补编
 *        那一个 program。所以「按需」不会漏 —— 任何模型、任何混合模式都仍然正确，
 *        只是没预编的组合会在第一次用到时付一次编译费。
 *    [3] SetShaderSet()：程序号为 0 的槽位直接返回，省掉几百次注定失败的
 *        glGetUniformLocation（它只会返回 -1 并置 GL_INVALID_VALUE）。
 *
 *    [4] 导出一个诊断计数 g_amberShaderProgramCount，供 Model.cpp 打启动日志。
 *
 *  升级 SDK 时：重新拷一份上游文件，把这四处改动重做一遍（grep "amber" 能找到全部）。
 * ============================================================================
 */

'''

src = HEADER + src

# ---------------------------------------------------------------- 2. 平台前缀宏
# 原版在三个平台分支里把文件名写死；补编这一处要能复用同一个名字，所以抽出来。
PREFIX_ANCHOR = '#include "CubismShader_OpenGLES2.hpp"\n'
PREFIX_CODE = '''#include "CubismShader_OpenGLES2.hpp"

// [amber] 补编时用什么路径前缀：桌面 GL 走 "FrameworkShaders/xxx"，移动端裸文件名。
#if defined(CSM_TARGET_WIN_GL) || defined(CSM_TARGET_LINUX_GL) || defined(CSM_TARGET_MAC_GL)
#define AMBER_SHADER_PREFIX "FrameworkShaders/"
#else
#define AMBER_SHADER_PREFIX ""
#endif
'''
assert src.count(PREFIX_ANCHOR) == 1
src = src.replace(PREFIX_ANCHOR, PREFIX_CODE, 1)


# ---------------------------------------------------------------- 3. 清空三处穷举循环
MARKER = '// ブレンドモードの組み合わせ分作成'
REPLACEMENT = '''// [amber] 原本这里穷举 16×5×6 = 474 个 program，启动时一次编完。
// 改成留空：真画到某个组合而它还没编过时，SetupShaderProgramForDrawable 会补编那一个。
// 这三处（Tegra / Android 非 Tegra / 桌面）都清空。'''

count = 0
search_from = 0
while True:
    at = src.find(MARKER, search_from)
    if at < 0:
        break
    # 找到紧随其后的 '{'，再做花括号配平
    brace = src.index('{', at)
    depth = 0
    i = brace
    while True:
        if src[i] == '{':
            depth += 1
        elif src[i] == '}':
            depth -= 1
            if depth == 0:
                break
        i += 1
    block = src[brace:i + 1]
    if 'LoadShaderProgramFromFile' not in block:
        # 这是后面「给所有槽位设 uniform 位置」的那段，同样带这句注释，不能删
        search_from = i + 1
        continue
    # 连同前面的注释行一起替换，缩进沿用原来的
    line_start = src.rfind('\n', 0, at) + 1
    indent = src[line_start:at]
    src = src[:line_start] + indent + REPLACEMENT.replace('\n', '\n' + indent) + src[i + 1:]
    count += 1
    search_from = line_start + len(indent) + len(REPLACEMENT)

assert count == 3, count


# ---------------------------------------------------------------- 4. SetShaderSet 早退
OLD_SETSET = '''void CubismShader_OpenGLES2::SetShaderSet(CubismShaderSet& shaderSets, const MaskType maskType, csmBool isBlendMode)
{
    shaderSets.AttributePositionLocation = glGetAttribLocation(shaderSets.ShaderProgram, "a_position");'''
NEW_SETSET = '''void CubismShader_OpenGLES2::SetShaderSet(CubismShaderSet& shaderSets, const MaskType maskType, csmBool isBlendMode)
{
    // [amber] 按需编译后，没编过的槽位程序号是 0。这时去查 attribute/uniform 只会得到 -1
    // 并置一个 GL_INVALID_VALUE，纯属浪费（启动阶段有几百次）。补编完成时会重新调到这里。
    if (shaderSets.ShaderProgram == 0)
    {
        return;
    }

    shaderSets.AttributePositionLocation = glGetAttribLocation(shaderSets.ShaderProgram, "a_position");'''
assert src.count(OLD_SETSET) == 1
src = src.replace(OLD_SETSET, NEW_SETSET, 1)


# ---------------------------------------------------------------- 5. 按需补编
OLD_PICK = '''    // シェーダーセット
    const csmInt32 shaderNameBegin = GetShaderNamesBegin(model.GetDrawableBlendModeType(index));
    CubismShaderSet* shaderSet = _shaderSets[shaderNameBegin + offset];'''
NEW_PICK = '''    // シェーダーセット
    const csmBlendMode blendMode = model.GetDrawableBlendModeType(index);
    const csmInt32 shaderNameBegin = GetShaderNamesBegin(blendMode);
    const csmInt32 setIndex = shaderNameBegin + offset;
    CubismShaderSet* shaderSet = _shaderSets[setIndex];

    // [amber] 按需补编：GenerateShaders 只预编了固定槽位，画到别的组合时在这里补。
    // 只有落在「混合模式组合区」的槽位才补 —— 固定槽位是 0 说明连 Normal 都没编出来，
    // 那是着色器全挂了，补也没用，直接让它画不出来。
    if (shaderSet->ShaderProgram == 0 && setIndex >= ShaderNames_NormalAtop)
    {
        // 把扁平索引还原成 (颜色混合 i, alpha 混合 j, 遮罩槽位)。
        // 布局见 GenerateShaders 原来的循环：i=0 时 j 从 1 起（Normal Over 用固定槽位），
        // 其余 i 每个吃 5 个 j；每种组合占 6 个槽位。
        const csmInt32 combo = (setIndex - ShaderNames_NormalAtop) / 6;
        const csmInt32 slot = (setIndex - ShaderNames_NormalAtop) % 6;

        csmInt32 colorBlendMode;
        csmInt32 alphaBlendMode;
        if (combo < 4)
        {
            colorBlendMode = ColorBlendMode_Nomal;
            alphaBlendMode = combo + 1;
        }
        else
        {
            const csmInt32 rest = combo - 4;
            colorBlendMode = 1 + rest / 5;
            alphaBlendMode = rest % 5;
        }

        const csmChar* vertShaderPath;
        const csmChar* fragShaderPath;
        MaskType maskType;
        switch (slot)
        {
        case 0:
            vertShaderPath = AMBER_SHADER_PREFIX "VertShaderSrcBlend.vert";
            fragShaderPath = AMBER_SHADER_PREFIX "FragShaderSrcBlend.frag";
            maskType = MaskType_None;
            break;
        case 1:
            vertShaderPath = AMBER_SHADER_PREFIX "VertShaderSrcMaskedBlend.vert";
            fragShaderPath = AMBER_SHADER_PREFIX "FragShaderSrcMaskBlend.frag";
            maskType = MaskType_Masked;
            break;
        case 2:
            vertShaderPath = AMBER_SHADER_PREFIX "VertShaderSrcMaskedBlend.vert";
            fragShaderPath = AMBER_SHADER_PREFIX "FragShaderSrcMaskInvertedBlend.frag";
            maskType = MaskType_MaskedInverted;
            break;
        case 3:
            vertShaderPath = AMBER_SHADER_PREFIX "VertShaderSrcBlend.vert";
            fragShaderPath = AMBER_SHADER_PREFIX "FragShaderSrcPremultipliedAlphaBlend.frag";
            maskType = MaskType_PremultipliedAlpha;
            break;
        case 4:
            vertShaderPath = AMBER_SHADER_PREFIX "VertShaderSrcMaskedBlend.vert";
            fragShaderPath = AMBER_SHADER_PREFIX "FragShaderSrcMaskPremultipliedAlphaBlend.frag";
            maskType = MaskType_MaskedPremultipliedAlpha;
            break;
        default:
            vertShaderPath = AMBER_SHADER_PREFIX "VertShaderSrcMaskedBlend.vert";
            fragShaderPath = AMBER_SHADER_PREFIX "FragShaderSrcMaskInvertedPremultipliedAlphaBlend.frag";
            maskType = MaskType_MaskedInvertedPremultipliedAlpha;
            break;
        }

        shaderSet->ShaderProgram = LoadShaderProgramFromFile(vertShaderPath, fragShaderPath, colorBlendMode, alphaBlendMode);
        SetShaderSet(*shaderSet, maskType, true);
        ++g_amberShaderProgramCount;
    }'''
assert src.count(OLD_PICK) == 1
src = src.replace(OLD_PICK, NEW_PICK, 1)


# ---------------------------------------------------------------- 6. 诊断计数
GEN_ANCHOR = 'void CubismShader_OpenGLES2::GenerateShaders()'
assert src.count(GEN_ANCHOR) == 1

# 6a. 全局变量（补编时也会 ++）
src = src.replace(GEN_ANCHOR, '''// [amber] 实际编译出来的 program 数（去重后），供 Model.cpp 打启动日志。
extern "C" int g_amberShaderProgramCount = 0;

''' + GEN_ANCHOR, 1)

# 6b. GenerateShaders 结尾统计一次
gen_at = src.index(GEN_ANCHOR)
brace = src.index('{', gen_at)
depth = 0
i = brace
while True:
    if src[i] == '{':
        depth += 1
    elif src[i] == '}':
        depth -= 1
        if depth == 0:
            break
    i += 1

COUNT_CODE = '''
    // [amber] 去重后统计真正编出来的 program 数：Add / Mult 复用 Normal 的程序，
    // 不去重会把省下来的那 12 个又算回去。这个数字进启动日志，优化有没有效一眼就看得出来。
    {
        csmVector<GLuint> seen;
        for (csmUint32 n = 0; n < _shaderSets.GetSize(); ++n)
        {
            const GLuint program = _shaderSets[n]->ShaderProgram;
            if (program == 0)
            {
                continue;
            }
            csmBool duplicate = false;
            for (csmUint32 k = 0; k < seen.GetSize(); ++k)
            {
                if (seen[k] == program)
                {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate)
            {
                seen.PushBack(program);
            }
        }
        g_amberShaderProgramCount = static_cast<int>(seen.GetSize());
    }
'''
src = src[:i] + COUNT_CODE + src[i:]

open(PATH, 'w', encoding='utf-8', newline='\n').write((BOM if has_bom else '') + src)
print('已打补丁：清空 %d 处穷举循环，补编逻辑已插入' % count)
