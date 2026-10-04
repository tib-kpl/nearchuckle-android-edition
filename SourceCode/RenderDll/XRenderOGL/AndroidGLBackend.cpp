// Android: GL4ES (default) or Mesa's Zink through OSMesa, see AndroidGLBackend.h.
#if defined(__ANDROID__)
#define FCGL_NO_REDIRECT
#include "AndroidGLBackend.h"

#include <android/log.h>
#include <android/native_window.h>
#include <dlfcn.h>
#include <inttypes.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <string>

// OSMesa (GL/osmesa.h), what is used of it
typedef void *OSMesaContext;
#define FCGL_GL_RGBA 0x1908
#define FCGL_GL_UNSIGNED_BYTE 0x1401
#define FCGL_GL_VENDOR 0x1F00
#define FCGL_GL_RENDERER 0x1F01
#define FCGL_GL_VERSION 0x1F02
#define OSMESA_ROW_LENGTH 0x10
#define OSMESA_Y_UP 0x11
#define OSMESA_FORMAT 0x22
#define OSMESA_DEPTH_BITS 0x30
#define OSMESA_STENCIL_BITS 0x31
#define OSMESA_PROFILE 0x33
#define OSMESA_COMPAT_PROFILE 0x35

typedef OSMesaContext (*PFN_OSMesaCreateContextAttribs)(const int *attribs, OSMesaContext share);
typedef OSMesaContext (*PFN_OSMesaCreateContextExt)(unsigned format, int depth, int stencil, int accum, OSMesaContext share);
typedef unsigned char (*PFN_OSMesaMakeCurrent)(OSMesaContext ctx, void *buffer, unsigned type, int width, int height);
typedef void (*PFN_OSMesaDestroyContext)(OSMesaContext ctx);
typedef void (*PFN_OSMesaPixelStore)(int pname, int value);
typedef void *(*PFN_OSMesaGetProcAddress)(const char *name);
typedef void (*PFN_glFinish)(void);
typedef const unsigned char *(*PFN_glGetString)(unsigned name);

namespace
{
struct ZinkContext
{
	OSMesaContext ctx;
	int width;
	int height;
	unsigned char *offscreen;	// where Mesa writes when no window buffer is held
};

struct Zink
{
	bool decided = false;
	bool enabled = false;
	PFN_OSMesaCreateContextAttribs createContextAttribs = nullptr;
	PFN_OSMesaCreateContextExt createContextExt = nullptr;
	PFN_OSMesaMakeCurrent makeCurrent = nullptr;
	PFN_OSMesaDestroyContext destroyContext = nullptr;
	PFN_OSMesaPixelStore pixelStore = nullptr;
	PFN_OSMesaGetProcAddress getProcAddress = nullptr;
	PFN_glFinish finish = nullptr;
	ZinkContext *current = nullptr;
	ANativeWindow *window = nullptr;
	bool geometryFailed = false;
	int lockFailures = 0;
} s_zink;

// What the renderer choice does, in "renderer.txt" in the game folder (rewritten at each start) and
// in the system log.
void Log(const char *fmt, ...)
{
	static bool s_started = false;
	char line[1024];
	va_list args;
	va_start(args, fmt);
	vsnprintf(line, sizeof(line), fmt, args);
	va_end(args);
	__android_log_print(ANDROID_LOG_INFO, "FarCryGL", "%s", line);
	const char *dir = getenv("FARCRY_DATA_DIR");
	std::string path = std::string((dir && *dir) ? dir : ".") + "/renderer.txt";
	if (FILE *f = fopen(path.c_str(), s_started ? "a" : "w"))
	{
		fprintf(f, "%s\n", line);
		fclose(f);
	}
	s_started = true;
}

bool LoadOSMesa()
{
	void *lib = dlopen("libOSMesa.so", RTLD_NOW | RTLD_LOCAL);
	if (!lib)
	{
		Log("libOSMesa.so could not be loaded: %s", dlerror());
		return false;
	}
	s_zink.getProcAddress = (PFN_OSMesaGetProcAddress)dlsym(lib, "OSMesaGetProcAddress");
	s_zink.createContextAttribs = (PFN_OSMesaCreateContextAttribs)dlsym(lib, "OSMesaCreateContextAttribs");
	s_zink.createContextExt = (PFN_OSMesaCreateContextExt)dlsym(lib, "OSMesaCreateContextExt");
	s_zink.makeCurrent = (PFN_OSMesaMakeCurrent)dlsym(lib, "OSMesaMakeCurrent");
	s_zink.destroyContext = (PFN_OSMesaDestroyContext)dlsym(lib, "OSMesaDestroyContext");
	s_zink.pixelStore = (PFN_OSMesaPixelStore)dlsym(lib, "OSMesaPixelStore");
	if (!s_zink.getProcAddress || !s_zink.makeCurrent || !s_zink.pixelStore ||
		(!s_zink.createContextAttribs && !s_zink.createContextExt))
	{
		Log("libOSMesa.so lacks the OSMesa functions");
		return false;
	}
	s_zink.finish = (PFN_glFinish)s_zink.getProcAddress("glFinish");

	// The Vulkan driver Zink uses: the one the launcher loaded (a Turnip driver, through
	// adrenotools: driver_loader.cpp sets VULKAN_PTR), else the system's.
	const char *vulkan = getenv("VULKAN_PTR");
	if (vulkan && *vulkan)
	{
		Log("Vulkan: the driver chosen in the launcher (handle %s)", vulkan);
	}
	else
	{
		void *system = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
		if (!system)
		{
			Log("libvulkan.so could not be loaded: %s", dlerror());
			return false;
		}
		char value[32];
		snprintf(value, sizeof(value), "%" PRIxPTR, (uintptr_t)system);
		setenv("VULKAN_PTR", value, 1);
		Log("Vulkan: the system's driver (libvulkan.so)");
	}
	setenv("GALLIUM_DRIVER", "zink", 1);
	return true;
}

ANativeWindow *WindowOf(SDL_Window *window)
{
	if (!window)
		return nullptr;
	return (ANativeWindow *)SDL_GetPointerProperty(SDL_GetWindowProperties(window),
		SDL_PROP_WINDOW_ANDROID_WINDOW_POINTER, nullptr);
}

void BindOffscreen(ZinkContext *zc)
{
	s_zink.makeCurrent(zc->ctx, zc->offscreen, FCGL_GL_UNSIGNED_BYTE, zc->width, zc->height);
	s_zink.pixelStore(OSMESA_ROW_LENGTH, zc->width);
	s_zink.pixelStore(OSMESA_Y_UP, 0);
}
}

