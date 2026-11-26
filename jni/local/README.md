# jni/local —— 需要你自己放东西进来

这个目录在仓库里是**空的**（只有这份说明）。构建 `libambergl.so` 需要三个文件，
它们都属于 Live2D 的专有组件，许可证不允许随代码分发，所以必须你自己下载后放进来。

| 文件名 | 从哪来 |
|---|---|
| `libLive2DCubismCore_armv7_vfp.a` | Cubism SDK for Native → `Core/lib/android/armv7/libLive2DCubismCore.a` |
| `CubismRenderer_OpenGLES2.cpp` | SDK → `Framework/src/Rendering/OpenGLES2/` |
| `CubismShader_OpenGLES2.cpp` | 同上目录 |

下载地址：<https://www.live2d.com/sdk/download/native/>（需要同意 Live2D 的许可协议）

放好之后**必须**跑一次补丁，否则旋转会整片塌掉：

```bash
python tools/patch_core_armv7.py     # 把 softfp 的 libm 引用改到硬浮点实现
python tools/patch_renderer.py       # 去掉每帧 760 次的同步 GL 查询，并加诊断计数器
python jni/build.py
```

为什么会塌、补丁具体改了什么，见 [`docs/TROUBLESHOOTING.md`](../../docs/TROUBLESHOOTING.md)
和 [`docs/DEVELOPMENT.md`](../../docs/DEVELOPMENT.md)。

> ⚠️ 不要把你放进去的这三个文件提交到 git。`.gitignore` 已经挡掉了，
> 但如果你改过文件名，请自己确认一次 `git status`。
