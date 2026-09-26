# -*- coding: utf-8 -*-
"""
抓 FeedLongArticleDetailApi.fetchArticleDetail 的真实调用参数。
这样就知道：能不能自己调 /aweme/v1/aweme/detail/ 拿正文。
同时抓 ArticleInfoStruct / ArticleDetailResponse 的命中。
"""
import subprocess
import time

import frida

ADB = r"C:\tools\alltools\platform-tools\adb.exe"
DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

KOU = "2H-:/a :5pm U@L.wS 07/07 【奴工理论：今年中文互联网最好的基层理论，没有之一】长按复制打开抖音，即可阅读文章 ^^xDTreMGbCaY99"

JS = r"""
Java.perform(function () {
    // 1. 抓 fetchArticleDetail 的入参
    try {
        var A = Java.use('com.ss.android.ugc.aweme.feed.search_article.api.FeedLongArticleDetailApi');
        var m = A.fetchArticleDetail;
        m.overloads.forEach(function (o) {
            o.implementation = function () {
                var a = arguments;
                send('>>> fetchArticleDetail(' + Array.prototype.slice.call(a).map(String).join(', ') + ')');
                return o.apply(this, a);
            };
        });
        send('fetchArticleDetail hooked');
    } catch (e) { send('api hook fail: ' + e); }

    // 2. 抓 ArticleInfoStruct 命中
    try {
        var K = Java.use('com.ss.ugc.aweme.ArticleInfoStruct');
        var seen = {};
        for (var k = K; k != null; k = k.superclass) {
            k.class.getDeclaredMethods().forEach(function (mm) {
                var n = mm.getName();
                if (n.indexOf('$') === 0 || seen[k.class.getName() + n]) return;
                seen[k.class.getName() + n] = 1;
                try {
                    mm.overloads.forEach(function (o) {
                        o.implementation = function () {
                            try {
                                var f = this.getClass().getDeclaredField('articleContent');
                                f.setAccessible(true);
                                var v = f.get(this);
                                if (v) send('=== ArticleInfoStruct 命中 contentLen=' + String(v).length);
                            } catch (e) {}
                            return o.apply(this, arguments);
                        };
                    });
                } catch (e) {}
            });
        }
        send('ArticleInfoStruct hooked');
    } catch (e) { send('struct hook fail: ' + e); }
});
"""


def sh(*a):
    return subprocess.run([ADB, "-s", DEV, "shell", *a], capture_output=True,
                          text=True, encoding="utf-8", errors="replace").stdout


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
    time.sleep(3)
    print("\n=== 写入文章口令到剪贴板，触发抖音识别 ===", flush=True)
    # 用 frida 在抖音进程里写剪贴板
    sc2 = s.create_script(r"""
    Java.perform(function () {
        var AT = Java.use('android.app.ActivityThread');
        var ctx = AT.currentApplication().getApplicationContext();
        var CD = Java.use('android.content.ClipData');
        var JS = Java.use('java.lang.String');
        var cm = Java.cast(ctx.getSystemService('clipboard'), Java.use('android.content.ClipboardManager'));
        cm.setPrimaryClip(CD.newPlainText(JS.$new('t'), JS.$new(%s)));
        send('clip set');
    });
    """ % ('"' + KOU.replace('"', '\\"') + '"'))
    sc2.on("message", lambda m, d: print(m.get("payload", m), flush=True))
    sc2.load()
    time.sleep(3)
    print("=== 切后台再回前台，触发剪贴板检测 ===", flush=True)
    sh("input", "keyevent", "KEYCODE_HOME")
    time.sleep(3)
    sh("am", "start", "-n", MAIN + "/.splash.SplashActivity")
    time.sleep(25)
    print("=== 观察结束 ===", flush=True)


main()
