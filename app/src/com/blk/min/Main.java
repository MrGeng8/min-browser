package com.blk.min;

import android.app.Activity;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.KeyEvent;
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

    static final String BLANK =
        "<html style=\"background:#000\"><head>" +
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<style>html,body{background:#000;margin:0;height:100%}</style></head>" +
        "<body></body></html>";

    /**
     * 强制纯黑。
     * filter 只能加在 body 上，html 背景单独写死 #000 —— 若加在 html 上，
     * Chromium 的 canvas 底色不参与过滤，大片浅色底不会被反相。
     */
    static final String JS_DARK =
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

    static class Tab {
        WebView wv;
        String url;
        String title;
        Tab(WebView v, String u, String t) { wv = v; url = u; title = t; }
    }

    final ArrayList<Tab> tabs = new ArrayList<Tab>();
    FrameLayout stage;
    EditText url;
    TextView darkBtn;
    TextView tabBtn;
    boolean forceDark = true;
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

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BLACK);

        // ---------- 工具条: [标签数] [地址] [●] [→] ----------
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(BLACK);
        bar.setPadding(pad, pad, pad, pad);

        tabBtn = mkBtn("1");
        bar.addView(tabBtn);

        url = new EditText(this);
        url.setSingleLine(true);
        url.setHint("搜索或输入网址");
        url.setHintTextColor(DIM);
        url.setTextColor(FG);
        url.setTextSize(13);
        url.setBackground(null);
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        url.setImeOptions(EditorInfo.IME_ACTION_GO);
        url.setSelectAllOnFocus(true);
        bar.addView(url, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        darkBtn = mkBtn("●");
        bar.addView(darkBtn);
        TextView goBtn = mkBtn("→");
        bar.addView(goBtn);

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        stage = new FrameLayout(this);
        stage.setBackgroundColor(BLACK);
        root.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        // ---------- 交互 ----------
        goBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { go(url.getText().toString()); }
        });
        url.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int a, KeyEvent e) {
                go(v.getText().toString());
                return true;
            }
        });
        darkBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                forceDark = !forceDark;
                syncDarkBtn();
                if (cur >= 0) tabs.get(cur).wv.reload();
            }
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

    // ================= 标签页 =================

    WebView makeWV() {
        WebView v = new WebView(this);
        v.setBackgroundColor(BLACK);
        WebSettings s = v.getSettings();
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
            v.loadDataWithBaseURL(null, BLANK, "text/html", "utf-8", null);
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

    void syncTabBtn() { tabBtn.setText(String.valueOf(tabs.size())); }

    void syncDarkBtn() { darkBtn.setTextColor(forceDark ? 0xFFFFFFFF : DIM); }

    TextView mkBtn(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(15);
        tv.setTextColor(FG);
        int p = (int) (8 * d);
        tv.setPadding(p, p, p, p);
        return tv;
    }

    /** 标签页面板：纯黑自绘 Dialog，不用系统 AlertDialog（后者是深灰，不符合纯黑要求） */
    void showTabs() {
        final Dialog dlg = new Dialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(BLACK);
        box.setPadding(pad * 2, pad * 2, pad * 2, pad * 2);

        TextView head = row("标签页  " + tabs.size(), DIM, 12);
        head.setPadding(pad * 2, pad, pad * 2, pad * 2);
        box.addView(head);

        for (int i = 0; i < tabs.size(); i++) {
            final int idx = i;
            Tab t = tabs.get(i);
            String title = (t.title == null || t.title.length() == 0) ? "新标签页" : t.title;
            if (title.length() > 26) title = title.substring(0, 26) + "…";

            TextView tv = row((i == cur ? "●  " : "○  ") + title,
                    i == cur ? 0xFFFFFFFF : 0xFF9A9A9A, 15);
            tv.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { selectTab(idx); dlg.dismiss(); }
            });
            tv.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { closeTab(idx); dlg.dismiss(); return true; }
            });
            box.addView(tv);
        }

        TextView add = row("＋  新建标签页", FG, 15);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { newTab(null); dlg.dismiss(); }
        });
        box.addView(add);

        TextView dl = row("↓  下载内容", FG, 15);
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

        TextView close = row("✕  关闭当前标签页", 0xFF9A9A9A, 15);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeTab(cur); dlg.dismiss(); }
        });
        box.addView(close);

        TextView hint = row("长按列表项可直接关闭该标签页", DIM, 11);
        hint.setPadding(pad * 2, pad * 2, pad * 2, pad);
        box.addView(hint);

        dlg.setContentView(box);
        dlg.show();
        Window dw = dlg.getWindow();
        if (dw != null) {
            dw.setBackgroundDrawable(new ColorDrawable(BLACK));
            dw.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.88),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
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
        tv.setTextColor(FG);
        tv.setTextSize(13);
        tv.setBackgroundColor(BLACK);
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
        v.evaluateJavascript(JS_DARK, new android.webkit.ValueCallback<String>() {
            @Override public void onReceiveValue(String r) {
                android.util.Log.i("Min", "darkInject=" + r + " url=" + v.getUrl());
            }
        });
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

        InputMethodManager im =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(url.getWindowToken(), 0);
    }

    @Override
    public boolean onKeyDown(int key, KeyEvent e) {
        if (key == KeyEvent.KEYCODE_BACK && cur >= 0 && tabs.get(cur).wv.canGoBack()) {
            tabs.get(cur).wv.goBack();
            return true;
        }
        return super.onKeyDown(key, e);
    }
}
