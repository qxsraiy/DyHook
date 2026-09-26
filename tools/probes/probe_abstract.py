# -*- coding: utf-8 -*-
"""
在抖音进程里抓 ArticleInfoStruct 的 articleContent 原始 JSON，
列出所有 key 和值预览，找出「摘要」到底叫什么字段。
"""
import json
import subprocess
import time

import frida

ADB = r"C:\tools\alltools\platform-tools\adb.exe"
DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

# 用任意文章口令触发模块的拦截（当前正停在文章页，能通过页面校验）
KOU = "2H-:/a :5pm U@L.wS 07/07 【测试】长按复制打开抖音，即可阅读文章 ^^xDTreMGbCaY99"

JS = r"""
Java.perform(function () {
    var CLS = 'com.ss.ugc.aweme.ArticleInfoStruct';
    var K;
    try { K = Java.use(CLS); } catch (e) { send('找不到类: ' + e); return; }

    var done = false;
    function dump(inst, how) {
        if (done) return;
        try {
            var f = inst.getClass().getDeclaredField('articleContent');
            f.setAccessible(true);
            var v = f.get(inst);
            if (v == null) return;
            var s = String(v);
            if (s.length < 20) return;
            done = true;
            send('=== 命中 (' + how + ') articleContent 长度=' + s.length + ' ===');
            try {
                var o = JSON.parse(s);
                var keys = Object.keys(o);
                send('顶层 key (' + keys.length + '): ' + keys.join(' | '));
                keys.forEach(function (k) {
                    var val = o[k];
                    var t = typeof val;
                    var prev = '';
                    if (t === 'string') prev = val.substring(0, 100).replace(/\n/g, '\\n');
                    else if (val === null) prev = 'null';
                    else prev = JSON.stringify(val).substring(0, 80);
                    send('  [' + k + '] (' + t + ', ' +
                         (t === 'string' ? val.length : '-') + '字) = ' + prev);
                });
            } catch (e) {
                send('JSON 解析失败，原文前 800 字:\n' + s.substring(0, 800));
            }
            // 同时看看 feData / articleExtra
            ['feData', 'articleExtra'].forEach(function (fn) {
                try {
                    var ff = inst.getClass().getDeclaredField(fn);
                    ff.setAccessible(true);
                    var fv = ff.get(inst);
                    if (fv != null) {
                        var fs = String(fv);
                        send('--- ' + fn + ' 长度=' + fs.length + ' 前300字:\n' + fs.substring(0, 300));
                    }
                } catch (e) {}
            });
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
                        try { dump(this, n); } catch (e) {}
                        return o.apply(this, arguments);
                    };
                });
            } catch (e) {}
        });
    }
    send('已 hook ArticleInfoStruct，等待命中…');
});
"""


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
    print("attach pid", pid, flush=True)
    s = dev.attach(pid)
    sc = s.create_script(JS)
    sc.on("message", lambda m, d: print(m.get("payload", m), flush=True))
    sc.load()
    time.sleep(3)

    # 用剪贴板触发模块的 hook（当前在文章页，能通过校验）
    print("\n触发模块拦截…", flush=True)
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

    time.sleep(25)
    print("\n=== 模块日志里的 JSON keys ===")
    print(sh("su", "-c", "grep -a 'JSON keys\\|引言' /storage/emulated/0/Documents/dyhooktxt/douyin.log "
                         "/storage/emulated/0/Documents/dyhooktxt/dyhook.log 2>/dev/null | tail -5"))


main()
