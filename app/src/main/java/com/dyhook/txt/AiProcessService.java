package com.dyhook.txt;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.io.File;

/**
 * 前台服务：在模块自己的进程里跑 AI 分析 + 落盘 + 通知栏进度。
 * 注意：抖音侧 hook 也会直接调用静态方法 run()，此时在抖音进程内执行。
 */
public class AiProcessService extends Service {

    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_SOURCE = "source";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_AUTHOR = "author";

    public static final String SRC_ARTICLE = "article";
    public static final String SRC_PERSONAL = "personal";

    /** 文章引言/备注，要作为正文首行。 */
    public static final String EXTRA_ABSTRACT = "abstract";

    private static volatile boolean running = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String text = intent.getStringExtra(EXTRA_TEXT);
        String source = intent.getStringExtra(EXTRA_SOURCE);
        String hintTitle = intent.getStringExtra(EXTRA_TITLE);
        String hintAuthor = intent.getStringExtra(EXTRA_AUTHOR);
        String rawPath = intent.getStringExtra(ProcessReceiver.EXTRA_RAWPATH);
        String abstractText = intent.getStringExtra(EXTRA_ABSTRACT);
        if (source == null) source = SRC_PERSONAL;

        if (text == null || text.trim().isEmpty()) {
            Notifier.fail(this, "没有收到内容");
            stopSelf();
            return START_NOT_STICKY;
        }
        DyLog.i("[服务] 收到任务 source=" + source + " len=" + text.length()
                + " raw=" + (rawPath != null));

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(Notifier.PROGRESS_ID, Notifier.progress(this, "准备中…"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(Notifier.PROGRESS_ID, Notifier.progress(this, "准备中…"));
            }
        } catch (Throwable t) {
            DyLog.w("[服务] startForeground 失败: " + t);
        }

        final String fText = text.trim(), fSource = source,
                fTitle = hintTitle, fAuthor = hintAuthor, fRaw = rawPath,
                fAbs = abstractText;
        new Thread(() -> {
            try {
                run(this, fText, fSource, fTitle, fAuthor, fRaw, fAbs);
            } finally {
                try {
                    stopForeground(true);
                } catch (Throwable ignored) {
                }
                stopSelf();
            }
        }, "dyhook-ai").start();

