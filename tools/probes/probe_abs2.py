# -*- coding: utf-8 -*-
"""把 long_article_abstract 与 markdown 的原始值抓出来对比，确认「摘要」到底是不是独立内容。"""
import subprocess
import time

import frida

ADB = r"C:\tools\alltools\platform-tools\adb.exe"
DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

JS = r"""
Java.perform(function () {
    var K = Java.use('com.ss.ugc.aweme.ArticleInfoStruct');
    var done = false;

    function dump(inst) {
        if (done) return;
        try {
            var c = inst.getClass();
            function fld(n) {
                try {
                    var f = c.getDeclaredField(n);
                    f.setAccessible(true);
                    var v = f.get(inst);
                    return v == null ? null : String(v);
                } catch (e) { return null; }
            }
            var content = fld('articleContent');
            if (content == null || content.length < 20) return;
            done = true;

            send('########## articleContent 原始长度=' + content.length);
            send('===== 原始 JSON 前 400 字 =====\n' + content.substring(0, 400));

            var o = null;
            try { o = JSON.parse(content); } catch (e) { send('JSON.parse 失败: ' + e); }
            if (o) {
                send('===== 顶层 key: ' + Object.keys(o).join(' | '));
                ['long_article_abstract', 'markdown'].forEach(function (k) {
                    var v = o[k];
                    if (v == null) { send('--- ' + k + ' = null'); return; }
                    send('===== ' + k + '  长度=' + v.length + ' =====');
                    send(v.substring(0, 400));
                    send('  ……（中略）……');
                    send(v.substring(Math.max(0, v.length - 200)));
                });
            }
        } catch (e) { send('dump 失败: ' + e); }
    }

    var seen = {};
    for (var k = K; k != null; k = k.superclass) {
        k.class.getDeclaredMethods().forEach(function (m) {
            var n = m.getName();
            if (n.indexOf('$') === 0 || seen[k.class.getName() + n]) return;
            seen[k.class.getName() + n] = 1;
            try {
                m.overloads.forEach(function (o) {
                    o.implementation = function () {
                        try { dump(this); } catch (e) {}
                        return o.apply(this, arguments);
                    };
                });
            } catch (e) {}
        });
    }
    send('已 hook，等待文章被读取…');
});
"""

KOU = "2H-:/a :5pm U@L.wS 07/07 【测试】长按复制打开抖音，即可阅读文章 ^^xDTreMGbCaY99"


def sh(*a):
    return subprocess.run([ADB, "-s", DEV, "shell", *a], capture_output=True,
                          text=True, encoding="utf-8", errors="replace").stdout


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
    time.sleep(3)

    sc2 = s.create_script("""
    Java.perform(function () {
        var AT = Java.use('android.app.ActivityThread');
        var ctx = AT.currentApplication().getApplicationContext();
        var CD = Java.use('android.content.ClipData');
        var JS = Java.use('java.lang.String');
        var cm = Java.cast(ctx.getSystemService('clipboard'),
                           Java.use('android.content.ClipboardManager'));
        cm.setPrimaryClip(CD.newPlainText(JS.$new('t'), JS.$new(%s)));
        send('clip set');
    });
    """ % ('"' + KOU.replace('"', '\\"') + '"'))
    sc2.on("message", lambda m, d: print(m.get("payload", m), flush=True))
    sc2.load()
    time.sleep(20)


main()
