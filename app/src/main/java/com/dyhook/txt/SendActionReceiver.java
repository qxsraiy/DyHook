package com.dyhook.txt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 「发件」按钮：把已保存的 txt 作为**新讨论贴**发到 Flarum 论坛。
 *
 * 通知栏状态流转（同一条通知上）：
 *   解析完成（带「发件」按钮）
 *      ↓ 点发件
 *   发件中…（按钮消失，立刻更新）
 *      ↓
 *   发件成功 / 发件失败
 *
 * 论坛帖子内容：
 *   标题 = 文章标题
 *   正文 = @作者by抖音
 *          （换行）
 *          文章正文……
 *
 * ⚠ 论坛地址/账号/密码只从本地文件 /sdcard/Documents/dyhooktxt/forum.txt 读，
 *   不写在代码里，也绝不进 git 仓库。
 */
public class SendActionReceiver extends BroadcastReceiver {

    public static final String ACTION_SEND_OUT = "com.dyhook.txt.SEND_OUT";

    /** 幂等锁：同一文件在 N 秒内只允许发一次（防广播重投/重复点击）。 */
    private static final java.util.Map<String, Long> RECENT = new java.util.HashMap<>();
    private static final long DEDUP_MS = 60_000L;

    private static synchronized boolean claim(String path) {
        long now = System.currentTimeMillis();
        Long last = RECENT.get(path);
        if (last != null && now - last < DEDUP_MS) return false;
        RECENT.put(path, now);
        java.util.Iterator<java.util.Map.Entry<String, Long>> it = RECENT.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > DEDUP_MS) it.remove();
        }
        return true;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_SEND_OUT.equals(intent.getAction())) return;

        final String path = intent.getStringExtra("path");
        final int notifId = intent.getIntExtra("notif_id", Notifier.PROGRESS_ID);
        final Context appCtx = context.getApplicationContext();
        DyLog.i("[发件] 待发送文件: " + path + " (notif=" + notifId + ")");

        if (path == null || path.isEmpty()) {
            DyLog.w("[发件] 没有文件路径");
            return;
        }
        // 守卫：只允许发送我们输出目录下的 txt
        if (!path.startsWith(FileSaver.OUT_DIR) || !path.endsWith(".txt")) {
            DyLog.w("[发件] 拒绝非法路径: " + path);
            Notifier.sendFailed(appCtx, notifId, new File(path).getName(), "非法路径");
            return;
        }
        final File f = new File(path);
        if (!f.exists()) {
            Notifier.sendFailed(appCtx, notifId, f.getName(), "文件不存在");
            return;
        }
        // 幂等：同一文件 60 秒内只发一次
        if (!claim(path)) {
            DyLog.w("[发件] 重复触发已忽略: " + path);
            return;
        }

        final ForumClient.Cfg cfg = ForumClient.loadCfg();
        if (!cfg.ready()) {
            DyLog.w("[发件] 论坛未配置");
            Notifier.sendFailed(appCtx, notifId, f.getName(),
                    "未配置论坛，请在模块界面填写地址/账号/密码");
            return;
        }

        // 立刻更新为「发件中」，按钮消失
        Notifier.sending(appCtx, notifId, f.getName());

        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                Parsed p = parse(f);
                if (p.content == null || p.content.trim().isEmpty()) {
                    Notifier.sendFailed(appCtx, notifId, f.getName(), "文件内容为空");
                    return;
                }
                // 正文最上面加一行 @作者by抖音
                String body = buildBody(p);
                DyLog.i("[发件] 标题=" + p.title + " | 正文首行=" + firstLine(body));

                ForumClient.Result r = ForumClient.createDiscussion(cfg, p.title, body);
                if (r.ok) {
                    DyLog.i("[发件] 成功: " + r.discussionUrl);
                    Notifier.sent(appCtx, notifId, f.getName(), r.discussionUrl);
                } else {
                    DyLog.e("[发件] " + r.error);
                    Notifier.sendFailed(appCtx, notifId, f.getName(), r.error);
                }
            } catch (Throwable t) {
                DyLog.e("[发件] 异常: " + t);
                Notifier.sendFailed(appCtx, notifId, f.getName(), String.valueOf(t.getMessage()));
            } finally {
                try {
                    pr.finish();
                } catch (Throwable ignored) {
                }
            }
        }, "dyhook-send").start();
    }

    /** 组装论坛正文：@作者by抖音 + 换行 + 正文。 */
    private static String buildBody(Parsed p) {
        StringBuilder sb = new StringBuilder();
        String author = p.author == null ? "" : p.author.trim();
        if (!author.isEmpty()) {
            if (!author.startsWith("@")) sb.append('@');
            sb.append(author);
            sb.append('\n');
        }
        sb.append(p.content);
        return sb.toString();
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    // ---------- 解析成品 txt ----------

    private static class Parsed {
        String title = "无标题";
        String author = "";
        String content = "";
    }

    /**
     * 解析格式：
     *   标题：xxx
     *   作者：xxx
     *   时间：xxx
     *   来源：xxx
     *   ==========
     *
     *   正文……
     */
    private static Parsed parse(File f) throws Exception {
        Parsed p = new Parsed();
        StringBuilder body = new StringBuilder();
        boolean inBody = false;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!inBody) {
                    if (line.startsWith("==========")) {
                        inBody = true;
                        continue;
                    }
                    if (line.startsWith("标题：")) p.title = line.substring(3).trim();
                    else if (line.startsWith("作者：")) p.author = line.substring(3).trim();
                } else {
                    body.append(line).append('\n');
                }
            }
        }
        if (!inBody) {
            StringBuilder all = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) all.append(line).append('\n');
            }
            body = all;
        }
        p.content = body.toString().trim();
        if (p.title.isEmpty()) p.title = "无标题";
        return p;
    }
}
