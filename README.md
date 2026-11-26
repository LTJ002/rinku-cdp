# Rinku-CDP

**A Chrome-based browser backend for WebDisplays — replaces CEF.**

**给 WebDisplays 换一个浏览器内核：用 Chrome 替代 CEF。**

---

## Choose your language · 选择语言

| | |
|---|---|
| 🇬🇧 **[English](README.en.md)** | Full documentation in English |
| 🇨🇳 **[简体中文](README.zh-CN.md)** | 完整中文文档 |

---

## Quick summary

WebDisplays' bundled **CEF** has no H.264 decoder. Sites that require it refuse to play, and the AV1 workaround decodes on the CPU — unusable for 1080p60 on laptop hardware.

This backend drives **Chrome** instead, which has hardware decoding for H.264, HEVC and VP9.

**WebDisplays 自带的 CEF 没有 H.264 解码器**，B 站等站点会拒绝播放；被迫改用 AV1 时只能 CPU 软解，1080P60 跑不动。

本项目改用 **Chrome** 驱动，它有完整的硬件解码。

---

## Download

Two jars are required — **[see Releases](../../releases)**:

| File | Purpose |
|---|---|
| `webdisplays-1.2.25-26.1.2.jar` | Screen blocks, laser pointer, rendering, GUI |
| `rinku-3.1.0-cdp.jar` | Browser backend (this repository) |

## Requirements

- Minecraft **26.1.2**
- NeoForge **26.1.2.125** or newer
- Google **Chrome** installed (120+ recommended)

## Install

1. Put both jars in `mods`
2. Launch the game
3. Search `logs/latest.log` for `reflection contract` — it must say `30 passed, 0 failed`

---

## License

[MIT](LICENSE)

## Credits

- **montoyo** — WebDisplays (public domain)
- **Keksuccino** — original Rinku (CEF backend)
