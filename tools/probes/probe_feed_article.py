# -*- coding: utf-8 -*-
"""
探测：不点进文章，能否拿到正文？

思路：抖音的文章模型 com.ss.ugc.aweme.ArticleInfoStruct 如果出现在
推荐流/搜索结果里，那我们 hook 它就能在「没进文章」时也拿到内容。

本脚本 hook 该类的构造与全部方法，打印每次命中的实例字段，
然后你可以滑动推荐流观察是否有命中。
"""
import sys
import time

import frida

DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

JS = r"""
Java.perform(function () {
    var CLS = "com.ss.ugc.aweme.ArticleInfoStruct";
    var hits = 0;

    function dump(obj, how) {
        try {
            var t = '', c = '';
            var f = obj.getClass().getDeclaredField('articleTitle');
            f.setAccessible(true);
            var v = f.get(obj);
            if (v) t = v.toString();
            var f2 = obj.getClass().getDeclaredField('articleContent');
            f2.setAccessible(true);
            var v2 = f2.get(obj);
            if (v2) c = v2.toString();
            hits++;
            send('HIT#' + hits + ' via=' + how +
                 '\n  title=[' + (t ? t.substring(0,40) : '') + ']' +
                 '\n  contentLen=' + (c ? c.length : 0));
        } catch (e) {
            send('dump fail: ' + e);
        }
    }

    var klass;
    try {
        klass = Java.use(CLS);
    } catch (e) {
        send('找不到类 ' + CLS + ' : ' + e);
        return;
    }

    klass.class.getDeclaredConstructors().forEach(function (ctor) {
        try {
            var c = Java.use(CLS + '$' + '');
        } catch (e) {}
    });

    // hook 所有方法（含父类），任意调用都能拿到 this
    var seen = {};
    for (var k = klass; k != null; k = k.superclass) {
        k.class.getDeclaredMethods().forEach(function (m) {
            var n = m.getName();
            if (n.indexOf('$') === 0) return;
            var key = k.class.getName() + '#' + n;
            if (seen[key]) return;
            seen[key] = true;
            try {
                var ov = k[n].overloads;
                ov.forEach(function (o) {
                    o.implementation = function () {
                        try { dump(this, n); } catch (e) {}
                        return o.apply(this, arguments);
                    };
                });
            } catch (e) {}
        });
    }
    send('已 hook ' + Object.keys(seen).length + ' 个方法，开始滑动观察…');
});
"""


def main():
    dev = frida.get_device(DEV, timeout=10)
    pid = None
    for a in dev.enumerate_applications():
        if a.identifier == MAIN:
            pid = a.pid
    if not pid:
        print("抖音没运行")
        return
    print("attach pid", pid)
    s = dev.attach(pid)
    sc = s.create_script(JS)
    sc.on("message", lambda m, d: print(m.get("payload", m), flush=True))
    sc.load()
    print("已加载，观察 120 秒（期间请在手机上滑动推荐流）…")
    time.sleep(120)


main()
