package com.dyhook.txt;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 抓取文章正文 + 作者信息。
 *  正文: com.ss.ugc.aweme.ArticleInfoStruct  (articleId/articleTitle/articleContent)
 *  作者: com.ss.android.ugc.aweme.searcharticle.detail.model.ArticleDetailInfo
 *        -> aweme -> author -> nickname / uniqueId(抖音号)
 * 抖音的模型可能由 Gson/Unsafe 反序列化生成而不走构造函数，
 * 所以同时 hook 构造函数 + 所有方法，双保险捕获实例。
 */
public class Models {

    private static volatile Object articleInst;
    private static volatile Object detailInst;
    private static final java.util.LinkedList<Object> awemeList = new java.util.LinkedList<>();

    public static class ArticleInfo {
        public String id;
        public String title;
        public String markdown;
        public String authorName;
        public String authorId;
    }

    public static void install(XposedModule module, ClassLoader cl) {
        hookClass(module, cl, "com.ss.ugc.aweme.ArticleInfoStruct",
                inst -> articleInst = inst, "ArticleInfoStruct");
        hookClass(module, cl, "com.ss.android.ugc.aweme.searcharticle.detail.model.ArticleDetailInfo",
                inst -> detailInst = inst, "ArticleDetailInfo");
        hookAwemeAuthor(module, cl);
    }

    /** 兜底：hook Aweme 的相关方法拿实例（含父类），用于取作者。 */
    private static void hookAwemeAuthor(XposedModule module, ClassLoader cl) {
        String CLS = "com.ss.android.ugc.aweme.feed.model.Aweme";
        Class<?> cls;
        try {
            cls = Class.forName(CLS, false, cl);
        } catch (Throwable t) {
            return;
        }
        String[] names = {"getAuthor", "getArticleInfo", "getAid", "getAwemeId", "getDesc",
                "getAuthorUid", "getSecAuthorUid", "getOriginAuthor", "getSimpleDesc"};
        int n = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                String mn = m.getName();
                boolean want = false;
                for (String w : names) if (mn.equals(w)) want = true;
                if (!want || m.getParameterCount() != 0) continue;
                if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
                String key = mn + "@" + c.getName();
                if (!seen.add(key)) continue;
                try {
                    module.hook(m)
                            .setPriority(XposedInterface.PRIORITY_LOWEST)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                Object self = chain.getThisObject();
                                if (self != null) cacheAweme(self);
                                return chain.proceed();
                            });
                    n++;
                } catch (Throwable ignored) {
                }
            }
        }
        module.log(Log.INFO, DyLog.TAG, "Aweme 作者兜底 hook x" + n);
    }

    private static void cacheAweme(Object aweme) {
        try {
            synchronized (awemeList) {
                for (Object o : awemeList) if (o == aweme) return;
                awemeList.addFirst(aweme);
                while (awemeList.size() > 20) awemeList.removeLast();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 构造函数 + 所有方法双保险捕获实例。 */
    private static void hookClass(XposedModule module, ClassLoader cl, String clsName,
                                  java.util.function.Consumer<Object> onInst, String tag) {
        Class<?> cls;
        try {
            cls = Class.forName(clsName, false, cl);
        } catch (Throwable t) {
            module.log(Log.WARN, DyLog.TAG, "找不到 " + clsName);
            return;
        }
        int cn = 0, mn = 0;
        try {
            for (Constructor<?> c : cls.getDeclaredConstructors()) {
                module.hook(c)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object r = chain.proceed();
                            if (r != null) onInst.accept(r);
                            return r;
                        });
                cn++;
            }
        } catch (Throwable ignored) {
        }
        try {
            for (Method m : cls.getDeclaredMethods()) {
                String n = m.getName();
                if (n.startsWith("$") || n.equals("hashCode") || n.equals("toString")
                        || n.equals("describeContents") || n.equals("equals")
                        || n.equals("clone") || n.equals("finalize")) continue;
                if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
                try {
                    module.hook(m)
                            .setPriority(XposedInterface.PRIORITY_LOWEST)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                Object self = chain.getThisObject();
                                if (self != null) onInst.accept(self);
                                return chain.proceed();
                            });
                    mn++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        module.log(Log.INFO, DyLog.TAG, tag + " hook: ctor=" + cn + " method=" + mn);
    }

    /** 读当前缓存的文章（延迟读字段，避免构造时字段未填）。 */
    public static ArticleInfo latestArticle() {
        Object inst = articleInst;
        if (inst == null) return null;
        try {
            ArticleInfo a = new ArticleInfo();
            a.id = readField(inst, "articleId");
            a.title = readField(inst, "articleTitle");
            String content = readField(inst, "articleContent");
            if (content == null || content.length() < 40) return null;
            try {
                org.json.JSONObject o = new org.json.JSONObject(content);
                a.markdown = o.optString("markdown", null);
                if (a.markdown == null || a.markdown.isEmpty()) {
                    a.markdown = o.optString("long_article_abstract", null);
                }
                if (a.markdown == null || a.markdown.isEmpty()) a.markdown = content;
            } catch (Throwable t) {
                a.markdown = content;
            }
            readAuthor(a);
            return a;
        } catch (Throwable t) {
            DyLog.w("读文章字段失败: " + t);
            return null;
        }
    }

    /** 从 ArticleDetailInfo -> aweme -> author 里读昵称和抖音号；兜底扫 Aweme 列表。 */
    private static void readAuthor(ArticleInfo a) {
        Object det = detailInst;
        if (det != null) {
            Object aweme = readFieldObj(det, "aweme");
            if (aweme != null && fillAuthorFromAweme(a, aweme)) return;
        }
        synchronized (awemeList) {
            for (Object aweme : awemeList) {
                if (readFieldObj(aweme, "articleInfo") != null && fillAuthorFromAweme(a, aweme)) return;
            }
            for (Object aweme : awemeList) {
                if (fillAuthorFromAweme(a, aweme)) return;
            }
        }
    }

    private static boolean fillAuthorFromAweme(ArticleInfo a, Object aweme) {
        Object author = readFieldObj(aweme, "author");
        if (author == null) return false;
        String name = firstNonEmpty(
                readField(author, "nickname"),
                readField(author, "nickName"),
                readField(author, "uniqueId"));
        String id = firstNonEmpty(
                readField(author, "uniqueId"),
                readField(author, "unique_id"),
                readField(author, "shortId"),
                readField(author, "short_id"));
        if (name == null && id == null) return false;
        a.authorName = name;
        a.authorId = id;
        return true;
    }

    private static String firstNonEmpty(String... ss) {
        for (String s : ss) {
            if (s != null && !s.trim().isEmpty() && !"null".equals(s)) return s.trim();
        }
        return null;
    }

    static String readField(Object o, String name) {
        Object v = readFieldObj(o, name);
        return v == null ? null : v.toString();
    }

    static Object readFieldObj(Object o, String name) {
        try {
            Class<?> c = o.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(o);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
