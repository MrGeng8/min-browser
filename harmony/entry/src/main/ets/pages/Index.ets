import { webview } from '@kit.ArkWeb';
import { BusinessError, request } from '@kit.BasicServicesKit';
import { window } from '@kit.ArkUI';
import { common, Want } from '@kit.AbilityKit';
import { picker } from '@kit.CoreFileKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const TAG: string = 'Min';

/** 纯黑主题（默认），和 Android 版一致：是真黑 #000000，不是深灰 */
const BLACK: string = '#FF000000';
const FG: string = '#FFEDEDED';
const DIM: string = '#FF5A5A5A';
/** 浅色主题：白底黑字，描边 / 次要文字都比底色深一档才看得出来 */
const WHITE: string = '#FFFFFFFF';
const FG_LIGHT: string = '#FF000000';
const DIM_LIGHT: string = '#FF8A8A8A';
const LINE_LIGHT: string = '#FFBFBFBF';

/** 国内可直连的 Bing */
const SEARCH: string = 'https://cn.bing.com/search?q=';

/**
 * 空白新标签页。
 * 不能用 about:blank —— 它渲染出来是白底，跟纯黑主题冲突（data: URL 也不行，
 * ArkWeb 下不生效且会把这串 URL 显示到地址栏）。所以建 Web 时先给 about:blank，
 * 控制器一挂上就用 loadData 灌一段自带背景的内联 HTML —— 背景跟着主题走，
 * 见 blankHtml()：纯黑主题下灌 #000，浅色主题下灌 #fff。
 */
const BLANK: string = 'about:blank';

/**
 * 强制纯黑。
 * filter 只能加在 body 上，html 背景单独写死 #000 —— 若加在 html 上，
 * Chromium 的 canvas 底色不参与过滤，大片浅色底不会被反相。
 * 这段脚本与 Android 版是同一份。
 */
const JS_DARK: string =
  "(function(){" +
  "var id='__blk',s=document.getElementById(id);" +
  "if(!s){s=document.createElement('style');s.id=id;" +
  "(document.head||document.documentElement).appendChild(s);}" +
  "s.textContent=" +
  "'html{background:#000!important;}'+" +
  "'body{filter:invert(1) hue-rotate(180deg)!important;}'+" +
  "'img,video,picture,canvas,svg,iframe,embed,object,input[type=image]" +
  "{filter:invert(1) hue-rotate(180deg)!important;}';" +
  "return 'ok';})()";

/** 关闭强制纯黑：把注入的 style 摘掉，不用整页 reload */
const JS_DARK_OFF: string =
  "(function(){var s=document.getElementById('__blk');if(s){s.remove();}return 'off';})()";

/** 这些 scheme 交给系统里对应的 App 处理 */
const EXTERNAL_SCHEMES: string[] = [
  'tel', 'mailto', 'sms', 'alipay', 'alipays', 'taobao', 'tmall',
  'weixin', 'wechat', 'dingtalk', 'mqq', 'market', 'honor'
];

/** 取出 URL 的 scheme */
function schemeOf(url: string): string {
  const index: number = url.indexOf(':');
  if (index <= 0) {
    return '';
  }
  return url.substring(0, index).toLowerCase();
}

class TabInfo {
  id: number;
  url: string;
  title: string;

  constructor(id: number, url: string, title: string) {
    this.id = id;
    this.url = url;
    this.title = title;
  }
}

class DownloadRec {
  name: string;
  done: boolean;

  constructor(name: string, done: boolean) {
    this.name = name;
    this.done = done;
  }
}

/**
 * Min —— 极简纯黑浏览器（HarmonyOS / ArkWeb 版）
 *
 * 与 Android 版同源：同一套纯黑配色、同一份强制纯黑注入脚本、同一个 Bing 搜索。
 * 差异在于内核：这里是 ArkWeb（`Web` 组件），下载交给 `WebDownloadDelegate`，
 * 由内核自己发请求（Cookie / UA / Referer 天然带上），不再手工拼请求头。
 */
@Entry
@Component
struct Index {
  /** 每个标签页一个 WebviewController，只在创建时绑定一次 */
  private ctrls: Map<number, webview.WebviewController> = new Map<number, webview.WebviewController>();
  /** 标签页的实时地址（不进 @State，避免每次导航都重建列表） */
  private curUrls: Map<number, string> = new Map<number, string>();
  /** 下载任务 guid -> 沙箱落盘路径 */
  private dlPaths: Map<string, string> = new Map<string, string>();
  /** 已经由内核委托接手的下载 URL，用于让 onDownloadStart 兜底不重复触发 */
  private dlHandled: Map<string, number> = new Map<string, number>();
  private dlDelegate: webview.WebDownloadDelegate | undefined = undefined;
  private nextId: number = 1;
  private toastTimer: number = -1;

