# -*- coding: utf-8 -*-
"""换 classloader 找到模块的 Models，读它缓存的 articleInst，dump 原始 JSON。"""
import time

import frida

DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

JS = r"""
Java.perform(function () {
    var target = null;
    Java.enumerateClassLoaders({
        onMatch: function (loader) {
            try {
                loader.loadClass('com.dyhook.txt.Models');
                if (target === null) { target = loader; send('找到 Models 的 classloader: ' + loader); }
            } catch (e) {}
        },
        onComplete: function () {}
    });
    if (target === null) { send('没找到 Models 的 classloader'); return; }

    try {
        Java.classFactory.loader = target;
        var M = Java.use('com.dyhook.txt.Models');
        var f = M.class.getDeclaredField('articleInst');
        f.setAccessible(true);
        var inst = f.get(null);
        if (inst === null) { send('articleInst = null（还没抓过文章）'); return; }
        send('拿到 articleInst: ' + inst.getClass().getName());

        var c = inst.getClass();
        function fld(n) {
            try {
                var fd = c.getDeclaredField(n);
                fd.setAccessible(true);
                var v = fd.get(inst);
                return v === null ? null : String(v);
            } catch (e) { return null; }
        }
        send('articleTitle = ' + fld('articleTitle'));
        send('articleId = ' + fld('articleId'));
        var content = fld('articleContent');
        if (content === null) { send('articleContent = null'); return; }
        send('#### articleContent 长度 = ' + content.length);
        var o = JSON.parse(content);
        send('#### 顶层 key: ' + Object.keys(o).join(' | '));
        ['long_article_abstract', 'markdown'].forEach(function (k) {
            var v = o[k];
            if (v === null || v === undefined) { send('===== ' + k + ' = 不存在'); return; }
            send('===== ' + k + '  长度=' + v.length + ' =====');
            send(v.substring(0, 350));
            send('   ……中略……');
            send(v.substring(Math.max(0, v.length - 150)));
        });
    } catch (e) { send('读取失败: ' + e); }
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
    print("attach", pid, flush=True)
    s = dev.attach(pid)
    sc = s.create_script(JS)
    sc.on("message", lambda m, d: print(m.get("payload", m), flush=True))
    sc.load()
    time.sleep(10)


main()
