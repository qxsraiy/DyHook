# -*- coding: utf-8 -*-
"""测两条新流程：
   1) 第三方分享 → 强制走 AI，标题至少 3 字
   2) 抖音分享 + 标题 < 2 字 + AI 关 → 标题拼作者
"""
import subprocess
import time

ADB = r"C:\tools\alltools\platform-tools\adb.exe"
DEV = "YOUR_DEVICE_SERIAL"
D = "/storage/emulated/0/Documents/dyhooktxt"
KEY = "YOUR_API_KEY"

MESSY = ("在吗？[微笑] 今天看到一个东西觉得挺有意思的分享给你看看哈～\n"
         "#热门话题# @某某某\n"
         "https://example.com/abcdefg\n"
         "其实这个事情本身挺简单的。就是说，我们平时用的很多东西，\n"
         "背后都有一套看不见的逻辑在起作用。\n"
         "\n"
         "重复一遍：其实这个事情本身挺简单的。就是说我们平时用的很多东西\n"
         "背后都有一套看不见的逻辑在起作用\n"
         "\n"
         "好了就这样吧，记得点赞关注哦！加群123456789\n"
         "转发自某某公众号")


def sh(*a):
    return subprocess.run([ADB, "-s", DEV, "shell", *a], capture_output=True,
                          text=True, encoding="utf-8", errors="replace").stdout


def root(c):
    return subprocess.run([ADB, "-s", DEV, "shell", "su", "-c", c], capture_output=True,
                          text=True, encoding="utf-8", errors="replace").stdout


def setcfg(ai):
    cfg = (f"enabled=1\nai_enabled={ai}\nai_base_url=http://39.98.49.205:54321\n"
           f"ai_key={KEY}\nai_model=deepseek-v4-flash\nauto_send=0\n")
    with open(r"C:\tools\dyhook\.cfg.txt", "w", encoding="utf-8", newline="\n") as f:
        f.write(cfg)
    subprocess.run([ADB, "-s", DEV, "push", r"C:\tools\dyhook\.cfg.txt",
                    D + "/config.txt"], capture_output=True)


def main():
    print("=" * 50)
    print("【测试 1】第三方分享 → 强制走 AI（当前 ai_enabled=0）")
    print("=" * 50)
    setcfg(0)
    root(f"rm -f {D}/青空_第三方*")
    sh("am", "start", "-n", "com.dyhook.txt/.ShareReceiverActivity",
       "--es", "text", MESSY, "--es", "source", "personal")
    time.sleep(45)
    out = root(f"f=$(ls -1t {D}/青空_*.txt 2>/dev/null | head -1); "
               f"echo 文件: $(basename \"$f\"); head -12 \"$f\"")
    print(out)

    print()
    print("=" * 50)
    print("【测试 2】抖音分享 + 标题<2字 + AI 关 → 标题拼作者")
    print("=" * 50)
    setcfg(0)
    sh("am", "start", "-n", "com.dyhook.txt/.ShareReceiverActivity",
       "--es", "text", "测试正文内容，用来验证短标题拼接逻辑。",
       "--es", "source", "article", "--es", "title", "人",
       "--es", "author", "青岚（qinglan123）")
    time.sleep(20)
    print(root(f"ls -1t {D}/青空_人*.txt 2>/dev/null | head -1"))
    print()
    print("=== 日志 ===")
    print(root(f"grep -aE '来源=|标题不足|AI 完成|强制AI|本地洗稿' {D}/dyhook.log {D}/douyin.log 2>/dev/null | tail -12"))


main()