  @State tabs: TabInfo[] = [];
  @State active: number = 0;
  @State urlText: string = '';
  @State darkOn: boolean = true;
  @State showPanel: boolean = false;
  @State showDownloads: boolean = false;
  @State downloads: DownloadRec[] = [];
  @State toastText: string = '';
  /** 搜索框上边框相对内容区顶部的偏移（vp），由 placeSearchBox 按整屏高度算出 */
  @State searchTop: number = 320;
  /** 浮层搜索框只在空白起始页常驻；浏览网页时收起，点「→」可唤出 */
  @State urlBoxVisible: boolean = true;

  aboutToAppear(): void {
    const start: string | undefined = AppStorage.get<string>('startUrl');
    this.newTab(start === undefined ? '' : start);
    this.placeSearchBox();
    // EntryAbility 里那次 setWindowBackgroundColor 会报 "window state is abnormal" 不生效，
    // 这里窗口就绪了，按当前主题再打一遍系统栏 / 窗口底色
    this.syncSystemBars();
  }

  /** 回到前台时重算一次：屏幕尺寸可能变了（旋转 / 折叠屏展开） */
  onPageShow(): void {
    this.placeSearchBox();
  }

  /**
   * 把搜索框放到「从屏幕底部往上 61.8%」处（即从顶部往下 38.2%）。
   * 布局坐标是相对内容区（安全区）的，跟整屏差一个状态栏高度，
   * 所以拿窗口真实尺寸和避让区算一次，避免不同机型上偏。
   */
  private placeSearchBox(): void {
    const host: common.UIAbilityContext | undefined = this.hostContext();
    if (host === undefined) {
      return;
    }
    window.getLastWindow(host).then((win: window.Window) => {
      const fullHeightPx: number = win.getWindowProperties().windowRect.height;
      const topInsetPx: number =
        win.getWindowAvoidArea(window.AvoidAreaType.TYPE_SYSTEM).topRect.height;
      const targetPx: number = fullHeightPx * 0.382;
      this.searchTop = px2vp(Math.max(0, targetPx - topInsetPx));
      hilog.info(0, TAG, 'search box top=' + this.searchTop + 'vp, screen=' + fullHeightPx +
        'px, statusBar=' + topInsetPx + 'px');
    }).catch((error: BusinessError) => {
      hilog.warn(0, TAG, 'place search box failed: %{public}s', error.message);
    });
  }

  aboutToDisappear(): void {
    if (this.toastTimer >= 0) {
      clearTimeout(this.toastTimer);
      this.toastTimer = -1;
    }
  }

  /** 返回键 = 网页后退；面板打开时先关面板 */
  onBackPress(): boolean {
    if (this.showDownloads) {
      this.showDownloads = false;
      return true;
    }
    if (this.showPanel) {
      this.showPanel = false;
      return true;
    }
    if (this.urlBoxVisible && !this.activeIsBlank()) {
      this.urlBoxVisible = false;
      return true;
    }
    const ctrl: webview.WebviewController | undefined = this.ctrls.get(this.active);
    if (ctrl !== undefined) {
      try {
        if (ctrl.accessBackward()) {
          ctrl.backward();
          return true;
        }
      } catch (error) {
        hilog.warn(0, TAG, 'backward failed');
      }
    }
    return false;
  }

  build() {
    Stack({ alignContent: Alignment.Bottom }) {
      // 底色层：扩展到状态栏 / 导航栏底下。
      // 页面内容默认只排布在安全区内，安全区之外（底部导航栏那条）露出的
      // 是窗口默认底色 —— 不铺这一层就会看到一条白边（浅色主题下是黑边）。
      Column()
        .width('100%')
        .height('100%')
        .backgroundColor(this.bg())
        .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])

