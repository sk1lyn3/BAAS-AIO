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
#ifdef BAAS_CAPTURE_PROBES
#include <unistd.h>
#endif

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

#ifdef BAAS_CAPTURE_PROBES
#define CAPTURE_PROBE(...) __VA_ARGS__
enum class CaptureStage { STOPPED, ACQUIRE, BUFFER, LOCK, COPY, UNLOCK, PREVIEW, PUBLISH, WAIT };
const char *capture_stage_name(int stage) {
    static const char *names[] = {"stopped", "acquire", "buffer", "lock", "copy", "unlock", "preview", "publish", "wait"};
    return names[stage];
}

// Approximate diagnostic snapshots; never take a capture/preview mutex to read them.
std::atomic<int> g_capture_stage{0}, g_capture_error_stage{0}, g_capture_error_status{0};
std::atomic<int64_t> g_capture_stage_ns{0}, g_capture_image_ns{0};
std::atomic<int64_t> g_source_timestamp_ns{0};
std::atomic<int> g_capture_tid{0};
std::atomic<uint64_t> g_capture_loops{0}, g_capture_images{0}, g_capture_empty{0}, g_capture_errors{0};

void capture_stage(CaptureStage stage) {
    g_capture_stage_ns.store(boot_time_ns(), std::memory_order_relaxed);
    g_capture_stage.store(static_cast<int>(stage), std::memory_order_relaxed);
}

void capture_error(CaptureStage stage, int status) {
    g_capture_error_stage.store(static_cast<int>(stage), std::memory_order_relaxed);
    g_capture_error_status.store(status, std::memory_order_relaxed);
    g_capture_errors.fetch_add(1, std::memory_order_relaxed);
}

#else
#define CAPTURE_PROBE(...) ((void) 0)
#endif

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
    CAPTURE_PROBE({
        g_capture_loops = 0; g_capture_images = 0;
        g_capture_empty = 0; g_capture_errors = 0;
        g_capture_image_ns = 0;
        g_source_timestamp_ns = 0; g_capture_tid = 0;
        g_capture_error_stage = 0; g_capture_error_status = 0;
        capture_stage(CaptureStage::STOPPED);
    });
}

