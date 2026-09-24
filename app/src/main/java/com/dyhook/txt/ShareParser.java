package com.dyhook.txt;

import java.util.regex.Pattern;

/** 只负责识别「文章口令」，不再处理视频链接。 */
public class ShareParser {

    /** 文章口令特征：长按复制打开抖音 + 阅读文章 */
    private static final Pattern ARTICLE_HINT =
            Pattern.compile("阅读文章|长按复制打开抖音");
    /** 视频链接特征（出现这些就放过，不拦截） */
    private static final Pattern VIDEO_HINT =
            Pattern.compile("v\\.douyin\\.com|douyin\\.com/video|douyin\\.com/note|iesdouyin\\.com");

    /** 是不是文章分享口令（要拦截）。 */
    public static boolean isArticleShare(String text) {
        if (text == null || text.length() < 6) return false;
        // 带视频链接的 -> 不是文章
        if (VIDEO_HINT.matcher(text).find()) return false;
        return ARTICLE_HINT.matcher(text).find();
    }
}
