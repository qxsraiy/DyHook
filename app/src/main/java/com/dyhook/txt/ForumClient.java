package com.dyhook.txt;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Flarum 论坛客户端（JSON:API）。
 *
 * ⚠ 凭据安全：论坛地址/账号/密码只从设备本地文件读取，
 *   绝不写入代码、绝不进 git 仓库、绝不上传。
 *   本地文件：/sdcard/Documents/dyhooktxt/forum.txt
 *      url=https://example.com
 *      username=xxx
 *      password=xxx
 *      tag_slug=your-tag-slug  （可选，留空则不带标签发帖）
 *      tag_id=15             （可选，填了就不查 slug）
 */
public class ForumClient {

    public static final String CFG_FILE = FileSaver.OUT_DIR + "/forum.txt";

    public static class Cfg {
        public String url = "";
        public String username = "";
        public String password = "";
        public String tagSlug = "";
        public String tagId = "";

        public boolean ready() {
            return !url.isEmpty() && !username.isEmpty() && !password.isEmpty();
        }
    }

    public static class Result {
        public boolean ok;
        public String error;
        public String discussionUrl;
        public String token;
        public int userId;
    }

    /** 读本地论坛配置。 */
    public static Cfg loadCfg() {
        Cfg c = new Cfg();
        File f = new File(CFG_FILE);
        if (!f.exists()) return c;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                int i = line.indexOf('=');
                if (i <= 0) continue;
                String k = line.substring(0, i).trim();
                String v = line.substring(i + 1).trim();
                switch (k) {
                    case "url": c.url = v; break;
                    case "username": c.username = v; break;
                    case "password": c.password = v; break;
                    case "tag_slug": c.tagSlug = v; break;
                    case "tag_id": c.tagId = v; break;
                    default: break;
                }
            }
        } catch (Throwable t) {
            DyLog.w("[论坛] 读配置失败: " + t);
        }
        c.url = c.url.replaceAll("/+$", "");
        return c;
    }

    /** 写本地论坛配置（模块界面调用）。 */
    public static void saveCfg(Cfg c) {
        try {
            File d = new File(FileSaver.OUT_DIR);
            if (!d.exists()) d.mkdirs();
            StringBuilder sb = new StringBuilder();
            sb.append("url=").append(c.url == null ? "" : c.url.trim()).append('\n');
            sb.append("username=").append(c.username == null ? "" : c.username.trim()).append('\n');
            sb.append("password=").append(c.password == null ? "" : c.password.trim()).append('\n');
            sb.append("tag_slug=").append(c.tagSlug == null ? "" : c.tagSlug.trim()).append('\n');
            sb.append("tag_id=").append(c.tagId == null ? "" : c.tagId.trim()).append('\n');
            try (OutputStream os = new java.io.FileOutputStream(CFG_FILE)) {
                os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
            DyLog.i("[论坛] 配置已保存到 " + CFG_FILE);
        } catch (Throwable t) {
            DyLog.e("[论坛] 保存配置失败: " + t);
        }
    }

    // ---------- API ----------

    /** 登录拿 token。 */
    public static Result login(Cfg c) {
        Result r = new Result();
        try {
            JSONObject body = new JSONObject();
            body.put("identification", c.username);
            body.put("password", c.password);
            Resp resp = post(c.url + "/api/token", body.toString(), null);
            if (resp.code != 200) {
                r.error = "登录失败 HTTP " + resp.code + "：" + truncate(resp.text, 200);
                return r;
            }
            JSONObject o = new JSONObject(resp.text);
            r.token = o.optString("token", "");
            r.userId = o.optInt("userId", 0);
            r.ok = !r.token.isEmpty();
            if (!r.ok) r.error = "登录返回没有 token";
            DyLog.i("[论坛] 登录成功 userId=" + r.userId);
            return r;
        } catch (Throwable t) {
            r.error = "登录异常：" + t.getMessage();
            DyLog.e("[论坛] " + r.error);
            return r;
        }
    }

    /** 按 slug 查标签 id。 */
    public static String findTagId(Cfg c, String token, String slug) {
        try {
            Resp resp = get(c.url + "/api/tags", token);
            if (resp.code != 200) return null;
            JSONArray arr = new JSONObject(resp.text).optJSONArray("data");
            if (arr == null) return null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.getJSONObject(i);
                String s = t.optJSONObject("attributes").optString("slug", "");
                if (slug.equalsIgnoreCase(s)) return t.optString("id", null);
            }
        } catch (Throwable t) {
            DyLog.w("[论坛] 查标签失败: " + t);
        }
        return null;
    }

    /** 发新讨论贴。 */
    public static Result createDiscussion(Cfg c, String title, String content) {
        Result r = login(c);
        if (!r.ok) return r;

        try {
            // 解析标签（默认留空 = 不指定标签，需在模块界面手动填写）
            String tagId = c.tagId == null ? "" : c.tagId.trim();
            if (tagId.isEmpty()) {
                String slug = c.tagSlug == null ? "" : c.tagSlug.trim();
                if (!slug.isEmpty()) {
                    tagId = findTagId(c, r.token, slug);
                    DyLog.i("[论坛] 标签 " + slug + " -> id=" + tagId);
                    if (tagId == null) {
                        r.ok = false;
                        r.error = "找不到标签「" + slug + "」，请在模块界面检查标签 slug";
                        return r;
                    }
                } else {
                    DyLog.i("[论坛] 未配置标签，将不带标签发帖");
                }
            }

            JSONObject attrs = new JSONObject();
            attrs.put("title", title == null ? "无标题" : title);
            attrs.put("content", content == null ? "" : content);

            JSONObject data = new JSONObject();
            data.put("type", "discussions");
            data.put("attributes", attrs);

            if (tagId != null && !tagId.trim().isEmpty()) {
                JSONObject tagRef = new JSONObject();
                tagRef.put("type", "tags");
                tagRef.put("id", tagId.trim());
                JSONObject tags = new JSONObject();
                tags.put("data", new JSONArray().put(tagRef));
                JSONObject rel = new JSONObject();
                rel.put("tags", tags);
                data.put("relationships", rel);
            }

            JSONObject payload = new JSONObject();
            payload.put("data", data);

            Resp resp = post(c.url + "/api/discussions", payload.toString(), r.token);
            if (resp.code != 201 && resp.code != 200) {
                r.ok = false;
                r.error = "发帖失败 HTTP " + resp.code + "：" + truncate(resp.text, 300);
                DyLog.e("[论坛] " + r.error);
                return r;
            }
            JSONObject o = new JSONObject(resp.text);
            String id = o.optJSONObject("data").optString("id", "");
            r.discussionUrl = c.url + "/d/" + id;
            r.ok = true;
            DyLog.i("[论坛] 发帖成功: " + r.discussionUrl);
            return r;
        } catch (Throwable t) {
            r.ok = false;
            r.error = "发帖异常：" + t.getMessage();
            DyLog.e("[论坛] " + r.error);
            return r;
        }
    }

    // ---------- HTTP ----------

    private static class Resp {
        int code;
        String text;
    }

    private static Resp post(String url, String body, String token) throws Exception {
        return doReq(url, "POST", body, token);
    }

    private static Resp get(String url, String token) throws Exception {
        return doReq(url, "GET", null, token);
    }

    private static Resp doReq(String url, String method, String body, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "DyHook/1.0 (Android)");
        if (token != null && !token.isEmpty()) {
            c.setRequestProperty("Authorization", "Token " + token);
        }
        if (body != null) {
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        Resp r = new Resp();
        r.code = c.getResponseCode();
        r.text = readAll(r.code >= 200 && r.code < 300 ? c.getInputStream() : c.getErrorStream());
        return r;
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
