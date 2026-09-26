# -*- coding: utf-8 -*-
"""实测三种 AI 模式的耗时。"""
import re
import subprocess
import time

ADB = r"C:\tools\alltools\platform-tools\adb.exe"
DEV = "YOUR_DEVICE_SERIAL"
D = "/storage/emulated/0/Documents/dyhooktxt"

KEY = "YOUR_API_KEY"


def sh(*a):
    return subprocess.run([ADB, "-s", DEV, "shell", *a], capture_output=True,
                          text=True, encoding="utf-8", errors="replace").stdout


def root(c):
    return subprocess.run([ADB, "-s", DEV, "shell", "su", "-c", c], capture_output=True,
                          text=True, encoding="utf-8", errors="replace").stdout


def set_mode(mode):
    cfg = (f"enabled=1\nai_mode={mode}\nai_enabled=1\n"
           f"ai_base_url=http://39.98.49.205:54321\nai_key={KEY}\n"
           f"ai_model=deepseek-v4-flash\nauto_send=0\n")
    with open(r"C:\tools\dyhook\.cfg.txt", "w", encoding="utf-8", newline="\n") as f:
        f.write(cfg)
    subprocess.run([ADB, "-s", DEV, "push", r"C:\tools\dyhook\.cfg.txt",
                    D + "/douyin_cfg.txt"], capture_output=True)
    subprocess.run([ADB, "-s", DEV, "push", r"C:\tools\dyhook\.cfg.txt",
                    D + "/config.txt"], capture_output=True)
    root(f"chmod 666 {D}/douyin_cfg.txt {D}/config.txt")


def main():
    # 长正文
    body = "这是一段测试正文，用来衡量不同 AI 模式的处理耗时。" * 60
    with open(r"C:\tools\dyhook\.long.txt", "w", encoding="utf-8", newline="\n") as f:
        f.write(body)
    subprocess.run([ADB, "-s", DEV, "push", r"C:\tools\dyhook\.long.txt",
                    "/data/local/tmp/long.txt"], capture_output=True)
    print(f"正文长度 = {len(body)} 字\n")

    for mode in ["off", "light", "full"]:
        set_mode(mode)
        root(f"rm -f {D}/青空_模式测试*")
        # 投递
        t0 = time.time()
        sh("am", "start", "-n", "com.dyhook.txt/.ShareReceiverActivity",
           "--es", "@sdcard/long.txt" if False else "text", body,
           "--es", "source", "article", "--es", "title", "模式测试",
           "--es", "author", "小抖（douyin）")
        # 等文件出现
        cost = None
        for _ in range(240):
            time.sleep(1)
            out = root(f"ls {D}/青空_模式测试* 2>/dev/null | head -1")
            if out.strip():
                cost = time.time() - t0
                break
        log = root(f"grep -aE 'AI 模式|轻量 AI|完整 AI|纯代码' {D}/douyin.log 2>/dev/null | tail -3")
        print(f"=== 模式 {mode} ===")
        print(f"  耗时: {cost:.1f}s" if cost else "  超时(>240s)")
        for l in log.strip().splitlines():
            print("   ", l)
        print()


main()
