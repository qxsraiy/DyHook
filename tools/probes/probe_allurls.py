# -*- coding: utf-8 -*-
"""hook VideoRef.allVideoURLs() 抓播放地址。"""
import frida
import time

DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

JS = r"""
Java.perform(function () {
    var out = { hooked: [], hits: 0, urls: [] };

    try {
        var VR = Java.use('com.ss.ttvideoengine.model.VideoRef');
        var ms = VR.allVideoURLs.overloads;
        ms.forEach(function (m) {
            m.implementation = function () {
                var r = m.apply(this, arguments);
                try {
                    out.hits++;
                    if (r !== null) {
                        var len = java.lang.reflect.Array.getLength(r);
                        for (var i = 0; i < len && out.urls.length < 20; i++) {
                            var v = java.lang.reflect.Array.get(r, i);
                            if (v !== null) {
                                var s = '' + v;
                                if (s.indexOf('http') === 0) {
                                    out.urls.push(s.substring(0, 200));
                                    send('URL ' + s.substring(0, 170));
                                }
                            }
                        }
                    }
                } catch (e) { send('ERR ' + e); }
                return r;
            };
        });
        out.hooked.push('allVideoURLs x' + ms.length);
    } catch (e) {
        out.hooked.push('allVideoURLs FAIL ' + e);
    }

    // 同时看 mMainURL 字段
    try {
        var f = Java.use('com.ss.ttvideoengine.model.VideoRef').class.getDeclaredField('mMainURL');
        out.hooked.push('hasField mMainURL');
    } catch (e) {
        out.hooked.push('no mMainURL');
    }

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
    time.sleep(25)


main()
