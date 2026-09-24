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
 *      ↓ 失败自动重试最多 10 次，每次隔 3 秒
 *   发件成功（并删除本地的源文件 + 成品文件）
 *   或 发件失败（文件保留，通知上出现「重试」按钮）
 *
 * 论坛帖子内容：
 *   标题 = 文章标题
 *   正文 = @作者by抖音
 *          （空 1 行）
 *          文章正文……
 *
 * ⚠ 论坛地址/账号/密码只从本地文件 /sdcard/Documents/dyhooktxt/forum.txt 读，
 *   不写在代码里，也绝不进 git 仓库。
 */
public class SendActionReceiver extends BroadcastReceiver {

    public static final String ACTION_SEND_OUT = "com.dyhook.txt.SEND_OUT";

    private static final int MAX_TRY = 10;
    private static final long RETRY_DELAY_MS = 3000L;

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

    /** 重试按钮要能重新触发，所以手动重试时绕过幂等锁。 */
    private static synchronized void release(String path) {
        RECENT.remove(path);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_SEND_OUT.equals(intent.getAction())) return;

        final String path = intent.getStringExtra("path");
        final String rawPath = intent.getStringExtra("rawpath");
        final int notifId = intent.getIntExtra("notif_id", Notifier.PROGRESS_ID);
        final Context appCtx = context.getApplicationContext();
        DyLog.i("[发件] 待发送文件: " + path + " (notif=" + notifId + ")");

        if (path == null || path.isEmpty()) {
            DyLog.w("[发件] 没有文件路径");
            return;
        }
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

        // 判断是不是「重试」：同一条通知上再次点发件
        boolean isRetry = intent.getBooleanExtra("retry", false);
        if (isRetry) {
            release(path);
        }
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

        Notifier.sending(appCtx, notifId, f.getName());
        cancelDouyinNotif(appCtx, notifId);

        final PendingResult pr = goAsync();
        new Thread(() -> {
            String err = null;
            ForumClient.Result ok = null;
            try {
                Parsed p = parse(f);
                if (p.content == null || p.content.trim().isEmpty()) {
                    Notifier.sendFailed(appCtx, notifId, f.getName(), "文件内容为空");
                    return;
                }
                String body = buildBody(p);
                String title = ForumClient.normalizeTitle(p.title);
                DyLog.i("[发件] 标题=" + title + " | 正文首行=" + firstLine(body));

                // ---- 自动重试：最多 10 次，每次隔 3 秒 ----
                for (int attempt = 1; attempt <= MAX_TRY; attempt++) {
                    if (attempt > 1) {
                        Notifier.retrying(appCtx, notifId, f.getName(), attempt, MAX_TRY);
                        try {
                            Thread.sleep(RETRY_DELAY_MS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    ForumClient.Result r = ForumClient.createDiscussion(cfg, title, body);
                    if (r.ok) {
                        ok = r;
                        break;
                    }
                    err = r.error;
                    DyLog.w("[发件] 第 " + attempt + "/" + MAX_TRY + " 次失败: " + err);
                }

                if (ok != null) {
                    DyLog.i("[发件] 成功: " + ok.discussionUrl);
                    // 成功后删除本地的源文件与成品文件
                    deleteQuietly(f);
                    if (rawPath != null && !rawPath.isEmpty()) deleteQuietly(new File(rawPath));
                    Notifier.sent(appCtx, notifId, f.getName(), ok.discussionUrl);
                } else {
                    DyLog.e("[发件] 重试 " + MAX_TRY + " 次仍失败: " + err);
                    Notifier.sendFailed(appCtx, notifId, f.getName(),
                            (err == null ? "未知错误" : err), path, rawPath);
                }
            } catch (Throwable t) {
                DyLog.e("[发件] 异常: " + t);
                Notifier.sendFailed(appCtx, notifId, f.getName(),
                        String.valueOf(t.getMessage()), path, rawPath);
            } finally {
                try {
                    pr.finish();
                } catch (Throwable ignored) {
                }
            }
        }, "dyhook-send").start();
    }

    private static void deleteQuietly(File f) {
        try {
            if (f != null && f.exists() && f.delete()) {
                DyLog.i("[发件] 已删除: " + f.getAbsolutePath());
            }
        } catch (Throwable t) {
            DyLog.w("[发件] 删除失败: " + t);
        }
    }

    /**
     * 让抖音进程把它自己发的那条「解析完成」通知撤掉，
     * 这样通知栏上只留模块自己发的「发件中 / 发件成功」。
     * （抖音侧由 HookEntry 注册的接收器处理）
     */
    private static void cancelDouyinNotif(Context ctx, int notifId) {
        try {
            Intent i = new Intent("com.dyhook.txt.CANCEL_NOTIF");
            i.setPackage("com.ss.android.ugc.aweme");
            i.putExtra("notif_id", notifId);
            ctx.sendBroadcast(i);
            DyLog.i("[通知] 已请求抖音撤销通知 id=" + notifId);
        } catch (Throwable t) {
            DyLog.w("[通知] 请求撤销失败: " + t);
        }
    }

    /**
     * 组装论坛正文：@作者by抖音 + 空 1 行 + 正文。
     *
     * 空行用「全角空格行」实现：Flarum 会把 <br> 和 &nbsp; 转义成字面文本，
     * 而连续换行会被浏览器折叠，只有含全角空格（U+3000）的段落才渲染成真正的空行。
     */
    private static String buildBody(Parsed p) {
        StringBuilder sb = new StringBuilder();
        String author = p.author == null ? "" : p.author.trim();
        if (!author.isEmpty()) {
            if (!author.startsWith("@")) sb.append('@');
            sb.append(author);
            sb.append("\n\n\u3000\n\n");   // 作者名下面空 1 行
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
