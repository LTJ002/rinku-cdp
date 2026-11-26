# Rinku-CDP — 用 Chrome 替代 CEF 的 WebDisplays 浏览器后端

一个 Minecraft 模组的浏览器后端：把 WebDisplays 原本依赖的 **CEF（Chromium Embedded Framework）** 换成 **Chrome DevTools Protocol（CDP）**，用独立 Chrome 进程渲染网页，再把画面和声音送进游戏。

**纯客户端**，服务端不需要安装。

---

## 为什么做这个

WebDisplays 自带的 CEF 151 **没有 H.264 解码器**（Chromium 开源版不含专有编解码器）。后果是：

- B 站、YouTube 等站点检测到「不支持 H.264」后直接拒绝播放
- 强行改码率、换编码也没用，因为解码器根本不存在
- 唯一的变通是强制 AV1 —— 但 AV1 在多数机器上是**纯 CPU 软解**

在 i5-8300H 这类笔记本 CPU 上，1080P60 的 AV1 软解跑不动，表现就是：**画面一卡一卡，声音正常，越长的视频越明显**。

Chrome 157 自带 H.264 / HEVC / VP9 的**硬件解码**。所以换个引擎，问题从根上消失。

---

## 工作原理

```
┌─────────────────────────────────────────────┐
│ Minecraft                                   │
│  ┌───────────────────────────────────────┐  │
│  │ WebDisplays（原版，未修改）            │  │
│  │   ↓ 反射调用 14 个方法                 │  │
│  │ Rinku API 兼容层（本仓库）             │  │
│  └───────────────────────────────────────┘  │
│  ┌───────────────────────────────────────┐  │
│  │ CdpBrowser → CdpPage → PageTexture    │  │
│  │   ↓ WebSocket                          │  │
│  └───────────────────────────────────────┘  │
└─────────────────┬───────────────────────────┘
                  │ CDP (WebSocket)
         ┌────────▼────────┐
         │ Chrome 进程      │  headless
         │  ┌────────────┐  │
         │  │ 网页标签页  │  │  每块屏幕一个窗口
         │  └────────────┘  │
         └─────────────────┘
```

### 关键设计

**1. 画面：screencast → JPEG → GPU 纹理**

Chrome 的 `Page.startScreencast` 输出 JPEG 帧，游戏侧用 STB 解码成 `NativeImage`，再 `glTexSubImage2D` 上传成 OpenGL 纹理。整条链路只在渲染线程碰 GPU。

**2. 声音：页面内 Web Audio 抓取**

Chrome 是 headless 的，没有音频输出设备。所以在页面里注入脚本：

```js
createMediaElementSource(video) → AudioWorklet → 通过 Runtime.addBinding 回传
```

音频样本以 float 数组送回 Java 侧，交给 `BrowserAudio` 混音后输出。这样既能听到声音，又不会让 Chrome 抢占系统音频设备。

**3. 输入：CDP Input 域**

鼠标、滚轮、键盘全部转成 CDP 的 `Input.dispatchMouseEvent` / `dispatchKeyEvent`。输入法（IME）通过 `Input.imeSetComposition` 支持中文输入。

**4. 每块屏幕一个 Chrome 窗口**

这点非常关键。Chrome 只对**窗口的活动标签页**做合成，后台标签页 `document.visibilityState === "hidden"`，screencast 收到 **0 帧**，但音频照常播放。

所以每块屏幕用 `Target.createTarget({newWindow: true})` 各自开一个窗口。实测数据：

| 模式 | 三个标签页收到的帧数 |
|---|---|
| 同窗口（默认） | 112, **0**, 96 |
| 每页独立窗口 | 97, 95, 92 |

---

## 功能

| 功能 | 状态 |
|---|---|
| 网页渲染（任意站点） | ✅ |
| 硬件解码（H.264 / HEVC / VP9） | ✅ 走 Chrome |
| 页面音频 → 游戏内 3D 音效 | ✅ |
| 鼠标 / 滚轮 / 键盘输入 | ✅ |
| 中文输入法（IME 合成） | ✅ |
| 多屏同时显示 | ✅ |
| 全屏视频（含原生全屏） | ✅ |
| 链接弹窗接管 | ✅ |
| 页面光标样式同步 | ✅ |
| 离开世界自动回收进程 | ✅ |

---

## 安装

### 前置

| 项目 | 要求 |
|---|---|
| Minecraft | 26.1.2 |
| NeoForge | 26.1.2.125 或更高 |
| **WebDisplays** | 1.2.25-26.1.2 |
| **Google Chrome** | 已安装（自动检测，也可用 `-Dwdcdp.chrome=` 指定路径） |

### 步骤

1. 把 `rinku-3.1.0-cdp.jar` 放进 `.minecraft/mods/`
2. 确保 `webdisplays-1.2.25-26.1.2.jar` 也在 `mods/` 里
3. 启动游戏

**不需要**安装 CEF，也不需要 `de.keksuccino.rinku` 的原版 Rinku —— 本模组的 `modId` 就是 `rinku`，会顶替它。

### 验证是否正常

启动后日志里应该出现：

```
=== WebDisplays reflection contract (stage 0A) ===
  PASS  de.keksuccino.rinku.Rinku is public
  ...
=== 30 passed, 0 failed ===
```

这是启动自检，确认 WebDisplays 需要的 14 个方法签名全部匹配。**有任何 FAIL 就说明版本不兼容**。

---

## 使用

### 创建屏幕

