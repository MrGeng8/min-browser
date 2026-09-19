package com.blk.min;

import android.app.Activity;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * Min —— 极简浏览器（多标签页版）
 * 约束：
 *  1) 纯黑 #FF000000（AMOLED 真黑），UI 与页面强制生效
 *  2) 无 AndroidX、无 layout 资源，dex 极小
 *  3) 可作 http/https 默认处理器
 *  4) 搜索引擎用 Bing（cn.bing.com，国内直连）
 *  5) 下载交给系统 DownloadManager，不自己写 IO
 */
public class Main extends Activity {

    static final int BLACK = 0xFF000000;
    static final int FG    = 0xFFEDEDED;
    static final int DIM   = 0xFF5A5A5A;
    /** 浮层搜索框的描边色，和纯黑背景形成反差 */
    static final int BORDER = 0xFF7A7A7A;
    /** 浅色主题：白底黑字，描边 / 次要文字都比底色深一档才看得出来 */
    static final int WHITE = 0xFFFFFFFF;
    static final int FG_LIGHT = 0xFF000000;
    static final int DIM_LIGHT = 0xFF8A8A8A;
    static final int LINE_LIGHT = 0xFFBFBFBF;

    /** 国内可直连的 Bing；原来的 duckduckgo 在国内不可达 */
    static final String SEARCH = "https://cn.bing.com/search?q=";

    /** 运行时申请存储权限的请求码（仅 Android 6~9 需要） */
    static final int REQ_WRITE = 1;

    /**
     * WebView 自己能处理的 scheme，放行；其余甩给系统。
     * intent: 必须放行 —— WebView 内部会解析 intent:// 并拉起对应 App，
     * 用 ACTION_VIEW 硬传会解析失败（需要 Intent.parseUri）。
     */
    static final String[] OWN_SCHEMES = {
        "http:", "https:", "file:", "content:", "data:", "blob:", "about:",
        "javascript:", "intent:"
    };

    /**
     * 强制纯黑。
     * 先测页面自身底色亮度：本来就是深色的页面不再反相（反了会变白底黑字，跟主题颠倒）。
     * 但 Bing 这类站点加载中背景色会"黑→白"闪烁，单次采样会误判，
     * 所以用 __blkDark 连续计数：连续两次深色才放弃，任何一次浅色立即注入。
     * filter 只能加在 body 上，html 背景单独写死 #000 —— 若加在 html 上，
     * Chromium 的 canvas 底色不参与过滤，大片浅色底不会被反相。
     */
    static final String JS_DARK =
        "(function(){" +
        "var id='__blk';" +
        "var bg='';" +
        // 注意：反相样式会把 html 背景改成 #000，读 html 会读到自己造成的黑色，
        // 形成注入/撤销来回打摆 —— 所以优先读 body 背景做原生底色判定
        "try{bg=getComputedStyle(document.body).backgroundColor;}catch(e){}" +
        "if(!bg||bg.indexOf('rgba(0, 0, 0, 0)')===0||bg==='transparent'){" +
        "try{bg=getComputedStyle(document.documentElement).backgroundColor;}catch(e){}}" +
        "var m=bg.match(/[\\d.]+/g),lum=255;" +
        "if(m&&m.length>=3){" +
        "var a=m.length>3?parseFloat(m[3]):1;" +
        "if(a>0.5)lum=0.299*m[0]+0.587*m[1]+0.114*m[2];}" +
        "if(lum<110){" +
        "window.__blkDark=(window.__blkDark||0)+1;" +
        "if(window.__blkDark>=2){var s0=document.getElementById(id);if(s0)s0.remove();" +
        "return 'skip-native-dark(x'+window.__blkDark+',lum='+lum+')';}" +
        "return 'maybe-dark(x'+window.__blkDark+',lum='+lum+')';}" +
        "window.__blkDark=0;" +
        "var s=document.getElementById(id);" +
        "if(!s){s=document.createElement('style');s.id=id;" +
        "(document.head||document.documentElement).appendChild(s);}" +
        "s.textContent=" +
        "'html{background:#000!important;}'+" +
        "'body{filter:invert(1) hue-rotate(180deg)!important;}'+" +
        "'img,video,picture,canvas,svg,iframe,embed,object,input[type=image]" +
        "{filter:invert(1) hue-rotate(180deg)!important;}';" +
        "return 'ok(lum='+lum+')';})()";

