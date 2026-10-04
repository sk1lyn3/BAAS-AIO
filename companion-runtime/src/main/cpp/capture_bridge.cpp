#include <jni.h>
#include "preview.h"

#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>
#include <zlib.h>

#include <cstdint>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <time.h>
#include <vector>

#define LOG_TAG "BaasCaptureBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

struct Capturer {
    AImageReader *reader = nullptr;
    ANativeWindow *window = nullptr;
    int width = 0;
    int height = 0;
    std::atomic<bool> stopping{false};
    std::thread worker;
};

// JNI lifecycle calls serialize here. Never join a worker while holding g_lock.
std::mutex g_lifecycle_lock;
std::mutex g_lock;
Capturer *g_capturer = nullptr;
std::vector<uint8_t> g_rgba;
int g_width = 0;
int g_height = 0;
uint64_t g_frame_count = 0;
int64_t g_frame_time_ns = 0;

int64_t boot_time_ns() {
    timespec now{};
    clock_gettime(CLOCK_BOOTTIME, &now);
    return static_cast<int64_t>(now.tv_sec) * 1000000000LL + now.tv_nsec;
}

uint32_t crc32_chunk(const char *type, const std::vector<uint8_t> &data) {
    uint32_t crc = crc32(0L, Z_NULL, 0);
    crc = crc32(crc, reinterpret_cast<const Bytef *>(type), 4);
    if (!data.empty()) {
        crc = crc32(crc, data.data(), static_cast<uInt>(data.size()));
    }
    return crc;
}

void write_be32(std::vector<uint8_t> &out, uint32_t value) {
    uint8_t bytes[4] = {
            static_cast<uint8_t>((value >> 24) & 0xff),
            static_cast<uint8_t>((value >> 16) & 0xff),
            static_cast<uint8_t>((value >> 8) & 0xff),
            static_cast<uint8_t>(value & 0xff)
    };
    out.insert(out.end(), bytes, bytes + 4);
}

void write_chunk(std::vector<uint8_t> &out, const char *type, const std::vector<uint8_t> &data) {
    write_be32(out, static_cast<uint32_t>(data.size()));
    out.insert(out.end(), type, type + 4);
    out.insert(out.end(), data.begin(), data.end());
    write_be32(out, crc32_chunk(type, data));
}

std::vector<uint8_t> encode_png(const std::vector<uint8_t> &rgba, int width, int height) {
    if (rgba.empty() || width <= 0 || height <= 0) return {};

    std::vector<uint8_t> raw;
    raw.resize(static_cast<size_t>(height) * (1 + static_cast<size_t>(width) * 4));
    for (int y = 0; y < height; ++y) {
        size_t dst_row = static_cast<size_t>(y) * (1 + static_cast<size_t>(width) * 4);
        raw[dst_row] = 0;
        memcpy(raw.data() + dst_row + 1,
               rgba.data() + static_cast<size_t>(y) * width * 4,
               static_cast<size_t>(width) * 4);
    }

    uLongf compressed_size = compressBound(static_cast<uLong>(raw.size()));
    std::vector<uint8_t> compressed(compressed_size);
    if (compress2(compressed.data(), &compressed_size, raw.data(), static_cast<uLong>(raw.size()),
                  Z_BEST_SPEED) != Z_OK) {
        return {};
    }
    compressed.resize(compressed_size);

    const uint8_t sig[8] = {0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    std::vector<uint8_t> out(sig, sig + sizeof(sig));

    std::vector<uint8_t> ihdr(13);
    ihdr[0] = static_cast<uint8_t>((width >> 24) & 0xff);
    ihdr[1] = static_cast<uint8_t>((width >> 16) & 0xff);
    ihdr[2] = static_cast<uint8_t>((width >> 8) & 0xff);
    ihdr[3] = static_cast<uint8_t>(width & 0xff);
    ihdr[4] = static_cast<uint8_t>((height >> 24) & 0xff);
    ihdr[5] = static_cast<uint8_t>((height >> 16) & 0xff);
    ihdr[6] = static_cast<uint8_t>((height >> 8) & 0xff);
    ihdr[7] = static_cast<uint8_t>(height & 0xff);
    ihdr[8] = 8;  // bit depth
    ihdr[9] = 6;  // RGBA
    ihdr[10] = 0;
    ihdr[11] = 0;
    ihdr[12] = 0;

    write_chunk(out, "IHDR", ihdr);
    write_chunk(out, "IDAT", compressed);
    write_chunk(out, "IEND", {});
    return out;
}

void release_capturer() {
    if (g_capturer) {
        g_capturer->stopping = true;
        if (g_capturer->worker.joinable()) g_capturer->worker.join();
        preview_stop();
        if (g_capturer->reader) {
            AImageReader_delete(g_capturer->reader);
        }
        delete g_capturer;
        g_capturer = nullptr;
    }
    std::lock_guard<std::mutex> guard(g_lock);
    g_rgba.clear();
    g_width = 0;
    g_height = 0;
    g_frame_count = 0;
    g_frame_time_ns = 0;
}

void acquire_frame(AImageReader *reader) {
    AImage *image = nullptr;
    if (AImageReader_acquireLatestImage(reader, &image) != AMEDIA_OK || !image) {
        return;
    }

    AHardwareBuffer *buffer = nullptr;
    if (AImage_getHardwareBuffer(image, &buffer) != AMEDIA_OK || !buffer) {
        AImage_delete(image);
        return;
    }

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);
    if (desc.format != AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM || desc.stride < desc.width) {
        AImage_delete(image);
        return;
    }
    void *addr = nullptr;
    if (AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
                             -1, nullptr, &addr) != 0 || !addr) {
        AImage_delete(image);
        return;
    }

    std::vector<uint8_t> copy;
    copy.resize(static_cast<size_t>(desc.width) * desc.height * 4);
    const auto *src = static_cast<const uint8_t *>(addr);
    for (uint32_t y = 0; y < desc.height; ++y) {
        memcpy(copy.data() + static_cast<size_t>(y) * desc.width * 4,
               src + static_cast<size_t>(y) * desc.stride * 4,
               static_cast<size_t>(desc.width) * 4);
    }

    AHardwareBuffer_unlock(buffer, nullptr);
    if (!preview_offer(image)) AImage_delete(image);

    {
        std::lock_guard<std::mutex> guard(g_lock);
        g_rgba.swap(copy);
        g_width = static_cast<int>(desc.width);
        g_height = static_cast<int>(desc.height);
        ++g_frame_count;
        g_frame_time_ns = boot_time_ns();
    }
}

