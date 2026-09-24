package com.dyhook.txt;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模块 App 与抖音进程(hook) 之间的共享配置（不同 uid，用公共目录文本文件传递）。
 */
public class SharedCfg {

    public static final String FILE = FileSaver.OUT_DIR + "/config.txt";

    public static synchronized Map<String, String> load() {
        Map<String, String> m = new LinkedHashMap<>();
        File f = new File(FILE);
        if (!f.exists()) return m;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                int i = line.indexOf('=');
                if (i > 0) m.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
            }
        } catch (Throwable t) {
            DyLog.w("读 config 失败: " + t);
        }
        return m;
    }

    public static synchronized void save(Map<String, String> m) {
        try {
            File d = new File(FileSaver.OUT_DIR);
            if (!d.exists()) d.mkdirs();
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : m.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            try (FileOutputStream fos = new FileOutputStream(FILE)) {
                fos.write(sb.toString().getBytes("UTF-8"));
            }
        } catch (Throwable t) {
            DyLog.w("写 config 失败: " + t);
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
