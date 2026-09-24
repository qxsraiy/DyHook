package com.dyhook.txt;

import android.content.ClipData;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 只拦截「文章口令」的复制动作：直接解析落盘，不弹框、不拦截视频链接。
 */
public class ShareInterceptor {

    private static final ThreadLocal<Boolean> BYPASS = new ThreadLocal<>();
    private static volatile boolean busy = false;

    public static void install(XposedModule module, ClassLoader cl) throws Throwable {
        Class<?> cmClass = Class.forName("android.content.ClipboardManager", false, cl);
        Method setPrimaryClip = cmClass.getDeclaredMethod("setPrimaryClip", ClipData.class);

        module.hook(setPrimaryClip)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    if (Boolean.TRUE.equals(BYPASS.get())) return chain.proceed();
                    if (!SharedCfg.getBool("enabled", true)) return chain.proceed();

                    ClipData clip = (ClipData) chain.getArg(0);
                    String text = extractText(clip);
                    if (text == null || !ShareParser.isArticleShare(text)) {
                        // 视频/其它分享 -> 原样放过
                        return chain.proceed();
                    }

                    // 前置校验：必须在文章详情页分享，其它页面一律不生效
                    if (!UiCtx.isOnArticlePage()) {
                        DyLog.i("[拦截] 不在文章页，忽略本次分享");
                        return chain.proceed();
                    }

                    DyLog.i("[拦截] 在文章页命中口令，开始处理");
                    if (!busy) {
                        busy = true;
                        new Thread(() -> {
                            try {
                                Extractor.run(module, text);
                            } catch (Throwable t) {
                                DyLog.e("[拦截] 处理异常: " + t);
                            } finally {
                                busy = false;
                            }
                        }, "dyhook-extract").start();
                    } else {
                        DyLog.w("[拦截] 上一篇文章还在处理中");
                    }
                    return chain.proceed();
                });
    }

    private static String extractText(ClipData clip) {
        if (clip == null || clip.getItemCount() == 0) return null;
        CharSequence cs = clip.getItemAt(0).getText();
        return cs == null ? null : cs.toString();
    }
}
