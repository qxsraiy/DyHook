# -*- coding: utf-8 -*-
"""找抖音真正使用的网络栈 (ttnet) 与视频 URL 来源。"""
import frida
import time

DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

JS = r"""
Java.perform(function () {
    var out = { ttnet: [], urlMethods: {}, playerCls: [], engineCls: [] };

    function methodsOf(cls) {
        var ms = [];
        try {
            var arr = cls.getDeclaredMethods();
            for (var i = 0; i < arr.length; i++) {
                var n = arr[i].getName();
                if (n.toLowerCase().indexOf('url') >= 0 || n.toLowerCase().indexOf('request') >= 0
                        || n.toLowerCase().indexOf('response') >= 0 || n.toLowerCase().indexOf('intercept') >= 0) {
                    ms.push(n);
                }
            }
        } catch (e) {}
        return ms;
    }

    Java.enumerateLoadedClassesSync().forEach(function (cn) {
        var l = cn.toLowerCase();
        if (l.indexOf('ttnet') >= 0 && out.ttnet.length < 60) out.ttnet.push(cn);
        if ((l.indexOf('ttvideoengine') >= 0 || l.indexOf('videoengine') >= 0) && out.engineCls.length < 30) out.engineCls.push(cn);
        if (l.indexOf('.player.') >= 0 && l.indexOf('aweme') >= 0 && out.playerCls.length < 30) out.playerCls.push(cn);
    });

    ['com.bytedance.ttnet.http.HttpRequestInfo',
     'com.bytedance.ttnet.http.HttpResponseInfo',
     'com.bytedance.ttnet.TTNetInit',
     'com.bytedance.retrofit2.SsResponse',
     'com.bytedance.retrofit2.Call'].forEach(function (n) {
        try {
            var c = Java.use(n);
            out.urlMethods[n] = methodsOf(c);
        } catch (e) { out.urlMethods[n] = ['<missing>']; }
    });

    send(JSON.stringify(out));
});
"""


def main():
    dev = frida.get_device(DEV, timeout=10)
    pid = None
    for a in dev.enumerate_applications():
        if a.identifier == MAIN:
            pid = a.pid
    s = dev.attach(pid)
    sc = s.create_script(JS)
    got = []
    sc.on("message", lambda m, d: got.append(m.get("payload", m)))
    sc.load()
    time.sleep(10)
    for g in got:
        print(g, flush=True)


main()
