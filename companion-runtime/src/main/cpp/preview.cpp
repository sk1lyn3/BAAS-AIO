#include "preview.h"
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <condition_variable>
#include <mutex>
#include <thread>

namespace {
std::mutex lifecycle, queue;
std::condition_variable changed;
std::thread worker;
bool running = false, window_changed = false;
ANativeWindow *pending_window = nullptr;
AImage *pending_image = nullptr;

GLuint shader(GLenum type, const char *source) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &source, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) { glDeleteShader(s); return 0; }
    return s;
}

void render_loop() {
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLConfig config = nullptr;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    ANativeWindow *window = nullptr;
    GLuint program = 0, texture = 0;
    auto native_buffer = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    auto create_image = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
    auto destroy_image = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
    auto bind_image = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    bool initialized = eglInitialize(display, nullptr, nullptr);
    const EGLint cfg[] = {EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                         EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE};
    EGLint count = 0;
    initialized = initialized && eglChooseConfig(display, cfg, &config, 1, &count) && count > 0;
    if (initialized) {
        const EGLint attr[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
        context = eglCreateContext(display, config, EGL_NO_CONTEXT, attr);
    }
    while (true) {
        AImage *image = nullptr;
        ANativeWindow *next = nullptr;
        bool replace = false;
        {
            std::unique_lock<std::mutex> lock(queue);
            changed.wait(lock, [] { return !running || window_changed || pending_image; });
            if (!running) break;
            replace = window_changed;
            if (replace) { next = pending_window; pending_window = nullptr; window_changed = false; }
            image = pending_image; pending_image = nullptr;
        }
        if (replace) {
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
            if (window) ANativeWindow_release(window);
            window = next;
            surface = initialized && context != EGL_NO_CONTEXT && window
                ? eglCreateWindowSurface(display, config, window, nullptr) : EGL_NO_SURFACE;
            if (surface != EGL_NO_SURFACE && eglMakeCurrent(display, surface, surface, context)) {
                eglSwapInterval(display, 0);
                if (!program) {
                    const char *vs = "attribute vec2 position; attribute vec2 uv; varying vec2 coord; void main(){coord=uv;gl_Position=vec4(position,0.,1.);}";
                    const char *fs = "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 coord; uniform samplerExternalOES frame; void main(){gl_FragColor=texture2D(frame,coord);}";
                    GLuint v = shader(GL_VERTEX_SHADER, vs), f = shader(GL_FRAGMENT_SHADER, fs);
                    if (v && f) {
                        program = glCreateProgram(); glAttachShader(program, v); glAttachShader(program, f); glLinkProgram(program);
                        GLint ok; glGetProgramiv(program, GL_LINK_STATUS, &ok);
                        if (!ok) { glDeleteProgram(program); program = 0; }
                    }
                    if (v) glDeleteShader(v);
                    if (f) glDeleteShader(f);
                    glGenTextures(1, &texture);
                    glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture);
                    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                }
            }
        }
        if (image && surface != EGL_NO_SURFACE && program && native_buffer && create_image && destroy_image && bind_image) {
            AHardwareBuffer *buffer = nullptr;
            if (AImage_getHardwareBuffer(image, &buffer) == AMEDIA_OK && buffer) {
                const EGLint attr[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
                EGLImageKHR imported = create_image(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, native_buffer(buffer), attr);
                if (imported != EGL_NO_IMAGE_KHR) {
                    EGLint width = 0, height = 0;
                    eglQuerySurface(display, surface, EGL_WIDTH, &width); eglQuerySurface(display, surface, EGL_HEIGHT, &height);
                    glViewport(0, 0, width, height);
                    glUseProgram(program); glActiveTexture(GL_TEXTURE0);
                    glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture); bind_image(GL_TEXTURE_EXTERNAL_OES, imported);
                    glUniform1i(glGetUniformLocation(program, "frame"), 0);
                    const GLfloat positions[] = {-1,1, -1,-1, 1,1, 1,-1};
                    const GLfloat coords[] = {0,0, 0,1, 1,0, 1,1};
                    GLint p = glGetAttribLocation(program, "position"), u = glGetAttribLocation(program, "uv");
                    glEnableVertexAttribArray(p); glEnableVertexAttribArray(u);
                    glVertexAttribPointer(p, 2, GL_FLOAT, GL_FALSE, 0, positions);
                    glVertexAttribPointer(u, 2, GL_FLOAT, GL_FALSE, 0, coords);
                    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
                    eglSwapBuffers(display, surface);
                    // Return the input image only after GPU sampling has finished.
                    glFinish();
                    destroy_image(display, imported);
                }
            }
        }
        if (image) AImage_delete(image);
    }
    if (surface != EGL_NO_SURFACE) {
        eglMakeCurrent(display, surface, surface, context);
        glClearColor(0,0,0,1); glClear(GL_COLOR_BUFFER_BIT); eglSwapBuffers(display, surface);
        if (texture) glDeleteTextures(1, &texture);
        if (program) glDeleteProgram(program);
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(display, surface);
    }
    if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
    if (initialized) eglTerminate(display);
    if (window) ANativeWindow_release(window);
}
}

void preview_stop() {
    std::lock_guard<std::mutex> serial(lifecycle);
    {
        std::lock_guard<std::mutex> lock(queue);
        running = false;
        if (pending_image) { AImage_delete(pending_image); pending_image = nullptr; }
        if (pending_window) { ANativeWindow_release(pending_window); pending_window = nullptr; }
        window_changed = false;
    }
    changed.notify_all();
    if (worker.joinable()) worker.join();
}

void preview_window(ANativeWindow *window) {
    if (!window) { preview_stop(); return; }
    std::lock_guard<std::mutex> serial(lifecycle);
    std::lock_guard<std::mutex> lock(queue);
    if (pending_window) ANativeWindow_release(pending_window);
    pending_window = window; window_changed = true;
    if (!running) { running = true; worker = std::thread(render_loop); }
    changed.notify_all();
}

bool preview_offer(AImage *image) {
    std::lock_guard<std::mutex> lock(queue);
    if (!running) return false;
    if (pending_image) AImage_delete(pending_image);
    pending_image = image;
    changed.notify_one();
    return true;
}
