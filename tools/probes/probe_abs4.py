# -*- coding: utf-8 -*-
"""找真正的「摘要」：dump feData / articleExtra / Aweme.desc 等所有可能的字段。"""
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

    var k = inst.getClass();
    ['articleExtra', 'feData', 'detailLynxUrl', 'containerLynxUrl'].forEach(function (n) {
        try {
            var f = k.getDeclaredField(n);
            f.setAccessible(true);
            var v = f.get(inst);
            if (v === null) { send('### ' + n + ' = null'); return; }
            var s = String(v);
            send('### ' + n + '  长度=' + s.length);
            send(s.substring(0, 500));
        } catch (e) { send('### ' + n + ' 读取失败: ' + e); }
    });

    // Aweme 侧的描述字段
    var d = M.class.getDeclaredField('detailInst');
    d.setAccessible(true);
    var det = d.get(null);
    send('\n=== detailInst = ' + (det === null ? 'null' : det.getClass().getName()));
    if (det !== null) {
        try {
            var af = det.getClass().getDeclaredField('aweme');
            af.setAccessible(true);
            var aw = af.get(det);
            if (aw !== null) {
                var ac = aw.getClass();
                ['desc', 'itemTitle', 'previewTitle', 'articleInfo', 'title'].forEach(function (n) {
                    try {
                        var f = ac.getDeclaredField(n);
                        f.setAccessible(true);
                        var v = f.get(aw);
                        send('  Aweme.' + n + ' = ' + (v === null ? 'null' : String(v).substring(0, 200)));
                    } catch (e) {}
                });
            }
        } catch (e) { send('  读 aweme 失败: ' + e); }
    }
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
