# Min Browser

> **16.5 KB 的安卓浏览器。零依赖，纯黑，省电。**

一个给 AMOLED 屏幕和极客用的极简浏览器。不打包任何浏览器内核，直接复用系统 WebView —— 所以它小到不像一个 App。

![APK](https://img.shields.io/badge/APK-16.5%20KB-blue)
![dex](https://img.shields.io/badge/dex-14.5%20KB-blue)
![minSdk](https://img.shields.io/badge/minSdk-21-green)
![targetSdk](https://img.shields.io/badge/targetSdk-28-green)
![License](https://img.shields.io/badge/license-MIT-orange)

---

## 为什么它这么小

| 常见做法 | Min 的做法 |
|---|---|
| 打包 Chromium / Gecko 内核（几十~上百 MB） | **复用系统 WebView**，零内核体积 |
| 引入 AndroidX / Material（+ 1~3 MB） | **零第三方依赖**，只用系统 framework API |
| XML 布局 + 资源文件 | **全部代码构建 UI**，无 layout 资源 |
| Gradle 构建出大量元数据 | 手动 `aapt2 → javac → d8`，**无 Gradle** |

最终 APK 里只有 4 个文件：

```
AndroidManifest.xml      3,112 字节
classes.dex             14,496 字节
res/mipmap-xxxhdpi/ic.png  771 字节
resources.arsc             576 字节
                        ─────────
                        16,857 字节  (16.5 KB)
```

## 实测数据

在 **HUAWEI Mate 9 Pro（Android 9 / EMUI 9.1）** 上实测：

| 指标 | 数值 | 说明 |
|---|---|---|
| **APK 体积** | **16,857 字节** | 约等于一张缩略图 |
| **应用自身 Dalvik Heap** | **≈ 1.3 MB** | `dumpsys meminfo` 实测 |
| dex 大小 | 14,496 字节 | |
| 整进程 PSS | ≈ 67 MB | 绝大部分是系统 WebView 引擎，**系统已有、多应用共享**，Min 不额外带来引擎开销 |

> 对比：同机上的 DeepSeek App 整进程 PSS ≈ 93 MB。Min 的 67 MB 里，真正的「Min 代码」只占约 1.3 MB。

## 特点

### 🖤 纯黑，是真黑

UI 与网页内容统一使用 **`#000000`**，不是 `#1A1A1A` 那种深灰。

工具条、状态栏、导航栏、背景全部写死 `#FF000000`。

### 🔋 省电

AMOLED 屏幕上黑色像素**不发光**，纯黑背景相比深灰能显著降低屏幕功耗。

网页侧还有一层可开关的**强制纯黑**：

```
浅色网页 ──[ invert + hue-rotate ]──> 纯黑背景 + 浅色文字
```

实测一个纯白底页面（`#FFFFFF`）加载后，屏幕 **97.35%** 的像素变成 `#000000`。

> **实现上的一个坑**：`filter` 必须加在 `body` 上，`html` 的背景单独写死 `#000`。
> 如果把 `filter` 加在 `html` 上，Chromium 的 canvas 底色**不参与过滤**，大片浅色底不会被反相
> —— 我们踩过这个坑，纯黑占比只有 12%，加在 `body` 之后才到 97%。

### ⚡ 极简

- **多标签页**：长按标签按钮直接新建；点按打开面板切换 / 关闭
- **后台标签自动 `onPause()`**，不偷跑 JS 定时器
- 地址栏自动全选，点一下直接输入新网址
- 搜索用 **Bing（cn.bing.com）**，国内直连
- 地址栏输入无点号的词 → 走搜索；有点号 → 当网址访问
- 返回键 = 网页后退

## 截图

| 浏览（纯黑模式） | 标签页管理 |
|---|---|
| ![浏览](docs/screenshot-browse.png) | ![标签页](docs/screenshot-tabs.png) |

## 安装

```bash
adb install -r Min.apk
```

或直接下载 [Releases](../../releases) 里的 APK 手动安装。

- `minSdk 21`（Android 5.0+）
- `targetSdk 28`
- 只需要 `INTERNET` 一个权限
- 注册为 `http` / `https` 默认处理器，可作为默认浏览器

## 构建

不依赖 Gradle，只需要 JDK 17+ 和 Android SDK。

```bash
# 需要：
#   JDK 17+
#   Android SDK: build-tools + platforms/android-28

ANDROID_SDK_ROOT=/path/to/android-sdk bash app/build.sh
```

脚本自动探测 `ANDROID_SDK_ROOT` / `ANDROID_HOME`，也支持常见默认路径。

产物在 `app/build/Min.apk`。首次构建会自动生成自签名密钥库 `app/min.keystore`。

### 源码结构

```
app/
├── AndroidManifest.xml            权限、Activity、http/https intent-filter
├── build.sh                       构建脚本（aapt2 → javac → d8 → zipalign → apksigner）
├── make_icon.py                   图标生成器（手写 PNG 编码，无第三方库）
├── res/mipmap-xxxhdpi/ic.png      图标
└── src/com/blk/min/Main.java      全部逻辑，单文件
```

## 已知限制

诚实地说清楚：

1. **强制纯黑是反相方案**：浅色网站会变纯黑，但**本身就是深色的网站会被反转成浅色**。这是 `invert` 的固有特性。遇到排版异常的网站，点工具条上的 `●` 关掉即可。
2. `filter` 会使 `body` 成为新的包含块，**个别网站的 `position:fixed` 悬浮元素可能错位**。
3. 无广告拦截、无隐私模式、无书签、无下载管理 —— 这是刻意的取舍。
4. 复用系统 WebView，因此**渲染能力取决于系统 WebView 版本**。

## 路线图

- [ ] 下载管理（接管 `setDownloadListener`）
- [ ] 主页设置
- [ ] 纯黑模式按站点白名单
- [ ] 自适应图标（adaptive icon）

## License

[MIT](LICENSE)

---

# Min Browser (English)

> **A 16.5 KB Android browser. Zero dependencies. Pure black. Battery-friendly.**

A minimal browser built for AMOLED screens and people who like small software. It bundles no engine — it reuses the system WebView, which is why it barely qualifies as an app.

- **16,857-byte APK**, 14,496-byte dex, **zero third-party dependencies**
- **Pure `#000000`** UI (not dark gray) — status bar, nav bar, toolbar, background
- **Force-dark for web pages**: inverts light pages to true black (measured 97.35% pure black on a white test page)
- **Multi-tab** with background tabs paused
- Search via **Bing** (`cn.bing.com`)
- `minSdk 21`, single `INTERNET` permission
- Measured **~1.3 MB app Dalvik heap**; total PSS ~67 MB, dominated by the shared system WebView

No Gradle. Build with `aapt2`, `javac`, `d8`, `zipalign`, `apksigner`:

```bash
ANDROID_SDK_ROOT=/path/to/android-sdk bash app/build.sh
```

Known limits: the force-dark uses CSS `invert`, so already-dark sites get flipped to light; `position:fixed` elements on some sites may shift. No adblock, no bookmarks, no downloads — by design.

MIT licensed.
