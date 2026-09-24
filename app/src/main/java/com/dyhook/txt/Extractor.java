package com.dyhook.txt;

import android.content.Context;

import io.github.libxposed.api.XposedModule;

/**
 * 抖音文章解析执行器（跑在抖音进程里）。
 *
 * 顺序：
 *   1) 先把源文件落到 Documents/dyhooktxt/ —— 抖音进程有存储权限，
 *      这一步独立于模块 App，即使后面 AI 环节失败，源文件也保住了。
 *   2) 直接在本进程跑完整流程（AI + 落盘 + 通知），彻底避开跨进程拦截。
 */
public class Extractor {

    public static void run(XposedModule module, String shareText) {
        UiCtx.toast("开始下载文章…");
        try {
            Models.ArticleInfo a = Models.latestArticle();
            if (a == null || a.markdown == null || a.markdown.trim().isEmpty()) {
                DyLog.w("[提取] 未缓存到文章内容");
                UiCtx.toast("提取失败：没抓到文章正文，请确认已打开文章页面");
                return;
            }

            String title = safe(a.title, null);
            String author = buildAuthor(a);
            String content = a.markdown.trim();

            DyLog.i("[提取] 抓到文章: " + title + " / " + author
                    + " len=" + content.length());

            // ---------- 1) 先保存源文件（含作者） ----------
            String rawTitle = (title == null || title.isEmpty()) ? "无标题" : title;
            String rawAuthor = (author == null || author.isEmpty()) ? "" : author;
            String rawPath = FileSaver.saveRaw(rawTitle, rawAuthor, "抖音文章", content);
            if (rawPath == null) {
                DyLog.e("[提取] 源文件保存失败");
                UiCtx.toast("保存失败：无法写入 Documents/dyhooktxt");
                return;
            }
            DyLog.i("[提取] 源文件已保存: " + rawPath);

            // ---------- 2) 在本进程跑 AI + 落盘 + 通知 ----------
            Context ctx = UiCtx.context();
            if (ctx == null) {
                DyLog.e("[提取] 无 Context，跳过 AI 处理");
                UiCtx.toast("源文件已保存；拿不到上下文，跳过 AI");
                return;
            }
            new Thread(() -> AiProcessService.run(ctx, content,
                    AiProcessService.SRC_ARTICLE, title, author, rawPath),
                    "dyhook-ai").start();
            DyLog.i("[提取] 已在抖音进程内启动 AI 处理");
        } catch (Throwable t) {
            DyLog.e("[提取] 异常: " + t);
            UiCtx.toast("解析出错：" + t.getMessage());
        }
    }

    /** 作者显示为 "昵称（抖音号）"。 */
    private static String buildAuthor(Models.ArticleInfo a) {
        String name = safe(a.authorName, null);
        String id = safe(a.authorId, null);
        if (name != null && id != null && !name.equals(id)) return name + "（" + id + "）";
        if (name != null) return name;
        if (id != null) return id;
        return null;
    }

    private static String safe(String s, String def) {
        if (s == null) return def;
        String t = s.trim();
        return (t.isEmpty() || "null".equals(t)) ? def : t;
    }
}
