# Native Libraries (JNI)

This directory contains pre-compiled native libraries for local on-device inference.

## Required Libraries

| Library | Purpose | Source |
|---------|---------|--------|
| `libllama.so` | llama.cpp for MobileVLM (GGUF inference) | Build from `ggerganov/llama.cpp` with Android NDK |
| `libncnn.so` | NCNN inference framework for SeeClick | Build from `Tencent/ncnn` with Vulkan |

## Directory Structure

```
app/src/main/jniLibs/
├── arm64-v8a/        # Modern 64-bit ARM devices
│   ├── libllama.so
│   └── libncnn.so
├── armeabi-v7a/      # Legacy 32-bit ARM devices
│   ├── libllama.so
│   └── libncnn.so
└── x86_64/           # Emulators and x86 devices
    ├── libllama.so
    └── libncnn.so
```

## Build Instructions

### libllama.so (llama.cpp)

```bash
git clone https://github.com/ggerganov/llama.cpp.git
cd llama.cpp
mkdir build-android && cd build-android

# For arm64-v8a
cmake -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release ..
make -j$(nproc)
cp libllama.so ../../app/src/main/jniLibs/arm64-v8a/
```

### libncnn.so (NCNN)

```bash
git clone https://github.com/Tencent/ncnn.git
cd ncnn
mkdir build-android && cd build-android

# For arm64-v8a
cmake -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DNCNN_VULKAN=ON \
  -DCMAKE_BUILD_TYPE=Release ..
make -j$(nproc)
cp libncnn.so ../../app/src/main/jniLibs/arm64-v8a/
```

## Fallback

If native libraries are not present, the app automatically falls back to
HTTP-based remote inference via the V2 Galaxy server.
