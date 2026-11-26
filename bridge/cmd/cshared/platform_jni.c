// Android only: bridges td_set_platform_cb to the Kotlin PlatformBridge.
// Compiled on every platform, but only non-empty for Android.
#include "platform_jni.h"

#ifdef __ANDROID__

#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include "_cgo_export.h"

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static JavaVM *g_vm;
static jobject g_helper;
static jclass g_cls;
static jmethodID g_m_ifaces;
static jmethodID g_m_default;
static jmethodID g_m_lookup;

// Go goroutines hop across threads, so every callback attaches its thread on
// demand and leaves it attached; the JVM frees it with the thread.
static JNIEnv *thread_env(void) {
  JavaVM *vm;
  JNIEnv *env = NULL;
  JavaVMAttachArgs args;
  pthread_mutex_lock(&g_lock);
  vm = g_vm;
  pthread_mutex_unlock(&g_lock);
  if (vm == NULL) return NULL;
  if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) return env;
  // Daemon: these threads die without a DetachCurrentThread, which would
  // otherwise leave the VM waiting on them at shutdown.
  args.version = JNI_VERSION_1_6;
  args.name = "taildeck-platform";
  args.group = NULL;
  if ((*vm)->AttachCurrentThreadAsDaemon(vm, &env, &args) != JNI_OK) return NULL;
  return env;
}

// Copies a Java string into malloc'd UTF-8 for Go, which frees it.
static char *dup_utf(JNIEnv *env, jstring s) {
  const char *utf;
  char *out;
  size_t n;
  if (s == NULL) return NULL;
  utf = (*env)->GetStringUTFChars(env, s, NULL);
  if (utf == NULL) return NULL;
  n = strlen(utf);
  out = malloc(n + 1);
  if (out != NULL) memcpy(out, utf, n + 1);
  (*env)->ReleaseStringUTFChars(env, s, utf);
  return out;
}

static char *call_string(jmethodID *slot, jstring arg) {
  JNIEnv *env = thread_env();
  jobject helper;
  jmethodID method;
  jstring s;
  char *out;
  if (env == NULL) return NULL;
  pthread_mutex_lock(&g_lock);
  helper = g_helper;
  method = *slot;
  pthread_mutex_unlock(&g_lock);
  if (helper == NULL || method == NULL) return NULL;
  if (arg == NULL) {
    s = (jstring)(*env)->CallObjectMethod(env, helper, method);
  } else {
    s = (jstring)(*env)->CallObjectMethod(env, helper, method, arg);
  }
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionClear(env);
    return NULL;
  }
  out = dup_utf(env, s);
  if (s != NULL) (*env)->DeleteLocalRef(env, s);
  return out;
}

static char *platform_ifaces(void *ctx) {
  (void)ctx;
  return call_string(&g_m_ifaces, NULL);
}

static char *platform_default_if(void *ctx) {
  (void)ctx;
  return call_string(&g_m_default, NULL);
}

static char *platform_lookup(char *host, void *ctx) {
  JNIEnv *env;
  jstring jhost;
  char *out;
  (void)ctx;
  if (host == NULL) return NULL;
  env = thread_env();
  if (env == NULL) return NULL;
  jhost = (*env)->NewStringUTF(env, host);
  if (jhost == NULL) {
    (*env)->ExceptionClear(env);
    return NULL;
  }
  out = call_string(&g_m_lookup, jhost);
  (*env)->DeleteLocalRef(env, jhost);
  return out;
}

// A failed lookup leaves a pending exception; the next JNI call would abort the
// process (ART: "unexpected pending exception"), so clear it per lookup.
static jmethodID method_id(JNIEnv *env, jclass cls, const char *name, const char *sig) {
  jmethodID id = (*env)->GetMethodID(env, cls, name, sig);
  if (id == NULL && (*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
  return id;
}

int td_jni_attach(JNIEnv *env, jobject helper) {
  JavaVM *vm = NULL;
  jclass cls;
  jmethodID m_ifaces, m_default, m_lookup;
  jobject new_helper, old_helper;
  jclass new_cls, old_cls;

  if (env == NULL || helper == NULL) return 0;
  if ((*env)->GetJavaVM(env, &vm) != JNI_OK || vm == NULL) return 0;
  cls = (*env)->GetObjectClass(env, helper);
  if (cls == NULL) return 0;
  // Instance methods: PlatformBridge.kt must not mark them @JvmStatic, because
  // GetMethodID never matches a static method.
  m_ifaces = method_id(env, cls, "interfacesJson", "()Ljava/lang/String;");
  m_default = method_id(env, cls, "defaultInterface", "()Ljava/lang/String;");
  m_lookup = method_id(env, cls, "lookup", "(Ljava/lang/String;)Ljava/lang/String;");
  if (m_ifaces == NULL || m_default == NULL || m_lookup == NULL) {
    (*env)->DeleteLocalRef(env, cls);
    return 0;
  }
  new_helper = (*env)->NewGlobalRef(env, helper);
  new_cls = (*env)->NewGlobalRef(env, cls);
  (*env)->DeleteLocalRef(env, cls);
  if (new_helper == NULL || new_cls == NULL) return 0;

  pthread_mutex_lock(&g_lock);
  old_helper = g_helper;
  old_cls = g_cls;
  g_vm = vm;
  g_helper = new_helper;
  g_cls = new_cls;
  g_m_ifaces = m_ifaces;
  g_m_default = m_default;
  g_m_lookup = m_lookup;
  pthread_mutex_unlock(&g_lock);
  if (old_helper != NULL) (*env)->DeleteGlobalRef(env, old_helper);
  if (old_cls != NULL) (*env)->DeleteGlobalRef(env, old_cls);

  td_set_platform_cb(platform_ifaces, platform_default_if, platform_lookup, 0);
  return 1;
}

JNIEXPORT __attribute__((used)) jboolean JNICALL
Java_top_bangwu_taildeck_PlatformBridge_nativeAttach(JNIEnv *env, jobject self) {
  return td_jni_attach(env, self) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT __attribute__((used)) void JNICALL
Java_top_bangwu_taildeck_PlatformBridge_nativeNetworkChanged(JNIEnv *env, jobject self) {
  char *reply;
  (void)env;
  (void)self;
  reply = td_network_changed();
  if (reply != NULL) td_free(reply);
}

#endif
