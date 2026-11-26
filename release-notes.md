用 Chrome 替代 CEF 的 WebDisplays 浏览器后端。

## 为什么换引擎

WebDisplays 自带的 CEF 没有 H.264 解码器，B 站等站点会拒绝播放；被迫改用 AV1 时只能 CPU 软解，1080P60 跑不动，表现是**画面卡顿但声音正常**。

Chrome 有完整的硬件解码（H.264 / HEVC / VP9），问题从根上解决。

## 下载

一个完整的网页屏幕需要**两个 jar**，都要装：

| 文件 | 作用 |
|---|---|
| `webdisplays-1.2.25-26.1.2.jar` | 屏幕方块、激光笔、渲染、GUI |
| `rinku-3.1.0-cdp.jar` | 浏览器后端（用 Chrome 替代 CEF） |

**不需要**再装原版 Rinku，也不需要 CEF。

## 安装

1. 把两个 jar 都放进 `mods` 文件夹
2. 启动游戏

要求：

- Minecraft **26.1.2**
- NeoForge **26.1.2.125** 或更新
- 系统已装 **Google Chrome**（建议 120+）

## 验证装好了

日志 `logs/latest.log` 里搜索 `reflection contract`，应显示：

```
=== 30 passed, 0 failed ===
```

30 项全 PASS 才算正确。

## 使用

1. 用 `/screen screen 64` 等命令拿物品
2. 用屏幕方块搭一个**完整矩形**墙
3. 手持配置器右键墙面 → **Create Screen**
4. 点 **Set URL** 输入网址

详细教程见 [README](https://github.com/LTJ002/rinku-cdp#readme)。

## 本版修复

- **多屏黑屏**：每块屏幕独立 Chrome 窗口（后台标签页合成 0 帧）
- **建屏失败**：不再比对方块朝向，失败时提示具体坐标
- **硬件解码**：跟随 Chrome 的编解码器选择，不再强制 AV1
- **中文输入**：支持输入法合成

## 校验值

```
rinku-3.1.0-cdp.jar
  大小:   150,651 字节
  SHA256: 0E7473B80F29BA524A0ACB92853B3C7AFB64F51D8686C0F046033FEAB80C6CFB

webdisplays-1.2.25-26.1.2.jar
  大小:   363,258 字节
  SHA256: 73382137B8E2C755CD50C0745DA5D7C7272457962BE1AD649C9D906505AAE569
```

## 致谢

- **montoyo** — WebDisplays 原作者，该项目已进入公有领域
- **Keksuccino** — 原版 Rinku（CEF 后端）作者

## 许可

MIT
