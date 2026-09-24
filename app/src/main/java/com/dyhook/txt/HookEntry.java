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
}