      Column() {
        // ---------- 顶部工具条：只剩按钮，地址/搜索框改成屏幕上的浮层 ----------
        Row() {
          // 竖向三点：点开标签页面板，长按直接新建标签页。
          // 用三个圆点堆出来而不是文字 '⋮'，避免字体里没有这个字形。
          Column({ space: 3 }) {
            Circle().width(3.5).height(3.5).fill(this.fg())
            Circle().width(3.5).height(3.5).fill(this.fg())
            Circle().width(3.5).height(3.5).fill(this.fg())
          }
          .padding({ left: 14, right: 14, top: 10, bottom: 10 })
          .onClick(() => {
            this.showPanel = !this.showPanel;
            this.showDownloads = false;
          })
          .gesture(LongPressGesture().onAction(() => {
            this.newTab('');
          }))

          Blank()

          // ● / ○ 同时表示当前主题：实心 = 纯黑，空心 = 浅色
          Text(this.darkOn ? '●' : '○')
            .fontSize(15)
            .fontColor(this.fg())
            .padding(10)
            .onClick(() => {
              this.toggleDark();
            })

          Text('→')
            .fontSize(15)
            .fontColor(this.fg())
            .padding(10)
            .onClick(() => {
              if (!this.urlBoxVisible) {
                const live: string | undefined = this.curUrls.get(this.active);
                this.urlText = (live === undefined || live === BLANK) ? '' : live;
                this.urlBoxVisible = true;
                return;
              }
              this.go(this.urlText);
            })
        }
        .width('100%')
        .backgroundColor(this.bg())

        // ---------- 网页层：所有标签页都活着，只切可见性 ----------
        Stack() {
          ForEach(this.tabs, (t: TabInfo) => {
            Web({ src: t.url, controller: this.ctrlOf(t.id) })
              .javaScriptAccess(true)
              .domStorageAccess(true)
              .fileAccess(false)
              .imageAccess(true)
              .onlineImageAccess(true)
              .mixedMode(MixedMode.All)
              .zoomAccess(true)
              .metaViewport(true)
              .horizontalScrollBarAccess(false)
              .verticalScrollBarAccess(false)
              .overScrollMode(OverScrollMode.NEVER)
              .darkMode(WebDarkMode.Off)
              .forceDarkAccess(false)
              .backgroundColor(this.bg())
              .visibility(t.id === this.active ? Visibility.Visible : Visibility.None)
              .onControllerAttached(() => {
                this.onAttached(t.id);
              })
              .onPageBegin((event: OnPageBeginEvent) => {
                const u: string = event.url;
                // about:blank / data: 是空白页的内部地址，不能当真实网址显示
                this.curUrls.set(t.id, (u === 'about:blank' || u.startsWith('data:')) ? BLANK : u);
                if (t.id === this.active) {
                  this.syncUrlBox();
                }
              })
              .onPageEnd((event: OnPageEndEvent) => {
                const u: string = event.url;
                const blank: boolean = u === 'about:blank' || u.startsWith('data:');
                this.curUrls.set(t.id, blank ? BLANK : u);
                if (t.id === this.active) {
                  this.urlText = blank ? '' : u;
                }
                this.paint(t.id);
              })
              .onTitleReceive((event: OnTitleReceiveEvent) => {
                this.setTitle(t.id, event.title);
              })
              .onLoadIntercept((event: OnLoadInterceptEvent) => {
                return this.handleLoadIntercept(event);
              })
              .onWindowNew((event: OnWindowNewEvent) => {
                // 没有第二个窗口，直接在当前标签页打开，否则弹窗被丢弃
                const target: string = event.targetUrl;
                const ctrl: webview.WebviewController | undefined = this.ctrls.get(t.id);
                if (ctrl !== undefined && target.length > 0 && !target.startsWith('about:')) {
                  try {
                    ctrl.loadUrl(target);
                  } catch (error) {
                    hilog.warn(0, TAG, 'window.open load failed');
                  }
                }
                try {
                  event.handler.setWebController(null);
                } catch (error) {
                  hilog.warn(0, TAG, 'setWebController(null) failed');
                }
              })
              .onErrorReceive((event: OnErrorReceiveEvent) => {
                if (!event.request.isMainFrame()) {
                  return;
                }
                const code: number = event.error.getErrorCode();
                if (code === 0 || code === -3) {
                  return;
                }
                this.toast('加载失败：' + event.error.getErrorInfo());
              })
              .onDownloadStart((event: OnDownloadStartEvent) => {
                // 兜底：万一 WebDownloadDelegate 的回调没生效，这里再走系统下载
                this.fallbackDownload(event, t.id);
              })
          }, (t: TabInfo) => t.id.toString())
        }
        .layoutWeight(1)
        .width('100%')
        .backgroundColor(this.bg())
      }
      .width('100%')
      .height('100%')
      .backgroundColor(this.bg())

