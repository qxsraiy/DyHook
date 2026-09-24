package com.dyhook.txt;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * OpenAI 兼容接口客户端（/v1/chat/completions），支持 http 与 https，
 * 可用于 OpenAI / DeepSeek / NewAPI / 任意兼容网关。
 */
public class AiClient {

    public static class Result {
        public String title;
        public String author;
        public String content;
        public boolean ok;
        public String error;
        public String raw;
        public String url;
    }

    /** 把用户填的地址补全成完整 endpoint。 */
    public static String buildEndpoint() {
        String base = SharedCfg.get("ai_base_url", "").trim();
        if (base.isEmpty()) return "";
        String url = base.replaceAll("/+$", "");
        if (url.endsWith("/v1/chat/completions")) return url;
        if (url.endsWith("/v1")) return url + "/chat/completions";
        return url + "/v1/chat/completions";
    }

    /** 配置是否完整。 */
    public static String configProblem() {
        if (SharedCfg.get("ai_base_url", "").trim().isEmpty()) return "接口地址未填";
        if (SharedCfg.get("ai_key", "").trim().isEmpty()) return "API Key 未填";
        if (SharedCfg.get("ai_model", "").trim().isEmpty()) return "模型未填";
        return null;
    }

    /** 测试连通性：问 AI「你是谁」。 */
    public static Result testConnection() {
        Result r = new Result();
        r.url = buildEndpoint();
        String problem = configProblem();
        if (problem != null) {
            r.error = problem;
            return r;
        }
        try {
            String content = callApi("你是谁？请用一句话介绍你自己。", 200);
            r.raw = content;
            if (content == null) {
                r.error = "没有拿到回复";
                return r;
            }
            r.content = content;
            r.ok = true;
            return r;
        } catch (Throwable t) {
            r.error = t.getMessage();
            return r;
        }
    }

    /** 结构化分析：把原始文本拆成 标题 / 作者 / 正文。 */
    public static Result analyze(String rawText, boolean isArticle,
                                 String hintTitle, String hintAuthor) {
        Result r = new Result();
        r.url = buildEndpoint();
        String problem = configProblem();
        if (problem != null) {
            r.error = problem;
            return r;
        }
        try {
            String content = callApi(buildPrompt(rawText, isArticle, hintTitle, hintAuthor), 8000);
            r.raw = content;
            if (content == null || content.trim().isEmpty()) {
                r.error = "AI 返回为空";
                return r;
            }
            DyLog.i("[AI] 原始返回长度=" + content.length());
            parseStructured(content, r);
            r.ok = r.content != null && !r.content.trim().isEmpty();
            if (!r.ok) r.error = "AI 返回无法解析成 JSON";
            return r;
        } catch (Throwable t) {
            r.error = t.getMessage();
            DyLog.e("[AI] 调用异常: " + t);
            return r;
        }
    }

    // ---------- 底层请求 ----------

    private static String callApi(String prompt, int maxTokens) throws Exception {
        String url = buildEndpoint();
        String key = SharedCfg.get("ai_key", "").trim();
        String model = SharedCfg.get("ai_model", "").trim();
        if (url.isEmpty()) throw new IllegalStateException("接口地址为空");

        JSONObject msg = new JSONObject();
        msg.put("role", "user");
        msg.put("content", prompt);

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", new JSONArray().put(msg));
        body.put("temperature", 0.1);
        body.put("max_tokens", maxTokens);
        body.put("stream", false);

        DyLog.i("[AI] 请求: " + url + " model=" + model);

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(180000);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Accept", "application/json");
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = c.getResponseCode();
        String respText = readAll(code == 200 ? c.getInputStream() : c.getErrorStream());
        if (code != 200) {
            throw new IllegalStateException("HTTP " + code + "：" + truncate(respText, 300));
        }

        JSONObject resp = new JSONObject(respText);
        return resp.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content");
    }

    // ---------- 提示词 ----------

    private static String buildPrompt(String raw, boolean isArticle,
                                      String hintTitle, String hintAuthor) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个中文文本整理助手。请阅读下面的【原始文本】，");
        sb.append("把它整理成结构化数据，并**清洗成正规的书面语言**。\n\n");
        sb.append("【输出要求】\n");
        sb.append("只输出一个 JSON 对象，不要输出任何其它文字，不要用 markdown 代码块包裹。\n");
        sb.append("JSON 必须且只能有这三个字段：\n");
        sb.append("  \"title\"  : 标题。原文有标题就用原文标题；没有就根据正文概括，不超过 30 字。\n");
        sb.append("  \"author\" : 作者或来源。原文里没有明确作者就填空字符串。\n");
        sb.append("  \"content\": 清洗后的正文全文。\n\n");

