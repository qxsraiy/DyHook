# -*- coding: utf-8 -*-
"""
打包 LibXposed 模块：注入 META-INF/xposed/* 并重新签名。

AGP 不会把 META-INF/xposed 打进 APK，所以需要构建后手动注入 + 重签。

用法:
    python tools/package.py
需先设置环境变量（或直接改下面的常量）:
    ANDROID_HOME   Android SDK 路径
"""
import os
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_APK = os.path.join(ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
OUT_APK = os.path.join(ROOT, "DyHook.apk")
XPOSED_DIR = os.path.join(ROOT, "app", "src", "main", "xposed", "META-INF", "xposed")
KEYSTORE = os.path.expanduser(r"~\.android\debug.keystore")

SDK = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
BT = os.path.join(SDK, "build-tools", "36.1.0")
APKSIGNER = os.path.join(BT, "apksigner.bat" if os.name == "nt" else "apksigner")
ZIPALIGN = os.path.join(BT, "zipalign.exe" if os.name == "nt" else "zipalign")


def main():
    if not os.path.exists(SRC_APK):
        print("找不到:", SRC_APK, "\n请先执行 gradle assembleDebug")
        sys.exit(1)
    if not SDK:
        print("请设置 ANDROID_HOME")
        sys.exit(1)

    tmp = OUT_APK + ".tmp"
    al = OUT_APK + ".aligned"

    print("读取:", SRC_APK)
    with zipfile.ZipFile(SRC_APK, "r") as zin, \
            zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.namelist():
            # 去掉旧签名
            if item.startswith("META-INF/") and (
                    item.endswith(".RSA") or item.endswith(".SF") or item.endswith(".MF")):
                continue
            zout.writestr(item, zin.read(item))
        # 注入 xposed 声明
        for f in sorted(os.listdir(XPOSED_DIR)):
            p = os.path.join(XPOSED_DIR, f)
            if os.path.isfile(p):
                zout.write(p, "META-INF/xposed/" + f)
                print("  注入 META-INF/xposed/" + f)

    print("zipalign...")
    subprocess.run([ZIPALIGN, "-f", "4", tmp, al], check=True)

    print("签名...")
    subprocess.run([
        APKSIGNER, "sign",
        "--ks", KEYSTORE, "--ks-pass", "pass:android",
        "--key-pass", "pass:android",
        "--out", OUT_APK, al,
    ], check=True)

    os.remove(tmp)
    os.remove(al)
    print("完成:", OUT_APK, os.path.getsize(OUT_APK), "bytes")


if __name__ == "__main__":
    main()
