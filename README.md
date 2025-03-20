# 🤖 ChatApp V2

This repo contains a full voice assistant pipeline optimized for Qualcomm NPU.

<div align="center">
<pre>
+---------------+       +---------------------+       +---------------+
|      STT      |       |         LLM         |       |      TTS      |
|   (whisper)   |  ---> |   (llama3.2-3B)     |  ---> |   (kokoro)    |
+---------------+       +---------------------+       +---------------+
</pre>
</div>

## ⚠️ Current Limitations

ChatApp V2 **does not work on all** consumer devices with Android 14.

Genie SDK requires a newer meta-build to run LLMs on-device. Functionality may vary depending on your phone vendor's meta-build selection.

### 📱 Verified Devices

<div align="center">
  
| Device | OS |
|--------|------|
| Samsung Galaxy S25 Ultra | One UI 6.1 (Android 15) |

</div>

### 🧠 Verified Models

<div align="center">

| Component       | Model/Device                   | OS / Context Length           |
|-----------------|--------------------------------|-------------------------------|
| LLM             | Llama 3.2 3B                   | 2048                          |
| STT             | Whisper Tiny                   | –                             |
| TTS             | Kokoro int8 Multi-lang v1.1    | –                             |

</div>

💡 If you have a listed device, update to the specified OS version or newer to run the Sample App locally.

## 🛠️ Requirements

### Platform

- Snapdragon® Gen 3 or Snapdragon® 8 Elite
- QNN SDK version 2.31.0 or newer
- Compiled QNN context binaries for the above QNN SDK version
- `local.properties` file with valid `sdk.dir` property in root directory:
  ```bash
  sdk.dir=<path to android sdk>
  ```

## 📲 Install App

1. Download `.apk`
2. Push `.apk` to device:
   ```bash
   adb push ChatApp.apk /data/local/tmp
   ```
3. Open ADB Shell on android device:
   ```bash
   adb shell
   ```
   Then install `ChatApp.apk`:
   ```bash
   pm install -r /data/local/tmp/ChatApp.apk
   ```

## 📁 Important Paths

- 🔧 `/opt/qcom/aistack/qairt/2.31.0`: Example QNN-SDK location
- 📚 `/opt/qcom/aitstack/qairt/2.31.0/lib/external`: WhisperKit Android `.so` files
- 📋 `app/src/main/assets/config/models.json`: List of available models
- 🗣️ `app/src/main/assets/kokoro-int8-multi-lang-v1_1`: Assets for TTS support, Download from [HERE](https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models)
- 🗂️ `app/src/main/assets/`: Whisper models location
- 📊 `app/src/main/assets/models`: LLM models location
- ⚙️ `app/src/main/assets/htp_config`: HTP config files location

## Building Sherpa-ONNX with QNN Support

This guide walks you through building Sherpa-ONNX with Qualcomm Neural Network (QNN) support for enhanced performance on Snapdragon devices.

### Prerequisites

