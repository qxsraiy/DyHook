# -*- coding: utf-8 -*-
"""同时触发 3 个发件，验证串行队列 + token 缓存。"""
import subprocess
import time

ADB = r"C:\tools\alltools\platform-tools\adb.exe"
DEV = "YOUR_DEVICE_SERIAL"
D = "/storage/emulated/0/Documents/dyhooktxt"


def sh(*a):
    return subprocess.run([ADB, "-s", DEV, "shell", *a], capture_output=True,
                          text=True, encoding="utf-8", errors="replace").stdout


def root(c):
    return subprocess.run([ADB, "-s", DEV, "shell", "su", "-c", c], capture_output=True,
                          text=True, encoding="utf-8", errors="replace").stdout


def main():
    files = root(f"ls -1 {D}/青空_队列测试*.txt").strip().splitlines()
    files = [f.strip() for f in files if f.strip()]
    print("待发文件:", len(files))
    for f in files:
        print("  ", f.rsplit("/", 1)[-1])

    root(f"cp {D}/dyhook.log {D}/.before.log")
    print("\n=== 同时触发 3 个发件 ===")
    t0 = time.time()
    for i, f in enumerate(files):
        sh("am", "broadcast", "-a", "com.dyhook.txt.SEND_OUT",
           "-n", "com.dyhook.txt/.SendActionReceiver",
           "--es", "path", f, "--ei", "notif_id", str(7000 + i))
        print(f"  已投递 #{i+1}")
    print("  （几乎同时发出，没有等待）")

    print("\n=== 等待队列跑完 ===")
    for _ in range(40):
        time.sleep(5)
        log = root(f"cat {D}/dyhook.log 2>/dev/null")
        if log.count("[发件] 成功") >= 3 or log.count("已重试仍失败") >= 1:
            break
    print(f"  总耗时 {time.time()-t0:.1f}s")

    print("\n=== 本次日志（只看新增）===")
    before = root(f"cat {D}/.before.log 2>/dev/null")
    after = root(f"cat {D}/dyhook.log 2>/dev/null")
    new = after[len(before):] if len(after) > len(before) else after
    for line in new.splitlines():
        if any(k in line for k in ["发件", "论坛", "队列"]):
            print("  ", line)


main()