1. 摆好一面矩形墙（屏幕方块，必须填满矩形，不能有洞）
2. 手持 **Configurator（配置器）** 右键墙面
3. 点 **Create Screen**

> 墙面方块朝向可以混着放，不影响测量。

### 设置网址

配置界面里点 **Set URL**，输入地址。

或使用命令：

```
/screen url "https://www.bilibili.com"
/screen res 1920 1080
/screen list
/screen info
```

### 多屏

直接创建多块屏幕即可，每块独立运行。多屏时的性能开销主要在 Chrome 侧（每块屏幕一个渲染进程）。

### 输入

- 用 **Linker（链接器）** 先右键屏幕，再右键 **Keyboard（键盘方块）** 完成配对
- 右键键盘打开输入界面
- 支持中文输入法

---

## 性能调优

### 分辨率

页面按**桌面尺寸**布局（最小 1280 宽），再按屏幕配置的分辨率缩放抓取。这样网站在桌面模式下渲染，不会切到手机版布局。

手动设置的分辨率上限是 1920×1080；自动模式上限 1280×720。

### 帧率

默认上限 60 fps，会根据实际解码情况自适应下调。视频播放时会自动跳过部分合成帧以留出解码余量。

### GPU 分配

默认让 Chrome 使用**低功耗 GPU**（通常是核显），把独显留给游戏：

```
--force_low_power_gpu
```

如果你的核显很弱（比如老 Intel HD），可以改配置文件换成独显。

---

## 已知限制

| 限制 | 说明 |
|---|---|
| 需要安装 Chrome | 不内置浏览器，用系统的 |
| Chrome 版本 | 建议 120+，`--headless=new` 需要较新版本 |
| 首次启动较慢 | Chrome 冷启动约 2–5 秒 |
| 内存占用 | 每块屏幕约 150–300 MB |
| DRM 视频 | Netflix 等需要 Widevine 的站点无法播放 |

---

## 从源码构建

### 环境

- JDK 25（用了 `Math.clamp` 等新 API）
- 已安装 NeoForge 26.1.2 的 Minecraft

### 步骤

```bat
:: 1. 配置类路径（指向你本机的 Minecraft 库）
::    编辑 cp.txt，把路径改成你自己的

:: 2. 编译
build.bat

:: 3. 打包
package.bat
:: 产物在 build/dist/wdcdp-1.0.0.jar
```

`build.bat` 使用 `sources.txt` 列出的文件清单，编译到 `build/classes`。

---

## 自检工具

仓库内含几个诊断工具，可独立运行：

```bat
:: 反射契约检查 —— 确认 WebDisplays 能调用全部方法
java -cp "build/classes;<neoforge classpath>" com.wdcdp.cdpscreen.validation.ReflectionValidator

:: 生命周期检查 —— 自动播放、弹窗、多屏、退出清理
java -cp "..." com.wdcdp.cdpscreen.validation.LifecycleProbe

:: 静态审计 —— 检查线程安全和资源泄漏
java -cp "..." com.wdcdp.cdpscreen.validation.StaticAudit
```

---

## 代码结构

```
src/main/java/
├── com/wdcdp/cdpscreen/
│   ├── CdpBackend.java          Chrome 进程管理、页面表、生命周期
│   ├── CdpBrowser.java          WebDisplays 看到的对象（14 个反射方法）
│   ├── CdpHooks.java            NeoForge 事件挂接
│   ├── CdpRenderQueue.java      渲染线程任务队列
│   ├── NavigationDebounce.java  导航去重
│   ├── audio/PageAudio.java     页面音频抓取
│   ├── cdp/
│   │   ├── CdpClient.java       WebSocket 多路复用
│   │   ├── CdpPage.java         页面：screencast、输入、音频纪元
│   │   ├── ChromeProcess.java   启动参数、进程回收
│   │   ├── ChromeFinder.java    定位 Chrome
│   │   ├── PageMedia.java       媒体暂停、视频节奏
│   │   ├── CdpKeys.java         按键码映射
│   │   └── CdpJson.java         JSON 辅助
│   ├── render/
│   │   ├── PageTexture.java     OpenGL 纹理管理
│   │   └── FrameDecoder.java    JPEG 解码
│   └── validation/              自检工具
└── de/keksuccino/rinku/         兼容层（保持原 Rinku 的类名和方法签名）
```

**兼容层说明**：`de.keksuccino.rinku` 这个包名是为了让 WebDisplays 的反射查找能命中。里面的代码是独立实现的，不含原 Rinku 的代码。

---

## 许可

MIT，见 [LICENSE](LICENSE)。

本仓库只包含 CDP 后端的独立实现，**不含** WebDisplays 或原 Rinku 的任何代码。它们由各自的作者以各自的条款发布，需要单独获取。

### 关于独立性

本仓库可以**单独编译**，不需要 WebDisplays 的源码或 jar 在 classpath 上。所有对 WebDisplays 的调用都通过反射完成：

| 调用点 | 方式 |
|---|---|
| `ClientInit.pageAudio()` | `Class.forName` + `getMethod("pageAudio")` |
| `ScreenBlockEntity.forgetClientScreens()` | `Class.forName` + `getMethod(...)` |
| `BrowserAudio` 的 `isOpen` / `startCdp` / `packetFloat` / `stop` / `flush` | `MixerBridge` 统一反射 |

这样做的两个好处：

1. **可以独立构建** —— 克隆下来配好 NeoForge classpath 就能编译
2. **兼容多个 WebDisplays 版本** —— 缺方法时降级为静音而不是崩溃

