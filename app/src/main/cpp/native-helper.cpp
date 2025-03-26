#include <jni.h>
#include <string>
#include <cstdlib>
#include <android/log.h>

#define LOG_TAG "NativeHelper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

/**
 * Set an environment variable from JNI
 */
JNIEXPORT jboolean JNICALL
Java_com_edgeai_chatappv2_NativeHelper_setEnvNative(JNIEnv *env, jclass clazz, jstring name, jstring value) {
    if (name == nullptr || value == nullptr) {
        LOGE("Name or value is null");
        return JNI_FALSE;
    }
    
    const char *nameChars = env->GetStringUTFChars(name, nullptr);
    const char *valueChars = env->GetStringUTFChars(value, nullptr);
    
    if (nameChars == nullptr || valueChars == nullptr) {
        LOGE("Failed to get string UTF chars");
        return JNI_FALSE;
    }
    
    int result = setenv(nameChars, valueChars, 1);
    
    LOGI("Setting %s=%s, result: %d", nameChars, valueChars, result);
    
    env->ReleaseStringUTFChars(name, nameChars);
    env->ReleaseStringUTFChars(value, valueChars);
    
    return result == 0 ? JNI_TRUE : JNI_FALSE;
}

/**
 * Get an environment variable value from JNI
 */
JNIEXPORT jstring JNICALL
Java_com_edgeai_chatappv2_NativeHelper_getEnvNative(JNIEnv *env, jclass clazz, jstring name) {
    if (name == nullptr) {
        LOGE("Name is null");
        return nullptr;
    }
    
    const char *nameChars = env->GetStringUTFChars(name, nullptr);
    
    if (nameChars == nullptr) {
        LOGE("Failed to get string UTF chars");
        return nullptr;
    }
    
    const char *value = getenv(nameChars);
    
    env->ReleaseStringUTFChars(name, nameChars);
    
    if (value == nullptr) {
        LOGI("Environment variable %s not found", nameChars);
        return nullptr;
    }
    
    LOGI("Got %s=%s", nameChars, value);
    return env->NewStringUTF(value);
}

/**
 * Verify if the ADSP_LIBRARY_PATH is set
 */
JNIEXPORT jboolean JNICALL
Java_com_edgeai_chatappv2_NativeHelper_verifyAdspLibraryPathNative(JNIEnv *env, jclass clazz) {
    const char *adspPath = getenv("ADSP_LIBRARY_PATH");
    
    if (adspPath != nullptr) {
        LOGI("ADSP_LIBRARY_PATH is set to %s", adspPath);
        return JNI_TRUE;
    } else {
        LOGE("ADSP_LIBRARY_PATH is not set");
        return JNI_FALSE;
    }
}

/**
 * Print basic diagnostic information
 */
JNIEXPORT void JNICALL
Java_com_edgeai_chatappv2_NativeHelper_printDiagnosticInfoNative(JNIEnv *env, jclass clazz) {
    LOGI("----- Native Environment Variables -----");
    
    // Check ADSP_LIBRARY_PATH
    const char *adspPath = getenv("ADSP_LIBRARY_PATH");
    LOGI("ADSP_LIBRARY_PATH = %s", adspPath ? adspPath : "not set");
    
    // Check LD_LIBRARY_PATH
    const char *ldLibraryPath = getenv("LD_LIBRARY_PATH");
    LOGI("LD_LIBRARY_PATH = %s", ldLibraryPath ? ldLibraryPath : "not set");
    
    LOGI("----- End of Diagnostics -----");
}

} // extern "C" 