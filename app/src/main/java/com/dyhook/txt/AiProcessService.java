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
                fTitle = hintTitle, fAuthor = hintAuthor, fRaw = rawPath;
        new Thread(() -> {
            try {
                run(this, fText, fSource, fTitle, fAuthor, fRaw);
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
        if (running) {
            DyLog.w("[服务] 已有任务在跑，忽略本次");
            return;
        }
        running = true;
        try {
            boolean isArticle = SRC_ARTICLE.equals(source);
            boolean aiOn = SharedCfg.getBool("ai_enabled", false);
            String srcLabel = isArticle ? "抖音文章" : "个人复制";

            // 1) 源文件：文章路径已由 hook 侧存好；个人复制路径在这里存
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

            // 2) AI 分析
            if (aiOn) {
                DyLog.i("[服务] AI 分析中…");
                progress(ctx, "AI 分析中…");
                AiClient.Result r = AiClient.analyze(raw, isArticle, hintTitle, hintAuthor);
                if (r.ok) {
                    aiTitle = r.title;
                    aiAuthor = r.author;
                    content = r.content;
                    DyLog.i("[服务] AI 完成: title=" + aiTitle + " author=" + aiAuthor
                            + " len=" + content.length());
                } else {
                    note = "AI 未生效(" + r.error + ")，成品按原文保存";
                    DyLog.w("[服务] " + note);
                }
            } else {
                DyLog.i("[服务] AI 未开启，成品按原文保存");
            }

            // 3) 组装 标题 / 作者
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
            } else {
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
            String msg = name + "（" + content.length() + " 字）"
                    + "\n源文件：" + new File(rawPath).getName();
            if (note != null) msg = msg + "\n" + note;
            Notifier.done(ctx, msg, path, content.length());
        } catch (Throwable t) {
            DyLog.e("[服务] 处理异常: " + t);
            Notifier.fail(ctx, String.valueOf(t.getMessage()));
        } finally {
            running = false;
        }
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
