package com.dyhook.txt;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 模块通知栏。
 *
 * 设计：**每个任务一个独立通知 ID**，状态在同一通知上流转：
 *   已完成（带「发件」按钮）
 *      ↓ 点发件
 *   发件中…（按钮消失）
 *      ↓
 *   发件成功 / 发件失败
 * 下一个任务用新的 ID → 新通知，旧通知保留作记录。
 */
public class Notifier {

    public static final String CHANNEL_ID = "dyhook_ai_v2";

    /** 任务通知 ID 基数（避免与前台服务进度通知冲突）。 */
    private static final int BASE = 2000;
    private static volatile int lastId = BASE;

    /** 生成一个新任务的通知 ID。 */
    public static synchronized int newTaskId() {
        int id = BASE + (int) (System.currentTimeMillis() / 1000 % 100000);
        if (id <= lastId) id = lastId + 1;
        lastId = id;
        return id;
    }

    public static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "文章解析与发件",
                        NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("抖音文案提取的解析进度、结果与发件状态");
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

    // ---------- 状态 1：解析完成，带「发件」按钮 ----------

    public static void done(Context c, int notifId, String fileName, String filePath, int chars) {
        ensureChannel(c);

        Intent send = new Intent();
        send.setClassName("com.dyhook.txt", "com.dyhook.txt.SendActionReceiver");
        send.setAction(SendActionReceiver.ACTION_SEND_OUT);
        send.putExtra("path", filePath);
        send.putExtra("notif_id", notifId);      // 让它回来更新同一条通知
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(c, notifId, send, flags);

        Notification n = base(c, "✅ 解析完成", fileName + "（" + chars + " 字）", false)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_send, "发件", pi).build())
                .build();
        post(c, notifId, n);
    }

    // ---------- 状态 2：发件中（按钮消失） ----------

    public static void sending(Context c, int notifId, String fileName) {
        ensureChannel(c);
        post(c, notifId, base(c, "📤 发件中…", fileName, true).build());
    }

    // ---------- 状态 3：发件成功 ----------

    public static void sent(Context c, int notifId, String fileName, String url) {
        ensureChannel(c);
        post(c, notifId, base(c, "✅ 发件成功", fileName + "\n" + url, false).build());
    }

    // ---------- 状态 3'：发件失败 ----------

    public static void sendFailed(Context c, int notifId, String fileName, String reason) {
        ensureChannel(c);
        post(c, notifId, base(c, "❌ 发件失败",
                fileName + "\n" + (reason == null ? "未知错误" : reason), false).build());
    }

    // ---------- 解析进度（前台服务用，固定 ID） ----------

    public static final int PROGRESS_ID = 1001;

    public static Notification progress(Context c, String text) {
        ensureChannel(c);
        return base(c, "正在分析文章", text, true).build();
    }

    public static void progressUpdate(Context c, String text) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(PROGRESS_ID, progress(c, text));
        } catch (Throwable ignored) {
        }
    }

    /** 解析失败（独立提示）。 */
    public static void fail(Context c, String reason) {
        ensureChannel(c);
        post(c, PROGRESS_ID, base(c, "❌ 解析失败",
                reason == null ? "未知错误" : reason, false).build());
    }

    private static void post(Context c, int id, Notification n) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(id, n);
        } catch (Throwable ignored) {
        }
    }
}
