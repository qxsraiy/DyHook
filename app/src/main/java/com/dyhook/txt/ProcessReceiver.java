package com.dyhook.txt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 统一入口（广播兜底通道）。
 * 主通道是抖音进程内直接调用 AiProcessService.run()，本接收器只在
 * 模块 App 自己发起时使用。
 */
public class ProcessReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.dyhook.txt.PROCESS";
    public static final String PKG = "com.dyhook.txt";
    public static final String CLS = "com.dyhook.txt.ProcessReceiver";
    public static final String SVC = "com.dyhook.txt.AiProcessService";
    public static final String ACT = "com.dyhook.txt.ShareReceiverActivity";

    public static final String EXTRA_RAWPATH = "rawpath";

    private static Intent fill(Intent i, String text, String source,
                               String title, String author, String rawPath) {
        i.putExtra(AiProcessService.EXTRA_TEXT, text);
        i.putExtra(AiProcessService.EXTRA_SOURCE, source);
        i.putExtra(AiProcessService.EXTRA_TITLE, title);
        i.putExtra(AiProcessService.EXTRA_AUTHOR, author);
        i.putExtra(EXTRA_RAWPATH, rawPath);
        return i;
    }

    public static Intent buildIntent(String text, String source, String title,
                                     String author, String rawPath) {
        Intent i = new Intent(ACTION);
        i.setClassName(PKG, CLS);
        return fill(i, text, source, title, author, rawPath);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String text = intent.getStringExtra(AiProcessService.EXTRA_TEXT);
        String source = intent.getStringExtra(AiProcessService.EXTRA_SOURCE);
        String title = intent.getStringExtra(AiProcessService.EXTRA_TITLE);
        String author = intent.getStringExtra(AiProcessService.EXTRA_AUTHOR);
        String rawPath = intent.getStringExtra(EXTRA_RAWPATH);
        if (source == null) source = AiProcessService.SRC_PERSONAL;
        if (text == null || text.trim().isEmpty()) return;

        Intent svc = new Intent(context, AiProcessService.class);
        fill(svc, text, source, title, author, rawPath);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Throwable t) {
            final String ft = text.trim(), fs = source, fti = title, fau = author, frp = rawPath;
            final Context appCtx = context.getApplicationContext();
            final PendingResult pr = goAsync();
            new Thread(() -> {
                try {
                    AiProcessService.run(appCtx, ft, fs, fti, fau, frp);
                } finally {
                    try { pr.finish(); } catch (Throwable ignored) {}
                }
            }, "dyhook-fallback").start();
        }
    }
}
