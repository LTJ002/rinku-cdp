# Rinku-CDP 3.1.0

给 WebDisplays 换一个浏览器内核：用 Chrome 替代 CEF。

[English](RELEASE-NOTES.md) · 简体中文

---

## 为什么换引擎

WebDisplays 自带的 **CEF** 没有 H.264 解码器。B 站等站点检测到后会拒绝播放；被迫改用 AV1 时只能 CPU 软解，1080P60 在笔记本上跑不动。表现是**画面卡顿但声音正常**。

本项目改用你电脑上已装的 Chrome。Chrome 有完整的硬件解码（H.264 / HEVC / VP9），播放流畅。

---

## 下载

一个完整的网页屏幕需要**两个 jar**，都要装。

| 文件 | 作用 |
|---|---|
| `webdisplays-1.2.25-26.1.2.jar` | 屏幕方块、激光笔、渲染、GUI |
| `rinku-3.1.0-cdp.jar` | 浏览器后端（本仓库） |

**不需要**再装原版 Rinku，也**不需要** CEF。

---

## 安装

1. 把两个 jar 都放进 `mods` 文件夹
2. 启动游戏

**要求**

- Minecraft **26.1.2**
- NeoForge **26.1.2.125** 或更新
- 系统已装 **Google Chrome**，建议 120+

### 验证

在 `logs/latest.log` 里搜索 `reflection contract`，应显示：

```
=== 30 passed, 0 failed ===
```

30 项全 PASS 才算正确。如果显示 `0 passed, 30 failed`，说明两个 jar 版本不匹配。

---

## 使用

1. 用 `/screen screen 64`、`/screen configurator`、`/screen linker`、`/screen keyboard` 拿物品
2. 用屏幕方块搭一个**完整矩形**墙
3. 手持配置器右键墙面 → **Create Screen**
4. 点 **Change URL** 输入网址

详细教程：[README](https://github.com/LTJ002/rinku-cdp/blob/main/README.zh-CN.md)

---

## 本版修复

- **多屏黑屏** —— 每块屏幕现在各自使用一个 Chrome 窗口。Chrome 只对窗口的活动标签页做合成，后台标签页合成 0 帧，这就是以前只有一块墙有画面、其余黑屏但有声音的原因。
- **建屏失败** —— 不再比对方块朝向。原来的测量会拒绝任何「一边走一边贴」的墙，导致 Create Screen 静默无反应。现在失败时会指出具体坐标。
- **硬件解码** —— 跟随 Chrome 的编解码器选择，不再强制 AV1。
- **中文输入** —— 支持中日韩输入法合成。

---

## 校验值

```
rinku-3.1.0-cdp.jar
  大小:   150,651 字节
  sha256: 0E7473B80F29BA524A0ACB92853B3C7AFB64F51D8686C0F046033FEAB80C6CFB

webdisplays-1.2.25-26.1.2.jar
  大小:   363,258 字节
  sha256: 73382137B8E2C755CD50C0745DA5D7C7272457962BE1AD649C9D906505AAE569
```

---

## 致谢

- **montoyo** —— WebDisplays 原作者，项目已进入公有领域。
- **Keksuccino** —— 原版 Rinku（CEF 后端）作者。

## 许可

本仓库源代码为 MIT。随附的 WebDisplays jar 属于公有领域，按该条款分发 —— 详见 [LICENSE](https://github.com/LTJ002/rinku-cdp/blob/main/LICENSE#scope-of-this-license)。
