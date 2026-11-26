// 两个平台的差异都收在这一个头里，好让 Model.cpp 能原样在两边编。
//
// 只有四件事不一样：
//   ① GL 头文件     Android 是 GLES2/gl2.h，Windows 是 GL/gl.h + 自己取扩展函数地址；
//   ② 日志          Android 走 logcat，Windows 走 stdout；
//   ③ 对齐分配      Android 是 posix_memalign，Windows 是 _aligned_malloc；
//   ④ 资源从哪读    这个不在这里，见 Assets 接口的两个实现（AssetPack / DirAssets）。
//
// 其余全部共用——这正是把渲染代码搬上桌面验证的意义：跑的是同一份 Model.cpp。
#pragma once

#include <cstddef>

#if defined(__ANDROID__)

#include <GLES2/gl2.h>

#else

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <GL/gl.h>

// 桌面 opengl32.lib 只导出 GL 1.1，2.0 以后（shader、FBO、VBO）得靠 wglGetProcAddress。
// GlGles2.hpp 是自动生成的，用宏把每个 glXxx 重定向到运行时加载的函数指针——
// 相当于一个小号 GLEW，内容直接来自 NDK 的 GLES2/gl2.h，所以和音箱上用的是同一份函数定义。
#include "GlGles2.hpp"

#endif

namespace amber {

/** 一行日志。Android 走 logcat（tag AmberGL），桌面走 stdout。 */
void LogLine(const char* message);

void* AlignedAlloc(std::size_t size, std::size_t alignment);
void AlignedFree(void* memory);

}  // namespace amber
