# -*- coding: utf-8 -*-
"""端到端自测：打开文章 -> 触发分享hook -> 检查源文件/成品/通知/日志。"""
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
    send('clip set');
});
"""


def sh(*a):
    return subprocess.run([ADB, "-s", DEV, "shell", *a], capture_output=True, text=True,
                          encoding="utf-8", errors="replace").stdout


def root(c):
    return subprocess.run([ADB, "-s", DEV, "shell", "su", "-c", c], capture_output=True,
                          text=True, encoding="utf-8", errors="replace").stdout


def dump_ui():
    sh("uiautomator", "dump", "/sdcard/st.xml")
    subprocess.run([ADB, "-s", DEV, "pull", "/sdcard/st.xml", r"C:\tools\dyhook\st.xml"],
                   capture_output=True)
    try:
        with open(r"C:\tools\dyhook\st.xml", encoding="utf-8") as f:
            return f.read()
    except Exception:
        return ""


def setclip():
    dev = frida.get_device(DEV, timeout=10)
    pid = None
    for a in dev.enumerate_applications():
        if a.identifier == MAIN:
            pid = a.pid
    if not pid:
        return False
    for _ in range(3):
        try:
            s = dev.attach(pid)
            sc = s.create_script(SETCLIP.replace("%S%", '"' + KOU + '"'))
            sc.on("message", lambda m, d: None)
            sc.load()
            time.sleep(2)
            s.detach()
            return True
        except Exception:
            time.sleep(2)
    return False


def main():
    print("=" * 55)
    print("STEP 1  清空日志")
    root(": > /storage/emulated/0/Documents/dyhooktxt/dyhook.log")
    sh("logcat", "-c")

    print("STEP 2  写入文章口令到剪贴板")
    setclip()

    print("STEP 3  重启抖音")
    sh("am", "force-stop", MAIN)
    time.sleep(3)
    sh("monkey", "-p", MAIN, "-c", "android.intent.category.LAUNCHER", "1")

    print("STEP 4  等待并点「打开看看」")
    opened = False
    for i in range(10):
        time.sleep(4)
        x = dump_ui()
        m = re.search(r'text="打开看看"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
        if m:
            cx = (int(m.group(1)) + int(m.group(3))) // 2
            cy = (int(m.group(2)) + int(m.group(4))) // 2
            print(f"        找到按钮，点击 ({cx},{cy})")
            sh("input", "tap", str(cx), str(cy))
            time.sleep(10)
            opened = True
            break
        print(f"        第{i+1}轮未见弹窗 (dump={len(x)})")
    cur = sh("dumpsys", "window").split("mCurrentFocus=")[-1][:95].strip()
    print("        当前:", cur)

    if not opened:
        print("        ⚠ 没能点开文章，仍继续测试 hook")

    print("STEP 5  触发分享 hook（写剪贴板 -> 命中拦截）")
    setclip()
    time.sleep(8)

    print("STEP 6  等待 AI 处理")
    for i in range(20):
        time.sleep(4)
        log = root("cat /storage/emulated/0/Documents/dyhooktxt/dyhook.log 2>/dev/null")
        if "成品已保存" in log or "处理异常" in log:
            break
        if i % 3 == 2:
            print(f"        …{(i+1)*4}s")

    print()
    print("=" * 55)
    print("【模块侧日志 dyhook.log】")
    print(root("cat /storage/emulated/0/Documents/dyhooktxt/dyhook.log 2>/dev/null") or "(空)")
    print("=" * 55)
    print("【LSPosed 侧日志 (投递/提取)】")
    out = root("grep -a DyHook /data/adb/lspd/log/verbose_*.log | tail -12")
    print(out)
    print("=" * 55)
    print("【目录】")
    print(root("ls -1t /storage/emulated/0/Documents/dyhooktxt/ | head -8"))
    print("【通知】")
    print(sh("dumpsys", "notification", "--noredact").count("com.dyhook.txt"), "处提及 dyhook")


main()
