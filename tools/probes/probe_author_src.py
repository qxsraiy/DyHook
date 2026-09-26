# -*- coding: utf-8 -*-
"""用抖音搜索打开一篇文章，然后 dump 所有可能含作者的数据源。"""
import re
import subprocess
import time

import frida

ADB = r"C:\tools\alltools\platform-tools\adb.exe"
DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

JS = r"""
Java.perform(function () {
    function brief(s, n) { s = String(s == null ? '' : s); return s.length > (n||120) ? s.substring(0,n)+'…' : s; }

    // ---- ArticleInfoStruct：dump 全部字符串字段（重点 articleExtra / feData）----
    try {
        var K = Java.use('com.ss.ugc.aweme.ArticleInfoStruct');
        var seen = {};
        for (var k = K; k != null; k = k.superclass) {
            k.class.getDeclaredMethods().forEach(function (m) {
                var n = m.getName();
                if (n.indexOf('$') === 0) return;
                var key = k.class.getName() + n;
                if (seen[key]) return;
                seen[key] = 1;
                try {
                    m.overloads.forEach(function (o) {
                        o.implementation = function () {
                            try {
                                var out = '=== ArticleInfoStruct.' + n + ' 命中';
                                ['articleTitle','articleExtra','feData','detailLynxUrl'].forEach(function (f) {
                                    try {
                                        var fd = this.getClass().getDeclaredField(f);
                                        fd.setAccessible(true);
                                        var v = fd.get(this);
                                        if (v) out += '\n   ' + f + ' = ' + brief(v, f==='feData'||f==='articleExtra' ? 600 : 60);
                                    } catch (e) {}
                                });
                                send(out);
                            } catch (e) {}
                            return o.apply(this, arguments);
                        };
                    });
                } catch (e) {}
            });
        }
        send('ArticleInfoStruct hooked');
    } catch (e) { send('struct fail ' + e); }

    // ---- Aweme.author ----
    try {
        var A = Java.use('com.ss.android.ugc.aweme.feed.model.Aweme');
        var seen2 = {};
        for (var k2 = A; k2 != null; k2 = k2.superclass) {
            k2.class.getDeclaredMethods().forEach(function (m) {
                var n = m.getName();
                if (n !== 'getAuthor' && n !== 'getArticleInfo') return;
                var key = k2.class.getName() + n;
                if (seen2[key]) return;
                seen2[key] = 1;
                try {
                    m.overloads.forEach(function (o) {
                        o.implementation = function () {
                            var r = o.apply(this, arguments);
                            try {
                                if (n === 'getAuthor' && r != null) {
                                    var c = r.getClass();
                                    var s = '=== Aweme.getAuthor -> ';
                                    ['nickname','uniqueId','shortId','uid'].forEach(function (f) {
                                        try {
                                            var fd = c.getDeclaredField(f); fd.setAccessible(true);
                                            var v = fd.get(r);
                                            if (v != null) s += f + '=' + brief(v, 30) + '  ';
                                        } catch (e) {}
                                    });
                                    send(s);
                                }
                            } catch (e) {}
                            return r;
                        };
                    });
                } catch (e) {}
            });
        }
        send('Aweme hooked');
    } catch (e) { send('aweme fail ' + e); }

    // ---- ArticleDetailInfo ----
    try {
        var D = Java.use('com.ss.android.ugc.aweme.searcharticle.detail.model.ArticleDetailInfo');
        D.class.getDeclaredMethods().forEach(function (m) {
            var n = m.getName();
            if (n.indexOf('$') === 0) return;
            try {
                m.overloads.forEach(function (o) {
                    o.implementation = function () {
                        send('=== ArticleDetailInfo.' + n + ' 命中');
                        return o.apply(this, arguments);
                    };
                });
            } catch (e) {}
        });
        send('ArticleDetailInfo hooked');
    } catch (e) { send('detail fail ' + e); }
});
"""


def sh(*a):
    return subprocess.run([ADB, "-s", DEV, "shell", *a], capture_output=True,
                          text=True, encoding="utf-8", errors="replace").stdout


def dump():
    sh("uiautomator", "dump", "/sdcard/s.xml")
    subprocess.run([ADB, "-s", DEV, "pull", "/sdcard/s.xml", r"C:\tools\dyhook\s.xml"],
                   capture_output=True)
    try:
        return open(r"C:\tools\dyhook\s.xml", encoding="utf-8").read()
    except Exception:
        return ""


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
    time.sleep(3)
    print("已 hook，开始搜索进文章…", flush=True)

    # 搜索
    sh("input", "tap", "540", "100")     # 顶部搜索框附近
    time.sleep(3)
    x = dump()
    if "搜索" not in x:
        print("  未进入搜索页，可见文本:", list(set(re.findall(r'text="([^"]{2,20})"', x)))[:10])
    sh("input", "text", "奴工理论")
    time.sleep(2)
    sh("input", "keyevent", "KEYCODE_ENTER")
    time.sleep(8)
    x = dump()
    texts = list(set(re.findall(r'text="([^"]{4,40})"', x)))
    print("  搜索结果文本:", texts[:15])

    # 找含"文章"的结果并点
    m = re.search(r'text="([^"]*奴工[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
    if m:
        cx = (int(m.group(2)) + int(m.group(4))) // 2
        cy = (int(m.group(3)) + int(m.group(5))) // 2
        print(f"  点击结果 ({cx},{cy})")
        sh("input", "tap", str(cx), str(cy))
        time.sleep(12)

    print("  当前界面:", sh("dumpsys", "window").split("mCurrentFocus=")[-1][:90].strip())
    time.sleep(20)
    print("=== 观察结束 ===")


main()
