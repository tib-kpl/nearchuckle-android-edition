// Android: where the renderer's OpenGL comes from.
//
// By default it is GL4ES (libGL.so, desktop OpenGL translated to OpenGL ES) in the EGL context that
// SDL creates. When the launcher asks for Zink (FC_GL_BACKEND=zink), it is Mesa's Zink (desktop
// OpenGL on Vulkan, so on the system's Vulkan driver or a Turnip one) through OSMesa: Mesa draws
// off screen and each frame is written into the window's buffer (ANativeWindow) when it is shown.
//
// The renderer gets its context and its functions through SDL_GL_*: those calls are sent here.
#pragma once

#if defined(__ANDROID__)
#include <SDL3/SDL.h>

// Whether Zink is used (decided once: asked by the launcher, and libOSMesa.so loaded).
bool FCGL_IsZink();

SDL_FunctionPointer FCGL_GetProcAddress(const char *name);
SDL_GLContext FCGL_CreateContext(SDL_Window *window);
bool FCGL_MakeCurrent(SDL_Window *window, SDL_GLContext context);
bool FCGL_DestroyContext(SDL_GLContext context);
bool FCGL_SwapWindow(SDL_Window *window);
bool FCGL_SetSwapInterval(int interval);

// A window for the chosen OpenGL: with Zink, none of SDL's EGL on it (the window's buffers are
// written by this code).
SDL_Window *FCGL_CreateWindow(const char *title, int width, int height, SDL_WindowFlags flags);

#ifndef FCGL_NO_REDIRECT
#define SDL_GL_GetProcAddress FCGL_GetProcAddress
#define SDL_GL_CreateContext FCGL_CreateContext
#define SDL_GL_MakeCurrent FCGL_MakeCurrent
#define SDL_GL_DestroyContext FCGL_DestroyContext
#define SDL_GL_SwapWindow FCGL_SwapWindow
#define SDL_GL_SetSwapInterval FCGL_SetSwapInterval
#endif
#endif
