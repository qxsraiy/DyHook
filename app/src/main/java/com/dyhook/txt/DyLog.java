package com.dyhook.txt;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 独立日志工具。
 *
 * ⚠ 关键：本类绝对不能引用 io.github.libxposed.* 或 HookEntry。
 * HookEntry 继承自 libxposed 的 XposedModule，而模块 App 自己的进程
 * 没有被 LSPosed 注入，那个类不存在；一旦在模块进程里触发加载 HookEntry
 * 就会 NoClassDefFoundError，导致 Activity/Service 全部无法实例化。
 */
public class DyLog {

    public static final String TAG = "DyHook";

    /** LSPosed 侧日志出口（只有被注入的进程才会设置）。 */
    public interface Sink {
        void log(int level, String msg);
    }

    private static volatile Sink sink;

    public static void setSink(Sink s) {
        sink = s;
    }

    public static void i(String msg) {
        write(Log.INFO, msg);
    }

    public static void w(String msg) {
        write(Log.WARN, msg);
    }

    public static void e(String msg) {
        write(Log.ERROR, msg);
    }

    private static final Object LOCK = new Object();
    private static volatile String privPath;

    private static void write(int level, String msg) {
        Sink s = sink;
        if (s != null) {
            try {
                s.log(level, msg);
            } catch (Throwable ignored) {
            }
        }
        if (level == Log.ERROR) Log.e(TAG, msg);
        else if (level == Log.WARN) Log.w(TAG, msg);
        else Log.i(TAG, msg);
        appendFile(level, msg);
    }

    private static String privLog() {
        if (privPath != null) return privPath;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method m = at.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            Object app = m.invoke(null);
            if (app instanceof Context) {
                privPath = new File(((Context) app).getFilesDir(), "dyhook.log").getAbsolutePath();
            }
        } catch (Throwable ignored) {
        }
        return privPath;
    }

    private static void appendFile(int level, String msg) {
        String lv = level == Log.ERROR ? "E" : (level == Log.WARN ? "W" : "I");
        String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                + " [" + lv + "] [" + android.os.Process.myPid() + "] " + msg + "\n";
        byte[] bytes;
        try {
            bytes = line.getBytes("UTF-8");
        } catch (Throwable t) {
            return;
        }
        // 1) 共享目录
        try {
            File d = new File(FileSaver.OUT_DIR);
            if (!d.exists()) d.mkdirs();
            synchronized (LOCK) {
                try (FileOutputStream fos = new FileOutputStream(new File(d, "dyhook.log"), true)) {
                    fos.write(bytes);
                }
            }
            return;
        } catch (Throwable ignored) {
        }
        // 2) 兜底：应用私有目录
        try {
            String p = privLog();
            if (p != null) {
                synchronized (LOCK) {
                    try (FileOutputStream fos = new FileOutputStream(new File(p), true)) {
                        fos.write(bytes);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
