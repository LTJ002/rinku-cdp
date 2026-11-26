A Chrome-based browser backend for WebDisplays — replaces CEF.

给 WebDisplays 换一个浏览器内核：用 Chrome 替代 CEF。

---

## Why replace the engine · 为什么换引擎

WebDisplays' bundled **CEF** has no H.264 decoder. Sites that require it refuse to play, and the AV1 workaround decodes on the CPU — unusable for 1080p60 on laptop hardware. The symptom is **video stuttering while audio plays fine**.

Chrome has hardware decoding for H.264, HEVC and VP9, so playback is smooth.

WebDisplays 自带的 **CEF 没有 H.264 解码器**，B 站等站点会拒绝播放；被迫改用 AV1 时只能 CPU 软解，1080P60 跑不动，表现是**画面卡顿但声音正常**。

Chrome 有完整的硬件解码（H.264 / HEVC / VP9），问题从根上解决。

---

## Download · 下载

A working screen needs **two jars**. Both are required.

一个完整的网页屏幕需要**两个 jar**，都要装：

| File · 文件 | Purpose · 作用 |
|---|---|
| `webdisplays-1.2.25-26.1.2.jar` | Screen blocks, laser pointer, rendering, GUI · 屏幕方块、激光笔、渲染、GUI |
| `rinku-3.1.0-cdp.jar` | Browser backend · 浏览器后端（用 Chrome 替代 CEF） |

You do **not** need the original Rinku, and you do **not** need CEF.

**不需要**再装原版 Rinku，也不需要 CEF。

---

## Install · 安装

1. Put both jars in `mods` · 把两个 jar 都放进 `mods` 文件夹
2. Launch the game · 启动游戏

**Requirements · 要求**

- Minecraft **26.1.2**
- NeoForge **26.1.2.125** or newer · 或更新
- Google **Chrome** installed, 120+ recommended · 已安装，建议 120+

### Verify · 验证

Search `logs/latest.log` for `reflection contract` — it must say:

在日志里搜索 `reflection contract`，应显示：

```
=== 30 passed, 0 failed ===
```

All 30 must pass. · 30 项全 PASS 才算正确。

---

## Usage · 使用

1. Get items with `/screen screen 64`, `/screen configurator`, etc.
2. Build a **filled rectangle** wall of screen blocks
3. Right-click the wall with the Configurator → **Create Screen**
4. Click **Set URL** and enter an address

1. 用 `/screen screen 64` 等命令拿物品
2. 用屏幕方块搭一个**完整矩形**墙
3. 手持配置器右键墙面 → **Create Screen**
4. 点 **Set URL** 输入网址

Full guide · 详细教程: [README](https://github.com/LTJ002/rinku-cdp#readme) · [中文](https://github.com/LTJ002/rinku-cdp/blob/main/README.zh-CN.md)

---

## Fixed in this version · 本版修复

- **Multi-screen black screens** — each screen gets its own Chrome window, because Chrome composites zero frames for background tabs
- **Screen creation failing** — no longer compares block facing; reports the offending coordinate instead
- **Hardware decoding** — follows Chrome's codec choice instead of forcing AV1
- **IME input** — Chinese/Japanese/Korean composition supported

- **多屏黑屏** — 每块屏幕独立 Chrome 窗口（后台标签页合成 0 帧）
- **建屏失败** — 不再比对方块朝向，失败时提示具体坐标
- **硬件解码** — 跟随 Chrome 的编解码器选择，不再强制 AV1
- **中文输入** — 支持输入法合成

---

## Checksums · 校验值

```
rinku-3.1.0-cdp.jar
  size · 大小: 150,651 bytes
  sha256: 0E7473B80F29BA524A0ACB92853B3C7AFB64F51D8686C0F046033FEAB80C6CFB

webdisplays-1.2.25-26.1.2.jar
  size · 大小: 363,258 bytes
  sha256: 73382137B8E2C755CD50C0745DA5D7C7272457962BE1AD649C9D906505AAE569
```

---

## Credits · 致谢

- **montoyo** — author of WebDisplays. The project is in the public domain. · WebDisplays 原作者，项目已进入公有领域
- **Keksuccino** — author of the original Rinku (CEF backend). · 原版 Rinku（CEF 后端）作者

## License · 许可

MIT