void capture_loop(Capturer *capturer) {
    // No NDK image callback: reader close cannot join a callback waiting on
    // the reader mutex. All acquired images are released before this join ends.
    while (!capturer->stopping) {
        acquire_frame(capturer->reader);
        std::this_thread::sleep_for(std::chrono::milliseconds(16));
    }
}

} // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_moe_baas_aio_companion_NativeCaptureBridge_setup(JNIEnv *env, jclass, jint width, jint height) {
    std::lock_guard<std::mutex> guard(g_lifecycle_lock);
    release_capturer();
    if (width <= 0 || height <= 0 || width > 4096 || height > 4096) return nullptr;

    auto *capturer = new Capturer();
    capturer->width = width;
    capturer->height = height;

    media_status_t status = AImageReader_newWithUsage(
            width,
            height,
            AIMAGE_FORMAT_RGBA_8888,
            AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,
            5,
            &capturer->reader);
    if (status != AMEDIA_OK || !capturer->reader) {
        LOGE("AImageReader_newWithUsage failed: %d", status);
        delete capturer;
        return nullptr;
    }

    status = AImageReader_getWindow(capturer->reader, &capturer->window);
    if (status != AMEDIA_OK || !capturer->window) {
        LOGE("AImageReader_getWindow failed: %d", status);
        AImageReader_delete(capturer->reader);
        delete capturer;
        return nullptr;
    }

    g_capturer = capturer;
    jobject surface = ANativeWindow_toSurface(env, capturer->window);
    if (!surface) {
        release_capturer();
        return nullptr;
    }
    capturer->worker = std::thread(capture_loop, capturer);
    LOGI("native capturer setup %dx%d", width, height);
    return surface;
}

extern "C" JNIEXPORT void JNICALL
Java_moe_baas_aio_companion_NativeCaptureBridge_release(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> guard(g_lifecycle_lock);
    release_capturer();
}

extern "C" JNIEXPORT void JNICALL
Java_moe_baas_aio_companion_NativeCaptureBridge_setPreviewSurface(JNIEnv *env, jclass, jobject surface) {
    preview_window(surface ? ANativeWindow_fromSurface(env, surface) : nullptr);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_moe_baas_aio_companion_NativeCaptureBridge_saveLatestPng(JNIEnv *env, jclass, jstring path) {
    if (!path) return JNI_FALSE;

    std::vector<uint8_t> rgba;
    int width;
    int height;
    {
        std::lock_guard<std::mutex> guard(g_lock);
        rgba = g_rgba;
        width = g_width;
        height = g_height;
    }
    if (rgba.empty()) return JNI_FALSE;

    const char *chars = env->GetStringUTFChars(path, nullptr);
    std::vector<uint8_t> png = encode_png(rgba, width, height);
    FILE *file = chars ? fopen(chars, "wb") : nullptr;
    bool ok = file && !png.empty() && fwrite(png.data(), 1, png.size(), file) == png.size();
    if (file && fclose(file) != 0) ok = false;
    if (chars) env->ReleaseStringUTFChars(path, chars);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_moe_baas_aio_companion_NativeCaptureBridge_frameInfo(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> guard(g_lock);
    jlong values[] = {g_width, g_height, static_cast<jlong>(g_frame_count), g_frame_time_ns};
    jlongArray result = env->NewLongArray(4);
    if (result) env->SetLongArrayRegion(result, 0, 4, values);
    return result;
}

extern "C" JNIEXPORT jobject JNICALL
Java_moe_baas_aio_companion_NativeCaptureBridge_captureFrame(JNIEnv *env, jclass) {
    std::vector<uint8_t> rgba;
    int width, height;
    jlong id, timestamp;
    {
        std::lock_guard<std::mutex> guard(g_lock);
        rgba = g_rgba;
        width = g_width;
        height = g_height;
        id = static_cast<jlong>(g_frame_count);
        timestamp = g_frame_time_ns;
    }
    if (rgba.empty()) return nullptr;
    uint64_t sum = 0, nonblack = 0;
    for (size_t i = 0; i < rgba.size(); i += 4) {
        sum += rgba[i] + rgba[i + 1] + rgba[i + 2];
        if (rgba[i] > 8 || rgba[i + 1] > 8 || rgba[i + 2] > 8) ++nonblack;
    }
    double pixels = static_cast<double>(width) * height;
    std::vector<uint8_t> png = encode_png(rgba, width, height);
    if (png.empty()) return nullptr;
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(png.size()));
    if (!bytes) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(png.size()),
                            reinterpret_cast<const jbyte *>(png.data()));
    jclass cls = env->FindClass("moe/baas/aio/companion/NativeFrame");
    if (!cls) return nullptr;
    jmethodID constructor = env->GetMethodID(cls, "<init>", "([BIIJJDD)V");
    if (!constructor) return nullptr;
    return env->NewObject(cls, constructor, bytes, width, height, id, timestamp,
                          sum / (pixels * 3), nonblack / pixels);
}
