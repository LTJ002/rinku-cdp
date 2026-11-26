# Rinku-CDP 3.1.0

A Chrome-based browser backend for WebDisplays — replaces CEF.

[简体中文](RELEASE-NOTES.zh-CN.md) · English

---

## Why replace the engine

WebDisplays ships with **CEF**, whose Chromium build has no H.264 decoder. Sites that require it refuse to play, and the AV1 workaround decodes on the CPU — unusable for 1080p60 on laptop hardware. The symptom is **video stuttering while audio plays fine**.

This backend drives the Chrome you already have installed. Chrome ships hardware decoding for H.264, HEVC and VP9, so playback is smooth.

---

## Download

A working screen needs **two jars**. Both are required.

| File | Purpose |
|---|---|
| `webdisplays-1.2.25-26.1.2.jar` | Screen blocks, laser pointer, rendering, GUI |
| `rinku-3.1.0-cdp.jar` | Browser backend (this repository) |

You do **not** need the original Rinku, and you do **not** need CEF.

---

## Install

1. Put both jars in `mods`
2. Launch the game

**Requirements**

- Minecraft **26.1.2**
- NeoForge **26.1.2.125** or newer
- Google **Chrome** installed, 120+ recommended

### Verify

Search `logs/latest.log` for `reflection contract`. It must say:

```
=== 30 passed, 0 failed ===
```

All 30 must pass. If you see `0 passed, 30 failed`, the two jars are mismatched.

---

## Usage

1. Get items with `/screen screen 64`, `/screen configurator`, `/screen linker`, `/screen keyboard`
2. Build a **filled rectangle** wall of screen blocks
3. Right-click the wall with the Configurator → **Create Screen**
4. Click **Change URL** and enter an address

Full guide: [README](https://github.com/LTJ002/rinku-cdp#readme)

---

## Fixed in this version

- **Multi-screen black screens** — each screen now gets its own Chrome window. Chrome composites zero frames for background tabs, which is why only one wall used to show a picture while the others stayed black with audio playing.
- **Screen creation failing** — no longer compares block facing. The measurement used to reject any wall whose blocks were placed while walking, so "Create Screen" silently did nothing. Failures now name the offending coordinate.
- **Hardware decoding** — follows Chrome's codec choice instead of forcing AV1.
- **IME input** — Chinese, Japanese and Korean composition supported.

---

## Checksums

```
rinku-3.1.0-cdp.jar
  size:   150,651 bytes
  sha256: 0E7473B80F29BA524A0ACB92853B3C7AFB64F51D8686C0F046033FEAB80C6CFB

webdisplays-1.2.25-26.1.2.jar
  size:   363,258 bytes
  sha256: 73382137B8E2C755CD50C0745DA5D7C7272457962BE1AD649C9D906505AAE569
```

---

## Credits

- **montoyo** — author of WebDisplays. The project is in the public domain.
- **Keksuccino** — author of the original Rinku (CEF backend).

## License

MIT for the source code in this repository. The bundled WebDisplays jar is public domain and is redistributed under those terms — see [LICENSE](https://github.com/LTJ002/rinku-cdp/blob/main/LICENSE#scope-of-this-license).
