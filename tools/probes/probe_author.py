# -*- coding: utf-8 -*-
"""可靠打开文章 -> 探测 ArticleDetailInfo 里的作者字段。"""
import re
import subprocess
import time

import frida

ADB = r"C:\tools\alltools\platform-tools\adb.exe"
DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"
KOU = "2H-:/a :5pm U@L.wS 07/07 【奴工理论：今年中文互联网最好的基层理论，没有之一】长按复制打开抖音，即可阅读文章 ^^xDTreMGbCaY99"

SETCLIP = r"""
Java.perform(function () {
    var AT = Java.use('android.app.ActivityThread');
    var ctx = AT.currentApplication().getApplicationContext();
    var CD = Java.use('android.content.ClipData');
    var JS = Java.use('java.lang.String');
    var cm = Java.cast(ctx.getSystemService('clipboard'), Java.use('android.content.ClipboardManager'));
    cm.setPrimaryClip(CD.newPlainText(JS.$new('t'), JS.$new(%S%)));
    send('ok');
});
"""

PROBE = r"""
Java.perform(function () {
    var out = { detail: null, article: null };

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
    function str(o, n) { var v = get(o, n); return v === null ? null : '' + v; }

    Java.choose('com.ss.android.ugc.aweme.searcharticle.detail.model.ArticleDetailInfo', {
        onMatch: function (inst) {
            if (out.detail !== null) return;
            var d = {};
            var aweme = get(inst, 'aweme');
            d.hasAweme = aweme !== null;
            if (aweme !== null) {
                var author = get(aweme, 'author');
                d.hasAuthor = author !== null;
                if (author !== null) {
                    d.nickname = str(author, 'nickname');
                    d.uniqueId = str(author, 'uniqueId');
                    d.unique_id = str(author, 'unique_id');
                    d.shortId = str(author, 'shortId');
                    d.short_id = str(author, 'short_id');
                }
            }
            out.detail = d;
        },
        onComplete: function () {}
    });

    Java.choose('com.ss.ugc.aweme.ArticleInfoStruct', {
        onMatch: function (inst) {
            if (out.article !== null) return;
            out.article = {
                title: str(inst, 'articleTitle'),
                id: str(inst, 'articleId'),
                contentLen: (str(inst, 'articleContent') || '').length
            };
        },
        onComplete: function () { send(JSON.stringify(out)); }
    });
});
"""


def sh(*a):
    return subprocess.run([ADB, "-s", DEV, "shell", *a], capture_output=True, text=True,
                          encoding="utf-8", errors="replace").stdout


def dump_ui():
    sh("uiautomator", "dump", "/sdcard/oo.xml")
    subprocess.run([ADB, "-s", DEV, "pull", "/sdcard/oo.xml", r"C:\tools\dyhook\oo.xml"],
                   capture_output=True)
    try:
        with open(r"C:\tools\dyhook\oo.xml", encoding="utf-8") as f:
            return f.read()
    except Exception:
        return ""


def main():
    print("1) 写口令…")
    dev = frida.get_device(DEV, timeout=10)
    pid = None
    for a in dev.enumerate_applications():
        if a.identifier == MAIN:
            pid = a.pid
    if pid:
        try:
            s = dev.attach(pid)
            sc = s.create_script(SETCLIP.replace("%S%", '"' + KOU + '"'))
            sc.load()
            time.sleep(2)
            s.detach()
            print("   ok")
        except Exception as e:
            print("   失败:", str(e)[:80])

    print("2) 重启抖音…")
    sh("am", "force-stop", MAIN)
    time.sleep(2)
    sh("monkey", "-p", MAIN, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(14)

    print("3) 找「打开看看」…")
    for round_ in range(4):
        x = dump_ui()
        m = re.search(r'text="打开看看"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
        if m:
            cx = (int(m.group(1)) + int(m.group(3))) // 2
            cy = (int(m.group(2)) + int(m.group(4))) // 2
            print(f"   找到，点击 ({cx},{cy})")
            sh("input", "tap", str(cx), str(cy))
            time.sleep(10)
            break
        print(f"   第{round_+1}轮没找到，重启一次")
        sh("am", "force-stop", MAIN)
        time.sleep(2)
        sh("monkey", "-p", MAIN, "-c", "android.intent.category.LAUNCHER", "1")
        time.sleep(12)

    print("4) 前台:", sh("dumpsys", "window").split("mCurrentFocus=")[-1][:90].strip())

    print("5) 探测作者字段…")
    dev = frida.get_device(DEV, timeout=10)
    pid = None
    for a in dev.enumerate_applications():
        if a.identifier == MAIN:
            pid = a.pid
    s = dev.attach(pid)
    sc = s.create_script(PROBE)
    got = []
    sc.on("message", lambda m, d: got.append(m.get("payload", m)))
    sc.load()
    time.sleep(8)
    for g in got:
        print(g)


main()
