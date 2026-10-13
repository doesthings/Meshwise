#include "egl_context.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>

#include <cstdio>

namespace fsvhost {

static EGLDisplay gDisplay = EGL_NO_DISPLAY;
static EGLContext gContext = EGL_NO_CONTEXT;
static EGLSurface gSurface = EGL_NO_SURFACE;

bool createHeadlessContext(int width, int height) {
    auto getPlatformDisplay = (PFNEGLGETPLATFORMDISPLAYEXTPROC)eglGetProcAddress("eglGetPlatformDisplayEXT");
    gDisplay = getPlatformDisplay ? getPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, nullptr)
                                  : eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLint major, minor;
    if (!eglInitialize(gDisplay, &major, &minor)) {
        std::fprintf(stderr, "eglInitialize failed\n");
        return false;
    }
    const EGLint cfgAttr[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                              EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
                              EGL_DEPTH_SIZE, 24, EGL_NONE};
    EGLConfig cfg;
    EGLint n = 0;
    eglChooseConfig(gDisplay, cfgAttr, &cfg, 1, &n);
    if (n < 1) {
        std::fprintf(stderr, "no EGL config\n");
        return false;
    }
    eglBindAPI(EGL_OPENGL_ES_API);
    const EGLint ctxAttr[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    gContext = eglCreateContext(gDisplay, cfg, EGL_NO_CONTEXT, ctxAttr);
    const EGLint pbAttr[] = {EGL_WIDTH, width, EGL_HEIGHT, height, EGL_NONE};
    gSurface = eglCreatePbufferSurface(gDisplay, cfg, pbAttr);
    if (!eglMakeCurrent(gDisplay, gSurface, gSurface, gContext)) {
        std::fprintf(stderr, "eglMakeCurrent failed: 0x%x\n", eglGetError());
        return false;
    }
    return true;
}

void destroyHeadlessContext() {
    if (gDisplay == EGL_NO_DISPLAY) return;
    eglMakeCurrent(gDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (gSurface != EGL_NO_SURFACE) eglDestroySurface(gDisplay, gSurface);
    if (gContext != EGL_NO_CONTEXT) eglDestroyContext(gDisplay, gContext);
    eglTerminate(gDisplay);
    gDisplay = EGL_NO_DISPLAY;
}

}  // namespace fsvhost
