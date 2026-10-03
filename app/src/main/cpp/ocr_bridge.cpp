#include <jni.h>
#include <dlfcn.h>
#include <exception>
#include <stdexcept>
#include <mutex>

namespace {
std::mutex guard;
bool running = false;
void *library = nullptr;
template<class T> T symbol(const char *name) {
    if (!library) {
        library = dlopen("libBAAS_ocr_server.so", RTLD_NOW | RTLD_LOCAL);
        if (!library) throw std::runtime_error(dlerror());
    }
    auto result = reinterpret_cast<T>(dlsym(library, name));
    if (!result) throw std::runtime_error(dlerror());
    return result;
}
void fail(JNIEnv *env, const std::exception &error) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
}

extern "C" JNIEXPORT void JNICALL
Java_moe_baas_aio_NativeOcr_start(JNIEnv *env, jclass, jstring path) {
    std::lock_guard<std::mutex> lock(guard);
    if (running) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "OCR already running"); return; }
    const char *value = env->GetStringUTFChars(path, nullptr);
    if (!value) return;
    try {
        symbol<void (*)(const char *)>("start_server")(value);
        running = true;
    } catch (const std::exception &error) { fail(env, error); }
    env->ReleaseStringUTFChars(path, value);
}

extern "C" JNIEXPORT void JNICALL
Java_moe_baas_aio_NativeOcr_stop(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(guard);
    if (!running) return;
    try {
        symbol<void (*)()>("stop_server")();
        running = false;
    } catch (const std::exception &error) { fail(env, error); }
}
