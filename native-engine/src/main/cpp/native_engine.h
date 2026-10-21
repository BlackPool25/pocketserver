#ifndef POCKETSERVER_NATIVE_ENGINE_H
#define POCKETSERVER_NATIVE_ENGINE_H

#include <jni.h>
#include <elf.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/resource.h>
#include <sched.h>
#include <signal.h>
#include <cerrno>
#include <cstring>
#include <string>
#include <vector>
#include <android/log.h>

#define LOG_TAG "PocketServerNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define REQUIRED_PAGE_SIZE 16384 // 16 KB boundary

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jboolean JNICALL
Java_com_pocketserver_nativeengine_NativeEngine_nativeIs16KbAligned(
    JNIEnv* env,
    jobject thiz,
    jstring file_path
);

JNIEXPORT jint JNICALL
Java_com_pocketserver_nativeengine_NativeEngine_nativeCreateRelativeSymlink(
    JNIEnv* env,
    jobject thiz,
    jstring target,
    jstring link_path
);

JNIEXPORT jint JNICALL
Java_com_pocketserver_nativeengine_NativeEngine_nativeAtomicRename(
    JNIEnv* env,
    jobject thiz,
    jstring old_path,
    jstring new_path
);

JNIEXPORT jint JNICALL
Java_com_pocketserver_nativeengine_NativeEngine_nativeFsyncDirectory(
    JNIEnv* env,
    jobject thiz,
    jstring dir_path
);

JNIEXPORT jlong JNICALL
Java_com_pocketserver_nativeengine_NativeEngine_nativeGetSystemPageSize(
    JNIEnv* env,
    jobject thiz
);

JNIEXPORT jint JNICALL
Java_com_pocketserver_nativeengine_NativeEngine_nativeSendSignalToProcessGroup(
    JNIEnv* env,
    jobject thiz,
    jint pgid,
    jint sig
);

JNIEXPORT jint JNICALL
Java_com_pocketserver_nativeengine_NativeEngine_nativeSendSignalToProcess(
    JNIEnv* env,
    jobject thiz,
    jint pid,
    jint sig
);

JNIEXPORT jstring JNICALL
Java_com_pocketserver_nativeengine_NativeEngine_nativeGetProcessState(
    JNIEnv* env,
    jobject thiz,
    jint pid
);

JNIEXPORT jint JNICALL
Java_com_pocketserver_nativeengine_NativeEngine_nativeSetCpuAffinity(
    JNIEnv* env,
    jobject thiz,
    jint pid,
    jlong cpu_mask
);

JNIEXPORT jint JNICALL
Java_com_pocketserver_nativeengine_NativeEngine_nativeSetProcessNice(
    JNIEnv* env,
    jobject thiz,
    jint pid,
    jint nice_value
);

#ifdef __cplusplus
}
#endif

#endif // POCKETSERVER_NATIVE_ENGINE_H
