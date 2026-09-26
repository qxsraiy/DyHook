# -*- coding: utf-8 -*-
"""列出 Aweme 中与 author/article/aid/desc 相关的方法名。"""
import frida
import time

DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

JS = r"""
Java.perform(function () {
    var out = { methods: [] };
    try {
        var C = Java.use('com.ss.android.ugc.aweme.feed.model.Aweme');
        var ms = C.class.getDeclaredMethods();
        for (var i = 0; i < ms.length; i++) {
            var n = ms[i].getName();
            var l = n.toLowerCase();
            if (l.indexOf('author') >= 0 || l.indexOf('article') >= 0 || l.indexOf('aid') >= 0
                || l.indexOf('desc') >= 0 || l.indexOf('awemeid') >= 0) {
                out.methods.push(n + '/' + ms[i].getParameterCount());
            }
        }
    } catch (e) { out.methods.push('ERR ' + e); }
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
    sc.on("message", lambda m, d: print(m.get("payload", m)))
    sc.load()
    time.sleep(6)


main()
