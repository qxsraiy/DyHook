# -*- coding: utf-8 -*-
"""列出 ArticleInfoStruct 的方法, 找可靠的 hook 点; 并测试 getter 是否会被调用。"""
import frida
import time

DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

JS = r"""
Java.perform(function () {
    var out = { methods: [], ctorCalled: false, getterHits: {}, instances: 0 };

    try {
        var C = Java.use('com.ss.ugc.aweme.ArticleInfoStruct');
        var ms = C.class.getDeclaredMethods();
        for (var i = 0; i < ms.length && out.methods.length < 80; i++) {
            var n = ms[i].getName();
            if (n.indexOf('$') >= 0) continue;
            out.methods.push(n + '/' + ms[i].getParameterCount());
        }
        // 试 hook 几个可能的 getter
        ['getArticleContent', 'getArticleTitle', 'getArticleId', 'component1', 'component2', 'component3'].forEach(function (gn) {
            try {
                var ov = C[gn].overloads;
                ov.forEach(function (m) {
                    if (m.argumentTypes.length !== 0) return;
                    m.implementation = function () {
                        out.getterHits[gn] = (out.getterHits[gn] || 0) + 1;
                        return m.apply(this, arguments);
                    };
                });
            } catch (e) {}
        });
    } catch (e) { out.methods.push('ERR ' + e); }

    // 统计堆里实例
    Java.choose('com.ss.ugc.aweme.ArticleInfoStruct', {
        onMatch: function (inst) { out.instances++; },
        onComplete: function () { send(JSON.stringify(out)); }
    });
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
