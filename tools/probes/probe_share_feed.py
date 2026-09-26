# -*- coding: utf-8 -*-
"""
决定性实验：在推荐流里长按文章卡片 -> 分享 -> 复制链接，
看剪贴板拿到什么（是否带 aweme_id）。
同时监控 setPrimaryClip 的全部写入。
"""
import subprocess
import time

import frida

ADB = r"C:\tools\alltools\platform-tools\adb.exe"
DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

JS = r"""
Java.perform(function () {
    var C = Java.use('android.content.ClipboardManager');
    C.setPrimaryClip.overload('android.content.ClipData').implementation = function (clip) {
        try {
            var t = clip.getItemAt(0).getText();
            if (t) send('CLIP >>> ' + String(t).substring(0, 300));
        } catch (e) {}
        return this.setPrimaryClip(clip);
    };
    send('clipboard hooked');
});
"""


def sh(*a):
    return subprocess.run([ADB, "-s", DEV, "shell", *a], capture_output=True,
                          text=True, encoding="utf-8", errors="replace").stdout


def dump_ui():
    sh("uiautomator", "dump", "/sdcard/st.xml")
    subprocess.run([ADB, "-s", DEV, "pull", "/sdcard/st.xml", r"C:\tools\dyhook\st.xml"],
                   capture_output=True)
    try:
        return open(r"C:\tools\dyhook\st.xml", encoding="utf-8").read()
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
    time.sleep(2)

    print("\n=== 滑动推荐流找文章卡片 ===")
    for i in range(10):
        sh("input", "swipe", "540", "1800", "540", "500", "250")
        time.sleep(3)
        x = dump_ui()
        if "文章" in x or "长文" in x:
            print(f"  第{i+1}屏发现疑似文章卡片")
            break
    else:
        print("  10 屏内没看到明显的文章卡片（可能推荐流里没有）")

    print("\n=== 尝试长按屏幕中央 ===")
    sh("input", "swipe", "540", "1200", "540", "1200", "800")
    time.sleep(3)
    x = dump_ui()
    texts = [t for t in ["分享", "复制链接", "复制口令", "收藏", "不感兴趣", "保存本地"] if t in x]
    print("  长按后可见操作:", texts or "（没弹出菜单）")

    if "分享" in x:
        print("  点「分享」…")
        import re
        m = re.search(r'text="分享"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
        if m:
            cx = (int(m.group(1)) + int(m.group(3))) // 2
            cy = (int(m.group(2)) + int(m.group(4))) // 2
            sh("input", "tap", str(cx), str(cy))
            time.sleep(4)
            x2 = dump_ui()
            m2 = re.search(r'text="复制链接"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x2)
            if m2:
                cx2 = (int(m2.group(1)) + int(m2.group(3))) // 2
                cy2 = (int(m2.group(2)) + int(m2.group(4))) // 2
                print("  点「复制链接」…")
                sh("input", "tap", str(cx2), str(cy2))
                time.sleep(4)

    print("\n=== 等待剪贴板事件 ===")
    time.sleep(12)


main()
