package com.dyhook.txt;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** 模块设置界面。 */
public class MainActivity extends Activity {

    private static final int BG = 0xFFF4F5F7;
    private static final int CARD = 0xFFFFFFFF;
    private static final int TEXT = 0xFF1A1A1A;
    private static final int SUB = 0xFF8A8A8E;
    private static final int ACCENT = 0xFFFE2C55;
    private static final int OK = 0xFF2E7D32;

    private LinearLayout root;
    private LinearLayout statusCard;
    /** 0 = 设置页，1 = 待发件列表页 */
    private int page = 0;
    private LinearLayout contentBox;
    private LinearLayout tabBar;
    private ScrollView contentScroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FileSaver.ensureDirCompat();
        // 迁移：把配置同时写一份到抖音创建的共享文件，
        // 这样抖音进程无需任何存储权限就能读到（否则 auto_send/ai_enabled/论坛凭据 都会读成空）
        try {
            SharedCfg.save(SharedCfg.load());
        } catch (Throwable t) {
            DyLog.w("同步配置到共享文件失败: " + t);
        }
        try {
            ForumClient.Cfg fc = ForumClient.loadCfg();
            if (fc.ready()) ForumClient.saveCfg(fc);
        } catch (Throwable t) {
            DyLog.w("同步论坛配置到共享文件失败: " + t);
        }
        setContentView(build());
        autoRequestPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        if (page == 1) renderFiles();
    }

    private View build() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(BG);
        outer.setFitsSystemWindows(true);

        // 内容区
        contentBox = new LinearLayout(this);
        contentBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        contentBox.setLayoutParams(cbp);
        outer.addView(contentBox);

        // 底栏
        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(CARD);
        tabBar.setPadding(0, dp(6), 0, dp(6));
        outer.addView(tabBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        contentScroll = new ScrollView(this);
        contentScroll.setBackgroundColor(BG);
        contentScroll.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(22), dp(16), dp(32));
        contentScroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 关键：ScrollView 必须撑满内容区，否则会被压扁导致内容被裁切/遮挡
        contentBox.addView(contentScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        applyInsets(outer);

        buildTabBar();
        render();
        return outer;
    }

    /** 给内容区加状态栏内边距、底栏加导航栏内边距，避免标题被系统栏压住。 */
    private void applyInsets(final View outer) {
        try {
            outer.setOnApplyWindowInsetsListener((v, insets) -> {
                int top = 0, bottom = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        top = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top;
                        bottom = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;
                    } catch (Throwable ignored) {
                    }
                }
                contentBox.setPadding(0, top, 0, 0);
                tabBar.setPadding(0, dp(6), 0, dp(6) + bottom);
                return insets;
            });
        } catch (Throwable ignored) {
        }
    }

    /** 底栏两个图标：设置 / 待发件。 */
    private void buildTabBar() {
        tabBar.removeAllViews();
        tabBar.addView(tabItem("⚙", "设置", 0));
        tabBar.addView(tabItem("📄", "待发件", 1));
    }

    private View tabItem(String icon, String label, int idx) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(0, dp(6), 0, dp(6));
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        boolean on = (page == idx);
        TextView ic = new TextView(this);
        ic.setText(icon);
        ic.setTextSize(20);
        ic.setGravity(Gravity.CENTER);
        ic.setTextColor(on ? ACCENT : SUB);
        col.addView(ic);
        TextView tx = new TextView(this);
        tx.setText(label);
        tx.setTextSize(11);
        tx.setGravity(Gravity.CENTER);
        tx.setTextColor(on ? ACCENT : SUB);
        col.addView(tx);
        col.setOnClickListener(v -> {
            if (page == idx) return;
            page = idx;
            buildTabBar();
            render();
        });
        return col;
    }

    /** 按当前页重建内容。 */
    private void render() {
        if (page == 0) renderSettings();
        else renderFiles();
    }

    /** 每次刷新都重建内容，保证配置改动立刻反映。 */
    private void renderSettings() {
        root.removeAllViews();

        TextView title = new TextView(this);
        title.setText("抖音文案提取");
        title.setTextSize(22);
        title.setTextColor(TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("文章：分享→复制口令  |  任意文字：系统分享到本模块");
        sub.setTextSize(12);
        sub.setTextColor(SUB);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = dp(18);
        sub.setLayoutParams(slp);
        root.addView(sub);

        // 状态
        statusCard = card();
        root.addView(statusCard);

        // 基础
        LinearLayout g1 = card();
        g1.addView(switchRow("启用自动提取", "在文章页复制口令后自动解析", "enabled", true));
        g1.addView(divider());
        g1.addView(switchRow("自动发件", "解析完直接发到论坛，只用提示条告知成功/失败",
                "auto_send", false));
        root.addView(g1);

        // AI
        LinearLayout g2 = card();
        g2.addView(switchRow("AI 分析", "下载后交给 AI 结构化（标题/作者/正文）", "ai_enabled", false));
        g2.addView(divider());
        g2.addView(editRow("接口地址", SharedCfg.get("ai_base_url", ""),
                "https://your-gateway.com", "ai_base_url", false));
        g2.addView(divider());
        g2.addView(editRow("API Key", mask(SharedCfg.get("ai_key", "")),
                "sk-...", "ai_key", true));
        g2.addView(divider());
        g2.addView(editRow("模型", SharedCfg.get("ai_model", ""),
                "your-model", "ai_model", false));
        g2.addView(divider());
        g2.addView(actionRow("测试 AI 连接", "点一下问 AI「你是谁」，看能不能通", "测试",
                v -> testAi()));
        root.addView(g2);

        // 论坛发件（凭据只存设备本地）
        ForumClient.Cfg fc = ForumClient.loadCfg();
        LinearLayout g3 = card();
        g3.addView(forumRow("论坛地址", fc.url, "https://your-forum.com", "url", false));
        g3.addView(divider());
        g3.addView(forumRow("账号", fc.username, "username", "username", false));
        g3.addView(divider());
        g3.addView(forumRow("密码", mask(fc.password), "password", "password", true));
        g3.addView(divider());
        g3.addView(forumRow("默认标签 slug", fc.tagSlug, "留空则不带标签", "tag_slug", false));
        g3.addView(divider());
        g3.addView(actionRow("测试论坛登录", "验证地址/账号/密码能否拿到 token", "测试",
                v -> testForum()));
        root.addView(g3);

        TextView foot = new TextView(this);
        foot.setText("命名：青空_标题_作者_时间戳.txt\n"
                + "格式：标题/作者/时间/来源 + 分隔线 + 正文\n"
                + "位置：/sdcard/Documents/dyhooktxt/\n"
                + "发件：通知栏「发件」按钮 → 发新讨论贴到论坛");
        foot.setTextSize(11);
        foot.setTextColor(SUB);
        foot.setLineSpacing(dp(3), 1f);
        foot.setPadding(dp(6), dp(10), 0, 0);
        root.addView(foot);
    }

    // ---------- 待发件列表页 ----------

    /** 列出还没发出去的文件（发件成功会删文件，所以「存在 = 未上传」）。 */
    private void renderFiles() {
        root.removeAllViews();

        TextView title = new TextView(this);
        title.setText("待发件");
        title.setTextSize(22);
        title.setTextColor(TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        // 列表固定显示「洗稿后的成品」（青空_）：
        // 不管 AI 开关如何，成品都会生成（AI 关时用本地正则清洗）
        String prefix = "青空_";

        TextView sub = new TextView(this);
        sub.setText("列出 " + prefix + "*.txt（洗稿后的成品）\n"
                + "发件成功后文件会被删除，所以「在列表里 = 还没上传」\n"
                + "点整行可删除（连同源文件），点右侧「发件」直接发到论坛");
        sub.setTextSize(12);
        sub.setTextColor(SUB);
        sub.setLineSpacing(dp(3), 1f);
        sub.setPadding(0, dp(8), 0, dp(14));
        root.addView(sub);

        java.io.File dir = new java.io.File(FileSaver.OUT_DIR);
        java.io.File[] all = dir.listFiles();
        java.util.List<java.io.File> list = new java.util.ArrayList<>();
        if (all != null) {
            for (java.io.File f : all) {
                if (f.isFile() && f.getName().startsWith(prefix)
                        && f.getName().endsWith(".txt")) {
                    list.add(f);
                }
            }
        }
        list.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        if (list.isEmpty()) {
            LinearLayout empty = card();
            TextView t = label("暂无待发件文件", 14, SUB);
            t.setPadding(dp(16), dp(20), dp(16), dp(20));
            empty.addView(t);
            root.addView(empty);
            return;
        }

        for (java.io.File f : list) {
            root.addView(fileRow(f));
        }

        TextView tip = label("共 " + list.size() + " 个文件", 12, SUB);
        tip.setPadding(dp(6), dp(12), 0, 0);
        root.addView(tip);
    }

    /** 一行：完整标题（可换行不折叠）+ 右侧「发件」按钮。 */
    private View fileRow(java.io.File f) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(14));
        row.setBackground(bg);
        row.setPadding(dp(14), dp(12), dp(10), dp(12));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dp(10);
        row.setLayoutParams(rp);

        // 左侧：标题（完整显示，多行换行）
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        String heading = readTitle(f);
        TextView t = new TextView(this);
        t.setText(heading);
        t.setTextSize(15);
        t.setTextColor(TEXT);
        t.setLineSpacing(dp(4), 1f);
        t.setMaxLines(20);                       // 允许换行，不折叠
        t.setEllipsize(null);
        col.addView(t);

        TextView meta = new TextView(this);
        meta.setText(metaOf(f));
        meta.setTextSize(11);
        meta.setTextColor(SUB);
        meta.setPadding(0, dp(6), 0, 0);
        col.addView(meta);
        row.addView(col);

        // 右侧：发件按钮
        Button b = new Button(this);
        b.setText("发件");
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        GradientDrawable bb = new GradientDrawable();
        bb.setColor(ACCENT);
        bb.setCornerRadius(dp(18));
        b.setBackground(bb);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(16), dp(6), dp(16), dp(6));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.leftMargin = dp(10);
        b.setLayoutParams(bp);
        b.setOnClickListener(v -> {
            b.setEnabled(false);
            b.setText("发送中");
            SendActionReceiver.sendFromUi(this, f.getAbsolutePath());
            // 稍后刷新列表（成功会删文件）
            root.postDelayed(this::renderFiles, 6000);
        });
        row.addView(b);

        // 点击整行 → 确认删除（连源文件一起删）
        row.setOnClickListener(v -> confirmDelete(f));
        return row;
    }

    /** 点列表行：确认后删除成品 + 配对的源文件。 */
    private void confirmDelete(java.io.File f) {
        String title = readTitle(f);
        String rawPath = SendActionReceiver.guessRawPath(f.getAbsolutePath());
        DyLog.i("[列表] 点击删除 " + f.getName() + " -> 配对源文件=" + rawPath);
        String extra = (rawPath == null) ? ""
                : "\n\n同时会删除源文件：\n" + new java.io.File(rawPath).getName();
        new android.app.AlertDialog.Builder(this)
                .setTitle("确认删除吗？")
                .setMessage(title + extra + "\n\n删除后不可恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    boolean ok1 = f.delete();
                    boolean ok2 = true;
                    if (rawPath != null) {
                        java.io.File rf = new java.io.File(rawPath);
                        if (rf.exists()) ok2 = rf.delete();
                    }
                    DyLog.i("[列表] 删除 " + f.getName() + " ok=" + ok1
                            + " | 源文件 ok=" + ok2);
                    android.widget.Toast.makeText(this,
                            (ok1 ? "✅ 已删除" : "❌ 删除失败"), android.widget.Toast.LENGTH_SHORT).show();
                    renderFiles();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 从文件头读「标题：」，读不到就用文件名。 */
    private String readTitle(java.io.File f) {
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("标题：")) {
                    String t = line.substring(3).trim();
                    if (!t.isEmpty()) return t;
                }
                if (line.startsWith("==========")) break;
            }
        } catch (Throwable ignored) {
        }
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private String metaOf(java.io.File f) {
        String when = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                .format(new java.util.Date(f.lastModified()));
        long kb = Math.max(1, f.length() / 1024);
        return when + "  ·  " + kb + " KB  ·  " + f.getName();
    }

    // ---------- 组件 ----------

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(14));
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        c.setLayoutParams(lp);
        return c;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0xFFEDEDED);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.leftMargin = dp(16);
        v.setLayoutParams(lp);
        return v;
    }

    private View switchRow(String t, String s, String key, boolean def) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(label(t, 15, TEXT));
        col.addView(label(s, 12, SUB));
        row.addView(col);
        Switch sw = new Switch(this);
        sw.setChecked(SharedCfg.getBool(key, def));
        sw.setOnCheckedChangeListener((b, c) -> {
            SharedCfg.setBool(key, c);
            refreshStatus();
        });
        row.addView(sw);
        return row;
    }

    private View editRow(String t, String val, String hint, String key, boolean secret) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(12), dp(12));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(label(t, 15, TEXT));
        String shown = (val == null || val.isEmpty()) ? "未设置" : val;
        TextView v = label(shown, 12, (val == null || val.isEmpty()) ? SUB : OK);
        v.setMaxLines(1);
        v.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(v);
        row.addView(col);
        Button b = new Button(this);
        b.setText("设置");
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(ACCENT);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setOnClickListener(x -> showEdit(t, hint, key, secret));
        row.addView(b);
        return row;
    }

    private TextView label(String s, int sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        return tv;
    }

    private View actionRow(String t, String s, String btn, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(10), dp(12));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(label(t, 15, TEXT));
        col.addView(label(s, 12, SUB));
        row.addView(col);
        Button b = new Button(this);
        b.setText(btn);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(ACCENT);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setOnClickListener(l);
        row.addView(b);
        return row;
    }

    /** 测试按钮：问 AI「你是谁」，把回复弹出来。 */
    private void testAi() {
        String problem = AiClient.configProblem();
        if (problem != null) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("配置不完整")
                    .setMessage(problem + "\n\n请先填好接口地址 / API Key / 模型。")
                    .setPositiveButton("好", null).show();
            return;
        }
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("测试 AI 连接")
                .setMessage("正在请求…\n\n" + AiClient.buildEndpoint())
                .setCancelable(false)
                .setNegativeButton("关闭", null)
                .create();
        dlg.show();

        new Thread(() -> {
            final AiClient.Result r = AiClient.testConnection();
            runOnUiThread(() -> {
                StringBuilder sb = new StringBuilder();
                sb.append("地址：").append(r.url).append('\n');
                sb.append("模型：").append(SharedCfg.get("ai_model", "")).append("\n\n");
                if (r.ok) {
                    sb.append("✅ 连接成功\n\nAI 回复：\n").append(r.content);
                } else {
                    sb.append("❌ 失败：").append(r.error);
                    if (r.raw != null && !r.raw.isEmpty()) {
                        sb.append("\n\n原始返回：\n").append(r.raw);
                    }
                }
                dlg.setMessage(sb.toString());
            });
        }, "dyhook-aitest").start();
    }

    private View statusLine(String t, String v, boolean ok) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(13), dp(16), dp(13));
        row.addView(label(t, 14, TEXT));
        View sp = new View(this);
        sp.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(sp);
        row.addView(label(v, 13, ok ? OK : SUB));
        return row;
    }

    // ---------- 逻辑 ----------

    private void showEdit(String title, String hint, String key, boolean secret) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(SharedCfg.get(key, ""));
        et.setSingleLine(true);
        if (secret) et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        else et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(et);
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton("保存", (d, w) -> {
                    SharedCfg.set(key, et.getText().toString().trim());
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String mask(String k) {
        if (k == null || k.isEmpty()) return "";
        if (k.length() <= 10) return k;
        return k.substring(0, 6) + "…" + k.substring(k.length() - 4);
    }

    // ---------- 论坛发件（凭据只存设备本地 forum.txt） ----------

    private View forumRow(String t, String val, String hint, String field, boolean secret) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(12), dp(12));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(label(t, 15, TEXT));
        String shown = (val == null || val.isEmpty()) ? "未设置" : val;
        TextView v = label(shown, 12, (val == null || val.isEmpty()) ? SUB : OK);
        v.setMaxLines(1);
        v.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(v);
        row.addView(col);
        Button b = new Button(this);
        b.setText("设置");
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(ACCENT);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setOnClickListener(x -> showForumEdit(t, hint, field, secret));
        row.addView(b);
        return row;
    }

    private void showForumEdit(String title, String hint, String field, boolean secret) {
        ForumClient.Cfg c = ForumClient.loadCfg();
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setSingleLine(true);
        switch (field) {
            case "url": et.setText(c.url); break;
            case "username": et.setText(c.username); break;
            case "password": et.setText(c.password); break;
            case "tag_slug": et.setText(c.tagSlug); break;
            default: break;
        }
        if (secret) et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        else et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(et);
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton("保存", (d, w) -> {
                    ForumClient.Cfg cur = ForumClient.loadCfg();
                    String v = et.getText().toString().trim();
                    switch (field) {
                        case "url": cur.url = v; break;
                        case "username": cur.username = v; break;
                        case "password": cur.password = v; break;
                        case "tag_slug": cur.tagSlug = v; break;
                        default: break;
                    }
                    ForumClient.saveCfg(cur);
                    ForumClient.clearToken();   // 配置变了，旧 token 作废
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 测试论坛登录。 */
    private void testForum() {
        final ForumClient.Cfg c = ForumClient.loadCfg();
        if (!c.ready()) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("配置不完整")
                    .setMessage("请先填写论坛地址 / 账号 / 密码。\n\n凭据只保存在本机：\n"
                            + ForumClient.CFG_FILE)
                    .setPositiveButton("好", null).show();
            return;
        }
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("测试论坛登录")
                .setMessage("正在登录…\n\n" + c.url)
                .setCancelable(false)
                .setNegativeButton("关闭", null)
                .create();
        dlg.show();
        new Thread(() -> {
            ForumClient.Result r = ForumClient.login(c);
            String slug = c.tagSlug;
            String tagId = r.ok ? ForumClient.findTagId(c, r.token, slug) : null;
            final String msg = r.ok
                    ? "✅ 登录成功\n\n地址：" + c.url + "\n用户ID：" + r.userId
                      + "\n默认标签：" + slug + "（id=" + tagId + "）"
                    : "❌ 登录失败：" + r.error;
            runOnUiThread(() -> dlg.setMessage(msg));
        }, "forum-test").start();
    }

    private void refreshStatus() {
        if (statusCard == null) return;
        statusCard.removeAllViews();
        boolean storage = hasStorage();
        boolean aiOn = SharedCfg.getBool("ai_enabled", false);
        boolean aiReady = aiOn
                && !SharedCfg.get("ai_base_url", "").isEmpty()
                && !SharedCfg.get("ai_key", "").isEmpty()
                && !SharedCfg.get("ai_model", "").isEmpty();

        statusCard.addView(statusLine("模块状态", "已安装（LSPosed 中启用）", true));
        statusCard.addView(divider());
        statusCard.addView(statusLine("存储权限", storage ? "已授权" : "待授权", storage));
        statusCard.addView(divider());
        statusCard.addView(statusLine("AI",
                !aiOn ? "未开启" : (aiReady ? "已就绪" : "配置不完整"),
                !aiOn || aiReady));
    }

    private boolean hasStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void autoRequestPermissions() {
        if (!hasStorage() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Throwable t) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Throwable ignored) {
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 100);
            } catch (Throwable ignored) {
            }
        }
        refreshStatus();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
