# -*- coding: utf-8 -*-
"""dump ArticleInfoStruct 的全部非空字段, 找作者/抖音号线索。"""
import frida
import time

DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

JS = r"""
Java.perform(function () {
    var out = { articleFields: {}, detailFields: {}, awemeFound: 0, authorFromAweme: null };

    function get(o, n) {
        try {
            var c = o.getClass();
            while (c !== null) {
                try { var f = c.getDeclaredField(n); f.setAccessible(true); return f.get(o); }
                catch (e) { c = c.getSuperclass(); }
            }
        } catch (e) {}
        return null;
    }

    function dumpAll(o, limit) {
        var res = {};
        try {
            var c = o.getClass();
            while (c !== null) {
                var fs = c.getDeclaredFields();
                for (var i = 0; i < fs.length; i++) {
                    try {
                        fs[i].setAccessible(true);
                        var n = fs[i].getName();
                        if (res.hasOwnProperty(n)) continue;
                        var v = fs[i].get(o);
                        if (v === null) continue;
                        var tn = fs[i].getType().getName();
                        if (tn === 'java.lang.String' || tn === 'int' || tn === 'long') {
                            var s = '' + v;
                            res[n] = s.length > limit ? s.substring(0, limit) + '…' : s;
                        } else {
                            res[n] = '<' + tn + '>';
                        }
                    } catch (e) {}
                }
                c = c.getSuperclass();
            }
        } catch (e) {}
        return res;
    }

    Java.choose('com.ss.ugc.aweme.ArticleInfoStruct', {
        onMatch: function (inst) {
            if (Object.keys(out.articleFields).length === 0) out.articleFields = dumpAll(inst, 90);
        },
        onComplete: function () {}
    });

    Java.choose('com.ss.android.ugc.aweme.searcharticle.detail.model.ArticleDetailInfo', {
        onMatch: function (inst) {
            if (Object.keys(out.detailFields).length === 0) out.detailFields = dumpAll(inst, 90);
        },
        onComplete: function () {}
    });

    // 找带 articleInfo 的 Aweme, 读作者
    Java.choose('com.ss.android.ugc.aweme.feed.model.Aweme', {
        onMatch: function (inst) {
            try {
                var ai = get(inst, 'articleInfo');
                if (ai !== null) {
                    out.awemeFound++;
                    if (out.authorFromAweme === null) {
                        var au = get(inst, 'author');
                        if (au !== null) {
                            out.authorFromAweme = {
                                nickname: '' + get(au, 'nickname'),
                                uniqueId: '' + get(au, 'uniqueId'),
                                shortId: '' + get(au, 'shortId')
                            };
                        }
                    }
                }
            } catch (e) {}
        },
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
    got = []
    sc.on("message", lambda m, d: got.append(m.get("payload", m)))
    sc.load()
    time.sleep(10)
    for g in got:
        print(g)


main()