- [Git](https://git-scm.com/downloads) installed
- [Android NDK](https://developer.android.com/ndk/downloads) (recommended version r25c or later)
- [Qualcomm AI Stack](https://developer.qualcomm.com/software/qualcomm-ai-stack) installed (v2.31.0 or compatible version)
- [CMake](https://cmake.org/download/) 3.18 or newer
- Java Development Kit (JDK) 11 or newer

### Environment Setup

Ensure the following environment variables are set:

```bash
# Set QNN SDK path
export QNN_SDK_PATH=/opt/qcom/aitstack/qairt/2.31.0

# Set Android NDK Path
export ANDROID_NDK=/Users/fangjun/software/my-android/ndk/28.x.x

# Verify your environment variables
echo $QNN_SDK_PATH
echo $ANDROID_NDK
```

### Step 1: Clone the Repository

```bash
# Clone the QNN-enabled fork of Sherpa-ONNX
git clone https://github.com/mjnong/sherpa-onnx-qnn.git
cd sherpa-onnx-qnn
```

#### Directory Setup

```bash
# Make the build directory in advance such that we can place Sherpa ONNX with QNN support in that directory by running the script
./scripts/qairt/download_onnx_qnn.sh
```

#### System Link TTS Api file from Sherpa-ONNX

```bash
ln -s <sherpa-onnx-qnn>/sherpa-onnx/kotlin-api/Tts.kt <android-project-path>/app/src/main/java/com/edgeai/chatappv2/Tts.kt
```

### Step 2: Build for Android (arm64-v8a)

```bash
# Run the build script (uses NDK and builds for arm64-v8a)
./build-android-arm64-v8a.sh
```

During the build process:
- The script will compile both Sherpa-ONNX and ONNX Runtime with QNN support
- Build artifacts will be placed in `build-android-arm64-v8a/install/lib/`
- The process may take several minutes depending on your hardware

### Step 3: Install Libraries to QNN Runtime Directory

```bash
# Create the external directory if it doesn't exist
sudo mkdir -p /opt/qcom/aitstack/qairt/2.31.0/lib/external

# Copy the ONNX Runtime library with QNN support
sudo cp build-android-arm64-v8a/install/lib/libonnxruntime.so /opt/qcom/aitstack/qairt/2.31.0/lib/external/

# Copy the Sherpa-ONNX JNI library
sudo cp build-android-arm64-v8a/install/lib/libsherpa-onnx-jni.so /opt/qcom/aitstack/qairt/2.31.0/lib/external/
```

### Step 4: Verify Installation

```bash
# Check that the libraries exist in the target directory
ls -la /opt/qcom/aitstack/qairt/2.31.0/lib/external/
```

### Troubleshooting

- **Build errors related to QNN SDK**: Ensure `QNN_SDK_PATH` points to a valid QNN SDK installation
- **Permission issues when copying libraries**: Make sure you have write permissions to the target directory
- **Missing dependencies**: Run `ldd build-android-arm64-v8a/install/lib/libonnxruntime.so` to check for missing dependencies

### ℹ️ Info

For more information, refer to the [Sherpa-ONNX documentation](https://github.com/k2-fsa/sherpa-onnx),

### Builds

<details>
<summary>Building WhisperKit Android to Support Voice Transcription (English)</summary>

1. Clone repository:
   ```bash
   git clone https://github.com/argmaxinc/WhisperKitAndroid.git
   cd WhisperKitAndroid
   ```
2. Update `jni/NativeWhisperKit.cpp` function names to match this project
     ```bash
     Java_com_edgeai_chatappv2_WhisperKitNative_<function_name>
     ```
3. Update `Whipserkit/src/TranscribeTask.cpp` to support the correct `lib`, `cache` and `files` path when building for `jni`.
     ```cpp
     #if (JNI_BUILD)
     #define TRANSCRIBE_TASK_TFLITE_ROOT_PATH    "/data/user/0/com.edgeai.chatappv2/files"
     #define TRANSCRIBE_TASK_DEFAULT_LIB_DIR     "/data/user/0/com.edgeai.chatappv2/lib"
     #define TRANSCRIBE_TASK_DEFAULT_CACHE_DIR   "/data/user/0/com.edgeai.chatappv2/cache"
     #elif (QNN_DELEGATE || GPU_DELEGATE) 
     ...
     ```
4. Update versions in `scripts/dev_env.sh` and `scripts/Dockerfile` with correct QNN SDK version e.g. `2.31.0`
    1. Example `scripts/dev_env.sh`
     ```bash
     aria2c $ARIA_OPTIONS -d $BUILD_DIR https://repo1.maven.org/maven2/com/qualcomm/qti/qnn-runtime/2.31.0/qnn-runtime-2.31.0.aar
     aria2c $ARIA_OPTIONS -d $BUILD_DIR https://repo1.maven.org/maven2/com/qualcomm/qti/qnn-litert-delegate/2.31.0/qnn-litert-delegate-2.31.0.aar
     ```
    2. Example `scripts/Dockerfile`
     ```bash
     ARG QNN_RUNTIME=qnn-runtime-2.31.0.aar
     ARG QNN_TFLITE_DELEGATE=qnn-litert-delegate-2.31.0.aar
     ```
5. Build dev environment `make env`
6. Build `.so` files inside of dev environment:
   ```bash
   make build jni
   ```
7. Copy `.so` files to `/opt/qcom/aitstack/qairt/2.31.0/lib/external`, files to transfer:
    
    From `external/libs/android/`:
    - `libavcodec.so`
    - `libavformat.so`
    - `libavutil.so`
    - `libqnn_delegate_jni.so`
    - `libSDL3.so`
    - `libswresample.so`
    - `libtensorflowlite.so`
    - `libtensorflowlite_gpu_delegate.so`
   
   From `build/android/`:
    - `libwhisperkit.so`
    - `libnative-whisperkit.so`

</details>

## 📱 Demo Videos

### App Showcase

<div align="center">
  <a href="https://mjnong.github.io/chatapp-v2/assets/chatappv2.mp4">
    <img src="https://img.shields.io/badge/Watch%20Demo-ChatApp%20V2%20with%20Llama%203.2-blue?style=for-the-badge&logo=github" alt="Watch ChatApp V2 Demo"/>
  </a>
</div>

Experience ChatApp V2 in action, featuring:
- Conversational interactions with Llama 3.2 3B
- Customizable voice settings (speed and speaker selection)
- On-device processing using the Snapdragon® NPU

### Real-time TTS Feature

<div align="center">
  <a href="https://mjnong.github.io/chatapp-v2/assets/realtimetts.mp4">
    <img src="https://img.shields.io/badge/Watch%20Demo-Real--time%20TTS%20Feature-orange?style=for-the-badge&logo=github" alt="Watch Real-time TTS Demo"/>
  </a>
</div>

The real-time TTS feature provides:
- Instant voice feedback as the model generates text
- Natural-sounding speech with sentence-level processing

## 📄 License

This repository is built on-top of:
- [WhisperKitAndroid](https://github.com/argmaxinc/WhisperKitAndroid) - [License](https://github.com/argmaxinc/WhisperKitAndroid/blob/main/LICENSE)
- [Qualcomm ChatApp](https://github.com/quic/ai-hub-apps/tree/main/apps/android/ChatApp) - [License](https://github.com/quic/ai-hub-apps/blob/main/LICENSE)

This app is released under the [AGPL-3.0 License](LICENSE).
