package com.dyhook.txt;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模块 App 与抖音进程(hook) 之间的共享配置。
 *
 * ⚠ 关键坑：Android 11+ 的 FUSE 沙盒下，**没有存储权限的 App 只能访问自己创建的文件**。
 * 解析跑在抖音进程里，抖音读不到模块创建的 config.txt（EACCES），
 * 于是 auto_send / ai_enabled 全部读成默认值 —— 表现为「开了自动发件却不发」。
 *
 * 解决：让**抖音先创建**一个配置文件（douyin_cfg.txt）。之后：
 *   - 抖音能读/写它自己的文件（永远可以）
 *   - 模块有 MANAGE_EXTERNAL_STORAGE，也能读/写它
 * 读取时优先用这份，读不到再退回模块自己的 config.txt。
 *
 * （注：/sdcard/Android/data/<pkg>/ 对其它 App 是硬隔离的，连
 *   MANAGE_EXTERNAL_STORAGE 都进不去，所以不能用那个目录。）
 */
public class SharedCfg {

    /** 模块目录里的配置（模块自己读写，也作为人肉查看用）。 */
    public static final String FILE = FileSaver.OUT_DIR + "/config.txt";

    /** 由抖音进程创建、双方都能访问的配置文件。 */
    public static final String FILE_SHARED = FileSaver.OUT_DIR + "/douyin_cfg.txt";

    /** 由抖音进程创建、双方都能写的日志文件（抖音进程的日志只有写这里才留得下）。 */
    public static final String LOG_SHARED = FileSaver.OUT_DIR + "/douyin.log";

    /** 由抖音进程创建、双方都能访问的论坛配置（否则抖音进程读不到论坛凭据）。 */
    public static final String FORUM_SHARED = FileSaver.OUT_DIR + "/douyin_forum.txt";

    private static String[] files() {
        return new String[]{FILE_SHARED, FILE};
    }

    public static synchronized Map<String, String> load() {
        for (String p : files()) {
            Map<String, String> m = read(p);
            if (!m.isEmpty()) return m;
        }
        return new LinkedHashMap<>();
    }

    private static Map<String, String> read(String path) {
        Map<String, String> m = new LinkedHashMap<>();
        File f = new File(path);
        if (!f.exists()) return m;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                int i = line.indexOf('=');
                if (i > 0) m.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
            }
        } catch (Throwable t) {
            DyLog.w("读 config 失败(" + path + "): " + t);
        }
        return m;
    }

    public static synchronized void save(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        byte[] data;
        try {
            data = sb.toString().getBytes("UTF-8");
        } catch (Throwable t) {
            return;
        }
        for (String p : files()) {
            File f = new File(p);
            // douyin_cfg.txt 必须由抖音自己创建，模块只覆盖内容；
            // 如果还不存在（抖音没跑过），先不动它，等抖音创建后再写。
            if (FILE_SHARED.equals(p) && !f.exists()) {
                DyLog.i("等待抖音创建 " + FILE_SHARED);
                continue;
            }
            try {
                File d = f.getParentFile();
                if (d != null && !d.exists()) d.mkdirs();
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(data);
                }
                DyLog.i("config 已写入 " + p);
            } catch (Throwable t) {
                DyLog.w("写 config 失败(" + p + "): " + t);
            }
        }
    }

    /**
     * 抖音侧调用：确保 douyin_cfg.txt 存在（必须由抖音创建，FUSE 才允许抖音读）。
     * 返回是否可用。
     */
    public static boolean ensureSharedFile() {
        try {
            File d = new File(FileSaver.OUT_DIR);
            if (!d.exists()) d.mkdirs();
            File f = new File(FILE_SHARED);
            if (!f.exists()) {
                boolean ok = f.createNewFile();
                DyLog.i("创建 " + FILE_SHARED + " -> " + ok);
            }
            // 日志文件也必须由抖音创建，否则抖音进程写不进去（FUSE 沙盒）
            File lg = new File(LOG_SHARED);
            if (!lg.exists()) {
                boolean ok2 = lg.createNewFile();
                DyLog.i("创建 " + LOG_SHARED + " -> " + ok2);
            }
            // 论坛凭据同理：抖音进程要读它才知道往哪发
            File ff = new File(FORUM_SHARED);
            if (!ff.exists()) {
                boolean ok3 = ff.createNewFile();
                DyLog.i("创建 " + FORUM_SHARED + " -> " + ok3);
            }
            return f.exists() && f.canRead();
        } catch (Throwable t) {
            DyLog.w("创建共享配置失败: " + t);
            return false;
        }
    }

    public static boolean getBool(String key, boolean def) {
        String v = load().get(key);
        if (v == null) return def;
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    public static void setBool(String key, boolean val) {
        Map<String, String> m = load();
        m.put(key, val ? "1" : "0");
        save(m);
    }

    public static String get(String key, String def) {
        String v = load().get(key);
        return v == null ? def : v;
    }

    public static void set(String key, String val) {
        Map<String, String> m = load();
        m.put(key, val == null ? "" : val);
        save(m);
    }
}
