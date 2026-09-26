# -*- coding: utf-8 -*-
"""
深挖：推荐流里的文章预加载。
hook FeedLongArticleDetailApi / ArticleDetailResponse / ArticleDetailInfo，
看滑动推荐流时（不点进文章）是否会自动拉取文章正文。
"""
import time

import frida

DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

JS = r"""
Java.perform(function () {
    function brief(s, n) { s = String(s == null ? '' : s); return s.length > (n||70) ? s.substring(0,n)+'…' : s; }

    function dumpArticle(obj, tag) {
        try {
            var c = obj.getClass();
            var out = tag;
            ['articleId','articleTitle','articleContent','aid','awemeId'].forEach(function (f) {
                try {
                    var fd = c.getDeclaredField(f); fd.setAccessible(true);
                    var v = fd.get(obj);
                    if (v != null) {
                        var sv = String(v);
                        out += '\n    ' + f + ' = ' + brief(sv, f === 'articleContent' ? 60 : 40)
                             + (f === 'articleContent' ? '  (len=' + sv.length + ')' : '');
                    }
                } catch (e) {}
            });
            send(out);
        } catch (e) { send(tag + ' dump fail ' + e); }
    }

    // ---- 1. ArticleDetailResponse 构造（响应到达）----
    try {
        var R = Java.use('com.ss.android.ugc.aweme.feed.search_article.api.ArticleDetailResponse');
        R.class.getDeclaredConstructors().forEach(function (c) {
            try {
                c.implementation = function () {
                    send('=== ArticleDetailResponse 构造 ===');
                    var r = c.apply(this, arguments);
                    try { dumpArticle(this, '  [Response]'); } catch (e) {}
                    return r;
                };
            } catch (e) {}
        });
        // 方法也 hook（防 Unsafe 反序列化）
        R.class.getDeclaredMethods().forEach(function (m) {
            var n = m.getName();
            if (n.indexOf('$') === 0) return;
            try {
                m.overloads.forEach(function (o) {
                    o.implementation = function () {
                        try { dumpArticle(this, '  [Response.' + n + ']'); } catch (e) {}
                        return o.apply(this, arguments);
                    };
                });
            } catch (e) {}
        });
        send('hook ArticleDetailResponse OK');
    } catch (e) { send('ArticleDetailResponse skip: ' + e); }

    // ---- 2. ArticleDetailInfo（模块里已在 hook 的那个）----
    try {
        var D = Java.use('com.ss.android.ugc.aweme.searcharticle.detail.model.ArticleDetailInfo');
        D.class.getDeclaredConstructors().forEach(function (c) {
            try {
                c.implementation = function () {
                    send('=== ArticleDetailInfo 构造 ===');
                    var r = c.apply(this, arguments);
                    try { dumpArticle(this, '  [Detail]'); } catch (e) {}
                    return r;
                };
            } catch (e) {}
        });
        send('hook ArticleDetailInfo OK');
    } catch (e) { send('ArticleDetailInfo skip: ' + e); }

    // ---- 3. 预加载 ViewModel ----
    try {
        var V = Java.use('com.ss.android.ugc.aweme.feed.search_article.FeedLongArticleViewModel');
        V.class.getDeclaredMethods().forEach(function (m) {
            var n = m.getName();
            if (n.indexOf('preload') < 0 && n.indexOf('load') < 0 && n.indexOf('request') < 0) return;
            try {
                m.overloads.forEach(function (o) {
                    o.implementation = function () {
                        send('>>> ViewModel.' + n + '() 被调用');
                        return o.apply(this, arguments);
                    };
                });
                send('hook ViewModel.' + n);
            } catch (e) {}
        });
    } catch (e) { send('ViewModel skip: ' + e); }

    send('探测就绪');
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
    print("attach pid", pid, flush=True)
    s = dev.attach(pid)
    sc = s.create_script(JS)
    sc.on("message", lambda m, d: print(m.get("payload", m), flush=True))
    sc.load()
    print("观察 180 秒…", flush=True)
    time.sleep(180)


main()
