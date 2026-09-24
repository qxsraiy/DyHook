package com.dyhook.txt;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 模块自己的通知栏（不依赖抖音，模块独立显示）。 */
public class Notifier {

    public static final String CHANNEL_ID = "dyhook_ai_v2";
    /** 前台服务进度通知（会被 stopSelf 移除，所以和结果分开） */
    public static final int PROGRESS_ID = 1001;
    /** 处理结果通知（带「发件」按钮，保留在通知栏） */
    public static final int RESULT_ID = 1002;

    public static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "AI 文章分析",
                        NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("抖音文案提取的处理进度与结果");
                nm.createNotificationChannel(ch);
            }
        }
    }

    private static Notification.Builder base(Context c, String title, String text, boolean ongoing) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(c, CHANNEL_ID);
        } else {
            b = new Notification.Builder(c);
        }
        b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setShowWhen(true);
        return b;
    }

    /** 处理中（用于 startForeground，必须立即返回）。 */
    public static Notification progress(Context c, String text) {
        ensureChannel(c);
        return base(c, "正在分析文章", text, true).build();
    }

    /** 处理完成：带「发件」按钮。 */
    public static void done(Context c, String fileName, String filePath, int chars) {
        ensureChannel(c);
        // 用显式组件，因为本方法可能被抖音进程的 Context 调用
        Intent send = new Intent();
        send.setClassName("com.dyhook.txt", "com.dyhook.txt.SendActionReceiver");
        send.setAction(SendActionReceiver.ACTION_SEND_OUT);
        send.putExtra("path", filePath);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(c, 1, send, flags);

        Notification n = base(c, "✅ 已完成", fileName + "（" + chars + " 字）", false)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_send, "发件", pi).build())
                .build();
        post(c, RESULT_ID, n);
    }

    /** 处理失败。 */
    public static void fail(Context c, String reason) {
        ensureChannel(c);
        post(c, RESULT_ID, base(c, "❌ 处理失败",
                reason == null ? "未知错误" : reason, false).build());
    }

    private static void post(Context c, int id, Notification n) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(id, n);
        } catch (Throwable ignored) {
        }
    }

    public static void cancelProgress(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(PROGRESS_ID);
        } catch (Throwable ignored) {
        }
    }
}