        sb.append("【content 的清洗规则 —— 这是重点，务必执行】\n");
        sb.append("1. 去掉所有 HTML 标签：<br>、<br/>、<br />、<p>、</p>、<div>、<span>、");
        sb.append("<a ...>、<img ...>、&nbsp;、&amp;、&lt;、&gt; 等。\n");
        sb.append("   - <br> 这类换行标签要**转成真正的换行**，不要直接删掉导致文字粘连。\n");
        sb.append("   - HTML 实体要还原成对应字符（&nbsp;→空格、&amp;→&、&quot;→\"、&lt;→<）。\n");
        sb.append("2. 修正错误的引号写法：\n");
        sb.append("   - 连续重复的引号（如 \"\"文字\"\"、''文字''、``文字``）→ 修正成一对中文引号「」或\"\"。\n");
        sb.append("   - 中英文引号混用、左右引号不配对 → 统一成中文全角引号 \" \" 或 「 」。\n");
        sb.append("3. 去掉转义残留：正文里出现的字面 \\n、\\t、\\\" 、\\\\ 等要还原成正常字符。\n");
        sb.append("4. 去掉分享口令与引流话术：\n");
        sb.append("   「2H-:/a …^^xxxx」「复制打开抖音」「长按复制打开抖音」");
        sb.append("「看看【xxx的作品】」「打开抖音看更多」之类，整行删掉。\n");
        sb.append("5. 清理排版：\n");
        sb.append("   - 合并连续的空行（最多保留一个空行分段）。\n");
        sb.append("   - 去掉行首行尾多余空格、去掉全角空格。\n");
        sb.append("   - 修掉被硬折行拆断的句子（同一段落内的换行合成一句）。\n");
        sb.append("6. 语言规范化（**只做轻度润色，不改原意、不增删信息**）：\n");
        sb.append("   - 网络用语、错别字、明显笔误 → 改成规范书面表达。\n");
        sb.append("   - 全角/半角混用、标点缺失 → 按中文标点规范统一。\n");
        sb.append("   - 不要改变作者的观点、语气与立场，不要摘要、不要扩写。\n");
        sb.append("7. content 里不要再包含标题行、作者行。\n");
        sb.append("8. 正文里正常出现的符号（#、——、【】、：、引号等）是正文的一部分，原样保留。\n");
        sb.append("9. 绝对不要编造原文中不存在的内容。\n");
        sb.append("10. title / author 字段里只放标题和作者本身，");
        sb.append("不要带括号注释、说明文字、来源标注。\n\n");

        sb.append("【JSON 格式要求】\n");
        sb.append("字符串里的换行写成 \\n，双引号写成 \\\"，确保输出是合法 JSON。\n\n");
        if (isArticle) {
            sb.append("【本次来源】抖音长文章\n");
            if ((hintTitle != null && !hintTitle.isEmpty())
                    || (hintAuthor != null && !hintAuthor.isEmpty())) {
                sb.append("【已知信息】（优先采用；字段值里不要带上这里的任何说明文字）\n");
                if (hintTitle != null && !hintTitle.isEmpty()) {
                    sb.append("标题 = ").append(hintTitle).append('\n');
                }
                if (hintAuthor != null && !hintAuthor.isEmpty()) {
                    sb.append("作者 = ").append(hintAuthor).append('\n');
                }
            }
        } else {
            sb.append("【本次来源】用户复制/识屏的文本\n");
            sb.append("这类文本常常夹带无关内容（打招呼、广告、无关标题等），");
            sb.append("除了标题/作者/正文之外的东西，直接丢弃。\n");
        }
        sb.append("\n【原始文本开始】\n").append(raw).append("\n【原始文本结束】\n");
        return sb.toString();
    }

    /** 从 AI 返回里抽出 JSON（容错：可能带 ```json 包裹或前后废话）。 */
    private static void parseStructured(String content, Result r) {
        String s = content.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            int end = s.lastIndexOf("```");
            if (end > 0) s = s.substring(0, end);
            s = s.trim();
        }
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a >= 0 && b > a) s = s.substring(a, b + 1);
        try {
            JSONObject o = new JSONObject(s);
            r.title = o.optString("title", "").trim();
            r.author = o.optString("author", "").trim();
            r.content = o.optString("content", "").trim();
        } catch (Throwable t) {
            DyLog.w("[AI] JSON 解析失败，退回整段文本");
            r.content = content.trim();
        }
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
