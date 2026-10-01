# NearChuckle Far Cry — Android Port

A complete, optimized and stable port of the cult game **Far Cry (CryEngine 1)** to **Android (ARM64-v8a)**, based on the NearChuckle project and SDL3.

---

## 🎮 Key features of the port

### 1. Modern Android Launcher (`LauncherActivity`)
- **Game folder selection**: automatic detection and a convenient folder browser dialog. Supports the original game archives (`FCData`, `Levels` and `.pak` files).
- **Graphics stack (gl4es / Mesa Zink)**: translates the classic desktop OpenGL 1.4-2.1 pipeline and assembly shaders (`GL_ARB_vertex_program`, `GL_ARB_fragment_program`) to native OpenGL ES 2.0/3.0 through the built-in `gl4es` translator, with Mesa Zink (OpenGL over Vulkan) support for the best performance on mobile GPUs (Adreno / Mali).
- **Custom Turnip driver support (AdrenoTools)**:
  - Install custom Turnip Vulkan drivers straight from `.zip` archives through the file manager.
  - Automatic parsing of `meta.json` and extraction of `libvulkan_freedreno.so`.
  - Vulkan ICD generation (`VK_ICD_FILENAMES`).
  - One-click switching between the installed drivers and the system driver.
  - GPU Turbo mode for Snapdragon processors.
- **Flexible graphics and resolution settings**:
  - Native screen resolution.
  - 1080p (Full HD).
  - 720p (HD — recommended for a balance of quality and speed).
  - 540p (qHD — for weaker devices).
- **Field of view (FOV)** adjustable from 70° to 120°.
- **Developer mode (`-DEVMODE`)** and a field for custom console arguments.
- **Camera sensitivity setting** for the touch controls.

---

### 2. Full on-screen touch controls (On-Screen Controls / OSC)
A touch interface designed specifically for Far Cry, with two sticks and buttons for all the main actions:
- **Left analog stick**: smooth character movement (WASD) with a circular dead zone.
- **Right look area (Touch Look)**: free camera and aim control, sending relative movements to SDL3.
- **Main action buttons**:
  - Fire / Shoot (LMB)
  - Aim / Zoom (RMB) — a "sticky" (toggle) button: one press locks the aim, as if RMB were held down, and a second press releases it. While the aim is locked, the button is highlighted in green, and you can lift your finger and calmly aim or look around. The lock is released automatically when the app goes to the background and when EDIT mode is entered.
  - Jump (`Space`)
  - Crouch (`C` / `Ctrl`)
  - Go prone / Crawl (`V`)
  - Walk / Sneak (`Z`, configurable)
  - Sprint / Hold breath (`Shift`)
  - Lean left / right (`Q` / `E`)
  - Reload (`R`)
  - Action / Use (`F`)
  - Fire mode (`X`)
  - Throw grenade (`G`)
  - Grenade type (`H`)
  - Switch weapon (Next `PageDown` / Previous `PageUp`, slots 1-4)
  - Drop weapon (`J`, configurable)
  - Flashlight (`L`)
  - Binoculars (`B`)
  - Night vision / CryVision (`T`)
  - Third-person view in vehicles (`F1`, configurable)
  - Objectives / PDA (`Tab`)
  - Console (`~`)
  - Pause / Menu (`Esc`)
- **Interactive edit mode (EDIT Mode)**:
  - Opened in-game with the **EDIT** button, or from the launcher with **"Configure controls"**.
  - Drag and drop any control anywhere on the screen.
  - Resize buttons (**Size + / -**).
  - Adjust button opacity (**Opacity + / -**).
  - Hide / show buttons you do not need (**Hide / Show**).
  - Reset to the default layout (**Reset**).
  - The personal layout is saved automatically in `SharedPreferences`.

---