      // ---------- 浮层搜索框 ----------
      // 位置：从屏幕底部往上 61.8%（= 从顶部往下 38.2%）的黄金分割点，左右居中。
      // 百分比是按内容区（安全区）算的，跟整屏差一个状态栏高度，所以这里用
      // 运行时算出来的 vp 值（见 placeSearchBox），任何机型都落在整屏 38.2% 处。
      Row() {
        TextInput({ text: this.urlText, placeholder: '搜索或输入网址' })
          .layoutWeight(1)
          .fontSize(14)
          .fontColor(this.fg())
          .placeholderColor(this.sub())
          .placeholderFont({ size: 14 })
          .backgroundColor(Color.Transparent)
          .borderRadius(0)
          .padding({ left: 18, right: 18 })
          .caretColor(this.fg())
          .enterKeyType(EnterKeyType.Go)
          .onChange((value: string) => {
            this.urlText = value;
          })
          .onSubmit(() => {
            this.go(this.urlText);
          })
      }
      .width('86%')
      .height(50)
      .backgroundColor(this.bg())
      .borderRadius(25)
      .borderWidth(1)
      .borderColor(this.line())
      .position({ x: '7%', y: this.searchTop })
      // 只在空白起始页常驻；浏览网页时收起，不再悬浮挡住页面内容
      .visibility(this.urlBoxVisible ? Visibility.Visible : Visibility.None)

