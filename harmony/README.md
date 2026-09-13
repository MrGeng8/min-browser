# Min Browser · HarmonyOS 版

> 与 Android 版同源的极简纯黑浏览器，用 **ArkTS + ArkWeb** 重写，产出可安装的 `.hap`。

复用系统内核（ArkWeb），不打包浏览器引擎；UI 与网页统一纯黑 `#000000`。

## 与 Android 版的关系

同一套设计、同一份「强制纯黑」注入脚本，但**不是 APK 转换**——鸿蒙不跑 APK，这是按鸿蒙的 API 重新实现的：

| 能力 | Android 版 | HarmonyOS 版 |
|---|---|---|
| 内核 | 系统 WebView | ArkWeb（`Web` 组件） |
| 下载 | `DownloadListener` + `DownloadManager` | `WebDownloadDelegate` + `WebDownloadItem` |
| 强制纯黑 | `evaluateJavascript(JS_DARK)` | `controller.runJavaScript(JS_DARK)` |
| 多标签 | 多个 WebView + `Visibility.GONE` | 多个 `Web` 组件 + `Visibility.None` |
| 纯黑提示 | 自绘 `Toast` | 自绘浮层（带定时器） |
| 外链 | `startActivity(ACTION_VIEW)` | `startAbility({action:'ohos.want.action.viewData'})` |

功能对齐：多标签、纯黑 UI、强制网页纯黑、Bing 搜索、下载、非 `http(s)` scheme 交系统、返回键=网页后退。

**已知差异**：Android 版后台标签会 `onPause()` 停掉 JS 定时器；ArkWeb 只有全局的 `WebviewController.pauseAllTimers()`，没有单标签暂停，因此鸿蒙版后台标签仅隐藏、JS 仍在跑。

## 工程结构

```
harmony/
├── AppScope/app.json5                 bundleName: com.mrgeng.minbrowser
├── build-profile.json5                产品配置 + signingConfigs
├── hvigorfile.ts / hvigor/            构建脚本
├── oh-package.json5
└── entry/src/main/
    ├── module.json5                   权限 + EntryAbility + http/https browsable
    ├── resources/                     纯黑启动窗口、图标、字符串
    └── ets/
        ├── entryability/EntryAbility.ets   启动、纯黑系统栏、接收外部 URL
        └── pages/Index.ets                 浏览器主逻辑（全部逻辑集中在这一个文件）
```

## 构建

**推荐：直接用 DevEco Studio 打开 `harmony/` 目录**，首次 Sync 会生成 `hvigor/hvigor-wrapper.js`。

**命令行构建**（用 DevEco 自带的 hvigor，免安装）：

```bash
export DEVECO_SDK_HOME=/Applications/DevEco-Studio.app/Contents/sdk
export HVIGOR_USER_HOME=/tmp/hvigor_home     # 若 ~/.hvigor 不可写，改道
export npm_config_cache=/tmp/npm_cache       # 若 ~/.npm 不可写，改道
export JAVA_HOME=/path/to/jdk17              # 打包阶段需要 java

/Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw \
    assembleHap --mode module -p product=default -p buildMode=debug --no-daemon
```

产物：`entry/build/default/outputs/default/entry-default-signed.hap`

安装：

```bash
hdc install -r entry-default-signed.hap
```

## 签名（真机安装的前提）

真机只接受**华为签发的 profile**（调试 profile 需绑定设备 UDID）。用 DevEco 自动生成：

1. DevEco Studio 打开 `harmony/`
2. `File > Project Structure… > Project > Signing Configs`
3. 勾选 **Automatically generate signature**（需登录华为开发者账号）
4. 确定 → 材料写入 `~/.ohos/config/`，配置写进 `build-profile.json5`

> `build-profile.json5` 里同时要在 product 上补 `"signingConfig": "default"`，否则只会打包出未签名的 hap。

仓库里提交的 `signingConfigs` 是生成时的本地路径（指向 `~/.ohos/config/…`），**换机器后需要重新生成**。

## 已知限制

- 强制纯黑是 `invert` 反相方案：**本身是深色的网站会被翻转成浅色**；空白页已做特判不注入（否则纯黑底会被翻成白底）
- `filter` 会让 `body` 成为包含块，个别站点 `position:fixed` 元素可能错位
- 下载由系统组件执行，不提供自定义保存路径 / 暂停续传；网页 JS 生成的 `blob:` 下载抓不到
- 渲染能力取决于系统 ArkWeb 版本

更详细的两端对照、真机实测数据与踩坑记录见 [`docs/v2.2-changes.md`](../docs/v2.2-changes.md)。
