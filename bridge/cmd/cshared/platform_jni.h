// JNI surface for the Android Flutter host: the Kotlin helper
// (top.bangwu.taildeck.PlatformBridge) owns the network APIs that Go cannot
// use on Android 11+, and this shim hands them to td_set_platform_cb.
#ifndef TD_PLATFORM_JNI_H
#define TD_PLATFORM_JNI_H

#ifdef __ANDROID__
#include <jni.h>

/* Stores the JavaVM and a global ref to the helper, then registers the
 * platform callbacks. Returns 1 on success. */
int td_jni_attach(JNIEnv *env, jobject helper);
#endif

#endif
