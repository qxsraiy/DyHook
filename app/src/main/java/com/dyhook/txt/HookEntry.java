package com.dyhook.txt;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * LSPosed (LibXposed API 102) 模块入口。
 * 框架读取 META-INF/xposed/java_init.list 得到本类名并实例化。
 *
 * ⚠ 本类只在「被注入的进程」里存在；模块 App 自己的进程没有 libxposed 类，
 * 所以其它类（Activity/Service/Receiver）一律不要引用 HookEntry，
 * 日志请用 DyLog。
 */
public class HookEntry extends XposedModule {

    public static final String TAG = DyLog.TAG;
    public static final String TARGET_PKG = "com.ss.android.ugc.aweme";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        // 把 LSPosed 日志出口交给独立日志类（它不依赖 libxposed）
        DyLog.setSink((level, msg) -> log(level, TAG, msg));
        log(Log.INFO, TAG, "onModuleLoaded process=" + param.getProcessName());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        DyLog.setSink((level, msg) -> log(level, TAG, msg));
        log(Log.INFO, TAG, "onPackageReady pkg=" + param.getPackageName());
        if (!TARGET_PKG.equals(param.getPackageName())) {
            return;
        }
        try {
            installNotifCancelReceiver();
        } catch (Throwable t) {
            log(Log.WARN, TAG, "注册取消通知接收器失败: " + t);
        }
        // 关键：由抖音进程创建共享配置文件，这样抖音自己永远能读
        // （FUSE 沙盒：无存储权限的 App 只能访问自己创建的文件）
        try {
            boolean ok = SharedCfg.ensureSharedFile();
            log(Log.INFO, TAG, "共享配置文件可用=" + ok
                    + "  auto_send=" + SharedCfg.getBool("auto_send", false)
                    + "  ai_enabled=" + SharedCfg.getBool("ai_enabled", false));
        } catch (Throwable t) {
            log(Log.WARN, TAG, "创建共享配置失败: " + t);
        }
        try {
            ShareInterceptor.install(this, param.getClassLoader());
            log(Log.INFO, TAG, "ShareInterceptor 安装完成");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "ShareInterceptor 安装失败", t);
        }
        try {
            Models.install(this, param.getClassLoader());
            log(Log.INFO, TAG, "Models 缓存 hook 安装完成");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Models 安装失败", t);
        }
    }

    /**
     * 在抖音进程里注册一个接收器：
     * 模块点「发件」后会广播过来，让抖音把自己发的那条「解析完成」通知撤掉，
     * 这样通知栏上只留模块自己发的那一条。
     */
    private void installNotifCancelReceiver() {
        final android.content.Context ctx = UiCtx.context();
        if (ctx == null) return;
        android.content.IntentFilter f = new android.content.IntentFilter(CANCEL_NOTIF_ACTION);
        ctx.registerReceiver(new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context c, android.content.Intent i) {
                if (i == null) return;
                int id = i.getIntExtra("notif_id", -1);
                if (id < 0) return;
                try {
                    android.app.NotificationManager nm =
                            (android.app.NotificationManager) c.getSystemService(
                                    android.content.Context.NOTIFICATION_SERVICE);
                    if (nm != null) nm.cancel(id);
                    DyLog.i("[通知] 已撤掉抖音侧通知 id=" + id);
                } catch (Throwable t) {
                    DyLog.w("[通知] 撤销失败: " + t);
                }
            }
        }, f);
        log(Log.INFO, TAG, "通知撤销接收器已注册");
    }

    /** 模块 -> 抖音：请撤掉指定 id 的通知。 */
    public static final String CANCEL_NOTIF_ACTION = "com.dyhook.txt.CANCEL_NOTIF";
}
