# -*- coding: utf-8 -*-
"""抓推荐流的全部网络 URL，找出文章卡片的接口与参数。"""
import time

import frida

DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

JS = r"""
Java.perform(function () {
    var seen = {};
    var n = 0;

    function log(url, extra) {
        if (!url) return;
        var u = String(url);
        // 只看业务接口
        if (u.indexOf('/aweme/v1/') < 0 && u.indexOf('/aweme/v2/') < 0) return;
        var key = u.split('?')[0];
        if (seen[key]) return;
        seen[key] = true;
        n++;
        send('URL#' + n + ' ' + u.substring(0, 260));
    }

    try {
        var C = Java.use('com.ttnet.org.chromium.net.impl.CronetUrlRequest');
        C.start.implementation = function () {
            try { log(this.getUrl(), null); } catch (e) {}
            return this.start();
        };
        send('Cronet hooked');
    } catch (e) { send('Cronet fail ' + e); }

    try {
        var R = Java.use('okhttp3.Request');
        R.url.overloads.forEach(function (o) {
            o.implementation = function () {
                var u = o.apply(this, arguments);
                try { log(u.toString(), null); } catch (e) {}
                return u;
            };
        });
        send('okhttp hooked');
    } catch (e) { send('okhttp fail'); }

    send('就绪');
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
    print("观察 120 秒…", flush=True)
    time.sleep(120)


main()
