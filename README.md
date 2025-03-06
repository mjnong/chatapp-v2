# 🤖 ChatApp V2

Chat application for Android on Snapdragon® with [Llama 3.2 3B](https://aihub.qualcomm.com/compute/models/llama_v3_2_3b_chat_quantized) using Genie SDK.

This app showcases the use of Genie C++ APIs from [QNN SDK](https://qpm.qualcomm.com/#/main/tools/details/qualcomm_ai_engine_direct) to run and accelerate LLMs using the Snapdragon® Neural Processing Unit (NPU).

🎙️ The app also supports loading of Whisper for voice input.

## ⚠️ Current Limitations

ChatApp V2 **does not work on all** consumer devices with Android 14.

Genie SDK requires a newer meta-build to run LLMs on-device. Functionality may vary depending on your phone vendor's meta-build selection.

### 📱 Verified Devices

| Device | OS |
|--------|------|
| Samsung Galaxy S25 Ultra | One UI 6.1 (Android 15) |

### 🧠 Verified Models

**LLM**
| Model | Context length |
|-------|----------------|
| Llama 3.2 3B | 2048 |

**Whisper**
| Model |
|-------|
| Whisper Tiny |

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
- 🗂️ `app/src/main/assets/`: Whisper models location
- 📊 `app/src/main/assets/models`: LLM models location
- ⚙️ `app/src/main/assets/htp_config`: HTP config files location

### Building WhisperKit Android to Support Voice Transcription (English)

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


## 📄 License

This repository is built on-top of:
- [WhisperKitAndroid](https://github.com/argmaxinc/WhisperKitAndroid) - [License](https://github.com/argmaxinc/WhisperKitAndroid/blob/main/LICENSE)
- [Qualcomm ChatApp](https://github.com/quic/ai-hub-apps/tree/main/apps/android/ChatApp) - [License](https://github.com/quic/ai-hub-apps/blob/main/LICENSE)

This app is released under the [AGPL-3.0 License](LICENSE).
