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
    private static volatile Object respInst;
    private static final java.util.LinkedList<Object> awemeList = new java.util.LinkedList<>();

    public static class ArticleInfo {
        public String id;
        public String title;
        public String markdown;
        /** 引言 / 摘要 / 备注（如果有），要放在正文最上面 */
        public String abstractText;
        public String authorName;
        public String authorId;
    }

    public static void install(XposedModule module, ClassLoader cl) {
        hookClass(module, cl, "com.ss.ugc.aweme.ArticleInfoStruct",
                inst -> articleInst = inst, "ArticleInfoStruct");
        hookClass(module, cl, "com.ss.android.ugc.aweme.searcharticle.detail.model.ArticleDetailInfo",
                inst -> detailInst = inst, "ArticleDetailInfo");
        hookClass(module, cl, "com.ss.android.ugc.aweme.feed.search_article.api.ArticleDetailResponse",
                inst -> respInst = inst, "ArticleDetailResponse");
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
                // 引言 / 备注：抖音把长文章的摘要放在这些 key 里，各版本名字不一
                a.abstractText = firstNonEmpty(
                        o.optString("long_article_abstract", null),
                        o.optString("article_abstract", null),
                        o.optString("abstract", null),
                        o.optString("summary", null),
                        o.optString("digest", null),
                        o.optString("introduction", null),
                        o.optString("lead", null),
                        o.optString("preface", null),
                        o.optString("desc", null),
                        o.optString("description", null));
                a.markdown = o.optString("markdown", null);
                if (a.markdown == null || a.markdown.isEmpty()) {
                    a.markdown = o.optString("long_article_abstract", null);
                }
                if (a.markdown == null || a.markdown.isEmpty()) a.markdown = content;
                DyLog.i("[文章] JSON keys=" + keysOf(o)
                        + " | 引言=" + (a.abstractText == null ? "无"
                        : a.abstractText.length() + "字"));
            } catch (Throwable t) {
                a.markdown = content;
            }
            diag(inst, a.id);
            readAuthor(a);
            return a;
        } catch (Throwable t) {
            DyLog.w("读文章字段失败: " + t);
            return null;
        }
    }

    /** 诊断：一次真机测试就能看出作者藏在哪个源里。 */
    private static void diag(Object inst, String articleId) {
        try {
            StringBuilder sb = new StringBuilder("[作者诊断] articleId=" + articleId);
            sb.append(" | ArticleInfoStruct=").append(articleInst != null ? "有" : "无");
            sb.append(" | DetailInfo=").append(detailInst != null ? "有" : "无");
            sb.append(" | Response=").append(respInst != null ? "有" : "无");
            synchronized (awemeList) {
                sb.append(" | Aweme缓存=").append(awemeList.size());
                int match = 0;
                for (Object w : awemeList) if (sameArticle(w, articleId)) match++;
                sb.append("(匹配 ").append(match).append(")");
            }
            // JSON 字段是否有内容
            String fe = readField(inst, "feData");
            String ex = readField(inst, "articleExtra");
            sb.append(" | feData=").append(fe == null ? 0 : fe.length());
            sb.append(" | articleExtra=").append(ex == null ? 0 : ex.length());
            DyLog.i(sb.toString());

            // 若 JSON 有内容，把可能的作者 key 打出来
            for (String j : new String[]{fe, ex}) {
                if (j == null || j.length() < 10) continue;
                String name = pickJson(j, new String[]{"nickname", "nick_name", "author_name",
                        "authorName", "user_name", "userName", "author_nickname"});
                String id = pickJson(j, new String[]{"unique_id", "uniqueId", "short_id",
                        "shortId", "sec_uid", "secUid"});
                if (name != null || id != null) {
                    DyLog.i("[作者诊断] JSON 里找到 name=" + name + " id=" + id);
                }
            }
        } catch (Throwable t) {
            DyLog.w("[作者诊断] 失败: " + t);
        }
    }

    /**
     * 读作者。**四级兜底，目标：必须拿到作者名**。
     *
     * 1) ArticleDetailInfo.aweme.author（当前文章的详情对象）
     * 2) ArticleDetailResponse.d（API 响应里的 Aweme）
     * 3) awemeList 里 articleInfo.articleId 与当前文章一致的 Aweme
     * 4) ArticleInfoStruct 里的 articleExtra / feData JSON（Lynx 前端数据，常带作者）
     *
     * 前三级要求 articleId 匹配，避免串到上一条；第 4 级只看当前文章自己的字段。
     */
    private static void readAuthor(ArticleInfo a) {
        // 1) ArticleDetailInfo.aweme
        Object det = detailInst;
        if (det != null) {
            Object aweme = readFieldObj(det, "aweme");
            if (aweme != null && fillAuthorFromAweme(a, aweme)) {
                DyLog.i("[作者] 来源=ArticleDetailInfo");
                return;
            }
        }
        // 2) ArticleDetailResponse.d
        Object resp = respInst;
        if (resp != null) {
            Object aweme = readFieldObj(resp, "d");
            if (aweme == null) aweme = readFieldObj(resp, "aweme");
            if (aweme != null && fillAuthorFromAweme(a, aweme)) {
                DyLog.i("[作者] 来源=ArticleDetailResponse");
                return;
            }
        }
        // 3) 缓存里 articleId 严格一致的 Aweme
        if (a.id != null && !a.id.isEmpty()) {
            synchronized (awemeList) {
                for (Object aweme : awemeList) {
                    if (sameArticle(aweme, a.id) && fillAuthorFromAweme(a, aweme)) {
                        DyLog.i("[作者] 来源=Aweme 缓存(articleId 严格匹配)");
                        return;
                    }
                }
            }
        }
        // 4) 缓存里「带 articleInfo 的 Aweme」——只认文章类，绝不碰视频
        //    （这一层是之前能拿到作者的关键路径，不能砍）
        synchronized (awemeList) {
            for (Object aweme : awemeList) {
                if (readFieldObj(aweme, "articleInfo") != null
                        && fillAuthorFromAweme(a, aweme)) {
                    DyLog.i("[作者] 来源=Aweme 缓存(文章类，含 articleInfo)");
                    return;
                }
            }
        }
        // 5) 当前文章自己的 JSON 字段（articleExtra / feData）
        Object inst = articleInst;
        if (inst != null) {
            String[] jsons = {readField(inst, "feData"), readField(inst, "articleExtra")};
            String[] keys = {"nickname", "nick_name", "author_name", "authorName",
                    "unique_id", "uniqueId", "short_id", "shortId", "sec_uid", "secUid",
                    "user_name", "userName", "author_nickname"};
            for (String j : jsons) {
                if (j == null || j.length() < 10) continue;
                String name = pickJson(j, new String[]{"nickname", "nick_name", "author_name",
                        "authorName", "user_name", "userName", "author_nickname"});
                String id = pickJson(j, new String[]{"unique_id", "uniqueId", "short_id",
                        "shortId", "sec_uid", "secUid"});
                if (name != null || id != null) {
                    a.authorName = name;
                    a.authorId = id;
                    DyLog.i("[作者] 来源=JSON 字段 name=" + name + " id=" + id);
                    return;
                }
            }
        }
        DyLog.w("[作者] 四级兜底都没拿到作者，留空（避免串到上一条）");
    }

    /**
     * 从 JSON 字符串里按 key 名字找第一个非空字符串值。
     * 不依赖固定结构，递归扫（抖音的 feData 结构经常变）。
     */
    private static String pickJson(String json, String[] keys) {
        try {
            Object o = new org.json.JSONTokener(json).nextValue();
            return findKey(o, keys, 0);
        } catch (Throwable t) {
            // 不是合法 JSON，退回正则粗扫
            for (String k : keys) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\"" + k + "\"\\s*:\\s*\"([^\"]{1,60})\"").matcher(json);
                if (m.find()) {
                    String v = m.group(1).trim();
                    if (!v.isEmpty() && !"null".equals(v)) return v;
                }
            }
            return null;
        }
    }

    private static String findKey(Object o, String[] keys, int depth) {
        if (o == null || depth > 12) return null;
        if (o instanceof org.json.JSONObject) {
            org.json.JSONObject jo = (org.json.JSONObject) o;
            for (String k : keys) {
                String v = jo.optString(k, null);
                if (v != null && !v.trim().isEmpty() && !"null".equals(v)) return v.trim();
            }
            java.util.Iterator<String> it = jo.keys();
            while (it.hasNext()) {
                String r = findKey(jo.opt(it.next()), keys, depth + 1);
                if (r != null) return r;
            }
        } else if (o instanceof org.json.JSONArray) {
            org.json.JSONArray ja = (org.json.JSONArray) o;
            for (int i = 0; i < ja.length(); i++) {
                String r = findKey(ja.opt(i), keys, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** 这个 Aweme 是不是当前这篇文章。 */
    private static boolean sameArticle(Object aweme, String articleId) {
        if (aweme == null || articleId == null || articleId.isEmpty()) return false;
        Object ai = readFieldObj(aweme, "articleInfo");
        if (ai != null) {
            String id = firstNonEmpty(readField(ai, "articleId"), readField(ai, "article_id"));
            if (articleId.equals(id)) return true;
        }
        return false;
    }

    /** 从 Aweme 里挖作者：多种字段名 + 嵌套结构都试。 */
    private static boolean fillAuthorFromAweme(ArticleInfo a, Object aweme) {
        Object author = readFieldObj(aweme, "author");
        if (author == null) author = readFieldObj(aweme, "authorUser");
        if (author == null) author = readFieldObj(aweme, "user");
        if (author == null) return false;

        String name = firstNonEmpty(
                readField(author, "nickname"),
                readField(author, "nickName"),
                readField(author, "nick_name"),
                readField(author, "displayName"),
                readField(author, "name"),
                readField(author, "uniqueId"));
        String id = firstNonEmpty(
                readField(author, "uniqueId"),
                readField(author, "unique_id"),
                readField(author, "shortId"),
                readField(author, "short_id"),
                readField(author, "uid"),
                readField(author, "secUid"));
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

    /** 列出 JSON 的顶层 key，便于确认摘要字段叫什么。 */
    private static String keysOf(org.json.JSONObject o) {
        try {
            StringBuilder sb = new StringBuilder();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                if (sb.length() > 0) sb.append(',');
                sb.append(it.next());
            }
            String s = sb.toString();
            return s.length() > 200 ? s.substring(0, 200) + "…" : s;
        } catch (Throwable t) {
            return "?";
        }
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
