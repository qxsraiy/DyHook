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
        sb.append("1. **输出必须是纯文本**，绝对不允许出现任何 HTML 标签。\n");
        sb.append("   - 特别是 <br>、<br/>、<br />、<p>、</p>、<div>、<span>、<a>、<img> 等，");
        sb.append("一个都不能留。\n");
        sb.append("   - 需要换行就直接换行（用真正的换行符），不要写 <br/>。\n");
        sb.append("   - HTML 实体要还原成对应字符（&nbsp;→空格、&amp;→&、&quot;→\"、&lt;→<）。\n");
        sb.append("2. **不允许使用 markdown 语法**，因为正文会被当作纯文本渲染：\n");
        sb.append("   - 不要用 ``` 代码围栏，不要用 ` 反引号。\n");
        sb.append("   - **每行开头不要有 4 个及以上空格、也不要缩进、不要用 Tab** ——");
        sb.append("这会被渲染成「代码框」，是必须避免的。\n");
        sb.append("   - 不要用 # 做标题、不要用 > 做引用、不要用 - / * 做列表符号。\n");
        sb.append("   - 需要分段就用空行，需要强调就用中文引号或书名号。\n");
        sb.append("   - **原文里的 markdown 图片 `![说明](图片地址)` 整段删掉**。\n");
        sb.append("   - **markdown 链接 `[文字](地址)` 只保留文字**，地址删掉。\n");
        sb.append("   - **所有 URL 一律删掉**（含 douyinpic.com 图片地址、blockview:// 内部引用）。\n");
        sb.append("3. 修正错误的引号写法：\n");
        sb.append("   - 连续重复的引号（如 \"\"文字\"\"、''文字''）→ 修正成一对中文引号「」或\"\"。\n");
        sb.append("   - 中英文引号混用、左右引号不配对 → 统一成中文全角引号。\n");
        sb.append("4. 去掉转义残留：正文里出现的字面 \\n、\\t、\\\" 、\\\\ 等要还原成正常字符。\n");
        sb.append("5. 去掉分享口令与引流话术：\n");
        sb.append("   「2H-:/a …^^xxxx」「复制打开抖音」「长按复制打开抖音」");
        sb.append("「看看【xxx的作品】」「打开抖音看更多」之类，整行删掉。\n");
        sb.append("6. 清理排版：\n");
        sb.append("   - 合并连续的空行（最多保留一个空行分段）。\n");
        sb.append("   - 去掉行首行尾多余空格、去掉全角空格。\n");
        sb.append("   - 修掉被硬折行拆断的句子（同一段落内的换行合成一句）。\n");
        sb.append("7. 语言规范化（**只做轻度润色，不改原意、不增删信息**）：\n");
        sb.append("   - 网络用语、错别字、明显笔误 → 改成规范书面表达。\n");
        sb.append("   - 全角/半角混用、标点缺失 → 按中文标点规范统一。\n");
        sb.append("   - 不要改变作者的观点、语气与立场，不要摘要、不要扩写。\n");
        sb.append("8. content 里不要再包含标题行、作者行。\n");
        sb.append("9. 正文里正常出现的符号（#、——、【】、：、引号等）是正文的一部分，原样保留。\n");
        sb.append("10. 绝对不要编造原文中不存在的内容。\n");
        sb.append("11. title / author 字段里只放标题和作者本身，");
        sb.append("不要带括号注释、说明文字、来源标注、更不要带任何标签。\n\n");

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

    /**
     * 第三方分享专用：内容通常夹杂大量无关东西（打招呼、广告、表情、
     * 重复转发的历史记录、乱码、断句），必须让 AI **重写通顺**并**找出/总结标题**。
     * 这条路径不论 AI 开关都会走。
     */
    public static Result analyzeMessy(String rawText, String hintTitle, String hintAuthor) {
        Result r = new Result();
        r.url = buildEndpoint();
        String problem = configProblem();
        if (problem != null) {
            r.error = problem;
            return r;
        }
        try {
            String content = callApi(buildMessyPrompt(rawText, hintTitle, hintAuthor), 8000);
            r.raw = content;
            if (content == null || content.trim().isEmpty()) {
                r.error = "AI 返回为空";
                return r;
            }
            DyLog.i("[AI·第三方] 原始返回长度=" + content.length());
            parseStructured(content, r);
            r.ok = r.content != null && !r.content.trim().isEmpty();
            if (!r.ok) r.error = "AI 返回无法解析成 JSON";
            return r;
        } catch (Throwable t) {
            r.error = "调用异常：" + t.getMessage();
            DyLog.e("[AI·第三方] " + r.error);
            return r;
        }
    }

    /** 第三方分享的提示词：强调「内容混乱，需要重写通顺」。 */
    private static String buildMessyPrompt(String raw, String hintTitle, String hintAuthor) {
        StringBuilder sb = new StringBuilder();
        sb.append("下面是从第三方应用分享过来的一段文字。\n");
        sb.append("**这类内容通常非常混乱**：可能夹杂打招呼、寒暄、广告、推广、");
        sb.append("聊天记录、表情符号、话题标签、@提及、链接、乱码、重复内容、");
        sb.append("被截断的句子、以及和正文完全无关的段落。\n\n");
        sb.append("你的任务是：**把真正有价值的内容整理出来，重写通顺，并给出标题**。\n\n");
        sb.append("【输出要求】\n");
        sb.append("只输出一个 JSON 对象，不要任何其它文字，不要 markdown 代码块：\n");
        sb.append("  \"title\"  : 标题。\n");
        sb.append("  \"author\" : 作者或来源（能从原文看出来就填，看不出来填空字符串）。\n");
        sb.append("  \"content\": 整理并重写后的正文。\n\n");

        sb.append("【title 的规则 —— 重要】\n");
        sb.append("1. 原文里有明确标题，就用它。\n");
        sb.append("2. 原文没有标题，**你必须根据正文自己总结一个**。\n");
        sb.append("3. **标题至少 3 个字符**，不要输出 1-2 个字的短标题。\n");
        sb.append("4. 标题不超过 30 字，不要带引号、不要带「标题：」这类前缀。\n\n");

        sb.append("【content 的规则 —— 重写通顺】\n");
        sb.append("1. **删掉一切与正文无关的东西**：\n");
        sb.append("   - 打招呼、寒暄、客套（「你好」「在吗」「谢谢」之类）\n");
        sb.append("   - 广告、推广、引流、二维码说明、加群信息\n");
        sb.append("   - 话题标签（#xxx#）、@提及、表情符号（[微笑]、🔥 等）\n");
        sb.append("   - **markdown 图片语法 `![说明](图片地址)` 整段删掉**\n");
        sb.append("   - **markdown 链接 `[文字](地址)` 只保留文字，地址删掉**\n");
        sb.append("   - **所有 URL 一律删掉**（含 douyinpic.com、blockview:// 之类）\n");
        sb.append("   - 分享口令、来源标注（「来自 xxx」「转发自 xxx」）\n");
        sb.append("   - 聊天记录里的昵称、时间戳、系统提示\n");
        sb.append("2. **把被截断、语序混乱、重复啰嗦的句子重写通顺**，让它读起来像一篇正常文章。\n");
        sb.append("3. **不要改变原意、不要增删事实、不要扩写**，只是整理和通顺化。\n");
        sb.append("4. 输出必须是**纯文本**：\n");
        sb.append("   - 不允许出现任何 HTML 标签（`<br>` `<p>` 等），需要换行就直接换行。\n");
        sb.append("   - 不允许使用 markdown 语法（``` 围栏、反引号、`#` 标题、`>` 引用、`-` 列表）。\n");
        sb.append("   - **每行开头不要有空格或 Tab 缩进**（会被渲染成代码框）。\n");
        sb.append("5. 分段用空行；合并连续空行。\n");
        sb.append("6. content 里不要再包含标题行、作者行。\n\n");

        sb.append("【JSON 格式要求】\n");
        sb.append("字符串里的换行写成 \\n，双引号写成 \\\"，确保输出是合法 JSON。\n\n");
        if ((hintTitle != null && !hintTitle.isEmpty())
                || (hintAuthor != null && !hintAuthor.isEmpty())) {
            sb.append("【参考信息】（能对上就用，对不上以原文为准）\n");
            if (hintTitle != null && !hintTitle.isEmpty()) {
                sb.append("可能相关的标题 = ").append(hintTitle).append('\n');
            }
            if (hintAuthor != null && !hintAuthor.isEmpty()) {
                sb.append("可能相关的作者 = ").append(hintAuthor).append('\n');
            }
            sb.append('\n');
        }
        sb.append("【原始文本开始】\n").append(raw).append("\n【原始文本结束】\n");
        return sb.toString();
    }

    /**
     * 代码级强制清洗（纯本地正则，不依赖 AI、不联网）。
     *
     * 处理四类问题：
     *   1) 残留 HTML 标签与实体（尤其 <br/>）
     *   2) 会被 Flarum 渲染成「代码框」的内容（``` 围栏 / 反引号 / 行首缩进）
     *   3) 抖音分享口令与引流话术
     *   4) 排版噪声（零宽字符、重复标点、引号、占位符、多余空格）
     */
    public static String cleanText(String s) {
        if (s == null) return null;
        // ⚠ 第一步必须归一化换行符：抖音的正文常常是 \r\n（CRLF），
        //   不统一的话后面所有按 \n 写的正则（空行折叠、行尾空白、^$ 锚点）全部失效。
        String t = s.replace("\r\n", "\n").replace("\r", "\n").replace("\u2028", "\n");

        // ---------- 1. HTML 标签 ----------
        // 1a) <br> 家族 → 真换行
        t = t.replaceAll("(?i)<\\s*br\\s*/?\\s*>", "\n");
        // 1b) 段落/块级标签 → 换行
        t = t.replaceAll("(?i)<\\s*/\\s*(p|div|section|article|li|tr|h[1-6]|blockquote)"
                + "\\s*>", "\n");
        t = t.replaceAll("(?i)<\\s*(p|div|section|article|li|tr|h[1-6]|blockquote)"
                + "[^>]*>", "");
        // 1c) 其它常见标签整体去掉
        t = t.replaceAll("(?i)<\\s*/?\\s*(span|strong|b|em|i|u|s|del|ins|a|img|figure|"
                + "figcaption|table|thead|tbody|td|th|ul|ol|font|hr|pre|code|iframe|"
                + "video|audio|source|sup|sub|small|mark|abbr|cite|q)\\b[^>]*>", "");
        // 1d) 兜底：像标签的尖括号结构（保留数学比较符：必须像 <xxx> 或 </xxx>）
        t = t.replaceAll("<\\s*/?\\s*[a-zA-Z][a-zA-Z0-9]{0,12}(\\s[^<>]{0,200})?/?\\s*>", "");

        // ---------- 2. HTML 实体 ----------
        t = t.replace("&nbsp;", " ").replace("&#160;", " ")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#34;", "\"")
                .replace("&#39;", "'").replace("&apos;", "'")
                .replace("&mdash;", "—").replace("&ndash;", "–")
                .replace("&hellip;", "…").replace("&middot;", "·")
                .replace("&ldquo;", "“").replace("&rdquo;", "”")
                .replace("&lsquo;", "‘").replace("&rsquo;", "’")
                .replace("&amp;", "&");
        t = decodeNumericEntities(t);

        // ---------- 3. 代码框相关 ----------
        // 3a) 去掉 markdown 代码围栏（``` 或 ~~~ 整行）
        t = t.replaceAll("(?m)^[ \\t]*(```|~~~)[a-zA-Z0-9+#._-]*[ \\t]*$", "");
        // 3b) 去掉行内反引号
        t = t.replace("`", "");

        // ---------- 3.5 markdown 图片 / 链接 / 裸 URL ----------
        // 3.5a) 图片 ![alt](url) —— 整段删（抖音文章里的配图，正文不需要）
        t = t.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "");
        // 3.5b) 抖音内部块引用 [xx](blockview://...) —— 整段删
        t = t.replaceAll("\\[[^\\]]*\\]\\(blockview://[^)]*\\)", "");
        // 3.5c) 其它 markdown 链接 [文字](url) —— 保留文字，去掉 URL
        t = t.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        // 3.5d) 剩下的裸 URL —— 删
        t = t.replaceAll("https?://\\S+", "");
        t = t.replaceAll("blockview://\\S*", "");
        // 3.5e) 图片 URL 参数残留（如 "width=640 height=360"）
        t = t.replaceAll("(?m)\\s+(width|height)=\\d+(\\s+(width|height)=\\d+)*\\s*$", "");

        // ---------- 4. 分享口令 / 引流话术（整行删） ----------
        t = removeNoiseLines(t);

        // ---------- 5. 排版噪声 ----------
        // 5a) 零宽字符、BOM、控制字符（保留 \n）
        t = t.replaceAll("[\\u200B-\\u200F\\u202A-\\u202E\\u2060\\uFEFF]", "");
        t = t.replaceAll("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]", "");
        // 5b) 占位符（抖音的图片/视频/表情标记）
        t = t.replaceAll("\\[(图片|视频|动图|表情|音乐|链接|话题|位置|投票|商品|"
                + "音频|直播|合集|文章)\\]", "");
        // 5c) 行首缩进（markdown 代码块触发条件）
        t = stripLeadingIndent(t);
        // 5d) 行尾空白
        t = t.replaceAll("(?m)[ \\t]+$", "");
        // 5e) 省略号先处理（要在重复标点收敛之前，否则 。。。 会先被压成 。）
        t = t.replace("......", "……").replace("。。。", "……")
                .replace("···", "……").replace("...", "……");
        // 5f) 重复标点收敛（省略号已在上一步处理，这里排除连续句点）
        t = t.replaceAll("[。]{2,}", "。").replaceAll("[，]{2,}", "，")
                .replaceAll("[！]{2,}", "！").replaceAll("[？]{2,}", "？")
                .replaceAll("[、]{2,}", "、").replaceAll("[；]{2,}", "；")
                .replaceAll("[：]{2,}", "：")
                .replaceAll("……{2,}", "……");
        // 5g) 引号规范化
        t = t.replaceAll("\"{2,}", "\"").replaceAll("'{2,}", "'");
        t = t.replaceAll("\"([^\"\\n]{1,200})\"", "“$1”");
        t = t.replaceAll("'([^'\\n]{1,200})'", "‘$1’");
        // 5h) 中文之间的多余空格
        t = t.replaceAll("([\\u4e00-\\u9fa5])\\s+([\\u4e00-\\u9fa5])", "$1$2");
        // 5i) 标点前的多余空格（删链接后常留下 "文字与  。"）
        t = t.replaceAll("[ \\t]+([，。！？；：、）】》」』])", "$1");
        t = t.replaceAll("([（【《「『])[ \\t]+", "$1");
        // 5j) 行内多余空格收敛
        t = t.replaceAll("[ \\t]{2,}", " ");

        // ---------- 6. 收敛空行 ----------
        t = t.replaceAll("[ \\t]+\\n", "\n");
        t = t.replaceAll("\\n{3,}", "\n\n");
        t = t.replaceAll("(?m)^\\s*$\\n(?=\\s*$)", "");

        return t.trim();
    }

    /** 删掉分享口令、引流话术等噪声整行。 */
    private static String removeNoiseLines(String s) {
        // 整行匹配的噪声模式（大小写不敏感）
        String[] linePatterns = {
                // 分享口令：2H-:/a :5pm U@L.wS 07/07 …^^xxxx
                "^\\s*[0-9A-Za-z]{1,4}-?:?/?[0-9A-Za-z:.@/ ]{0,40}\\^\\^[0-9A-Za-z]{4,}\\s*$",
                "^\\s*\\^\\^[0-9A-Za-z]{4,}\\s*$",
                // 「长按复制打开抖音，即可阅读文章」
                ".*长按复制(打开)?抖音.*",
                ".*复制(此|本)?(链接|口令).*打开抖音.*",
                ".*复制(打开)?抖音.*",
                ".*打开抖音(看更多|搜索|app).*",
                ".*看看【.*的作品】.*",
                ".*【.*的作品】.*",
                ".*抖音(搜索|扫一扫|扫一下).*",
                ".*(关注|点赞|收藏)(我|一下)?(不迷路|哦|吧)?\\s*$",
                ".*点击(下方|链接).*(查看|阅读|了解).*",
                ".*(转发|分享)(自|至)?(微博|微信|抖音|头条|小红书).*",
        };
        String[] lines = s.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String l = line;
            boolean drop = false;
            String trimmed = l.trim();
            if (!trimmed.isEmpty()) {
                for (String p : linePatterns) {
                    if (trimmed.matches("(?i)" + p)) {
                        drop = true;
                        break;
                    }
                }
            }
            if (!drop) sb.append(l).append('\n');
        }
        return sb.toString();
    }

    /** 去掉每行行首的空白（4 个以上空格 / Tab / 全角空格都会触发 markdown 代码块）。 */
    private static String stripLeadingIndent(String s) {
        String[] lines = s.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String l = line;
            if (!l.isEmpty()) {
                int i = 0;
                while (i < l.length()) {
                    char c = l.charAt(i);
                    if (c == ' ' || c == '\t' || c == '\u3000' || c == '\u00A0') i++;
                    else break;
                }
                l = l.substring(i);
            }
            sb.append(l).append('\n');
        }
        return sb.toString();
    }

    /** 解码 &#96; / &#x60; 这类数字实体。 */
    private static String decodeNumericEntities(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("&#(x?)([0-9a-fA-F]{1,6});").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            try {
                int cp = m.group(1).isEmpty()
                        ? Integer.parseInt(m.group(2))
                        : Integer.parseInt(m.group(2), 16);
                m.appendReplacement(sb, java.util.regex.Matcher
                        .quoteReplacement(new String(Character.toChars(cp))));
            } catch (Throwable t) {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(sb);
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
            r.content = cleanText(o.optString("content", "").trim());
        } catch (Throwable t) {
            DyLog.w("[AI] JSON 解析失败，退回整段文本");
            r.content = cleanText(content.trim());
        }
        // 标题/作者也过一遍，防止带上标签
        r.title = cleanText(r.title);
        if (r.author != null) r.author = cleanText(r.author);
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
