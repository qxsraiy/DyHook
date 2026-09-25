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

    /**
     * 串行队列：**单线程执行器**，一次只发一篇，失败重试也排着来。
     * 这样不会出现「点 10 个 → 10 条线程同时登录同时发」撞 429 的情况。
     */
    private static final java.util.concurrent.ExecutorService QUEUE =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "dyhook-send-queue");
                t.setDaemon(true);
                return t;
            });

    /** 排队中 / 正在发的数量，用于在通知里显示进度。 */
    private static final java.util.concurrent.atomic.AtomicInteger PENDING =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** 排队执行（所有发件入口都走这里）。 */
    private static void submit(Runnable task) {
        PENDING.incrementAndGet();
        QUEUE.submit(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                DyLog.e("[发件] 队列任务异常: " + t);
            } finally {
                PENDING.decrementAndGet();
            }
        });
    }

    /** 当前排队数量（含正在执行的）。 */
    public static int pendingCount() {
        return PENDING.get();
    }

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

        final PendingResult pr = goAsync();
        submit(() -> {
            try {
                doSend(appCtx, path, rawPath, notifId, false);
            } finally {
                try {
                    pr.finish();
                } catch (Throwable ignored) {
                }
            }
        });
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

    // ==================== 自动发件（静默模式，只用 Toast） ====================

    /**
     * 从模块界面「待发件」列表手动发件。
     * 用通知模式，这样能看到重试进度。
     */
    public static void sendFromUi(Context ctx, String path) {
        final Context app = ctx.getApplicationContext();
        final int notifId = Notifier.newTaskId();
        final String rawPath = guessRawPath(path);
        submit(() -> {
            try {
                doSend(app, path, rawPath, notifId, false);
            } catch (Throwable t) {
                DyLog.e("[发件] 界面发件异常: " + t);
                Notifier.sendFailed(app, notifId, new File(path).getName(),
                        String.valueOf(t.getMessage()));
            }
        });
    }

    /**
     * 成品是 青空_&lt;标题&gt;_&lt;作者&gt;_&lt;yyyyMMdd&gt;_&lt;HHmmss&gt;.txt
     * 源文件是 源_&lt;yyyyMMdd&gt;_&lt;HHmmss&gt;_&lt;标题&gt;_&lt;作者&gt;.txt
     * 注意时间戳自身含下划线，要按「最后两段」取。
     */
    public static String guessRawPath(String namedPath) {
        try {
            File named = new File(namedPath);
            String n = named.getName();
            if (!n.startsWith("青空_")) return null;
            String stem = n.substring(3);
            int dot = stem.lastIndexOf('.');
            if (dot > 0) stem = stem.substring(0, dot);

            String[] parts = stem.split("_");
            if (parts.length < 3) return null;
            String ts = parts[parts.length - 2] + "_" + parts[parts.length - 1]; // yyyyMMdd_HHmmss
            if (!ts.matches("\\d{8}_\\d{6}")) return null;

            // 标题 = 去掉尾部 <作者>_<时间戳> 之后剩下的
            StringBuilder titleB = new StringBuilder();
            for (int i = 0; i < parts.length - 3; i++) {
                if (titleB.length() > 0) titleB.append('_');
                titleB.append(parts[i]);
            }
            String title = titleB.toString();

            File dir = new File(FileSaver.OUT_DIR);
            File[] all = dir.listFiles();
            if (all == null) return null;

            // 优先：源_<ts>_<标题>...
            String want = "源_" + ts + "_" + title;
            for (File f : all) {
                if (f.getName().startsWith(want)) return f.getAbsolutePath();
            }
            // 兜底：只要时间戳对得上
            for (File f : all) {
                if (f.getName().startsWith("源_" + ts + "_")) return f.getAbsolutePath();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 直接发件（自动发件开关打开时调用）。
     * silent = true：不弹通知，只用 Toast 提示成功/失败。
     */
    public static void sendNow(Context ctx, String path, String rawPath, boolean silent) {
        submit(() -> {
            try {
                doSend(ctx, path, rawPath, -1, silent);
            } catch (Throwable t) {
                DyLog.e("[发件] 自动发件异常: " + t);
                if (silent) toast(ctx, "❌ 发件失败：" + t.getMessage());
            }
        });
    }

    /** 核心发件逻辑。notifId < 0 表示静默模式（用 Toast 而非通知）。 */
    private static void doSend(Context appCtx, String path, String rawPath,
                               int notifId, boolean silent) {
        final boolean notify = !silent;
        File f = new File(path);
        if (!f.exists()) {
            fail(appCtx, notifId, notify, f.getName(), "文件不存在", null, null);
            return;
        }
        final ForumClient.Cfg cfg = ForumClient.loadCfg();
        if (!cfg.ready()) {
            fail(appCtx, notifId, notify, f.getName(),
                    "未配置论坛，请在模块界面填写地址/账号/密码", null, null);
            return;
        }

        if (notify) {
            int behind = pendingCount() - 1;
            Notifier.sending(appCtx, notifId, f.getName()
                    + (behind > 0 ? "\n（队列里还有 " + behind + " 篇在等）" : ""));
            cancelDouyinNotif(appCtx, notifId);
        }

        String err = null;
        ForumClient.Result ok = null;
        try {
            Parsed p = parse(f);
            if (p.content == null || p.content.trim().isEmpty()) {
                fail(appCtx, notifId, notify, f.getName(), "文件内容为空", path, rawPath);
                return;
            }
            String body = buildBody(p);
            String title = ForumClient.normalizeTitle(p.title);
            DyLog.i("[发件] 标题=" + title + " | 正文首行=" + firstLine(body)
                    + " | 正文长度=" + body.length()
                    + " | 论坛=" + cfg.url + " | 标签=" + cfg.tagSlug);

            for (int attempt = 1; attempt <= MAX_TRY; attempt++) {
                if (attempt > 1) {
                    if (notify) Notifier.retrying(appCtx, notifId, f.getName(), attempt, MAX_TRY);
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                DyLog.i("[发件] 第 " + attempt + " 次尝试…");
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
                deleteQuietly(f);
                if (rawPath != null && !rawPath.isEmpty()) deleteQuietly(new File(rawPath));
                if (notify) {
                    Notifier.sent(appCtx, notifId, f.getName(), ok.discussionUrl);
                } else {
                    toast(appCtx, "✅ 已发件：" + shortTitle(p.title));
                }
            } else {
                DyLog.e("[发件] 重试 " + MAX_TRY + " 次仍失败: " + err);
                fail(appCtx, notifId, notify, f.getName(),
                        err == null ? "未知错误" : err, path, rawPath);
            }
        } catch (Throwable t) {
            DyLog.e("[发件] 异常: " + t);
            fail(appCtx, notifId, notify, f.getName(), String.valueOf(t.getMessage()), path, rawPath);
        }
    }

    private static void fail(Context c, int notifId, boolean notify, String fileName,
                             String reason, String path, String rawPath) {
        if (notify) {
            Notifier.sendFailed(c, notifId, fileName, reason, path, rawPath);
        } else {
            toast(c, "❌ 发件失败：" + reason + "\n文件已保留，可在模块里手动发");
        }
    }

    private static String shortTitle(String t) {
        if (t == null) return "";
        String s = t.trim();
        return s.length() > 18 ? s.substring(0, 18) + "…" : s;
    }

    private static void toast(Context c, String msg) {
        try {
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.post(() -> android.widget.Toast.makeText(c, msg,
                    android.widget.Toast.LENGTH_LONG).show());
        } catch (Throwable ignored) {
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
        // 发帖前再清洗一次：HTML 标签 / 代码围栏 / 行首缩进（会渲染成代码框）
        sb.append(AiClient.cleanText(p.content));
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
