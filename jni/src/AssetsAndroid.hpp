// Android 侧的资源读取器：从 APK 的 assets/ 里读。
//
// 单独一个文件，是为了让 Model.cpp 里那一大坨渲染和动作逻辑能原样编到 PC 上跑。
// 两个平台只有「字节从哪来」不一样，其余共用。
#pragma once

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>

#include "Model.hpp"

namespace amber {

/** APK 里 assets/model 的读音：读文件、列目录。 */
class AssetPack : public Assets
{
public:
    void Open(JNIEnv* env, jobject manager);

    bool Read(const std::string& path, std::vector<char>& out) const override;
    std::vector<std::string> List(const std::string& directory) const override;

private:
    AAssetManager* manager_ = nullptr;
};

}  // namespace amber