void acquire_frame(AImageReader *reader) {
    CAPTURE_PROBE(capture_stage(CaptureStage::ACQUIRE));
    AImage *image = nullptr;
    const auto acquired = AImageReader_acquireLatestImage(reader, &image);
    if (acquired != AMEDIA_OK || !image) {
        CAPTURE_PROBE({
            if (acquired == AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE) g_capture_empty.fetch_add(1, std::memory_order_relaxed);
            else capture_error(CaptureStage::ACQUIRE, acquired == AMEDIA_OK ? AMEDIA_ERROR_UNKNOWN : acquired);
        });
        return;
    }
    CAPTURE_PROBE(g_capture_images.fetch_add(1, std::memory_order_relaxed));
    CAPTURE_PROBE(g_capture_image_ns.store(boot_time_ns(), std::memory_order_relaxed));
    CAPTURE_PROBE({
        int64_t timestamp = 0;
        if (AImage_getTimestamp(image, &timestamp) == AMEDIA_OK)
            g_source_timestamp_ns.store(timestamp, std::memory_order_relaxed);
    });

    CAPTURE_PROBE(capture_stage(CaptureStage::BUFFER));
    AHardwareBuffer *buffer = nullptr;
    const auto buffer_status = AImage_getHardwareBuffer(image, &buffer);
    if (buffer_status != AMEDIA_OK || !buffer) {
        CAPTURE_PROBE(capture_error(CaptureStage::BUFFER, buffer_status == AMEDIA_OK ? AMEDIA_ERROR_UNKNOWN : buffer_status));
        AImage_delete(image);
        return;
    }

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);
    if (desc.format != AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM || desc.stride < desc.width) {
        CAPTURE_PROBE(capture_error(CaptureStage::BUFFER, AMEDIA_ERROR_UNSUPPORTED));
        AImage_delete(image);
        return;
    }
    void *addr = nullptr;
    CAPTURE_PROBE(capture_stage(CaptureStage::LOCK));
    const auto lock_status = AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
                                                 -1, nullptr, &addr);
    if (lock_status != 0 || !addr) {
        CAPTURE_PROBE(capture_error(CaptureStage::LOCK, lock_status == 0 ? AMEDIA_ERROR_UNKNOWN : lock_status));
        AImage_delete(image);
        return;
    }

    CAPTURE_PROBE(capture_stage(CaptureStage::COPY));
    std::vector<uint8_t> copy;
    copy.resize(static_cast<size_t>(desc.width) * desc.height * 4);
    const auto *src = static_cast<const uint8_t *>(addr);
    for (uint32_t y = 0; y < desc.height; ++y) {
        memcpy(copy.data() + static_cast<size_t>(y) * desc.width * 4,
               src + static_cast<size_t>(y) * desc.stride * 4,
               static_cast<size_t>(desc.width) * 4);
    }

    CAPTURE_PROBE(capture_stage(CaptureStage::UNLOCK));
    const auto unlock_status = AHardwareBuffer_unlock(buffer, nullptr);
    if (unlock_status != 0) CAPTURE_PROBE(capture_error(CaptureStage::UNLOCK, unlock_status));
    CAPTURE_PROBE(capture_stage(CaptureStage::PREVIEW));
    if (!preview_offer(image)) AImage_delete(image);

    CAPTURE_PROBE(capture_stage(CaptureStage::PUBLISH));
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
    CAPTURE_PROBE(g_capture_tid.store(gettid(), std::memory_order_relaxed));
    // No NDK image callback: reader close cannot join a callback waiting on
    // the reader mutex. All acquired images are released before this join ends.
    while (!capturer->stopping) {
        // Budget the whole iteration: sleeping another 16 ms after the CPU copy
        // throttles a 60 Hz source to about 40 FPS on slower devices.
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(16);
        CAPTURE_PROBE(g_capture_loops.fetch_add(1, std::memory_order_relaxed));
        acquire_frame(capturer->reader);
        CAPTURE_PROBE(capture_stage(CaptureStage::WAIT));
        std::this_thread::sleep_until(deadline);
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

#ifdef BAAS_CAPTURE_PROBES
extern "C" JNIEXPORT jstring JNICALL
Java_moe_baas_aio_companion_NativeCaptureBridge_captureProbeDebug(JNIEnv *env, jclass) {
    const auto stage_ns = g_capture_stage_ns.load(std::memory_order_relaxed);
    const auto image_ns = g_capture_image_ns.load(std::memory_order_relaxed);
    const auto now = boot_time_ns();
    char json[512];
    snprintf(json, sizeof(json),
             "{\"stage\":\"%s\",\"stageAgeMs\":%lld,\"loops\":%llu,\"images\":%llu,"
             "\"empty\":%llu,\"imageAgeMs\":%lld,\"errors\":%llu,\"lastErrorStage\":\"%s\",\"lastErrorStatus\":%d,"
             "\"workerTid\":%d,\"sourceTimestampNs\":%lld}",
             capture_stage_name(g_capture_stage.load(std::memory_order_relaxed)),
             stage_ns ? static_cast<long long>((now - stage_ns) / 1000000) : -1LL,
             static_cast<unsigned long long>(g_capture_loops.load(std::memory_order_relaxed)),
             static_cast<unsigned long long>(g_capture_images.load(std::memory_order_relaxed)),
             static_cast<unsigned long long>(g_capture_empty.load(std::memory_order_relaxed)),
             image_ns ? static_cast<long long>((now - image_ns) / 1000000) : -1LL,
             static_cast<unsigned long long>(g_capture_errors.load(std::memory_order_relaxed)),
             capture_stage_name(g_capture_error_stage.load(std::memory_order_relaxed)),
             g_capture_error_status.load(std::memory_order_relaxed),
             g_capture_tid.load(std::memory_order_relaxed),
             static_cast<long long>(g_source_timestamp_ns.load(std::memory_order_relaxed)));
    return env->NewStringUTF(json);
}

#endif

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