      if (this.showPanel) {
        this.panelView()
      }
      if (this.showDownloads) {
        this.downloadView()
      }
      if (this.toastText.length > 0) {
        Text(this.toastText)
          .fontSize(13)
          .fontColor(this.fg())
          .backgroundColor(this.bg())
          .borderRadius(6)
          .padding({ left: 18, right: 18, top: 10, bottom: 10 })
          .margin({ bottom: 90 })
          .maxLines(3)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .constraintSize({ maxWidth: '88%' })
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.bg())
  }

  // ------------------------------------------------------------------ 标签页

  private ctrlOf(id: number): webview.WebviewController {
    let ctrl: webview.WebviewController | undefined = this.ctrls.get(id);
    if (ctrl === undefined) {
      ctrl = new webview.WebviewController();
      this.ctrls.set(id, ctrl);
    }
    return ctrl;
  }

  private newTab(url: string): void {
    const id: number = this.nextId++;
    this.ctrls.set(id, new webview.WebviewController());
    const start: string = url.length > 0 ? url : BLANK;
    const list: TabInfo[] = this.tabs.slice();
    list.push(new TabInfo(id, start, '新标签页'));
    this.tabs = list;
    this.curUrls.set(id, start);
    this.active = id;
    this.urlText = url;
    this.showPanel = false;
    this.syncUrlBox();
  }

  private selectTab(id: number): void {
    this.active = id;
    const live: string | undefined = this.curUrls.get(id);
    this.urlText = (live === undefined || live === BLANK) ? '' : live;
    this.showPanel = false;
    this.syncUrlBox();
  }

  private closeTab(id: number): void {
    const list: TabInfo[] = [];
    for (const t of this.tabs) {
      if (t.id !== id) {
        list.push(t);
      }
    }
    this.ctrls.delete(id);
    this.curUrls.delete(id);
    this.tabs = list;
    this.showPanel = false;
    if (list.length === 0) {
      this.newTab('');
      return;
    }
    if (this.active === id) {
      this.selectTab(list[list.length - 1].id);
    }
  }

  private setTitle(id: number, title: string): void {
    const text: string = title.trim();
    if (text.length === 0) {
      return;
    }
    let changed: boolean = false;
    const list: TabInfo[] = [];
    for (const t of this.tabs) {
      if (t.id === id && t.title !== text) {
        changed = true;
        list.push(new TabInfo(t.id, t.url, text));
      } else {
        list.push(t);
      }
    }
    if (changed) {
      this.tabs = list;
    }
  }

  // ------------------------------------------------------------------ 导航

  /** 当前活动标签页是否为空白起始页 */
  private activeIsBlank(): boolean {
    const live: string | undefined = this.curUrls.get(this.active);
    return live === undefined || live === BLANK;
  }

  /** 浮层搜索框跟随活动标签页：空白起始页显示，浏览网页收起 */
  private syncUrlBox(): void {
    this.urlBoxVisible = this.activeIsBlank();
  }

  private go(input: string): void {
    let url: string = input.trim();
    if (url.length === 0) {
      return;
    }
    if (!url.includes('://')) {
      const isHost: boolean = url.includes('.') && !url.includes(' ');
      url = isHost ? 'https://' + url : SEARCH + encodeURIComponent(url);
    }
    const ctrl: webview.WebviewController | undefined = this.ctrls.get(this.active);
    if (ctrl === undefined) {
      return;
    }
    try {
      ctrl.loadUrl(url);
      this.urlText = url;
      this.curUrls.set(this.active, url);
      this.syncUrlBox();
    } catch (error) {
      const err = error as BusinessError;
      this.toast('加载失败：' + err.message);
    }
  }

  /** 非 http(s) 链接甩给系统；intent 类交给内核自己处理 */
  private handleLoadIntercept(event: OnLoadInterceptEvent): boolean {
    const url: string = event.data.getRequestUrl();
    if (url.length === 0) {
      return false;
    }
    if (url.startsWith('http://') || url.startsWith('https://') || url.startsWith('file://') ||
      url.startsWith('data:') || url.startsWith('blob:') || url.startsWith('about:') ||
      url.startsWith('javascript:')) {
      return false;
    }
    const scheme: string = schemeOf(url);
    if (EXTERNAL_SCHEMES.includes(scheme)) {
      const host: common.UIAbilityContext | undefined = this.hostContext();
      if (host !== undefined) {
        const want: Want = { action: 'ohos.want.action.viewData', uri: url };
        host.startAbility(want).then(() => {
          hilog.info(0, TAG, 'opened external %{public}s', url);
        }).catch((error: BusinessError) => {
          this.toast('没有可以打开该链接的应用');
          hilog.warn(0, TAG, 'open external failed: %{public}s', error.message);
        });
      }
      return true;
    }
    return false;
  }

  // ------------------------------------------------------------------ 主题

  /**
   * 一个 @State darkOn 同时决定两件事：
   * 开 = 纯黑主题（App 界面全黑 + 网页反相成黑），关 = 浅色主题（白底黑字 + 网页保持原色）。
   * 下面几个方法在 build() 里被读到，darkOn 一变就会重算并刷新相关组件。
   */
  private bg(): string {
    return this.darkOn ? BLACK : WHITE;
  }

  private fg(): string {
    return this.darkOn ? FG : FG_LIGHT;
  }

  /** 次要文字：提示、占位符 */
  private sub(): string {
    return this.darkOn ? DIM : DIM_LIGHT;
  }

  /** 搜索框描边：比底色深一档 */
  private line(): string {
    return this.darkOn ? '#FF7A7A7A' : LINE_LIGHT;
  }

  /** 系统栏 / 窗口底色的文字色 */
  private barFg(): string {
    return this.darkOn ? '#FFFFFFFF' : '#FF000000';
  }

  /**
   * 空白新标签页的内联 HTML。它的底必须跟着主题走 —— 空白页不注入反相，
   * 底色就是它看到的全部，写死纯黑的话切到浅色主题时这一页会明显"没反应"。
   */
  private blankHtml(): string {
    const bg: string = this.darkOn ? '#000' : '#fff';
    return '<html style="background:' + bg + '">' +
      '<head><meta name="viewport" content="width=device-width,initial-scale=1"></head>' +
      '<body style="background:' + bg + ';margin:0;height:100%"></body></html>';
  }

  // ------------------------------------------------------------------ 强制纯黑

  /** 黑白主题切换：App 界面 + 系统栏 + 空白页底色 + 网页侧强制纯黑，一起切 */
  private toggleDark(): void {
    this.darkOn = !this.darkOn;
    for (const t of this.tabs) {
      const ctrl: webview.WebviewController | undefined = this.ctrls.get(t.id);
      if (ctrl === undefined) {
        continue;
      }
      const live: string | undefined = this.curUrls.get(t.id);
      if (live === undefined || live === BLANK) {
        // 空白页没有内容可反相，重灌一份跟着主题走的空白 HTML 就够了
        try {
          ctrl.loadData(this.blankHtml(), 'text/html', 'UTF-8');
        } catch (error) {
          hilog.warn(0, TAG, 'repaint blank tab %{public}d failed', t.id);
        }
        continue;
      }
      // 注入 / 摘除同一份脚本，不整页 reload —— 保留滚动位置和表单状态
      try {
        ctrl.runJavaScript(this.darkOn ? JS_DARK : JS_DARK_OFF);
      } catch (error) {
        hilog.warn(0, TAG, 'toggle dark failed for tab %{public}d', t.id);
      }
    }
    this.syncSystemBars();
  }

  /** 状态栏 / 导航栏 / 窗口底色跟着主题走 —— 它们在 EntryAbility 里只在启动时设过一次 */
  private syncSystemBars(): void {
    const host: common.UIAbilityContext | undefined = this.hostContext();
    if (host === undefined) {
      return;
    }
    window.getLastWindow(host).then((win: window.Window) => {
      win.setWindowBackgroundColor(this.bg());
      return win.setWindowSystemBarProperties({
        statusBarColor: this.bg(),
        statusBarContentColor: this.barFg(),
        navigationBarColor: this.bg(),
        navigationBarContentColor: this.barFg()
      });
    }).catch((error: BusinessError) => {
      hilog.warn(0, TAG, 'sync system bars failed: %{public}s', error.message);
    });
  }

  private paint(id: number): void {
    if (!this.darkOn) {
      return;
    }
    const live: string | undefined = this.curUrls.get(id);
    // 空白页没有内容可反相，注入了反而把纯黑底翻成白色
    if (live === undefined || live === BLANK) {
      return;
    }
    const ctrl: webview.WebviewController | undefined = this.ctrls.get(id);
    if (ctrl === undefined) {
      return;
    }
    try {
      ctrl.runJavaScript(JS_DARK);
    } catch (error) {
      hilog.warn(0, TAG, 'inject dark failed');
    }
  }

  // ------------------------------------------------------------------ 下载

  private onAttached(id: number): void {
    const ctrl: webview.WebviewController | undefined = this.ctrls.get(id);
    if (ctrl === undefined) {
      return;
    }
    // 空白标签页：about:blank 是白底，换成自带主题背景的内联 HTML
    const live: string | undefined = this.curUrls.get(id);
    if (live === undefined || live === BLANK) {
      try {
        ctrl.loadData(this.blankHtml(), 'text/html', 'UTF-8');
        this.curUrls.set(id, BLANK);
      } catch (error) {
        hilog.warn(0, TAG, 'load blank html failed');
      }
    }
    try {
      ctrl.setDownloadDelegate(this.delegate());
    } catch (error) {
      const err = error as BusinessError;
      hilog.warn(0, TAG, 'setDownloadDelegate failed: %{public}s', err.message);
    }
  }

  /**
   * 下载托管给 WebDownloadDelegate：请求由内核发出，
   * Cookie / UA / Referer 天然带上，不需要像 Android 版那样手工拼请求头。
   */
  private delegate(): webview.WebDownloadDelegate {
    if (this.dlDelegate === undefined) {
      const d: webview.WebDownloadDelegate = new webview.WebDownloadDelegate();
      d.onBeforeDownload((item: webview.WebDownloadItem) => {
        this.beforeDownload(item);
      });
      d.onDownloadFinish((item: webview.WebDownloadItem) => {
        this.finishDownload(item);
      });
      d.onDownloadFailed((item: webview.WebDownloadItem) => {
        this.toast('下载失败：' + item.getSuggestedFileName());
      });
      this.dlDelegate = d;
    }
    return this.dlDelegate;
  }

  private beforeDownload(item: webview.WebDownloadItem): void {
    const name: string = this.fileNameOf(item);
    const host: common.UIAbilityContext | undefined = this.hostContext();
    if (host === undefined) {
      item.cancel();
      return;
    }
    const dir: string = host.cacheDir + '/download';
    const path: string = dir + '/' + name;
    try {
      fs.mkdirSync(dir, true);
      // 必须在 onBeforeDownload 里调用 start，否则任务停在 PENDING
      item.start(path);
      this.dlPaths.set(item.getGuid(), path);
      this.dlHandled.set(item.getUrl(), Date.now());
      this.addDownload(name, false);
      this.toast('↓ ' + name);
    } catch (error) {
      const err = error as BusinessError;
      item.cancel();
      this.toast('下载失败：' + err.message);
      hilog.warn(0, TAG, 'start download failed: %{public}s', err.message);
    }
  }

  private finishDownload(item: webview.WebDownloadItem): void {
    const name: string = this.fileNameOf(item);
    this.markDownload(name);
    const path: string | undefined = this.dlPaths.get(item.getGuid());
    if (path === undefined) {
      return;
    }
    this.saveToUser(path, name);
  }

  /** 系统文件保存框：让用户自己选落盘位置（沙箱里的文件用户看不见） */
  private saveToUser(srcPath: string, name: string): void {
    const host: common.UIAbilityContext | undefined = this.hostContext();
    if (host === undefined) {
      return;
    }
    const dp: picker.DocumentViewPicker = new picker.DocumentViewPicker(host);
    const options: picker.DocumentSaveOptions = new picker.DocumentSaveOptions();
    options.newFileNames = [name];
    dp.save(options).then((uris: string[]) => {
      if (uris.length === 0) {
        return;
      }
      const file = fs.openSync(uris[0], fs.OpenMode.READ_WRITE | fs.OpenMode.TRUNC);
      fs.copyFileSync(srcPath, file.fd);
      fs.closeSync(file);
      this.toast('已保存：' + name);
    }).catch((error: BusinessError) => {
      hilog.warn(0, TAG, 'save cancelled or failed: %{public}s', error.message);
    });
  }

  private fileNameOf(item: webview.WebDownloadItem): string {
    let name: string = item.getSuggestedFileName();
    if (name.length === 0) {
      const url: string = item.getUrl();
      const cut: number = url.lastIndexOf('/');
      name = cut >= 0 ? url.substring(cut + 1) : url;
    }
    name = name.split('/').join('_').split('\\').join('_');
    const q: number = name.indexOf('?');
    if (q >= 0) {
      name = name.substring(0, q);
    }
    if (name.length === 0) {
      name = 'download';
    }
    return name;
  }

  private addDownload(name: string, done: boolean): void {
    const list: DownloadRec[] = this.downloads.slice();
    list.push(new DownloadRec(name, done));
    this.downloads = list;
  }

  private markDownload(name: string): void {
    const list: DownloadRec[] = [];
    for (const r of this.downloads) {
      list.push(r.name === name ? new DownloadRec(r.name, true) : r);
    }
    this.downloads = list;
  }

  /**
   * 兜底下载：等 1.5s，如果内核的下载委托没有接手这个 URL，就自己发起系统下载。
   * 请求头按 Android 版那套补齐 —— 论坛附件、网盘直链缺了 UA / Cookie / Referer 多半 403。
   */
  private fallbackDownload(event: OnDownloadStartEvent, tabId: number): void {
    const url: string = event.url;
    setTimeout(() => {
      const seen: number | undefined = this.dlHandled.get(url);
      if (seen !== undefined) {
        hilog.info(0, TAG, 'download handled by delegate: %{public}s', url);
        return;
      }
      const host: common.UIAbilityContext | undefined = this.hostContext();
      if (host === undefined) {
        return;
      }
      hilog.info(0, TAG, 'download via request.downloadFile: %{public}s', url);
      const name: string = this.nameFromDisposition(event.contentDisposition, url);
      const dir: string = host.cacheDir + '/download';
      const path: string = dir + '/' + name;
      try {
        fs.mkdirSync(dir, true);
      } catch (error) {
        hilog.warn(0, TAG, 'mkdir download dir failed');
      }
      const referer: string | undefined = this.curUrls.get(tabId);
      const config: request.DownloadConfig = {
        url: url,
        filePath: path,
        header: this.headersFor(url, event.userAgent, referer === undefined ? '' : referer),
        enableMetered: true,
        enableRoaming: true,
        title: name,
        description: 'Min'
      };
      request.downloadFile(host, config).then((task: request.DownloadTask) => {
        this.dlHandled.set(url, Date.now());
        this.addDownload(name, false);
        this.toast('↓ ' + name);
        task.on('complete', () => {
          this.markDownload(name);
          this.saveToUser(path, name);
        });
        task.on('fail', (err: number) => {
          this.toast('下载失败（' + err + '）');
        });
      }).catch((error: BusinessError) => {
        this.toast('下载失败：' + error.message);
      });
    }, 1500);
  }

  private headersFor(url: string, userAgent: string, referer: string): Record<string, string> {
    const header: Record<string, string> = {};
    if (userAgent.length > 0) {
      header['User-Agent'] = userAgent;
    }
    if (referer.startsWith('http')) {
      header['Referer'] = referer;
    }
    try {
      const cookie: string = webview.WebCookieManager.fetchCookieSync(url);
      if (cookie.length > 0) {
        header['Cookie'] = cookie;
      }
    } catch (error) {
      hilog.warn(0, TAG, 'read cookie failed');
    }
    return header;
  }

  /** 从 Content-Disposition 抠文件名，抠不到就用 URL 末段 */
  private nameFromDisposition(disposition: string, url: string): string {
    let name: string = '';
    const marker: string = 'filename=';
    const at: number = disposition.toLowerCase().indexOf(marker);
    if (at >= 0) {
      name = disposition.substring(at + marker.length).trim();
      if (name.startsWith('"')) {
        name = name.substring(1);
      }
      const end: number = name.indexOf('"');
      if (end >= 0) {
        name = name.substring(0, end);
      }
      const semi: number = name.indexOf(';');
      if (semi >= 0) {
        name = name.substring(0, semi);
      }
    }
    if (name.length === 0) {
      const cut: number = url.lastIndexOf('/');
      name = cut >= 0 ? url.substring(cut + 1) : url;
    }
    name = name.split('/').join('_').split('\\').join('_');
    const q: number = name.indexOf('?');
    if (q >= 0) {
      name = name.substring(0, q);
    }
    if (name.length === 0) {
      name = 'download';
    }
    return name;
  }

  // ------------------------------------------------------------------ 面板

  @Builder
  panelView() {
    Column() {
      Text('标签页  ' + this.tabs.length)
        .fontSize(12)
        .fontColor(this.sub())
        .width('100%')
        .padding({ left: 24, right: 24, top: 8, bottom: 12 })

      ForEach(this.tabs, (t: TabInfo) => {
        Text((t.id === this.active ? '●  ' : '○  ') + this.ellipsis(t.title))
          .fontSize(15)
          .fontColor(t.id === this.active ? this.fg() : '#FF9A9A9A')
          .width('100%')
          .padding(16)
          .onClick(() => {
            this.selectTab(t.id);
          })
          .gesture(LongPressGesture().onAction(() => {
            this.closeTab(t.id);
          }))
      }, (t: TabInfo) => t.id.toString())

      Text('＋  新建标签页')
        .fontSize(15)
        .fontColor(this.fg())
        .width('100%')
        .padding(16)
        .onClick(() => {
          this.newTab('');
        })

      Text('↓  下载内容（' + this.downloads.length + '）')
        .fontSize(15)
        .fontColor(this.fg())
        .width('100%')
        .padding(16)
        .onClick(() => {
          this.showPanel = false;
          this.showDownloads = true;
        })

      Text('✕  关闭当前标签页')
        .fontSize(15)
        .fontColor('#FF9A9A9A')
        .width('100%')
        .padding(16)
        .onClick(() => {
          this.closeTab(this.active);
        })

      Text('长按列表项可直接关闭该标签页')
        .fontSize(11)
        .fontColor(this.sub())
        .width('100%')
        .padding({ left: 24, right: 24, top: 12, bottom: 4 })
    }
    .width('88%')
    .backgroundColor(this.bg())
    .padding(8)
    .borderRadius(12)
    // 定位到左上角三个点按钮的正下方：Stack 底部对齐的默认布局改成绝对定位
    .position({ x: '6%', y: 52 })
  }

  @Builder
  downloadView() {
    Column() {
      Text('下载内容  ' + this.downloads.length)
        .fontSize(12)
        .fontColor(this.sub())
        .width('100%')
        .padding({ left: 24, right: 24, top: 8, bottom: 12 })

      if (this.downloads.length === 0) {
        Text('还没有下载')
          .fontSize(15)
          .fontColor('#FF9A9A9A')
          .width('100%')
          .padding(16)
      }

      ForEach(this.downloads, (r: DownloadRec) => {
        Text((r.done ? '✓  ' : '↓  ') + this.ellipsis(r.name))
          .fontSize(15)
          .fontColor(r.done ? this.fg() : '#FF9A9A9A')
          .width('100%')
          .padding(16)
          .onClick(() => {
            if (r.done) {
              this.saveToUser(this.sandboxPathOf(r.name), r.name);
            }
          })
      }, (r: DownloadRec) => r.name + (r.done ? '1' : '0'))

      Text('✓ 的项点一下可以再另存到本地')
        .fontSize(11)
        .fontColor(this.sub())
        .width('100%')
        .padding({ left: 24, right: 24, top: 12, bottom: 4 })
    }
    .width('88%')
    .backgroundColor(this.bg())
    .padding(8)
    .borderRadius(12)
    .margin({ bottom: 120 })
  }

  private sandboxPathOf(name: string): string {
    for (const entry of this.dlPaths.values()) {
      if (entry.endsWith('/' + name)) {
        return entry;
      }
    }
    return '';
  }

  private ellipsis(text: string): string {
    return text.length > 26 ? text.substring(0, 26) + '…' : text;
  }

  // ------------------------------------------------------------------ 杂项

  private hostContext(): common.UIAbilityContext | undefined {
    const host = this.getUIContext().getHostContext();
    if (host === undefined) {
      return undefined;
    }
    return host as common.UIAbilityContext;
  }

  /** 纯黑 toast：不用系统那套深灰胶囊 */
  private toast(message: string): void {
    this.toastText = message;
    if (this.toastTimer >= 0) {
      clearTimeout(this.toastTimer);
    }
    this.toastTimer = setTimeout(() => {
      this.toastText = '';
      this.toastTimer = -1;
    }, 2000);
  }
}
