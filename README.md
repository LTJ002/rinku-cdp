# Rinku-CDP

**给 WebDisplays 换一个浏览器内核：用 Chrome 替代 CEF。**

---

## 这是什么

WebDisplays 是 Minecraft 里的网页屏幕模组 —— 你可以用方块搭一面墙，在墙上显示真实的网页、播放 B 站视频。

它原本用一个叫 **CEF**（Chromium 的嵌入式版本）的浏览器内核。这个内核有个硬伤：**没有 H.264 解码器**。

结果是：

| 现象 | 原因 |
|---|---|
| B 站视频提示「浏览器不支持播放」 | 站点检测到没有 H.264，拒绝播放 |
| 视频一卡一卡，声音却正常 | 被迫用 AV1，纯 CPU 软解跑不动 1080P60 |
| 视频越长越卡 | 软解跟不上，缓冲越积越多 |

**Rinku-CDP 的做法**：不用 CEF 了，直接调用你电脑上已装的 Chrome。Chrome 有完整的硬件解码（H.264 / HEVC / VP9），视频流畅问题从根上消失。

---

## 快速开始

### 你需要什么

| 项目 | 要求 |
|---|---|
| Minecraft | **26.1.2** |
| NeoForge | **26.1.2.125** 或更新 |
| WebDisplays | **1.2.25-26.1.2** |
| Google Chrome | 已安装即可（建议 120 以上） |

### 三步装好

**1.** 把 `rinku-3.1.0-cdp.jar` 放进 `mods` 文件夹

**2.** 确认 `webdisplays-1.2.25-26.1.2.jar` 也在 `mods` 里

**3.** 启动游戏

**不需要**装 CEF，**不需要**装原版 Rinku —— 本模组的 mod id 就是 `rinku`，会顶替它。

### 确认装好了

启动后打开日志文件 `logs/latest.log`，搜索 `reflection contract`，应该看到：

```
=== WebDisplays reflection contract (stage 0A) ===
  PASS  de.keksuccino.rinku.Rinku is public
  ...
=== 30 passed, 0 failed ===
```

**30 项全 PASS** 就是装对了。有 FAIL 说明版本不匹配。

---

## 怎么用

### 创建第一块屏幕

1. **搭一面墙** —— 用屏幕方块铺成一个矩形，必须填满，不能有洞
2. **手持 Configurator（配置器）** 右键墙面
3. 点 **Create Screen**

> 方块朝向可以混着放，不影响使用。

### 设置网址

配置界面里点 **Set URL**，输入地址即可。

也可以用命令：

```
/screen url "https://www.bilibili.com"
```

### 调整清晰度

```
/screen res 1920 1080
```

手动模式最高 1920×1080。自动模式会根据墙的大小算，上限 1280×720。

### 想用键盘输入

1. 手持 **Linker（链接器）** 右键屏幕
2. 再右键 **Keyboard（键盘方块）**
3. 右键键盘打开输入界面

支持中文输入法。

### 多个屏幕

直接建多块就行，各自独立。

> **性能提示**：每块屏幕会占用一个 Chrome 渲染进程，大约 150–300 MB 内存。建太多会吃内存。

---

## 常见问题

**Q：Chrome 会被弹窗打扰吗？**

不会。Chrome 以 headless 模式运行，你看不到窗口，它也听不到声音 —— 声音是抓取后送进游戏的。

**Q：会抢占系统音频吗？**

不会。Chrome 被设置为不打开系统音频设备，音频样本在页面内抓取，由游戏混音输出。

**Q：B 站登录状态会保存吗？**

会。Chrome 用独立的 profile 目录，登录一次就记住。

**Q：能用 Netflix 吗？**

不能。需要 Widevine DRM 的站点无法播放。

**Q：占用多少资源？**

单块屏幕 1080P 播放视频时，Chrome 侧约 15–25% CPU（有硬件解码时更低），内存 150–300 MB。

**Q：能同时开几块屏幕？**

取决于内存。8 GB 分配下建议不超过 4 块。

---

## 技术细节

<details>
<summary>点开看实现原理（对普通用户不必读）</summary>

### 架构

```
Minecraft
 └─ WebDisplays（原版，未修改）
     └─ 反射调用 14 个方法
         └─ Rinku API 兼容层（本仓库）
             └─ CdpBrowser → CdpPage → PageTexture
                 └─ WebSocket (CDP)
                     └─ Chrome 进程（headless）
                         └─ 网页标签页
```