bool FCGL_IsZink()
{
	if (!s_zink.decided)
	{
		s_zink.decided = true;
		const char *backend = getenv("FC_GL_BACKEND");
		if (backend && !strcmp(backend, "zink"))
		{
			Log("renderer: Zink (Mesa, OpenGL on Vulkan) asked");
			s_zink.enabled = LoadOSMesa();
			if (!s_zink.enabled)
				Log("renderer: back to GL4ES");
		}
		else
		{
			Log("renderer: GL4ES (OpenGL on OpenGL ES)");
		}
	}
	return s_zink.enabled;
}

SDL_FunctionPointer FCGL_GetProcAddress(const char *name)
{
	if (!FCGL_IsZink())
		return SDL_GL_GetProcAddress(name);
	return (SDL_FunctionPointer)s_zink.getProcAddress(name);
}

SDL_Window *FCGL_CreateWindow(const char *title, int width, int height, SDL_WindowFlags flags)
{
	if (!FCGL_IsZink())
		return SDL_CreateWindow(title, width, height, flags);
	SDL_PropertiesID props = SDL_CreateProperties();
	SDL_SetStringProperty(props, SDL_PROP_WINDOW_CREATE_TITLE_STRING, title);
	SDL_SetNumberProperty(props, SDL_PROP_WINDOW_CREATE_WIDTH_NUMBER, width);
	SDL_SetNumberProperty(props, SDL_PROP_WINDOW_CREATE_HEIGHT_NUMBER, height);
	SDL_SetNumberProperty(props, SDL_PROP_WINDOW_CREATE_FLAGS_NUMBER, flags & ~SDL_WINDOW_OPENGL);
	// no EGL surface on the window: its buffers are locked and written by FCGL_SwapWindow
	SDL_SetBooleanProperty(props, SDL_PROP_WINDOW_CREATE_EXTERNAL_GRAPHICS_CONTEXT_BOOLEAN, true);
	SDL_Window *window = SDL_CreateWindowWithProperties(props);
	SDL_DestroyProperties(props);
	return window;
}

SDL_GLContext FCGL_CreateContext(SDL_Window *window)
{
	if (!FCGL_IsZink())
		return SDL_GL_CreateContext(window);

	int width = 0, height = 0;
	SDL_GetWindowSizeInPixels(window, &width, &height);
	if (width <= 0 || height <= 0)
	{
		width = 1280;
		height = 720;
	}
	const int attribs[] = {
		OSMESA_FORMAT, FCGL_GL_RGBA,
		OSMESA_DEPTH_BITS, 24,
		OSMESA_STENCIL_BITS, 8,
		OSMESA_PROFILE, OSMESA_COMPAT_PROFILE,
		0
	};
	OSMesaContext ctx = nullptr;
	if (s_zink.createContextAttribs)
		ctx = s_zink.createContextAttribs(attribs, nullptr);
	if (!ctx && s_zink.createContextExt)
		ctx = s_zink.createContextExt(FCGL_GL_RGBA, 24, 8, 0, nullptr);
	if (!ctx)
	{
		Log("OSMesa could not create a context (Zink and the Vulkan driver did not start)");
		SDL_SetError("OSMesa could not create a context");
		return nullptr;
	}
	ZinkContext *zc = new ZinkContext;
	zc->ctx = ctx;
	zc->width = width;
	zc->height = height;
	zc->offscreen = (unsigned char *)calloc((size_t)width * height, 4);
	Log("context %dx%d", width, height);
	return (SDL_GLContext)zc;
}

