# -*- coding: utf-8 -*-
"""
更宽的探测：
1) 枚举所有名字含 Article 的类，看有哪些模型
2) hook 网络层（Cronet/ttnet/OkHttp），打印含 article 的请求 URL 与响应长度
用来判断：不进文章页，抖音有没有请求过文章正文。
"""
import time

import frida

DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

JS = r"""
Java.perform(function () {
    // ---- 1. 枚举 Article 相关类 ----
    var names = [];
    Java.enumerateLoadedClasses({
        onMatch: function (n) {
            if (n.indexOf('Article') >= 0 || n.indexOf('article') >= 0) names.push(n);
        },
        onComplete: function () {
            send('=== Article 相关类 (' + names.length + ') ===\n' + names.slice(0, 40).join('\n'));
        }
    });

    // ---- 2. hook 网络 URL ----
    var netHits = 0;
    function logUrl(url, len) {
        if (!url) return;
        if (url.indexOf('article') < 0 && url.indexOf('Article') < 0) return;
        netHits++;
        send('NET#' + netHits + ' len=' + (len || '?') + '  ' + url.substring(0, 180));
    }

    // Cronet (抖音主用)
    ['com.ttnet.org.chromium.net.impl.CronetUrlRequest',
     'org.chromium.net.impl.CronetUrlRequest'].forEach(function (cn) {
        try {
            var C = Java.use(cn);
            C.start.implementation = function () {
                try { logUrl(this.getUrl(), null); } catch (e) {}
                return this.start();
            };
            send('hook ' + cn + ' OK');
        } catch (e) { send('hook ' + cn + ' skip'); }
    });

    // OkHttp (部分接口)
    ['okhttp3.Request'].forEach(function (cn) {
        try {
            var C = Java.use(cn);
            var m = C.url;
            m.overloads.forEach(function (o) {
                o.implementation = function () {
                    var u = o.apply(this, arguments);
                    try { logUrl(u.toString(), null); } catch (e) {}
                    return u;
                };
            });
            send('hook okhttp3.Request.url OK');
        } catch (e) { send('hook okhttp3 skip'); }
    });
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
    print("观察 150 秒…", flush=True)
    time.sleep(150)


main()
