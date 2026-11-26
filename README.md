# Rinku-CDP

**A Chrome-based browser backend for WebDisplays — replaces CEF.**

> **Read this in:** **English** · [简体中文](README.zh-CN.md)

---

## What this is

WebDisplays is a Minecraft mod that lets you build walls out of screen blocks and display real web pages on them — including video sites.

It ships with **CEF** (Chromium Embedded Framework). That engine has one fatal gap: **no H.264 decoder**.

The consequences:

| Symptom | Cause |
|---|---|
| Video sites report "browser does not support playback" | Site detects no H.264 support and refuses to play |
| Video stutters while audio plays fine | Forced onto AV1, which decodes on the CPU — 1080p60 is too slow |
| Longer videos get worse | Software decoding falls further behind as the buffer grows |

**Rinku-CDP takes a different approach**: it drives the Chrome you already have installed. Chrome ships hardware decoding for H.264, HEVC and VP9, so playback is smooth.

---

## What you need to download

A working screen needs **two jars**. Both are required.

| File | Purpose | Where |
|---|---|---|
| `webdisplays-1.2.25-26.1.2.jar` | Screen blocks, laser pointer, rendering, GUI | [Releases](../../releases) |
| `rinku-3.1.0-cdp.jar` | Browser backend (this repository) | [Releases](../../releases) |

> **Why two?** WebDisplays talks to its browser library through reflection, so the browser engine is a separate mod. The original uses Rinku (CEF). This repository provides its own CDP backend that takes over the same mod id (`rinku`), so WebDisplays needs no changes.

You do **not** need the original Rinku, and you do **not** need CEF.

---

## Installation

### Requirements

| Item | Version | How to check |
|---|---|---|
| Minecraft | **26.1.2** | Launcher version list |
| NeoForge | **26.1.2.125** or newer | Launcher version list |
| Google Chrome | Installed, 120+ recommended | Chrome → About |
| Allocated memory | 6 GB or more recommended | Launcher JVM arguments |

### Three steps

**1. Download both jars** from the [Releases](../../releases) page.

**2. Put them in your `mods` folder.**

Open your game directory (most launchers have an "Open game folder" button) and drop both jars into `mods`.

```
<game dir>/mods/webdisplays-1.2.25-26.1.2.jar
<game dir>/mods/rinku-3.1.0-cdp.jar
```

**3. Launch the game.**

Before building any screens, confirm the install worked (next section).

### Confirming the install

Open `logs/latest.log` and search for `reflection contract`. You should see:

```
=== WebDisplays reflection contract (stage 0A) ===
  PASS  de.keksuccino.rinku.Rinku is public
  PASS  de.keksuccino.rinku.Rinku#createBrowser (String, boolean, int, int) -> Object
  ...
=== 30 passed, 0 failed ===
```

**All 30 must pass.** If you see `0 passed, 30 failed`, the two jars are mismatched — make sure both come from this repository's Releases page.

---

## Usage

### 1. Get the items

In-game, with cheats enabled or as an operator:

```
/screen screen 64
/screen configurator
/screen linker
/screen keyboard
```

That gives you screen blocks, the configurator, the linker and the keyboard.

> The **laser pointer** (used to click inside pages) is not in that command list — take it from the creative inventory, named **Laser Pointer**.

### 2. Build a wall

Place screen blocks in a **filled rectangle**.

Rules:

- Must be a complete rectangle — no holes
- Blocks may face different directions; that does not matter
- Minimum 1×1; 16×9 or smaller is recommended

```
Correct (3×2):
■■■
■■■

Incorrect (one block missing):
■■■
■□■    ← hole, cannot create a screen
```

### 3. Create the screen

1. Hold the **Configurator**
2. **Right-click** any block of the wall
3. The config screen shows the measured size (e.g. `3 × 2`)
4. Click **Create Screen**

If the button stays on `Measuring wall…`, the wall is not a full rectangle — the screen will tell you which block is wrong.

### 4. Set the URL

After creation the config screen shows, at the top:

```
Owner: <player name>
Manual budget: 1920 × 1080        ← or "Auto budget"
Current capture: 1920 × 1080      ← what Chrome is actually producing
```

and these controls:

- **Change URL** — the page address
- **Resolution** — two input boxes (**ResX** / **ResY**) plus **Apply Resolution** and **Auto**
- **Rotation** — four buttons, `0°` / `90°` / `180°` / `270°`

Click **Change URL** and enter an address:

```
https://www.youtube.com
```

The page loads within a few seconds.

