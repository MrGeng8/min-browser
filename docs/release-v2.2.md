## 体积

| 项目 | 大小 |
|---|---|
| **APK** | **20,953 字节（20.5 KB）** |
| classes.dex | 17,956 字节 |
| 图标 | 1,132 字节 |
| 第三方依赖 | **零** |

不打包浏览器内核，复用系统 WebView。

## 本次新增：下载

接管 WebView 的 `setDownloadListener`，落盘交给系统 `DownloadManager` —— 不自己写 IO。

- 自动带 **UA / Cookie / Referer**：论坛附件、网盘直链缺了这三个基本 403
- 文件名按 `Content-Disposition` 推断，存进系统下载目录，通知栏可见
- 存储权限按需申请：Android 6~9 弹一次；**Android 10+ 完全不需要**（文件由系统 DownloadProvider 落盘）
- 纯黑 toast 提示（开始 / 失败 / 拒绝权限），沿用 `#000000` 配色
- 标签页面板新增「↓ 下载内容」入口
- 非 `http(s)` 链接（`tel:` `mailto:` `weixin:` 等）交给系统处理，以前点了没反应

## 本次新增：搜索框移到黄金分割位置

地址栏不再固定在屏幕顶端，改为浮层搜索框：

- **位置：从屏幕底部往上 61.8%**（= 顶部往下 38.2%），左右居中
- 描边 `#7A7A7A`，与纯黑背景形成反差
- 位置按「整屏高度 × 0.382 − 状态栏高度」在运行时计算，不受状态栏高度影响
- 左上角的标签页数量改为**竖向三点**（单标签时不再是一个孤零零的「1」）；点按开面板，长按新建标签页

## 本次新增：鸿蒙版（HarmonyOS / ArkTS）

鸿蒙不跑 APK，所以这是按鸿蒙 API **重新实现**的一套，不是打包格式转换（工程见仓库 `harmony/`）：

- ArkWeb 的 `Web` 组件替代 WebView，`WebDownloadDelegate` 替代 DownloadManager
- **「强制纯黑」用的是同一份注入脚本**
- 功能对齐：多标签、纯黑 UI、强制网页纯黑、Bing 搜索、下载、可作默认浏览器

## 修复

**空白新标签页整片发白**：空白页底色本就是纯黑，而强制纯黑用的 `invert(1) hue-rotate(180deg)` 会把黑底反相成**白底**。现改为空白页不注入反相（实测：纯白 83.9% → 纯黑 99.3%）。

## 相对 2.1 的变化

| 项目 | 2.1 | 2.2 |
|---|---|---|
| APK | 16,857 字节 | 20,953 字节 |
| classes.dex | 14,496 字节 | 17,956 字节 |
| 权限 | `INTERNET` | `INTERNET` + `WRITE_EXTERNAL_STORAGE`（`maxSdkVersion=28`） |

## 真机验证

| 设备 | 验证项 |
|---|---|
| Mate 9 Pro（Android 9） | 下载落盘 md5 一致；UA/Cookie/Referer 齐全；运行时授权弹窗流程完整；纯黑 toast |
| Mate 20 Pro（Android 10） | 免权限分支下载成功；搜索框位于 38.16%（目标 38.2%）、居中偏差 0.5px；空白页纯黑 99.3% |
| HarmonyOS 6 / API 24 | `hdc install` 成功；下载走内核委托且三个请求头齐全、系统保存框正常拉起；白底页强制纯黑 97.8%；空白页纯黑 99.7% |

## 安装

```bash
adb install -r Min-2.2.apk          # Android
hdc install -r Min-2.2.hap          # HarmonyOS
```

- Android：`minSdk 21` / `targetSdk 28`，可作为 http/https 默认浏览器
- HarmonyOS：`com.mrgeng.minbrowser`，可作为默认浏览器

> **注意**：附件里的 HAP 是**调试签名**（profile 绑定了开发者设备 UDID），只能装在签名时的那台设备上；其他人需要在自己的 DevEco 里重新自动签名后再安装。

## 已知限制

强制纯黑用的是 CSS `invert` 方案，**本身是深色的网站会被翻转成浅色**；`filter` 会让 `body` 成为包含块，个别网站的 `position:fixed` 元素可能错位。遇到排版异常点工具条上的 `●` 关闭即可。

下载由系统组件执行，**不提供自定义保存路径、暂停/续传控制**；网页用 JS 生成的 `blob:` 下载抓不到。
