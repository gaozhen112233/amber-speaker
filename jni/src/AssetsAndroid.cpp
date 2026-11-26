#include "AssetsAndroid.hpp"

#include <android/log.h>

#include <algorithm>
#include <cstdlib>

namespace amber {

void AssetPack::Open(JNIEnv* env, jobject manager)
{
    manager_ = AAssetManager_fromJava(env, manager);
}

bool AssetPack::Read(const std::string& path, std::vector<char>& out) const
{
    if (!manager_) return false;
    AAsset* asset = AAssetManager_open(manager_, path.c_str(), AASSET_MODE_BUFFER);
    if (!asset) return false;
    const off64_t length = AAsset_getLength64(asset);
    out.resize(static_cast<size_t>(length));
    const int read = static_cast<int>(AAsset_read(asset, out.data(), static_cast<size_t>(length)));
    AAsset_close(asset);
    return read == static_cast<int>(length);
}

std::vector<std::string> AssetPack::List(const std::string& directory) const
{
    std::vector<std::string> names;
    if (!manager_) return names;
    AAssetDir* dir = AAssetManager_openDir(manager_, directory.c_str());
    if (!dir) return names;
    while (const char* name = AAssetDir_getNextFileName(dir))
    {
        std::string one(name);
        if (one.empty() || one[0] == '.') continue;
        names.push_back(one);
    }
    AAssetDir_close(dir);
    std::sort(names.begin(), names.end());
    return names;
}

// ------------------------------------------------------------------ Platform

void LogLine(const char* message)
{
    __android_log_write(ANDROID_LOG_INFO, "AmberGL", message);
}

void* AlignedAlloc(std::size_t size, std::size_t alignment)
{
    void* memory = nullptr;
    // posix_memalign 要求 alignment 是 sizeof(void*) 的幂次倍，对齐小于指针宽度时退化成 malloc。
    if (alignment < sizeof(void*)) return malloc(size);
    return posix_memalign(&memory, alignment, size) == 0 ? memory : nullptr;
}

void AlignedFree(void* memory)
{
    free(memory);
}

}  // namespace amber