> `Current capture` is the most useful line when something looks wrong: if it shows a size much smaller than the budget, Chrome is downscaling the capture.

> First load is slower (Chrome cold start), roughly 2–5 seconds.

### 5. Adjust resolution (optional)

Type into the **ResX / ResY** boxes, then click **Apply Resolution**. Or click **Auto** to derive it from the wall size (320 px per block by default).

The same can be done with a command:

```
/screen res 1920 1080
```

> The value you type is the **page rendering budget**, capped at 7680. The frame actually captured for the wall is capped separately: 1920×1080 in manual mode, 1280×720 in auto mode.

### 6. Interact with the page

Hold the **Laser Pointer** and aim at the screen:

| Input | Effect |
|---|---|
| Left click | Click page elements |
| Right click | Page context menu |
| Middle click | Middle-click on the page |
| Scroll wheel | Scroll the page |
| Move crosshair | Move the page cursor |

> **The laser pointer only works while you are not sneaking.** Sneaking is the mod's "let me interact with the block normally" gesture, so holding Shift disables page clicks.

### 7. Type into the page (optional)

To type (searching, logging in) you need a keyboard block:

1. Hold the **Linker**, **right-click the screen** (selects it — you get an overlay message)
2. **Right-click the Keyboard block** with the Linker still in hand (pairs them)
3. **Switch away from the Linker** — while it is held, right-clicking the keyboard only pairs, it does not open the panel
4. **Right-click the keyboard** to open the input panel
5. Type; IME input (Chinese, Japanese, Korean) is supported
6. Press **Esc** to close

> If right-clicking the keyboard does nothing, check the overlay message: `not linked` means step 1–2 did not complete.

---

## Uninstalling

1. Remove `rinku-3.1.0-cdp.jar` from `mods`
2. Delete the `config/rinku/` folder if you do not want to keep page logins
3. Screens already placed become ordinary blocks; the save is not damaged

To go back to CEF, remove this rinku jar and install the original Rinku — WebDisplays picks it up automatically.

---

## Multiple screens

Repeat the steps above. Each screen runs independently.

| Screens | Memory | Notes |
|---|---|---|
| 1 | ~300 MB | Fine anywhere |
| 2–4 | ~600–1200 MB | OK with 8 GB allocated |
| 5+ | 1.5 GB+ | Needs more memory |

> Each screen spawns its own Chrome renderer process.

---

## Commands

| Command | Effect |
|---|---|
| `/screen` | Show help |
| `/screen screen [count]` | Get screen blocks |
| `/screen configurator` | Get the configurator |
| `/screen linker` | Get the linker |
| `/screen keyboard` | Get a keyboard |
| `/screen url "<url>" [side]` | Set the URL of the screen you are looking at |
| `/screen res <w> <h> [side]` | Set the resolution |
| `/screen list` | List nearby screens |
| `/screen info` | Describe the screen under the crosshair |
| `/screen at <x> <y> <z> <side> url "<url>"` | Act on a screen by coordinates |

`side` is one of `north`, `south`, `east`, `west`, `top`, `bottom`.

---

## Troubleshooting

### The screen is black, but the log looks fine

**Cause**: Chrome not found, or the page failed to load.

**Fix**:
1. Confirm Chrome is installed in its default location
2. In `logs/latest.log`, search for `Chrome` or `CDP backend ready`
3. If it says `Chrome not found`, point at it explicitly with a JVM argument:
   ```
   -Dwdcdp.chrome="C:\Program Files\Google\Chrome\Application\chrome.exe"
   ```

### Video stutters

Check, in order:

1. Resolution too high — try 1280×720
2. Too many screens at once
3. Chrome is not using hardware decoding — open `chrome://gpu` and check

### Only one screen shows a picture

This was fixed in 3.1.0 — make sure you are on the version from Releases.

**Cause**: Chrome only composites the *active tab of a window*. Background tabs report `document.visibilityState === "hidden"` and produce **zero** screencast frames, while audio keeps playing. The fix is one Chrome window per screen.

### No audio

Check:

1. In-game volume
2. Distance — audio is positional, it fades with range
3. Search the log for `Page audio connected`

### Chinese text in a page shows as boxes

Page content is rendered by Chrome using system fonts, so this is unusual. If it happens:

1. Add Chinese to Chrome's language settings
2. Or use a site that ships its own web fonts

> This is unrelated to the game's own UI font, which belongs to other mods and does not affect page content.

### A site says "browser does not support playback"

