# -*- coding: utf-8 -*-
"""从 FeedLongArticleDetailApi 的 Retrofit 注解里读出文章详情 API 的真实地址与参数。"""
import time

import frida

DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

JS = r"""
Java.perform(function () {
    var CLS = 'com.ss.android.ugc.aweme.feed.search_article.api.FeedLongArticleDetailApi';
    var K;
    try { K = Java.use(CLS); } catch (e) { send('找不到 ' + CLS + ': ' + e); return; }

    var methods = K.class.getDeclaredMethods();
    send('方法数 = ' + methods.length);
    methods.forEach(function (m) {
        var line = '\n=== ' + m.getName() + ' ===';
        // 参数
        var ps = m.getParameterTypes();
        for (var i = 0; i < ps.length; i++) {
            var p = ps[i];
            line += '\n  参数[' + i + '] ' + p.getName();
            try {
                p.getAnnotations().forEach(function (a) {
                    line += '  @' + a.annotationType().getName() + ' ' + a.toString();
                });
            } catch (e) {}
        }
        // 返回
        try {
            var rt = m.getGenericReturnType().toString();
            line += '\n  返回: ' + rt;
        } catch (e) {}
        // 注解（GET/POST 路径）
        try {
            m.getAnnotations().forEach(function (a) {
                line += '\n  @' + a.annotationType().getName() + ' -> ' + a.toString();
            });
        } catch (e) {}
        send(line);
    });

    // 也看看 ArticleDetailResponse 的字段
    try {
        var R = Java.use('com.ss.android.ugc.aweme.feed.search_article.api.ArticleDetailResponse');
        var fs = R.class.getDeclaredFields();
        var f2 = '\n=== ArticleDetailResponse 字段 (' + fs.length + ') ===';
        fs.forEach(function (f) { f2 += '\n  ' + f.getName() + ' : ' + f.getType().getName(); });
        send(f2);
    } catch (e) { send('Response 字段读取失败: ' + e); }

    // 以及 ArticleDetailInfo 的字段
    try {
        var D = Java.use('com.ss.android.ugc.aweme.searcharticle.detail.model.ArticleDetailInfo');
        var fs2 = D.class.getDeclaredFields();
        var f3 = '\n=== ArticleDetailInfo 字段 (' + fs2.length + ') ===';
        fs2.forEach(function (f) { f3 += '\n  ' + f.getName() + ' : ' + f.getType().getName(); });
        send(f3);
    } catch (e) { send('DetailInfo 字段读取失败: ' + e); }
});
"""


def main():
    dev = frida.get_device(DEV, timeout=10)
    pid = None
    for a in dev.enumerate_applications():
        if a.identifier == MAIN:
            pid = a.pid
    if not pid:
        print("抖音没运行"); return
    s = dev.attach(pid)
    sc = s.create_script(JS)
    sc.on("message", lambda m, d: print(m.get("payload", m), flush=True))
    sc.load()
    time.sleep(6)


main()
