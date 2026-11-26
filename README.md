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

## 文件清单

一个完整的网页屏幕需要**两个 jar**，缺一不可：

| 文件 | 作用 | 来源 |
|---|---|---|
| `webdisplays-1.2.25-26.1.2.jar` | 屏幕方块、激光笔、渲染、GUI | 本仓库 [Releases](../../releases) |
| `rinku-3.1.0-cdp.jar` | 浏览器后端（本仓库） | 本仓库 [Releases](../../releases) |

> **为什么是两个？** WebDisplays 通过反射调用浏览器库，两者是分开的模组。原版用的是 Rinku（CEF 内核），本仓库用自己实现的 CDP 后端顶替它，mod id 保持 `rinku` 以便无缝替换。

**不需要**再安装原版 Rinku，也不需要 CEF。

---

## 安装

### 前置条件

| 项目 | 要求 | 检查方法 |
|---|---|---|
| Minecraft | **26.1.2** | 启动器里看版本 |
| NeoForge | **26.1.2.125** 或更新 | 启动器版本列表 |
| Google Chrome | 已安装，建议 120+ | 打开 Chrome → 关于 |
| 内存 | 建议分配 6 GB 以上 | 启动器 JVM 参数 |

### 三步安装

**第一步：下载两个 jar**

从 [Releases](../../releases) 页面下载：
- `webdisplays-1.2.25-26.1.2.jar`
- `rinku-3.1.0-cdp.jar`

**第二步：放进 mods 文件夹**

打开你的游戏目录（启动器里通常有「打开游戏文件夹」按钮），把两个 jar 放进 `mods` 文件夹。

路径示例：
```
D:\Minecraft-26.1.2\mods\webdisplays-1.2.25-26.1.2.jar
D:\Minecraft-26.1.2\mods\rinku-3.1.0-cdp.jar
```

**第三步：启动游戏**

启动后**先别急着建屏幕**，先确认装对了（见下一节）。

### 确认装好了

打开 `logs/latest.log`，搜索 `reflection contract`，应该看到：

```
=== WebDisplays reflection contract (stage 0A) ===
  PASS  de.keksuccino.rinku.Rinku is public
  PASS  de.keksuccino.rinku.Rinku#createBrowser (String, boolean, int, int) -> Object
  ...
=== 30 passed, 0 failed ===
```

**30 项全 PASS** 才算装对。

如果看到 `0 passed, 30 failed`，说明两个 jar 版本不匹配 —— 确认都用本仓库 Release 里的版本。

---

## 使用教程

### 第一步：准备材料

在游戏里用命令拿到需要的物品（需要 OP 或开启作弊）：

```
/screen screen 64
/screen configurator
/screen linker
/screen keyboard
```

分别给你：屏幕方块、配置器、链接器、键盘。

### 第二步：搭一面墙

用**屏幕方块**在地面或墙上铺一个**矩形**。

**规则**：
- 必须是完整矩形，中间不能有洞
- 所有方块朝向可以混着放（不影响）
- 最小 1×1，最大建议不超过 16×9

```
正确示例（3×2）：
■■■
■■■

错误示例（缺一块）：
■■■
■□■    ← 有洞，无法创建
```

### 第三步：创建屏幕

1. 手持 **Configurator（配置器）**
2. **右键**墙面任意一个方块
3. 弹出配置界面，会显示测量的尺寸（如 `3 × 2`）
4. 点 **Create Screen**

如果按钮显示 `Measuring wall…` 且一直不动，说明墙不是完整矩形 —— 界面会提示缺哪一块。

### 第四步：设置网址

创建成功后，配置界面里会出现：

- **Set URL** —— 设置网页地址
- **Resolution** —— 设置清晰度
- **Rotation** —— 旋转画面

点 **Set URL**，输入网址，例如：

```
https://www.bilibili.com
```

点确认。屏幕会在几秒内加载页面。

> **提示**：第一次加载较慢（Chrome 冷启动），大约 2–5 秒。

### 第五步：调整清晰度（可选）

配置界面里的 **Resolution** 可以手动设置：

```
1920 × 1080    ← 高清（默认上限）
1280 × 720     ← 流畅
```

也可以用命令：

```
/screen res 1920 1080
```

点 **Auto** 让模组根据墙的大小自动算。

### 第六步：用鼠标操作网页

手持 **Laser Pointer（激光笔）**，对着屏幕：

| 操作 | 效果 |
|---|---|
| 左键 | 点击网页元素 |
| 右键 | 网页右键菜单 |
| 滚轮 | 滚动页面 |
| 移动准星 | 移动鼠标指针 |

### 第七步：用键盘输入（可选）

想在网页里打字（搜索、登录）需要键盘方块：

1. 手持 **Linker（链接器）**，**右键屏幕**（选中它）
2. 再**右键 Keyboard（键盘方块）**（完成配对）
3. **右键键盘**，打开输入界面
4. 直接打字，支持中文输入法
5. 按 **Esc** 关闭输入界面

---

## 多屏幕

重复上面的步骤即可建多块屏幕，各自独立运行。

**性能参考**：

