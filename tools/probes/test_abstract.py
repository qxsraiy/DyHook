# -*- coding: utf-8 -*-
"""打开指定文章 -> 触发解析 -> 验证摘要是否进了正文首行。"""
import subprocess
import sys
import time

import frida

ADB = r"C:\tools\alltools\platform-tools\adb.exe"
DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"
D = "/storage/emulated/0/Documents/dyhooktxt"

KOU = "2H-:/a :5pm U@L.wS 07/07 【测试】长按复制打开抖音，即可阅读文章 ^^xDTreMGbCaY99"


def sh(*a):
    return subprocess.run([ADB, "-s", DEV, "shell", *a], capture_output=True,
                          text=True, encoding="utf-8", errors="replace").stdout


def root(c):
    return subprocess.run([ADB, "-s", DEV, "shell", "su", "-c", c], capture_output=True,
                          text=True, encoding="utf-8", errors="replace").stdout


def main():
    aid = sys.argv[1] if len(sys.argv) > 1 else "7668253903141604608"

    print("1) 打开文章页…")
    sh("am", "start", "-a", "android.intent.action.VIEW",
       "-d", f"snssdk1128://article/detail/{aid}")
    time.sleep(10)
    print("   ", sh("dumpsys", "window").split("mCurrentFocus=")[-1][:80].strip())

    print("2) 触发解析（写剪贴板）…")
    root(f"rm -f {D}/青空_*机器节省*.txt {D}/源_*机器节省*.txt")
    dev = frida.get_device(DEV, timeout=10)
    pid = None
    for a in dev.enumerate_applications():
        if a.identifier == MAIN:
            pid = a.pid
    s = dev.attach(pid)
    sc = s.create_script("""
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
    sc.on("message", lambda m, d: print("   ", m.get("payload", m), flush=True))
    sc.load()

    print("3) 等待处理…")
    time.sleep(60)

    print("\n=== 日志 ===")
    print(root(f"grep -aE '文章\\]|服务\\]' {D}/douyin.log {D}/dyhook.log 2>/dev/null | tail -12"))

    print("\n=== 成品内容（前 25 行）===")
    print(root(f"f=$(ls -1t {D}/青空_*机器节省*.txt 2>/dev/null | head -1); "
               f"echo 文件: $(basename \"$f\"); head -25 \"$f\""))


main()
