# -*- coding: utf-8 -*-
"""验证：从抖音进程启动模块服务，现在能否真正跑起来（NoClassDefFoundError 已修）。"""
import subprocess
import time

import frida

ADB = r"C:\tools\alltools\platform-tools\adb.exe"
DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

JS = r"""
Java.perform(function () {
    var AT = Java.use('android.app.ActivityThread');
    var ctx = AT.currentApplication().getApplicationContext();
    var I = Java.use('android.content.Intent');
    var JS = Java.use('java.lang.String');
    var i = I.$new();
    i.setClassName(JS.$new('com.dyhook.txt'), JS.$new('com.dyhook.txt.AiProcessService'));
    i.putExtra(JS.$new('text'), JS.$new('跨进程服务测试：验证模块进程能否被抖音拉起'));
    i.putExtra(JS.$new('source'), JS.$new('personal'));
    i.putExtra(JS.$new('title'), JS.$new('跨进程测试'));
    try {
        ctx.startForegroundService(i);
        send('startForegroundService OK');
    } catch (e) {
        send('startForegroundService FAIL: ' + e);
    }
});
"""


def sh(*a):
    return subprocess.run([ADB, "-s", DEV, "shell", *a], capture_output=True,
                          text=True, encoding="utf-8", errors="replace").stdout


def main():
    sh("logcat", "-c")
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
    print("\n等待 30 秒…")
    time.sleep(30)
    print("\n=== 模块进程日志 ===")
    print(sh("logcat", "-d", "-s", "DyHook:*")[:1500])
    print("\n=== 模块进程 ===")
    print(sh("ps", "-A") and [l for l in sh("ps", "-A").splitlines() if "dyhook" in l])


main()