| 屏幕数 | 内存占用 | 建议 |
|---|---|---|
| 1 块 | 约 300 MB | 无压力 |
| 2–4 块 | 约 600–1200 MB | 8 GB 分配可承受 |
| 5 块以上 | 1.5 GB+ | 需要更大内存 |

> 每块屏幕会启动一个独立的 Chrome 渲染进程。

---

## 命令参考

| 命令 | 作用 |
|---|---|
| `/screen` | 显示帮助 |
| `/screen screen [数量]` | 获得屏幕方块 |
| `/screen configurator` | 获得配置器 |
| `/screen linker` | 获得链接器 |
| `/screen keyboard` | 获得键盘 |
| `/screen url "<网址>" [面]` | 给准星对准的屏幕设网址 |
| `/screen res <宽> <高> [面]` | 设置分辨率 |
| `/screen list` | 列出附近所有屏幕 |
| `/screen info` | 显示准星对准的屏幕信息 |
| `/screen at <x> <y> <z> <面> url "<网址>"` | 按坐标操作，不用瞄准 |

「面」可以是 `north` / `south` / `east` / `west` / `top` / `bottom`。

---

## 常见问题

### 屏幕是黑的，但日志显示一切正常

**原因**：Chrome 没找到，或者页面加载失败。

**解决**：
1. 确认 Chrome 装在默认位置
2. 检查 `logs/latest.log` 里搜 `Chrome` 或 `CDP backend ready`
3. 如果显示 `Chrome not found`，用启动参数指定路径：
   ```
   -Dwdcdp.chrome="C:\Program Files\Google\Chrome\Application\chrome.exe"
   ```

### 视频卡顿

**检查项**：
1. 分辨率是否设得太高（降到 1280×720 试试）
2. 是否同时开了太多屏幕
3. Chrome 是否用了硬件解码（打开 `chrome://gpu` 检查）

### 多块屏幕只有一块亮

这个 bug 已在 3.1.0 修复。如果还遇到，确认用的是 Release 里的版本。

**原因**：Chrome 只对窗口的活动标签页做合成，后台标签页合成 0 帧。修复方式是每块屏幕各开一个 Chrome 窗口。

### 没有声音

**检查项**：
1. 游戏内音量设置
2. 屏幕距离 —— 模组有 3D 音效，离太远听不到
3. 日志里搜 `Page audio connected` 确认音频已接上

### 中文显示成方块 / 空白

这是 MuoniumPlayer 等其他模组的字体问题，与屏幕无关。屏幕内的网页中文由 Chrome 渲染，不受影响。

### 网页显示「浏览器不支持」

如果用的是原版 Rinku（CEF），这是 H.264 缺失导致的。换成本仓库的 CDP 后端即可解决。

### 想清除登录状态

删除游戏目录下的 `config/wdcdp/chrome-profile` 文件夹，下次启动会重建。

---

## 技术细节

<details>
<summary>点开看实现原理</summary>

### 架构

```
Minecraft
 └─ WebDisplays（屏幕方块、渲染）
     └─ 反射调用 14 个方法
         └─ Rinku API 兼容层（本仓库）
             └─ CdpBrowser → CdpPage → PageTexture
                 └─ WebSocket (CDP)
                     └─ Chrome 进程（headless）
                         └─ 网页标签页
```

### 画面链路

```
Chrome 合成 → Page.startScreencast → JPEG 帧
    → WebSocket → STB 解码 → NativeImage
    → glTexSubImage2D → OpenGL 纹理 → 画到墙上
```

整条链路只在渲染线程碰 GPU。

### 音频链路

Chrome 是 headless 的，没有音频输出设备。所以在页面里注入脚本：

```js
createMediaElementSource(video) → AudioWorklet → Runtime.addBinding 回传
```

音频样本以 float 数组送回 Java 侧，交给游戏混音后输出。

### 为什么每块屏幕单独开窗口

Chrome 只对窗口的活动标签页做合成。后台标签页 `document.visibilityState === "hidden"`，screencast 收到 **0 帧**，但音频照常播放 —— 表现就是「黑屏但有声音」。

实测数据（三个标签页同时播视频）：

| 模式 | 各标签页收到的帧数 |
|---|---|
| 同窗口 | 112, **0**, 96 |
| 每页独立窗口 | 97, 95, 92 |

所以用 `Target.createTarget({newWindow: true})`。

### 代码结构

```
src/main/java/
├── com/wdcdp/cdpscreen/
│   ├── CdpBackend.java        Chrome 进程管理、页面表、生命周期
│   ├── CdpBrowser.java        WebDisplays 看到的对象（14 个反射方法）
│   ├── CdpHooks.java          NeoForge 事件挂接
│   ├── MixerBridge.java       反射桥接音频混音器
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

## 致谢

- **montoyo** —— WebDisplays 原作者。该项目已进入**公有领域**，作者只希望保留署名。
- **Keksuccino** —— 原版 Rinku（CEF 后端）作者。本仓库的 `de.keksuccino.rinku` 包名是为了兼容 WebDisplays 的反射查找，代码为独立实现。

---

## 许可

[MIT](LICENSE)