### 3. Engine and architecture
- **64-bit ARM64-v8a architecture**:
  - All structures and pointers use proper 64-bit types (`intptr_t`, `INT_PTR`, `UINT_PTR`); pointer truncations were removed from `CHandle`, `GL_VertBuffer`, `EvalFuncs_RE`, `RendElement`, `CREOcean`, `ShaderCore`.
  - Correct ARM64 architecture detection in `CPUDetect.h`.
  - Uses the POSIX `<dirent.h>` standard for the Android Bionic file system.
  - Correct SDL3 type mapping (`SDL_SharedObject`, `SDL_GL_GetProcAddress`).
- **Modular library loading**:
  - `libFarCry.so` — the main executable module, the entry point.
  - `libCrySystem.so` — engine subsystem, memory manager, CryPak.
  - `libCry3DEngine.so`, `libCryGame.so`, `libCryInput.so`, `libCrySoundSystem.so`, `libCryPhysics.so`, `libCryAISystem.so`, `libCryAnimation.so`, `libCryEntitySystem.so`, `libCryMovie.so`, `libCryScriptSystem.so`.
  - `libXRenderOGL.so` — OpenGL renderer with Mesa Zink support.
  - `libSDL3.so`, `libopenal.so`, `libogg.so`, `libvorbis.so`, `libvorbisfile.so` — engine dependencies.
  - `libdriverloader.so` — native loader for Turnip and the AdrenoTools hooks.

### 4. Error log and crash reports (diagnostics)
- **Automatic crash capture:**
  - When a Java exception (`UncaughtExceptionHandler`), a native signal (`SIGSEGV`, `SIGABRT`, `SIGBUS`, `SIGFPE`, `SIGILL`) or a critical CryEngine error (`CSystem::Error`) occurs, a structured diagnostic report is created.
- **Report contents:**
  - Device model, board, Android version, API level, list of supported ABIs.
  - Launcher configuration (game path, whether Zink is active, the selected Turnip driver, command-line arguments).
  - Call stack (stack trace / backtrace).
  - The tail of the system log (`logcat -d -v time`).
  - The contents of the engine's game log `log.txt`.
- **Quick copy:**
  - The launcher has a **"Logs and crash reports"** button.
  - The report screen has a **"Copy the whole report"** button — the report is copied to the clipboard in one tap, to send it to the developer quickly.
  - The **"Share"** button sends the report through any messenger or e-mail app.
- **Isolated process:**
  - The `CrashReportActivity` screen runs in a dedicated `:crash` process, so the report is shown even when the main game process crashes.

### 5. Launcher update notifications (Self-Update)
- **Automatic check:**
  - At start-up, the launcher reads the `update.json` update manifest and compares its `versionCode` with the installed build.
  - If a newer version is available, an **"Update available"** dialog is shown with two buttons: **"Download"** and **"Cancel"**.
  - "Download" downloads the APK with a progress bar and opens the system installer right away; "Cancel" postpones the installation until the next launcher start.
- **Manual check:**
  - The **"Check for updates"** button in the launcher forces a check (ignoring the cache) and says when there is no update or no network.
- **Cache and offline use:**
  - The server's answer is cached for 6 hours (`UpdateManager.CACHE_TTL_MS`), so frequent game starts do not send needless requests.
  - Without internet access the dialog is simply not shown — the game start is never blocked.
- **`update.json` manifest format:**

  | Field | Type | Description |
  | --- | --- | --- |
  | `versionCode` | int | **Required.** The build number; must match the `versionCode` in `android/app/build.gradle` of the published APK. |
  | `versionName` | string | The version shown to the user (`1.0.2`). |
  | `apkUrl` | string | Link to the APK. If missing, the first `.apk` of the latest GitHub release is used. |
  | `size` | long | APK size in bytes (a fallback for the progress bar when the server sends no `Content-Length`). |
  | `notes` | string | What's new — shown in the update dialog. |
  | `mandatory` | bool | `true` — the dialog cannot be closed with the "Back" button (the "Cancel" button stays). |

