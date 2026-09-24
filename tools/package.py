# -*- coding: utf-8 -*-
"""
打包 LibXposed 模块：注入 META-INF/xposed/* 并重新签名。

AGP 不会把 META-INF/xposed 打进 APK，所以需要构建后手动注入 + 重签。

⚠ 关键：Android 11+ (API 30+) 要求 resources.arsc **不压缩**且 4 字节对齐，
   否则普通安装会报：
     Failed parse during installPackageLI: Targeting R+ (version 30 and above)
     requires the resources.arsc of installed APKs to be stored uncompressed
     and aligned on a 4-byte boundary
   所以这里按原始压缩方式逐条回写，并强制 resources.arsc 用 ZIP_STORED。

用法:
    python tools/package.py
需先设置环境变量（或直接改下面的常量）:
    ANDROID_HOME   Android SDK 路径
"""
import os
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
SRC_APK = os.path.join(ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
OUT_APK = os.path.join(ROOT, "DyHook.apk")
XPOSED_DIR = os.path.join(ROOT, "app", "src", "main", "xposed", "META-INF", "xposed")
KEYSTORE = os.path.expanduser(r"~\.android\debug.keystore")

SDK = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
BT = os.path.join(SDK, "build-tools", "36.1.0")
APKSIGNER = os.path.join(BT, "apksigner.bat" if os.name == "nt" else "apksigner")
ZIPALIGN = os.path.join(BT, "zipalign.exe" if os.name == "nt" else "zipalign")

# 这些条目必须不压缩（API 30+ 要求 + 运行时要求）
MUST_STORE = {"resources.arsc"}
MUST_STORE_PREFIX = ("res/",)          # res/ 下小文件压缩与否无所谓，但保持原样


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
    stored = []
    with zipfile.ZipFile(SRC_APK, "r") as zin, \
            zipfile.ZipFile(tmp, "w") as zout:
        for info in zin.infolist():
            name = info.filename
            # 去掉旧签名
            if name.startswith("META-INF/") and (
                    name.endswith(".RSA") or name.endswith(".SF") or name.endswith(".MF")):
                continue
            data = zin.read(name)

            # 压缩方式：resources.arsc 必须 STORED，其余保持原样
            if name in MUST_STORE:
                ctype = zipfile.ZIP_STORED
                stored.append(name)
            else:
                ctype = info.compress_type

            zi = zipfile.ZipInfo(name, date_time=info.date_time)
            zi.compress_type = ctype
            zi.external_attr = info.external_attr
            zi.internal_attr = info.internal_attr
            zi.create_system = info.create_system
            zout.writestr(zi, data)

        # 注入 xposed 声明
        for f in sorted(os.listdir(XPOSED_DIR)):
            p = os.path.join(XPOSED_DIR, f)
            if os.path.isfile(p):
                with open(p, "rb") as fh:
                    data = fh.read()
                zi = zipfile.ZipInfo("META-INF/xposed/" + f)
                zi.compress_type = zipfile.ZIP_STORED
                zout.writestr(zi, data)
                print("  注入 META-INF/xposed/" + f)

    print("  不压缩存储:", stored)

    print("zipalign -f 4 ...")
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

    # ---------- 自检 ----------
    print("\n自检:")
    with zipfile.ZipFile(OUT_APK) as z:
        info = z.getinfo("resources.arsc")
        ok_store = info.compress_type == zipfile.ZIP_STORED
        offset = info.header_offset
        # 数据区起始偏移 = 本地文件头(30) + 文件名长度 + 扩展区长度
        with open(OUT_APK, "rb") as f:
            f.seek(offset)
            head = f.read(30)
        name_len = int.from_bytes(head[26:28], "little")
        extra_len = int.from_bytes(head[28:30], "little")
        data_off = offset + 30 + name_len + extra_len
        ok_align = data_off % 4 == 0
        print("  resources.arsc  压缩方式 =", "STORED ✓" if ok_store else "DEFLATED ✗")
        print("  resources.arsc  数据偏移 =", data_off, "->",
              "4字节对齐 ✓" if ok_align else "未对齐 ✗")

    print("\n完成:", OUT_APK, os.path.getsize(OUT_APK), "bytes")


if __name__ == "__main__":
    main()
