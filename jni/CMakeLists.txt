cmake_minimum_required(VERSION 3.18)
project(ambergl CXX)

# 路径里不含中文：SDK 原路径是「大肥鱼插件\sdk\...」，Ninja 和 clang 对它都出过问题，
# 所以 E:\toolchain\sdk 是指过去的 junction。
set(CSM_ROOT "E:/toolchain/sdk")
set(CORE     "${CSM_ROOT}/Core")
set(FRAMEWORK "${CSM_ROOT}/Framework")

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Cubism Core 是闭源静态库，32 位 ARM 上有一个必须先知道的坑：
#
#   · 官方 android/ 目录只给 arm64-v8a / x86 / x86_64，没有 armeabi-v7a；
#   · harmonyos/armeabi-v7a 那份虽然能链，但它是**纯软件浮点**编译的
#     （符号表里全是 __aeabi_fadd / __aeabi_fmul / __aeabi_fcmp*）。在这台 Cortex-A7 上，
#     715 个部件的模型每帧光 csmUpdateModel 就要 590ms —— 整机只有 1.5fps，动作看起来像慢放。
#   · experimental/rpi 那份是 ARMv6 + VFPv2 + AAPCS VFP（**硬件浮点**），同一个模型
#     的 Update 只要 52ms，快 11 倍，帧率 1.5 → 7.6fps。
#
# 但 rpi 那份是 -mfloat-abi=hard 编的，而 Android 的 libm 是 softfp：它调
# sincosf/atan2f/floorf/powf 时按 VFP 寄存器传参，libm 却按 r0 读。后果是旋转变形器
# （头、手臂、尾巴）整片塌掉，弯曲变形器（身体、腿、裙子）却完好——真机上就是
# 「头和手和尾巴都没了」。修法见 src/LibmHardFloatArmv7.cpp；配套的
# tools/patch_core_armv7.py 把这四个引用在库里改名成 __amber_hf_*，库走我们提供的
# 硬浮点壳，Framework 继续用系统 softfp 版。
#
# 所以这里用 rpi 那份，并且**拷进工程**（jni/local/）而不是引用 SDK 路径：
# SDK 升级时 experimental 目录随时可能消失，而这是渲染能不能跑起来的前提。
# 注意链接的是打过补丁的那份，原始副本存成 *.orig.a，随时可以重放生成。
#
# 另外它自带一个用 fprintf(stdout) 的日志实现，Android 的 libc 不导出 stdout，
# 所以 Model.cpp 提供了自己的 csmiPlatformDependentLogPrint 把它顶掉。
add_library(Live2DCubismCore STATIC IMPORTED)
set_target_properties(Live2DCubismCore PROPERTIES
  IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/local/libLive2DCubismCore_armv7_vfp.a"
  INTERFACE_INCLUDE_DIRECTORIES "${CORE}/include")

set(FRAMEWORK_SOURCE OpenGL)
add_subdirectory(${FRAMEWORK} ${CMAKE_BINARY_DIR}/Framework)
target_compile_definitions(Framework PUBLIC CSM_TARGET_ANDROID_ES2)

# 着色器按需编译。SDK 里的 GenerateShaders() 会把 16 种颜色混合 × 5 种 alpha 混合 ×
# 6 种遮罩形态全部编一遍（474 个 program，实测骁龙 888 上 5.7 秒），而这个模型只用得到
# Normal/Add/Mult 三种固定槽位。工程里放了一份打了补丁的拷贝（jni/local/，diff 见该文件
# 开头的说明），这里把 SDK 的那一份从源列表里换掉。
#
# 不用「再建一个库、靠链接顺序覆盖」那种办法：Framework 是静态库，同名符号谁先被拉进来
# 取决于链接顺序，换个构建系统就会悄悄换回原版。直接把源文件换掉，编进 .so 的一定是这份。
#
# 渲染器也换掉一份（jni/local/CubismRenderer_OpenGLES2.cpp，用 tools/patch_renderer.py 生成）：
# 原版 DrawMeshOpenGL() 每画一个部件都要 glGetIntegerv(GL_CURRENT_PROGRAM) 确认 program
# 绑好了，然后在末尾 glUseProgram(0) 解掉。这台机器每帧约 760 次 draw，等于每帧 760 次
# 同步状态查询 + 760 次多余的 program 解绑/重绑。去掉它们不影响绘制语义
# （DrawModel 结束时 RestoreProfile() 会恢复外部 program），顺便在里面加了
# 「主体 / mask」分开的计时计数器，用来判断合批值不值得做。
get_target_property(FRAMEWORK_SOURCES Framework SOURCES)
list(REMOVE_ITEM FRAMEWORK_SOURCES "${FRAMEWORK}/src/Rendering/OpenGL/CubismShader_OpenGLES2.cpp")
list(APPEND FRAMEWORK_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/local/CubismShader_OpenGLES2.cpp")
list(REMOVE_ITEM FRAMEWORK_SOURCES "${FRAMEWORK}/src/Rendering/OpenGL/CubismRenderer_OpenGLES2.cpp")
list(APPEND FRAMEWORK_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/local/CubismRenderer_OpenGLES2.cpp")
set_target_properties(Framework PROPERTIES SOURCES "${FRAMEWORK_SOURCES}")

# 补丁版放在 jni/local，头文件仍在 SDK 里，所以要把那个目录加进搜索路径。
target_include_directories(Framework PRIVATE "${FRAMEWORK}/src/Rendering/OpenGL")

add_library(ambergl SHARED
  src/Model.cpp
  src/Model.hpp
  src/Platform.hpp
  src/LibmHardFloatArmv7.cpp
  src/AssetsAndroid.cpp
  src/AssetsAndroid.hpp
  src/jni.cpp)

target_include_directories(ambergl PRIVATE
  src
  "${CSM_ROOT}/Samples/OpenGL/thirdParty/stb")

target_compile_options(ambergl PRIVATE -fexceptions)
target_link_libraries(ambergl
  Framework
  Live2DCubismCore
  GLESv2
  android
  log
  m)

# 16KB 页对齐，新系统装得进去（NDK r26 及以下要手动加）
target_link_options(ambergl PRIVATE "-Wl,-z,max-page-size=16384")
