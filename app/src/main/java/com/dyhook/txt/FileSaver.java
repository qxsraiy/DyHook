package com.dyhook.txt;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 落盘 /sdcard/Documents/dyhooktxt/。 */
public class FileSaver {

    public static final String OUT_DIR =
            Environment.getExternalStorageDirectory().getAbsolutePath() + "/Documents/dyhooktxt";

    public static void ensureDirCompat() {
        try {
            File d = new File(OUT_DIR);
            if (!d.exists()) d.mkdirs();
        } catch (Throwable ignored) {
        }
    }

    public static String ts() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    public static String nowReadable() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    /** 清洗成安全文件名片段。 */
    public static String sanitize(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        t = t.replaceAll("\\s+", " ");
        if (t.length() > max) t = t.substring(0, max);
        return t;
    }

    /** 源文件：源_时间戳_标题_作者.txt */
    public static String saveRaw(String title, String author, String source, String content) {
        ensureDirCompat();
        String t = sanitize(title, 36);
        if (t.isEmpty()) t = "无标题";
        String a = sanitize(author, 26);
        String name = "源_" + ts() + "_" + t + (a.isEmpty() ? "" : "_" + a) + ".txt";
        File f = new File(new File(OUT_DIR), name);
        StringBuilder sb = new StringBuilder();
        sb.append("【源文件】").append('\n');
        sb.append("标题：").append(title == null ? "" : title.trim()).append('\n');
        sb.append("作者：").append(author == null ? "" : author.trim()).append('\n');
        sb.append("来源：").append(source == null ? "" : source).append('\n');
        sb.append("时间：").append(nowReadable()).append('\n');
        sb.append("==========").append("\n\n");
        sb.append(content == null ? "" : content.trim());
        return write(f, sb.toString());
    }

    /** 成品：青空_标题_作者_时间戳.txt */
    public static String saveNamed(String title, String author, String content, String source) {
        ensureDirCompat();
        String t = sanitize(title, 40);
        String a = sanitize(author, 30);
        if (t.isEmpty()) t = "无标题";
        if (a.isEmpty()) a = "未知作者";

        String name = "青空_" + t + "_" + a + "_" + ts() + ".txt";
        File f = new File(new File(OUT_DIR), name);

        StringBuilder sb = new StringBuilder();
        sb.append("标题：").append(title == null ? "" : title.trim()).append('\n');
        sb.append("作者：").append(author == null ? "" : author.trim()).append('\n');
        sb.append("时间：").append(nowReadable()).append('\n');
        sb.append("来源：").append(source == null ? "" : source).append('\n');
        sb.append("==========").append("\n\n");
        sb.append(content == null ? "" : content.trim());
        return write(f, sb.toString());
    }

    private static String write(File f, String body) {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(body.getBytes("UTF-8"));
            return f.getAbsolutePath();
        } catch (Throwable t) {
            DyLog.e("写文件失败: " + t);
            try {
                Context c = UiCtx.context();
                if (c != null) {
                    File fb = new File(c.getFilesDir(), f.getName());
                    try (FileOutputStream fos = new FileOutputStream(fb)) {
                        fos.write(body.getBytes("UTF-8"));
                    }
                    return fb.getAbsolutePath();
                }
            } catch (Throwable t2) {
                DyLog.e("兜底写失败: " + t2);
            }
            return null;
        }
    }
}
