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
 *   解析完成（带「发件」按钮）
 *      ↓ 点发件
 *   发件中…（按钮消失）
 *      ↓
 *   发件成功 / 发件失败（带「重试」按钮）
 * 下一个任务用新的 ID → 新通知，旧通知保留作记录。
 */
public class Notifier {

    public static final String CHANNEL_ID = "dyhook_ai_v2";

    /** 任务通知 ID 基数（避免与前台服务进度通知冲突）。 */
    private static final int BASE = 2000;
    private static volatile int lastId = BASE;

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

    private static int piFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return flags;
    }

    private static PendingIntent sendPi(Context c, int notifId, String path, String rawPath) {
        Intent i = new Intent();
        i.setClassName("com.dyhook.txt", "com.dyhook.txt.SendActionReceiver");
        i.setAction(SendActionReceiver.ACTION_SEND_OUT);
        i.putExtra("path", path);
        i.putExtra("rawpath", rawPath);
        i.putExtra("notif_id", notifId);
        return PendingIntent.getBroadcast(c, notifId, i, piFlags());
    }

    // ---------- 状态 1：解析完成，带「发件」按钮 ----------

    public static void done(Context c, int notifId, String fileName, String filePath,
                            String rawPath, int chars) {
        ensureChannel(c);
        Notification n = base(c, "✅ 解析完成", fileName + "（" + chars + " 字）", false)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_send, "发件",
                        sendPi(c, notifId, filePath, rawPath)).build())
                .build();
        post(c, notifId, n);
    }

    // ---------- 状态 2：发件中（按钮消失） ----------

    public static void sending(Context c, int notifId, String fileName) {
        ensureChannel(c);
        post(c, notifId, base(c, "📤 发件中…", fileName, true).build());
    }

    /** 发件中（带重试计数）。 */
    public static void retrying(Context c, int notifId, String fileName, int attempt, int max) {
        ensureChannel(c);
        post(c, notifId, base(c, "📤 发件中…", fileName
                + "\n第 " + attempt + "/" + max + " 次尝试…", true).build());
    }

    // ---------- 状态 3：发件成功 ----------

    public static void sent(Context c, int notifId, String fileName, String url) {
        ensureChannel(c);
        post(c, notifId, base(c, "✅ 发件成功", fileName + "\n" + url, false).build());
    }

    // ---------- 状态 3'：发件失败（带「重试」按钮） ----------

    public static void sendFailed(Context c, int notifId, String fileName, String reason,
                                  String path, String rawPath) {
        ensureChannel(c);
        Notification.Builder b = base(c, "❌ 发件失败（已重试仍失败）",
                fileName + "\n" + (reason == null ? "未知错误" : reason)
                        + "\n文件已保留，可点「重试」再发。", false);
        if (path != null && !path.isEmpty()) {
            b.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_rotate, "重试",
                    sendPi(c, notifId, path, rawPath)).build());
        }
        post(c, notifId, b.build());
    }

    /** 无文件可重试的失败（配置错误等）。 */
    public static void sendFailed(Context c, int notifId, String fileName, String reason) {
        sendFailed(c, notifId, fileName, reason, null, null);
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

    /** 解析失败。 */
    public static void fail(Context c, String reason) {
        ensureChannel(c);
        post(c, PROGRESS_ID, base(c, "❌ 解析失败",
                reason == null ? "未知错误" : reason, false).build());
    }

    /**
     * 清掉进度通知。
     * 寄生路径（抖音进程内直接跑）没有前台服务来 stopForeground，
     * 必须显式取消，否则会永远卡在「AI 分析中」。
     */
    public static void cancelProgress(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(PROGRESS_ID);
        } catch (Throwable ignored) {
        }
    }

    private static void post(Context c, int id, Notification n) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(id, n);
        } catch (Throwable ignored) {
        }
    }
}
