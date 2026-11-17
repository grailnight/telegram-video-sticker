#include <jni.h>
#include <stdlib.h>

#include "stickerenc.h"

#define NATIVE(name) Java_io_github_grailnight_videosticker_NativeEncoder_##name

typedef struct {
    JNIEnv *env;
    jobject listener;
    jmethodID method;
} callback_t;

static int on_progress(void *ctx, int done, int total) {
    callback_t *cb = ctx;
    jboolean go = (*cb->env)->CallBooleanMethod(cb->env, cb->listener, cb->method, (jint)done, (jint)total);
    if ((*cb->env)->ExceptionCheck(cb->env)) return 0;
    return go ? 1 : 0;
}

JNIEXPORT jlong JNICALL NATIVE(nativeCreate)(JNIEnv *env, jclass cls, jint width, jint height) {
    (void)env;
    (void)cls;
    return (jlong)(intptr_t)se_create(width, height);
}

JNIEXPORT void JNICALL NATIVE(nativeDestroy)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    se_destroy((se_store *)(intptr_t)handle);
}

JNIEXPORT jint JNICALL NATIVE(nativeAddFrame)(JNIEnv *env, jclass cls, jlong handle, jobject buffer,
                                             jboolean bottomUp) {
    (void)cls;
    const uint8_t *pixels = (*env)->GetDirectBufferAddress(env, buffer);
    if (!pixels) return SE_ERR_GENERIC;
    return se_add_rgba((se_store *)(intptr_t)handle, pixels, bottomUp ? 1 : 0);
}

JNIEXPORT jboolean JNICALL NATIVE(nativeHasAlpha)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    return se_has_alpha((se_store *)(intptr_t)handle) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL NATIVE(nativeEncode)(JNIEnv *env, jclass cls, jlong handle, jstring path,
                                            jintArray order, jint fps, jint bitrate, jint cpuUsed,
                                            jint threads, jobject listener) {
    (void)cls;
    callback_t cb = {env, listener, NULL};
    if (listener) {
        jclass listenerClass = (*env)->GetObjectClass(env, listener);
        cb.method = (*env)->GetMethodID(env, listenerClass, "onEncodeProgress", "(II)Z");
        if (!cb.method) return SE_ERR_GENERIC;
    }
    const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (!cpath) return SE_ERR_MEMORY;
    jsize count = (*env)->GetArrayLength(env, order);
    jint *indices = (*env)->GetIntArrayElements(env, order, NULL);
    long result = SE_ERR_MEMORY;
    if (indices) {
        result = se_encode((se_store *)(intptr_t)handle, (const int *)indices, (int)count, fps, bitrate,
                           cpuUsed, threads, cpath, listener ? on_progress : NULL, &cb);
        (*env)->ReleaseIntArrayElements(env, order, indices, JNI_ABORT);
    }
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    return (jlong)result;
}