    /** 关闭强制纯黑：把注入的 style 摘掉，不用整页 reload（保留滚动位置与表单状态） */
    static final String JS_DARK_OFF =
        "(function(){var s=document.getElementById('__blk');if(s){s.remove();}return 'off';})()";

    static class Tab {
        WebView wv;
        String url;
        String title;
        Tab(WebView v, String u, String t) { wv = v; url = u; title = t; }
    }

    final ArrayList<Tab> tabs = new ArrayList<Tab>();
    /** 顶部三点按钮的三个圆点，切主题时要重刷颜色 */
    final ArrayList<View> dots = new ArrayList<View>();
    FrameLayout stage;
    EditText url;
    TextView darkBtn;
    View tabBtn;
    TextView goBtn;
    FrameLayout root;
    LinearLayout col;
    LinearLayout bar;
    LinearLayout searchBox;
    GradientDrawable boxBg;
    boolean forceDark = true;
    /** 浮层搜索框只在空白起始页常驻；浏览网页时收起，点「→」可唤出 */
    boolean urlBoxVisible = true;
    int cur = -1;
    float d;
    int pad;
    /** 等待存储权限的下载请求：{url, userAgent, contentDisposition, mime, referer} */
    String[] pend;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(BLACK));
        w.setStatusBarColor(BLACK);
        w.setNavigationBarColor(BLACK);
        w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        d = getResources().getDisplayMetrics().density;
        pad = (int) (5 * d);

        // ---------- 根容器：FrameLayout，方便把搜索框做成浮层 ----------
        root = new FrameLayout(this);
        root.setBackgroundColor(bg());

        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(bg());

        // ---------- 顶部工具条: [⋮] [●] [→]（地址框挪到浮层了）----------
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(bg());
        bar.setPadding(pad, pad, pad, pad);

        // 竖向三点：点开标签页面板，长按直接新建标签页。
        // 用三个圆点画出来，不依赖字体里的 '⋮' 字形。
        tabBtn = mkDots();
        bar.addView(tabBtn);

        // 占位撑开，把 ● 和 → 顶到右边
        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        darkBtn = mkBtn("●");
        bar.addView(darkBtn);
        goBtn = mkBtn("→");
        bar.addView(goBtn);

        col.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        stage = new FrameLayout(this);
        stage.setBackgroundColor(bg());
        col.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---------- 浮层搜索框 ----------
        // 位置：从屏幕底部往上 61.8%（= 顶部往下 38.2%），左右居中；
        // 边框用中灰 #7A7A7A，与纯黑背景形成反差。
        url = new EditText(this);
        url.setSingleLine(true);
        url.setHint("搜索或输入网址");
        url.setHintTextColor(sub());
        url.setTextColor(fg());
        url.setTextSize(14);
        url.setBackground(null);
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        url.setImeOptions(EditorInfo.IME_ACTION_GO);
        url.setSelectAllOnFocus(true);

        searchBox = new LinearLayout(this);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        boxBg = new GradientDrawable();
        boxBg.setShape(GradientDrawable.RECTANGLE);
        boxBg.setColor(bg());
        boxBg.setCornerRadius(25 * d);
        boxBg.setStroke((int) Math.max(1, d), line());
        searchBox.setBackground(boxBg);
        searchBox.setPadding((int) (13 * d), 0, (int) (13 * d), 0);
        searchBox.addView(url, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // 整屏高度（含系统栏），减去状态栏高度，换算到内容区的坐标
        DisplayMetrics real = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(real);
        int statusBar = 0;
        int sbId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (sbId > 0) statusBar = getResources().getDimensionPixelSize(sbId);
        int topMargin = (int) (real.heightPixels * 0.382f) - statusBar;

        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                (int) (real.widthPixels * 0.86f), (int) (50 * d));
        slp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        slp.topMargin = Math.max(0, topMargin);
        root.addView(searchBox, slp);

        setContentView(root);

        // ---------- 交互 ----------
        goBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 收起状态下的「→」= 唤出地址框并预填当前网址；显示状态下才是提交
                if (!urlBoxVisible) {
                    Tab t = (cur >= 0 && cur < tabs.size()) ? tabs.get(cur) : null;
                    url.setText(t == null || t.url == null ? "" : t.url);
                    setUrlBoxVisible(true);
                    url.requestFocus();
                    return;
                }
                go(url.getText().toString());
            }
        });
        url.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int a, KeyEvent e) {
                go(v.getText().toString());
                return true;
            }
        });
        darkBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleTheme(); }
        });
        tabBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTabs(); }
        });
        // 长按标签数 = 直接新建标签页，省一次点击
        tabBtn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { newTab(null); return true; }
        });
        syncDarkBtn();

        route(getIntent());
    }

    @Override
    protected void onNewIntent(Intent it) {
        super.onNewIntent(it);
        setIntent(it);
        route(it);
    }

    @Override
    protected void onDestroy() {
        for (int i = 0; i < tabs.size(); i++) {
            tabs.get(i).wv.destroy();
        }
        tabs.clear();
        super.onDestroy();
    }

    // ================= 主题 =================

    /**
     * 一个 forceDark 同时决定两件事：
     * 开 = 纯黑主题（App 界面全黑 + 网页反相成黑），关 = 浅色主题（白底黑字 + 网页保持原色）。
     * Android 没有 ArkUI 那种 @State 自动重算，所以切主题后要 applyTheme() 手动刷一遍控件。
     */
    int bg() { return forceDark ? BLACK : WHITE; }
    int fg() { return forceDark ? FG : FG_LIGHT; }
    /** 次要文字：提示、占位符 */
    int sub() { return forceDark ? DIM : DIM_LIGHT; }
    /** 搜索框描边：比底色深一档 */
    int line() { return forceDark ? BORDER : LINE_LIGHT; }

    /**
     * 空白新标签页的内联 HTML。它的底必须跟着主题走 —— 空白页不注入反相，
     * 底色就是它看到的全部，写死纯黑的话切到浅色主题时这一页会明显"没反应"。
     */
    String blankHtml() {
        String bg = forceDark ? "#000" : "#fff";
        return "<html style=\"background:" + bg + "\"><head>" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<style>html,body{background:" + bg + ";margin:0;height:100%}</style></head>" +
            "<body></body></html>";
    }

    /** 切换主题：重刷 App 界面配色 + 系统栏，网页层注入 / 摘除反相脚本 */
    void toggleTheme() {
        forceDark = !forceDark;
        applyTheme();
        repaintAllTabs();
    }

    /** 把主题配色重新应用到每个控件上 */
    void applyTheme() {
        root.setBackgroundColor(bg());
        col.setBackgroundColor(bg());
        bar.setBackgroundColor(bg());
        stage.setBackgroundColor(bg());
        goBtn.setTextColor(fg());
        syncDarkBtn();
        url.setTextColor(fg());
        url.setHintTextColor(sub());
        boxBg.setColor(bg());
        boxBg.setStroke((int) Math.max(1, d), line());
        searchBox.setBackground(boxBg);
        for (int i = 0; i < dots.size(); i++) {
            GradientDrawable c = new GradientDrawable();
            c.setShape(GradientDrawable.OVAL);
            c.setColor(fg());
            dots.get(i).setBackground(c);
        }
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(bg()));
        w.setStatusBarColor(bg());
        w.setNavigationBarColor(bg());
        // 浅色主题下状态栏 / 导航栏是白底，系统图标必须切成深色才看得见（该 flag 是 API 23+）
        if (Build.VERSION.SDK_INT >= 23) {
            View decor = w.getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (forceDark) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    /** 主题切换后重刷所有标签页：纯黑注入反相，浅色摘除；空白页重灌主题背景 */
    void repaintAllTabs() {
        for (int i = 0; i < tabs.size(); i++) {
            Tab t = tabs.get(i);
            if (t.url == null || t.url.length() == 0) {
                t.wv.loadDataWithBaseURL(null, blankHtml(), "text/html", "utf-8", null);
                continue;
            }
            // 注入 / 摘除同一份脚本，不整页 reload —— 保留滚动位置和表单状态
            t.wv.evaluateJavascript(forceDark ? JS_DARK : JS_DARK_OFF, null);
        }
    }

    // ================= 标签页 =================

    WebView makeWV() {
        // 让 WebView 始终认为处于浅色模式：否则系统夜间模式（或跟随系统的站点）会直接输出深色页，
        // 与反相脚本叠加后就变成「App 黑、网页白」的颠倒效果
        Configuration wc = new Configuration(getResources().getConfiguration());
        wc.uiMode = (wc.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | Configuration.UI_MODE_NIGHT_NO;
        WebView v = new WebView(createConfigurationContext(wc));
        v.setBackgroundColor(bg());
        WebSettings s = v.getSettings();
        if (Build.VERSION.SDK_INT >= 33) {
            s.setAlgorithmicDarkeningAllowed(false);   // 关闭系统自动加深，颜色策略完全交给本应用
        }
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        v.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView w, String u) {
                if (u == null) return false;
                for (int i = 0; i < OWN_SCHEMES.length; i++) {
                    if (u.startsWith(OWN_SCHEMES[i])) return false;
                }
                // tel: mailto: market: weixin: 之类甩给系统；否则点了没反应
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
                } catch (Exception e) {
                    toast("无法打开：" + u);
                }
                return true;
            }
            @Override public void onPageFinished(WebView w, String u) {
                Tab t = tabOf(w);
                // about:blank 是空白页的内部地址，不能当成真实网址显示
                if (t != null && u != null && !u.startsWith("about:")) {
                    t.url = u;
                    if (cur >= 0 && tabs.get(cur) == t) url.setText(u);
                }
                // 页面加载完重新判定一次：导航到网页后地址框自动收起
                if (t != null && cur >= 0 && tabs.get(cur) == t) syncUrlBox();
                paint(w);
            }
        });
        v.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView w, int p) {
                if (p == 100) paint(w);
            }
            @Override public void onReceivedTitle(WebView w, String title) {
                Tab t = tabOf(w);
                if (t == null || title == null) return;
                String tt = title.trim();
                // 无 <title> 的空白页会被 Chromium 用网址当标题，需过滤
                if (tt.length() == 0 || tt.startsWith("about:")) return;
                t.title = tt;
            }
        });
        // 没有 DownloadListener 时，附件类链接会被 WebView 静默丢弃（点了没反应）
        v.setDownloadListener(new DownloadListener() {
            @Override public void onDownloadStart(String u, String ua, String cd, String mime, long len) {
                startDownload(u, ua, cd, mime, v.getUrl());
            }
        });
        return v;
    }

    Tab tabOf(WebView w) {
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).wv == w) return tabs.get(i);
        }
        return null;
    }

    void newTab(String u) {
        WebView v = makeWV();
        Tab t = new Tab(v, "", "新标签页");
        tabs.add(t);
        stage.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        v.setVisibility(View.GONE);
        selectTab(tabs.size() - 1);
        if (u != null) {
            url.setText(u);
            go(u);
        } else {
            v.loadDataWithBaseURL(null, blankHtml(), "text/html", "utf-8", null);
        }
    }

    void selectTab(int i) {
        if (i < 0 || i >= tabs.size()) return;
        for (int k = 0; k < tabs.size(); k++) {
            Tab t = tabs.get(k);
            if (k == i) {
                t.wv.setVisibility(View.VISIBLE);
                t.wv.onResume();          // 恢复前台标签页
            } else {
                t.wv.setVisibility(View.GONE);
                t.wv.onPause();           // 后台标签页暂停，省电
            }
        }
        cur = i;
        url.setText(tabs.get(i).url == null ? "" : tabs.get(i).url);
        syncTabBtn();
        syncUrlBox();
    }

    /** 当前活动标签页是否为空白起始页（空白页的 url 保持空串）*/
    boolean activeIsBlank() {
        if (cur < 0 || cur >= tabs.size()) return true;
        String u = tabs.get(cur).url;
        return u == null || u.length() == 0;
    }

    /** 浮层搜索框跟随活动标签页：空白起始页显示，浏览网页收起 */
    void syncUrlBox() { setUrlBoxVisible(activeIsBlank()); }

    void setUrlBoxVisible(boolean on) {
        if (urlBoxVisible == on) return;
        urlBoxVisible = on;
        searchBox.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) {
            // 收起时同时收键盘，否则输入法会悬在网页上无处可去
            url.clearFocus();
            InputMethodManager im =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null) im.hideSoftInputFromWindow(url.getWindowToken(), 0);
        }
    }

    void closeTab(int i) {
        if (i < 0 || i >= tabs.size()) return;
        Tab t = tabs.remove(i);
        stage.removeView(t.wv);
        t.wv.destroy();
        if (tabs.isEmpty()) {
            cur = -1;
            newTab(null);
            return;
        }
        selectTab(Math.min(i, tabs.size() - 1));
    }

    /** 顶部那个竖向三点按钮：可访问性描述里带上标签数，界面上的数字在标签面板里 */
    void syncTabBtn() { tabBtn.setContentDescription("标签页 " + tabs.size()); }

    /** 竖向三点按钮：竖排三个小圆点，避免依赖字体里的 '⋮' 字形 */
    View mkDots() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int dot = (int) (3.5 * d);
        for (int i = 0; i < 3; i++) {
            View v = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dot, dot);
            if (i > 0) lp.topMargin = (int) (3 * d);
            v.setLayoutParams(lp);
            GradientDrawable c = new GradientDrawable();
            c.setShape(GradientDrawable.OVAL);
            c.setColor(fg());
            v.setBackground(c);
            box.addView(v);
            dots.add(v);
        }
        box.setPadding((int) (14 * d), (int) (12 * d), (int) (14 * d), (int) (12 * d));
        return box;
    }

    /** ● / ○ 同时表示当前主题：实心 = 纯黑，空心 = 浅色 */
    void syncDarkBtn() {
        darkBtn.setText(forceDark ? "●" : "○");
        darkBtn.setTextColor(fg());
    }

    TextView mkBtn(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(15);
        tv.setTextColor(fg());
        int p = (int) (8 * d);
        tv.setPadding(p, p, p, p);
        return tv;
    }

    /** 标签页面板：纯黑自绘 Dialog，不用系统 AlertDialog（后者是深灰，不符合纯黑要求） */
    void showTabs() {
        final Dialog dlg = new Dialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(bg());
        box.setPadding(pad * 2, pad * 2, pad * 2, pad * 2);

        TextView head = row("标签页  " + tabs.size(), sub(), 12);
        head.setPadding(pad * 2, pad, pad * 2, pad * 2);
        box.addView(head);

        for (int i = 0; i < tabs.size(); i++) {
            final int idx = i;
            Tab t = tabs.get(i);
            String title = (t.title == null || t.title.length() == 0) ? "新标签页" : t.title;
            if (title.length() > 26) title = title.substring(0, 26) + "…";

            TextView tv = row((i == cur ? "●  " : "○  ") + title,
                    i == cur ? fg() : sub(), 15);
            tv.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { selectTab(idx); dlg.dismiss(); }
            });
            tv.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { closeTab(idx); dlg.dismiss(); return true; }
            });
            box.addView(tv);
        }

        TextView add = row("＋  新建标签页", fg(), 15);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { newTab(null); dlg.dismiss(); }
        });
        box.addView(add);

        TextView dl = row("↓  下载内容", fg(), 15);
        dl.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dlg.dismiss();
                try {
                    startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
                } catch (Exception e) {
                    toast("系统里没有下载管理器");
                }
            }
        });
        box.addView(dl);

        TextView close = row("✕  关闭当前标签页", sub(), 15);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeTab(cur); dlg.dismiss(); }
        });
        box.addView(close);

        TextView hint = row("长按列表项可直接关闭该标签页", sub(), 11);
        hint.setPadding(pad * 2, pad * 2, pad * 2, pad);
        box.addView(hint);

        dlg.setContentView(box);
        dlg.show();
        Window dw = dlg.getWindow();
        if (dw != null) {
            dw.setBackgroundDrawable(new ColorDrawable(bg()));
            dw.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.88),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            // 贴到左上角三个点按钮的正下方，而不是屏幕居中
            WindowManager.LayoutParams lp = dw.getAttributes();
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.y = (int) (52 * d);
            dw.setAttributes(lp);
        }
    }

    TextView row(String text, int color, int size) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(size);
        tv.setPadding(pad * 3, pad * 3, pad * 3, pad * 3);
        return tv;
    }

    // ================= 下载 =================

    /** Android 6~9 写公共下载目录需要运行时授权；10+ 由系统 DownloadProvider 落盘，不需要 */
    void startDownload(String u, String ua, String cd, String mime, String ref) {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                   != PackageManager.PERMISSION_GRANTED) {
            pend = new String[]{u, ua, cd, mime, ref};
            requestPermissions(
                    new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            return;
        }
        enqueue(u, ua, cd, mime, ref);
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] g) {
        if (rc == REQ_WRITE) {
            String[] q = pend;
            pend = null;
            if (q == null) return;
            if (g != null && g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) {
                enqueue(q[0], q[1], q[2], q[3], q[4]);
            } else {
                toast("未授予存储权限，下载已取消");
            }
            return;
        }
        super.onRequestPermissionsResult(rc, p, g);
    }

    void enqueue(String u, String ua, String cd, String mime, String ref) {
        String name = URLUtil.guessFileName(u, cd, mime);
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(u));
            if (mime != null && mime.length() > 0) r.setMimeType(mime);
            // 带上 UA / Cookie / Referer：论坛附件、网盘直链缺了这三个多半 403
            if (ua != null && ua.length() > 0) r.addRequestHeader("User-Agent", ua);
            String ck = CookieManager.getInstance().getCookie(u);
            if (ck != null && ck.length() > 0) r.addRequestHeader("Cookie", ck);
            if (ref != null && ref.startsWith("http")) r.addRequestHeader("Referer", ref);
            r.setTitle(name);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) { toast("系统下载服务不可用"); return; }
            dm.enqueue(r);
            toast("↓ " + name);
        } catch (Exception e) {
            toast("下载失败：" + e.getMessage());
        }
    }

    /** 纯黑 Toast：系统默认 Toast 是深灰底，不符合本项目配色 */
    void toast(String s) {
        Toast t = new Toast(this);
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(fg());
        tv.setTextSize(13);
        tv.setBackgroundColor(bg());
        tv.setPadding(pad * 3, pad * 2, pad * 3, pad * 2);
        t.setView(tv);
        t.setDuration(Toast.LENGTH_SHORT);
        t.show();
    }

    // ================= 导航 =================

    void route(Intent it) {
        String u = null;
        if (it != null && Intent.ACTION_VIEW.equals(it.getAction()) && it.getData() != null) {
            u = it.getData().toString();
        }
        if (cur < 0) {                       // 冷启动
            newTab(u);
        } else if (u != null) {              // 已被唤起时又收到链接
            Tab t = tabs.get(cur);
            if (t.url != null && t.url.length() > 0) newTab(u);   // 当前页有内容则开新标签
            else { url.setText(u); go(u); }
        }
    }

    void paint(final WebView v) {
        if (!forceDark) return;
        // 空白页不要注入反相：它的底本来就是纯黑，invert 一下会翻成整片白
        String u = v.getUrl();
        if (u == null || u.startsWith("about:") || u.startsWith("data:")) return;
        v.evaluateJavascript(JS_DARK, new android.webkit.ValueCallback<String>() {
            @Override public void onReceiveValue(String r) {
                android.util.Log.i("Min", "darkInject=" + r + " url=" + v.getUrl());
            }
        });
        final Runnable later = new Runnable() {
            @Override public void run() {
                if (!forceDark) return;
                String u2 = v.getUrl();
                if (u2 == null || u2.startsWith("about:") || u2.startsWith("data:")) return;
                v.evaluateJavascript(JS_DARK, null);
            }
        };
        // Bing 这类站点加载中背景色会闪烁，多采样几次让"连续深色才跳过"的
        // 判定收敛到稳定状态
        v.postDelayed(later, 700);
        v.postDelayed(later, 2000);
    }

    void go(String input) {
        if (cur < 0 || input == null) return;
        String u = input.trim();
        if (u.length() == 0) return;

        if (u.indexOf("://") < 0) {
            boolean host = u.indexOf('.') >= 0 && u.indexOf(' ') < 0;
            u = host ? "https://" + u : SEARCH + Uri.encode(u);
        }
        Tab t = tabs.get(cur);
        t.url = u;
        t.wv.loadUrl(u);
        url.setText(u);
        url.clearFocus();
        syncUrlBox();

        InputMethodManager im =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(url.getWindowToken(), 0);
    }

    @Override
    public boolean onKeyDown(int key, KeyEvent e) {
        if (key == KeyEvent.KEYCODE_BACK) {
            // 浏览网页时手动唤出的地址框先收起，再退网页
            if (urlBoxVisible && !activeIsBlank()) {
                setUrlBoxVisible(false);
                return true;
            }
            if (cur >= 0 && tabs.get(cur).wv.canGoBack()) {
                tabs.get(cur).wv.goBack();
                return true;
            }
        }
        return super.onKeyDown(key, e);
    }
}