If you are on the original Rinku (CEF), that is the missing H.264 decoder. Switch to this CDP backend.

### Clearing login state

Delete `config/rinku/chrome-profile` in your game directory. It is recreated on the next launch.

---

## Technical details

<details>
<summary>How it works</summary>

### Architecture

```
Minecraft
 └─ WebDisplays (screen blocks, rendering)
     └─ reflection: 14 methods
         └─ Rinku API compatibility layer (this repo)
             └─ CdpBrowser → CdpPage → PageTexture
                 └─ WebSocket (CDP)
                     └─ Chrome process (headless)
                         └─ page tabs
```

### Frame pipeline

```
Chrome composite → Page.startScreencast → JPEG frame
    → WebSocket → STB decode → NativeImage
    → glTexSubImage2D → OpenGL texture → drawn on the wall
```

GPU work happens only on the render thread.

### Audio pipeline

Chrome runs headless and has no audio output device. A script injected into the page taps its Web Audio graph:

```js
createMediaElementSource(video) → AudioWorklet → Runtime.addBinding
```

Samples arrive on the Java side as float arrays and are mixed into the game's audio.

### Why each screen gets its own window

Chrome only composites the active tab of a window. A background tab reports `document.visibilityState === "hidden"`, produces **zero** screencast frames, and still plays audio — which reads as "black screen with sound".

Measured with three tabs playing the same video:

| Mode | Frames per tab |
|---|---|
| Same window | 112, **0**, 96 |
| One window each | 97, 95, 92 |

So the backend uses `Target.createTarget({newWindow: true})`.

### Code layout

```
src/main/java/
├── com/wdcdp/cdpscreen/
│   ├── CdpBackend.java       Chrome process, page table, lifecycle
│   ├── CdpBrowser.java       The object WebDisplays sees (14 reflective methods)
│   ├── CdpHooks.java         NeoForge event wiring
│   ├── MixerBridge.java      Reflection bridge to the audio mixer
│   ├── audio/PageAudio.java  In-page audio capture
│   ├── cdp/
│   │   ├── CdpClient.java    WebSocket multiplexing
│   │   ├── CdpPage.java      A page: screencast, input, audio
│   │   ├── ChromeProcess.java Launch flags, process teardown
│   │   ├── ChromeFinder.java Locating Chrome
│   │   └── PageMedia.java    Media pause, video cadence
│   ├── render/
│   │   ├── PageTexture.java  OpenGL texture management
│   │   └── FrameDecoder.java JPEG decoding
│   └── validation/           Self-check tools
└── de/keksuccino/rinku/      Compatibility layer (keeps Rinku's signatures)
```

### Independence

This repository compiles on its own — it does not need WebDisplays' source or jar. Every call into WebDisplays goes through reflection:

| Call site | Mechanism |
|---|---|
| `ClientInit.pageAudio()` | `Class.forName` + `getMethod` |
| `ScreenBlockEntity.forgetClientScreens()` | Same |
| `BrowserAudio`'s 5 methods | All via `MixerBridge` |

That means it builds standalone and tolerates several WebDisplays versions — a missing method degrades to silence instead of crashing.

### Building from source

Requires JDK 25 and a NeoForge 26.1.2 classpath.

```bat
:: 1. copy cp.example.txt to cp.txt and replace <MINECRAFT_LIBRARIES>
:: 2. compile
build.bat
:: 3. package
package.bat
```

### Self-checks

```bat
:: reflection contract
java -cp "build/classes;<classpath>" com.wdcdp.cdpscreen.validation.ReflectionValidator

:: lifecycle (autoplay, popups, multi-screen, world exit)
java -cp "..." com.wdcdp.cdpscreen.validation.LifecycleProbe

:: static audit (thread safety, resource leaks)
java -cp "..." com.wdcdp.cdpscreen.validation.StaticAudit
```

</details>

---

## Known limitations

| Limitation | Detail |
|---|---|
| Chrome required | No bundled browser; the system one is used |
| Slow first start | Chrome cold start, about 2–5 seconds |
| Memory | 150–300 MB per screen |
| DRM video | Netflix and other Widevine sites will not play |
| 26.1.2 only | Other Minecraft versions need a separate port |

---

## Credits

- **montoyo** — author of WebDisplays. The project is in the **public domain**; the author only asks for credit.
- **Keksuccino** — author of the original Rinku (CEF backend). The `de.keksuccino.rinku` package name here exists so WebDisplays' reflective lookup succeeds; the code is an independent implementation.

---

## License

[MIT](LICENSE)