### 画面怎么来的

```
Chrome 合成 → Page.startScreencast → JPEG 帧
    → WebSocket → STB 解码 → NativeImage
    → glTexSubImage2D → OpenGL 纹理 → 画到墙上
```

整条链路只在渲染线程碰 GPU。

### 声音怎么来的

Chrome 是 headless 的，没有音频输出设备。所以在页面里注入脚本：

```js
createMediaElementSource(video) → AudioWorklet → Runtime.addBinding 回传
```

音频样本以 float 数组送回 Java 侧，交给游戏混音后输出。

### 为什么每块屏幕要单独开窗口

Chrome 只对**窗口的活动标签页**做合成。后台标签页 `document.visibilityState === "hidden"`，screencast 收到 **0 帧**，但音频照常播放 —— 表现就是「黑屏但有声音」。

实测数据（三个标签页同时播视频）：

| 模式 | 各标签页收到的帧数 |
|---|---|
| 同窗口 | 112, **0**, 96 |
| 每页独立窗口 | 97, 95, 92 |

所以用 `Target.createTarget({newWindow: true})` 让每块屏幕各占一个窗口。

### 代码结构

```
src/main/java/
├── com/wdcdp/cdpscreen/
│   ├── CdpBackend.java        Chrome 进程管理、页面表、生命周期
│   ├── CdpBrowser.java        WebDisplays 看到的对象（14 个反射方法）
│   ├── CdpHooks.java          NeoForge 事件挂接
│   ├── MixerBridge.java       反射桥接 WebDisplays 的音频混音器
│   ├── audio/PageAudio.java   页面音频抓取
│   ├── cdp/
│   │   ├── CdpClient.java     WebSocket 多路复用
│   │   ├── CdpPage.java       页面：screencast、输入、音频
│   │   ├── ChromeProcess.java 启动参数、进程回收
│   │   ├── ChromeFinder.java  定位 Chrome
│   │   └── PageMedia.java     媒体暂停、视频节奏
│   ├── render/
│   │   ├── PageTexture.java   OpenGL 纹理管理
│   │   └── FrameDecoder.java  JPEG 解码
│   └── validation/            自检工具
└── de/keksuccino/rinku/       兼容层（保持原 Rinku 的签名）
```

### 独立性

本仓库可以**单独编译**，不需要 WebDisplays 的源码或 jar。所有对 WebDisplays 的调用都通过反射：

| 调用点 | 方式 |
|---|---|
| `ClientInit.pageAudio()` | `Class.forName` + `getMethod` |
| `ScreenBlockEntity.forgetClientScreens()` | 同上 |
| `BrowserAudio` 的 5 个方法 | 统一走 `MixerBridge` |

好处：能独立构建，且兼容多个 WebDisplays 版本（缺方法时降级为静音而非崩溃）。

### 从源码构建

需要 JDK 25 和 NeoForge 26.1.2 的 classpath。

```bat
:: 1. 复制 cp.example.txt 为 cp.txt，把 <MINECRAFT_LIBRARIES> 改成你的路径
:: 2. 编译
build.bat
:: 3. 打包
package.bat
```

### 自检工具

```bat
:: 反射契约检查
java -cp "build/classes;<classpath>" com.wdcdp.cdpscreen.validation.ReflectionValidator

:: 生命周期检查（自动播放、弹窗、多屏、退出清理）
java -cp "..." com.wdcdp.cdpscreen.validation.LifecycleProbe

:: 静态审计（线程安全、资源泄漏）
java -cp "..." com.wdcdp.cdpscreen.validation.StaticAudit
```

</details>

---

## 已知限制

| 限制 | 说明 |
|---|---|
| 需要安装 Chrome | 不内置浏览器，用系统已装的 |
| 首次启动慢 | Chrome 冷启动约 2–5 秒 |
| 内存占用 | 每块屏幕 150–300 MB |
| DRM 视频 | Netflix 等需 Widevine 的站点无法播放 |
| 仅 26.1.2 | 其他 MC 版本需要重新适配 |

---

## 许可

[MIT](LICENSE)

本仓库只包含 CDP 后端的独立实现，**不含** WebDisplays 或原 Rinku 的任何代码。它们由各自作者以各自条款发布，需要单独获取。
