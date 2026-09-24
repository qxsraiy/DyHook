package com.dyhook.txt;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/** 从抖音进程里拿到 Application / 当前 Activity / 主线程 Handler。 */
public class UiCtx {

    private static Application app;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static Handler main() {
        return MAIN;
    }

    public static Application app() {
        if (app != null) return app;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method cur = at.getDeclaredMethod("currentActivityThread");
            cur.setAccessible(true);
            Object thread = cur.invoke(null);
            Method getApp = at.getDeclaredMethod("getApplication");
            getApp.setAccessible(true);
            app = (Application) getApp.invoke(thread);
        } catch (Throwable ignored) {
        }
        return app;
    }

    public static Context context() {
        Application a = app();
        return a != null ? a : null;
    }

    /** 取当前前台 Activity（反射 mActivities）。 */
    @SuppressWarnings("unchecked")
    public static Activity currentActivity() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method cur = at.getDeclaredMethod("currentActivityThread");
            cur.setAccessible(true);
            Object thread = cur.invoke(null);
            Field f = at.getDeclaredField("mActivities");
            f.setAccessible(true);
            Map<Object, Object> activities = (Map<Object, Object>) f.get(thread);
            if (activities == null) return null;
            for (Object rec : activities.values()) {
                Field af = rec.getClass().getDeclaredField("activity");
                af.setAccessible(true);
                Activity act = (Activity) af.get(rec);
                if (act == null) continue;
                Field paused = rec.getClass().getDeclaredField("paused");
                paused.setAccessible(true);
                if (!(Boolean) paused.get(rec)) {
                    return act;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 前置校验：当前必须停留在「文章详情页」。
     * 只有在这里分享，才认为是文章口令，其它页面分享一律放过。
     */
    public static boolean isOnArticlePage() {
        Activity act = currentActivity();
        if (act == null) return false;
        try {
            String n = act.getClass().getName();
            if (n.contains("searcharticle") || n.contains("ArticleDetailActivity")) return true;
            // 有些版本走 article 包
            return n.contains("aweme.article") && n.toLowerCase().contains("detail");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 弹一个 Toast（切到主线程，用抖音 App 的 Context）。 */
    public static void toast(String msg) {
        final Context ctx = context();
        if (ctx == null) return;
        main().post(() -> {
            try {
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        });
    }
}
