#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include "whisper.h"
#include "whisper_core.h"

#define TAG "ShortsNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static std::atomic<bool> g_abort{false};

struct JniCb {
    JNIEnv * env;
    jobject listener;
    jmethodID mid;
    int last;
};

static void on_progress(int percent, void * user) {
    JniCb * c = (JniCb *) user;
    if (c->listener && c->mid && percent != c->last) {
        c->last = percent;
        c->env->CallVoidMethod(c->listener, c->mid, (jint) percent);
        if (c->env->ExceptionCheck()) c->env->ExceptionClear();
    }
}

static bool on_abort(void *) { return g_abort.load(); }

extern "C" {

JNIEXPORT jlong JNICALL Java_com_shortsmaker_proof_WhisperNative_nInit(JNIEnv * env, jclass, jstring path) {
    const char * p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context * ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return (jlong) ctx;
}

JNIEXPORT void JNICALL Java_com_shortsmaker_proof_WhisperNative_nFree(JNIEnv *, jclass, jlong ptr) {
    if (ptr) whisper_free((whisper_context *) ptr);
}

JNIEXPORT void JNICALL Java_com_shortsmaker_proof_WhisperNative_nAbort(JNIEnv *, jclass) {
    g_abort.store(true);
}

// Returns UTF-8 JSON bytes, or null on error (message in errOut[0]).
JNIEXPORT jbyteArray JNICALL Java_com_shortsmaker_proof_WhisperNative_nTranscribe(
        JNIEnv * env, jclass, jlong ptr, jstring pcmPath, jint threads, jstring lang, jboolean translate,
        jobject listener, jobjectArray errOut) {
    g_abort.store(false);
    const char * pp = env->GetStringUTFChars(pcmPath, nullptr);
    const char * lg = env->GetStringUTFChars(lang, nullptr);
    std::string path = pp, language = lg;
    env->ReleaseStringUTFChars(pcmPath, pp);
    env->ReleaseStringUTFChars(lang, lg);

    JniCb cb{env, listener, nullptr, -1};
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        cb.mid = env->GetMethodID(cls, "onProgress", "(I)V");
    }
    std::string err;
    std::string json = transcribe_pcm16_file((whisper_context *) ptr, path, threads, language, translate == JNI_TRUE,
                                             on_progress, on_abort, &cb, err);
    if (json.empty()) {
        jstring e = env->NewStringUTF(err.empty() ? "unknown error" : sanitize_utf8(err).c_str());
        env->SetObjectArrayElement(errOut, 0, e);
        return nullptr;
    }
    jbyteArray arr = env->NewByteArray((jsize) json.size());
    env->SetByteArrayRegion(arr, 0, (jsize) json.size(), (const jbyte *) json.data());
    return arr;
}

JNIEXPORT jstring JNICALL Java_com_shortsmaker_proof_WhisperNative_nSystemInfo(JNIEnv * env, jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}

}  // extern "C"
