# -*- coding: utf-8 -*-
"""扫描论坛所有帖子，找出正文里标题出现 >=2 次（有重复）的。"""
import json
import re
import subprocess
import sys

cfg = json.load(open(r"C:\tools\dyhook\forum.local.json", encoding="utf-8"))
base = cfg["url"].rstrip("/")
CURL = r"C:\Windows\System32\curl.exe"


def curl(a):
    # -g 关闭 curl 的 URL 通配（否则 [] 会被当 glob）
    return subprocess.run([CURL, "-g", "-s", "-m", "40"] + a, capture_output=True,
                          text=True, encoding="utf-8", errors="replace").stdout


t = json.loads(curl(["-X", "POST", base + "/api/token",
                     "-H", "Content-Type: application/json",
                     "-d", json.dumps({"identification": cfg["username"],
                                       "password": cfg["password"]})]))["token"]

bad = []
total = 0
page = 0
while page < 6:
    raw = curl([f"{base}/api/discussions?sort=-createdAt&page[offset]={page*20}",
                "-H", "Authorization: Token " + t])
    try:
        data = json.loads(raw).get("data", [])
    except Exception:
        print("解析失败 page", page, raw[:120])
        break
    if not data:
        break
    for d in data:
        total += 1
        a = d["attributes"]
        ti = a["title"]
        j = json.loads(curl([base + "/api/discussions/" + d["id"],
                             "-H", "Authorization: Token " + t]))
        pid = j["data"]["relationships"]["posts"]["data"][0]["id"]
        html = json.loads(curl([base + "/api/posts/" + pid,
                                "-H", "Authorization: Token " + t]))["data"]["attributes"]["contentHtml"]
        txt = re.sub(r"<[^>]+>", "", html)
        n = txt.count(ti)
        if n >= 2:
            bad.append((d["id"], ti[:26], a["createdAt"][:16], n))
    page += 1

print(f"共扫描 {total} 篇")
print("\n=== 正文里标题出现 >=2 次（有重复）===")
for x in bad:
    print("  id=%-4s %-28s 创建=%s  重复%d次" % x)
print(f"\n共 {len(bad)} 篇有重复")