- **Where the manifest is read from (checked in order):**
  1. `https://rohitcodes.fyi/nearchuckle/update.json` — the project's file host (the same one that serves the shader cache).
  2. `https://raw.githubusercontent.com/Player124413/NearChuckle-android-edition/Android/android/update.json` — the copy in the repository (`android/update.json`), which works without third-party hosting.
- **How to release an update:**
  1. Raise `versionCode` / `versionName` in `android/app/build.gradle`.
  2. Build the APK and attach `app-release.apk` to a GitHub release.
  3. In `android/update.json` (and/or in the copy on the file host), set the new `versionCode`, `versionName` and `notes`.
- **Installing the downloaded APK:**
  - The APK is saved in the app's private folder and handed to the installer through `androidx.core.content.FileProvider` (`res/xml/file_paths.xml`).
  - On Android 8+, the launcher first asks to allow "Install unknown apps", and starts the installer automatically after returning from the settings.

---

### 6. Keyboard, mouse and gamepad support
- **Keyboard:** works out of the box through SDL — hardware keys reach the engine
  (`SDLSurface.onKey` → `SDLActivity.handleKeyEvent` → `CSDLKeyboard` reads the SDL events).
- **Mouse:** works through SDL; the engine enables relative mode
  (`SDL_SetWindowRelativeMouseMode`), so on Android 8+ SDL captures the pointer
  (pointer capture) and a real mouse controls the view, as on a PC.
- **Gamepad:** CryEngine 1 has no gamepad backend on Android, so a `GamepadMapper`
  was added (pure Java, the engine and SDL are not modified). It intercepts gamepad
  events at the `GameActivity.dispatchKeyEvent` / `dispatchGenericMotionEvent` level and
  turns them into the same key/mouse injections the on-screen buttons use
  (`onNativeKeyDown/Up`, `onNativeMouse`). It leaves keyboard and mouse events alone.
  Layout:

  | Gamepad control | Action |
  | --- | --- |
  | Left stick / D-pad | Movement (W A S D) |
  | Right stick | Look (mouse, uses the launcher's sensitivity) |
  | RT / R2 / R1 | Fire (LMB) |
  | LT / L2 | Aim (RMB) |
  | A | Jump (Space) |
  | B | Crouch (C) |
  | X | Reload (R) |
  | Y | Action (F) |
  | L1 | Sprint (Shift) |
  | Left stick click | Go prone (V) |
  | Right stick click | Grenade (G) |
  | Start / Mode | Menu (Esc) |
  | Select / Back | Objectives / PDA (Tab) |

- On pause, focus loss or gamepad disconnection, `releaseAll()` releases every injected
  key/button — nothing gets stuck.
- For playing with a gamepad or keyboard, the launcher has a "Hide touch buttons" switch.

---

## 🛠️ Building the project

### Requirements
- Ubuntu 22.04+ or another modern x86_64 Linux
- Android NDK r25c (`25.2.9519653`)
- JDK 17
- CMake 3.22+
- Ninja / Make

### Quick build with the script
```bash
# 1. Build the native engine and all dependencies for ARM64-v8a
./buildscripts/build_android.sh --arch arm64 --release --ndk /path/to/android-ndk-r25c

# 2. Build the APK with Gradle
cd android
./gradlew :app:assembleRelease
```
The APK will be at:
`android/app/build/outputs/apk/release/app-release.apk`

---

## 🚀 Automatic build with GitHub Actions
The repository is set up to compile automatically on every push to the branch with GitHub Actions (`.github/workflows/build_android.yml`):
- Full compilation of the NearChuckle C++ engine and its dependencies (SDL3, OpenAL, Vorbis, Ogg).
- Build of `driverloader` with `adrenotools`.
- Generation of a release `app-release.apk` with all the `.so` libraries packed in `lib/arm64-v8a`.
- Publication of the finished artifacts for download:
  - `NearChuckle-FarCry-Android-APK`
  - `NearChuckle-FarCry-Native-SO-arm64-v8a`
