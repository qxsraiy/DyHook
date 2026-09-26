# -*- coding: utf-8 -*-
"""把文章相关模型的所有字段列出来，找作者信息来源。"""
import time

import frida

DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

JS = r"""
Java.perform(function () {
    var classes = [
        'com.ss.ugc.aweme.ArticleInfoStruct',
        'com.ss.android.ugc.aweme.searcharticle.detail.model.ArticleDetailInfo',
        'com.ss.android.ugc.aweme.feed.search_article.api.ArticleDetailResponse',
        'com.ss.android.ugc.aweme.searcharticle.detail.model.ArticleDetailData',
        'com.ss.ugc.aweme.ArticleDetailStruct'
    ];
    classes.forEach(function (cn) {
        var K;
        try { K = Java.use(cn); } catch (e) { send('\n### ' + cn + '  <未加载>'); return; }
        var out = '\n### ' + cn;
        var fields = [];
        for (var k = K; k != null && k != Object; k = k.superclass) {
            try {
                k.class.getDeclaredFields().forEach(function (f) {
                    fields.push('   ' + f.getName() + ' : ' + f.getType().getName());
                });
            } catch (e) {}
        }
        out += '  (' + fields.length + ' 字段)\n' + fields.join('\n');
        send(out);
    });

    // 再找找名字里带 Author 的类
    var auth = [];
    Java.enumerateLoadedClasses({
        onMatch: function (n) {
            if (n.indexOf('Author') >= 0 && n.indexOf('com.ss.') >= 0 && n.indexOf('$') < 0)
                auth.push(n);
        },
        onComplete: function () {
            send('\n### 含 Author 的类 (' + auth.length + ')\n' + auth.slice(0, 30).join('\n'));
        }
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
    s = dev.attach(pid)
    sc = s.create_script(JS)
    sc.on("message", lambda m, d: print(m.get("payload", m), flush=True))
    sc.load()
    time.sleep(6)


main()
