# -*- coding: utf-8 -*-
"""hook Cronet 的 UrlRequest.Builder.build(), 看能否抓到视频 CDN 请求。"""
import frida
import time

DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

JS = r"""
Java.perform(function () {
    var out = { hooked: [], urls: [] };

    function tryHook(clsName, methodName) {
        try {
            var C = Java.use(clsName);
            var ms = C[methodName].overloads;
            ms.forEach(function (m) {
                m.implementation = function () {
                    var r = m.apply(this, arguments);
                    try {
                        var u = '';
                        for (var i = 0; i < arguments.length; i++) {
                            var a = arguments[i];
                            if (a !== null && a !== undefined && ('' + a).indexOf('http') === 0) {
                                u = '' + a;
                                break;
                            }
                        }
                        if (u === '') {
                            try { u = '' + this.getUrl(); } catch (e) {}
                        }
                        if (u !== '' && out.urls.length < 60) {
                            out.urls.push(u.substring(0, 200));
                            send('URL ' + u.substring(0, 160));
                        }
                    } catch (e) {}
                    return r;
                };
            });
            out.hooked.push(clsName + '.' + methodName + ' x' + ms.length);
        } catch (e) {
            out.hooked.push(clsName + '.' + methodName + ' FAIL ' + e);
        }
    }

    tryHook('com.ttnet.org.chromium.net.UrlRequest$Builder', 'build');
    tryHook('com.ttnet.org.chromium.net.CronetEngine', 'newUrlRequestBuilder');
    tryHook('com.ttnet.org.chromium.net.impl.CronetEngineBase', 'newUrlRequestBuilder');

    send('HOOKED: ' + JSON.stringify(out.hooked));
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
    sc.on("message", lambda m, d: print(m.get("payload", m), flush=True))
    sc.load()
    time.sleep(20)


main()
