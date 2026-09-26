# -*- coding: utf-8 -*-
"""列出 feData 的全部 key 与值预览，找「摘要」。"""
import time

import frida

DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

JS = r"""
Java.perform(function () {
    var loader = null;
    Java.enumerateClassLoaders({
        onMatch: function (l) {
            try { l.loadClass('com.dyhook.txt.Models'); if (loader === null) loader = l; } catch (e) {}
        },
        onComplete: function () {}
    });
    if (loader === null) { send('没找到 classloader'); return; }
    Java.classFactory.loader = loader;

    var M = Java.use('com.dyhook.txt.Models');
    var c = M.class.getDeclaredField('articleInst');
    c.setAccessible(true);
    var inst = c.get(null);
    if (inst === null) { send('articleInst=null'); return; }

    var f = inst.getClass().getDeclaredField('feData');
    f.setAccessible(true);
    var s = String(f.get(inst));
    var o = JSON.parse(s);
    var keys = Object.keys(o).sort();
    send('#### feData 顶层 key (' + keys.length + '):');
    send(keys.join(' | '));
    send('');
    keys.forEach(function (k) {
        var v = o[k];
        var t = typeof v;
        var prev;
        if (v === null) prev = 'null';
        else if (t === 'string') prev = v.substring(0, 160).replace(/\n/g, '\\n');
        else if (t === 'object') prev = JSON.stringify(v).substring(0, 120);
        else prev = String(v);
        send('[' + k + '] (' + t + ') = ' + prev);
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
    time.sleep(10)


main()
