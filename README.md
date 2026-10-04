# Near Chuckle - Android Edition

> [!NOTE]
> **Fork pour la traduction française** de [Player124413/NearChuckle-android-edition](https://github.com/Player124413/NearChuckle-android-edition).
> - Choix de la langue du jeu dans le lanceur (« Langue du jeu ») : automatique (langue de l'appareil) ou l'une des langues présentes dans `FCData/Localized` de vos fichiers (`French.pak`, `German.pak`…).
> - Le moteur ouvre le paquet de langue choisi au lieu de toujours `English.pak`.
> - Lanceur traduit en français.
>
> **Fork for the French translation** of [Player124413/NearChuckle-android-edition](https://github.com/Player124413/NearChuckle-android-edition).
> The launcher has a "Game language" choice (automatic, or one of the languages in your game's `FCData/Localized`), the engine opens that
> language pak instead of always `English.pak`, and the launcher is translated into French.

![Screenshot of Far Cry on Linux](assets/fort.jpg)

Far Cry (CryEngine 1) ported to Android and Linux via SDL3 with support for Mesa Zink (OpenGL over Vulkan) and custom Turnip GPU drivers.

Based on NearChuckle, with Android architecture inspired by [SCARaw/Android-OpenMW](https://github.com/SCARaw/Android-OpenMW) and [xyzz/openMW-android](https://github.com/xyzz/openMW-android).

---

## Android Port Features

### 🚀 Full-featured Launcher
- **Game folder selection:** Easy selection of the folder holding your Far Cry installation (containing `FCData`, `Levels`, etc.) with a built-in file browser and a check of the game files.
- **Resolutions:** Automatic (fits the device screen), FHD (1080p), HD (720p - best for FPS), qHD (540p).
- **FOV setting:** Adjust the field of view right from the menu (90° by default).
- **Developer mode:** Quickly enable `-DEVMODE`.
- **Custom arguments:** Enter any CryEngine console variables and command-line parameters.

### ⚡ Mesa Zink (OpenGL over Vulkan)
- Full desktop OpenGL 2.1 / 3.x implementation on top of the modern **Vulkan** graphics API.
- Removes the limits of mobile OpenGL ES, so all of Far Cry's shaders work correctly.
- Zink optimizations for mobile GPUs (`ZINK_DESCRIPTORS=lazy`, GL/GLSL profile overrides).

### 🎮 Turnip driver support (ZIP archives)
- **One-click install:** Just select or drop a `.zip` archive with a Turnip driver (from Kimchi, Banners-Turnip, WinNative, StevenMXZ, Vauzi or any AdrenoTools-compatible build).
- The launcher automatically extracts the archive, reads `meta.json` (or finds `libvulkan_freedreno.so`), generates the ICD manifest and registers the driver.
- **Rootless redirection:** Uses the `libadrenotools` library to replace the system Vulkan driver with a custom Turnip one, without root.
- **GPU Turbo mode:** Keeps the Adreno GPU at its maximum clocks through the `/dev/kgsl-3d0` ioctl.
- Quick switching between the installed Turnip versions and the device's system driver.

### 🕹️ Polished touch controls with an EDIT button
- **Analog joystick:** Precise, smooth control of walking and running (W, A, S, D).
- **Camera look area (Touch Look):** Smooth camera rotation and aiming with adjustable sensitivity.
- **Full set of Far Cry action buttons:**
  - Fire / Shoot (LMB)
  - Aim / Scope zoom (RMB)
  - Jump (Space)
  - Crouch (C)
  - Go prone / Crawl (Z)
  - Reload (R)
  - Interact / Use (F) — get into jeeps, boats and hang gliders, open doors, pick up weapons
  - Flashlight (L)
  - Binoculars (B)
  - Night vision / CryVision thermal vision (T)
  - Grenade (G)
  - Switch weapon (next / previous)
  - Pause / Menu (Esc)
  - Quick save (F5) / Quick load (F9)
  - Console (~)
- **Interactive edit mode (EDIT button):**
  - The **EDIT** button is available in-game, or in a separate configurator from the launcher.
  - **Move:** Touch and drag any button or the joystick anywhere on the screen.
  - **Resize:** **[Size +]** and **[Size -]** buttons in the toolbar.
  - **Opacity:** **[Opacity +]** and **[Opacity -]** buttons to fine-tune visibility.
  - **Visibility:** The **[Hide / Show]** button hides buttons you do not need (in edit mode, hidden buttons are outlined with a red dashed line so you can bring them back).
  - **Reset:** The **[Reset]** button instantly restores the default layout.
  - **Saving:** Positions, sizes and visibility are saved in `SharedPreferences` and applied immediately.

---

## How to Run on Android

1. Install the compiled APK (`app-release.apk` or `app-debug.apk`) on your Android device.
2. Copy the files of your installed Far Cry game from the PC to the phone (for example into `/sdcard/FarCry/`):
   - The `FCData` folder (with all the `.pak` files)
   - The `Levels` folder (with all the game levels)
   - The `Profiles` folder
3. Start the **Far Cry** launcher:
   - Tap **"Select folder"** and choose the `/sdcard/FarCry` folder. The launcher checks the files and shows a green mark `✓ Far Cry files found`.
   - In the driver section, select a driver, or tap **"Install Turnip driver from ZIP"** if you have a Turnip archive.
   - Optionally, set up the button layout with **"On-screen controls setup (EDIT)"**.
4. Tap **"LAUNCH FAR CRY"** and play!

---

## Building from Source

### Requirements
- Android NDK r25+ (or r26)
- Android SDK (API 34)
- CMake 3.14+
- Java 17+

### Building the native libraries
Run the build script:
```bash
./buildscripts/build_android.sh --arch arm64 --release --ndk /path/to/android-ndk
```
The script builds `libFarCry.so`, the engine libraries `libCry*.so` and the renderer `libXRenderOGL.so`, and puts them into `android/app/src/main/jniLibs/arm64-v8a/`.

### Building the APK
Go to the `android` folder and build the APK with Gradle:
```bash
cd android
./gradlew assembleRelease
```
The APK will be in:
`android/app/build/outputs/apk/release/app-release-unsigned.apk` (or debug).

---

## Project Structure

```
NearChuckle-android-wip-edition/
├── android/                        # Android app
│   ├── app/
│   │   ├── src/main/AndroidManifest.xml
│   │   ├── src/main/java/
│   │   │   ├── org/libsdl/app/     # SDL3 Android runtime
│   │   │   └── com/nearchuckle/farcry/
│   │   │       ├── LauncherActivity.java      # Main launcher
│   │   │       ├── GameActivity.java          # Engine start + Zink + Turnip
│   │   │       ├── ConfigureControlsActivity.java # Button editor
│   │   │       ├── controls/                  # Touch controls + edit mode
│   │   │       └── driver/                    # Turnip ZIP archive manager
│   │   └── src/main/cpp/
│   │       ├── driver_loader.cpp              # JNI hook for custom drivers
│   │       └── adrenotools/                   # Rootless Vulkan driver replacement
│   └── build.gradle
├── buildscripts/
│   └── build_android.sh            # NDK library build script
├── Externals/SDL/include/          # SDL3 headers
└── SourceCode/                     # Far Cry engine source code (CryEngine 1)
```

## License / Credits
- Crytek Far Cry (CryEngine 1)
- [NearChuckle](https://github.com/Player124413/NearChuckle-android-wip-edition)
- [SCARaw/Android-OpenMW](https://github.com/SCARaw/Android-OpenMW) & [xyzz/openMW-android](https://github.com/xyzz/openMW-android) for the launcher and touch controls architecture references
- [bylaws/libadrenotools](https://github.com/bylaws/libadrenotools) for the library that loads custom Adreno Turnip drivers
- Mesa 3D Graphics Library (Zink & Turnip Freedreno)