        return START_NOT_STICKY;
    }

    /** 实际处理逻辑（服务 / 抖音进程内直接调用 / 兜底线程共用）。 */
    public static void run(Context ctx, String raw, String source,
                           String hintTitle, String hintAuthor, String rawPath) {
        run(ctx, raw, source, hintTitle, hintAuthor, rawPath, null);
    }

    /** 实际处理逻辑（带引言）。 */
    public static void run(Context ctx, String raw, String source,
                           String hintTitle, String hintAuthor, String rawPath,
                           String abstractText) {
        if (running) {
            DyLog.w("[服务] 已有任务在跑，忽略本次");
            return;
        }
        running = true;
        try {
            boolean isArticle = SRC_ARTICLE.equals(source);
            // 第三方分享：内容通常夹杂大量无关东西，**强制走 AI 重写**（不论开关）
            boolean mustAi = !isArticle;
            boolean aiCfgOn = SharedCfg.getBool("ai_enabled", false);
            boolean aiOn = aiCfgOn || mustAi;
            String srcLabel = isArticle ? "抖音文章" : "第三方分享";
            DyLog.i("[服务] 来源=" + srcLabel + " | 强制AI=" + mustAi
                    + " | AI开关=" + aiCfgOn + " | 本次走AI=" + aiOn);

            // 1) 源文件：文章路径已由 hook 侧存好；第三方分享在这里存
            if (rawPath == null || rawPath.isEmpty()) {
                String rawTitle = (hintTitle != null && !hintTitle.trim().isEmpty())
                        ? hintTitle.trim() : "无标题";
                rawPath = FileSaver.saveRaw(rawTitle, hintAuthor, srcLabel, raw);
                DyLog.i("[服务] 源文件已保存: " + rawPath);
            } else {
                DyLog.i("[服务] 源文件已由 hook 保存: " + rawPath);
            }

            String title = hintTitle;
            String author = hintAuthor;
            String content = raw;
            String note = null;
            String aiAuthor = null;
            String aiTitle = null;

            // 2) AI 洗稿（第三方强制；抖音按开关）
            if (aiOn) {
                long t0 = System.currentTimeMillis();
                progress(ctx, mustAi ? "AI 重写中…" : "AI 分析中…");
                AiClient.Result r = mustAi
                        ? AiClient.analyzeMessy(raw, hintTitle, hintAuthor)
                        : AiClient.analyze(raw, isArticle, hintTitle, hintAuthor);
                if (r.ok) {
                    aiTitle = r.title;
                    aiAuthor = r.author;
                    content = r.content;
                    DyLog.i("[服务] AI 完成: title=" + aiTitle + " author=" + aiAuthor
                            + " len=" + content.length()
                            + " 耗时 " + (System.currentTimeMillis() - t0) + "ms");
                } else {
                    note = mustAi
                            ? "强制 AI 未生效(" + r.error + ")，已降级为本地洗稿"
                            : "AI 未生效(" + r.error + ")，成品按原文清洗保存";
                    DyLog.w("[服务] " + note);
                }
            } else {
                DyLog.i("[服务] 未走 AI");
            }

            // 3) 本地洗稿（不论走没走 AI，保存/发件前都统一过一遍）
            content = AiClient.cleanText(content);
            DyLog.i("[服务] 本地洗稿完成，正文长度=" + (content == null ? 0 : content.length()));

            // 4) 组装 标题 / 作者
            if (isArticle) {
                if (hintTitle != null && !hintTitle.trim().isEmpty()) {
                    title = hintTitle.trim();
                } else if (aiTitle != null && !aiTitle.trim().isEmpty()) {
                    title = aiTitle.trim();
                }

                String hookAuthor = (hintAuthor == null) ? "" : hintAuthor.trim();
                String useAi = (aiAuthor == null) ? "" : aiAuthor.trim();
                if (!hookAuthor.isEmpty()) {
                    author = hookAuthor;
                } else if (!useAi.isEmpty()) {
                    author = useAi;
                } else {
                    author = "未知作者";
                }
                if (!author.contains("by抖音")) author = author + "by抖音";

                // 标题少于 2 字、且没走 AI → 把作者昵称拼在后面
                // 例：标题「人」+ 作者「青岚」 → 「人by抖音青岚」
                if (!aiOn && title != null && title.trim().length() < 2) {
                    String nick = pureNickname(author);
                    if (!nick.isEmpty()) {
                        title = title.trim() + "by抖音" + nick;
                        DyLog.i("[服务] 标题不足 2 字，已拼上作者: " + title);
                    }
                }
            } else {
                // 第三方分享：标题以 AI 找到/总结的为准（提示词要求至少 3 字）
                if (aiTitle != null && !aiTitle.trim().isEmpty()) title = aiTitle.trim();
                if (aiAuthor != null && !aiAuthor.trim().isEmpty()) author = aiAuthor.trim();
                if (author == null) author = "";
            }
            DyLog.i("[服务] 最终: 标题=" + title + " | 作者=" + author);

            // 4) 存成品
            String path = FileSaver.saveNamed(title, author, content, srcLabel);
            if (path == null) {
                Notifier.fail(ctx, "保存失败，无法写入 Documents/dyhooktxt");
                return;
            }

            String name = new File(path).getName();
            DyLog.i("[服务] 成品已保存: " + path);

            // 5) 自动发件？
            if (SharedCfg.getBool("auto_send", false)) {
                DyLog.i("[服务] 自动发件已开启，直接发件");
                SendActionReceiver.sendNow(ctx, path, rawPath, true);
            } else {
                int notifId = Notifier.newTaskId();
                DyLog.i("[服务] 通知 ID = " + notifId);
                Notifier.done(ctx, notifId, name, path, rawPath, content.length());
            }
        } catch (Throwable t) {
            DyLog.e("[服务] 处理异常: " + t);
            Notifier.fail(ctx, String.valueOf(t.getMessage()));
        } finally {
            running = false;
            // 关键：寄生路径没有前台服务来 stopForeground，
            // 必须显式清掉「AI 分析中…」的进度通知，否则会永远卡在通知栏
            Notifier.cancelProgress(ctx);
            DyLog.i("[服务] 任务结束，进度通知已清理");
        }
    }

    /** 从「昵称（抖音号）by抖音」里取出纯昵称。 */
    private static String pureNickname(String author) {
        if (author == null) return "";
        String s = author.trim();
        if (s.endsWith("by抖音")) s = s.substring(0, s.length() - "by抖音".length());
        // 去掉结尾的（抖音号）
        s = s.replaceAll("[（(][^）)]{1,40}[）)]\\s*$", "");
        return s.trim();
    }

    private static void progress(Context ctx, String text) {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(Notifier.PROGRESS_ID, Notifier.progress(ctx, text));
        } catch (Throwable ignored) {
        }
    }
}