bool FCGL_MakeCurrent(SDL_Window *window, SDL_GLContext context)
{
	if (!FCGL_IsZink())
		return SDL_GL_MakeCurrent(window, context);

	ZinkContext *zc = (ZinkContext *)context;
	if (!zc)
	{
		s_zink.current = nullptr;
		return true;
	}
	BindOffscreen(zc);
	if (s_zink.current != zc)
	{
		s_zink.current = zc;
		PFN_glGetString getString = (PFN_glGetString)s_zink.getProcAddress("glGetString");
		if (getString)
		{
			const unsigned char *vendor = getString(FCGL_GL_VENDOR);
			const unsigned char *renderer = getString(FCGL_GL_RENDERER);
			const unsigned char *version = getString(FCGL_GL_VERSION);
			Log("OpenGL: %s / %s / %s", vendor ? (const char *)vendor : "?",
				renderer ? (const char *)renderer : "?", version ? (const char *)version : "?");
		}
	}
	return true;
}

bool FCGL_DestroyContext(SDL_GLContext context)
{
	if (!FCGL_IsZink())
		return SDL_GL_DestroyContext(context);

	ZinkContext *zc = (ZinkContext *)context;
	if (!zc)
		return true;
	if (s_zink.current == zc)
		s_zink.current = nullptr;
	if (s_zink.destroyContext)
		s_zink.destroyContext(zc->ctx);
	free(zc->offscreen);
	delete zc;
	return true;
}

bool FCGL_SwapWindow(SDL_Window *window)
{
	if (!FCGL_IsZink())
		return SDL_GL_SwapWindow(window);

	ZinkContext *zc = s_zink.current;
	if (!zc)
		return false;

	// the window (Android gives a new one after the app comes back to the front)
	ANativeWindow *native = WindowOf(window);
	if (native != s_zink.window)
	{
		if (s_zink.window)
			ANativeWindow_release(s_zink.window);
		s_zink.window = native;
		s_zink.geometryFailed = false;
		s_zink.lockFailures = 0;
		if (native)
		{
			ANativeWindow_acquire(native);
			if (ANativeWindow_setBuffersGeometry(native, zc->width, zc->height, WINDOW_FORMAT_RGBX_8888) != 0)
			{
				s_zink.geometryFailed = true;
				Log("the window's buffers could not be set to %dx%d", zc->width, zc->height);
			}
			else
			{
				Log("window %p: buffers %dx%d", native, zc->width, zc->height);
			}
		}
	}

	ANativeWindow_Buffer buffer;
	bool shown = false;
	if (s_zink.window && !s_zink.geometryFailed && ANativeWindow_lock(s_zink.window, &buffer, nullptr) == 0)
	{
		if (buffer.width == zc->width && buffer.height == zc->height && buffer.bits)
		{
			// glFinish writes the frame into the buffer Mesa is given
			s_zink.makeCurrent(zc->ctx, buffer.bits, FCGL_GL_UNSIGNED_BYTE, buffer.width, buffer.height);
			s_zink.pixelStore(OSMESA_ROW_LENGTH, buffer.stride);
			s_zink.pixelStore(OSMESA_Y_UP, 0);
			if (s_zink.finish)
				s_zink.finish();
			shown = true;
		}
		else if (s_zink.lockFailures++ < 5)
		{
			Log("window buffer %dx%d, not the %dx%d drawn", buffer.width, buffer.height, zc->width, zc->height);
		}
		ANativeWindow_unlockAndPost(s_zink.window);
	}
	else if (s_zink.window && s_zink.lockFailures++ < 5)
	{
		Log("the window could not be locked");
	}

	// the next frame is drawn with no window buffer held (it is given back above)
	BindOffscreen(zc);
	if (!shown && s_zink.finish)
		s_zink.finish();
	return true;
}

bool FCGL_SetSwapInterval(int interval)
{
	if (!FCGL_IsZink())
		return SDL_GL_SetSwapInterval(interval);
	// the window's buffer queue paces the frames
	return true;
}
#endif
