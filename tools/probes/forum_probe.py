# -*- coding: utf-8 -*-
"""Flarum API 侦察：可达性 / 登录 / 标签列表。凭据只从本地文件读。"""
import json
import ssl
import urllib.error
import urllib.request

CFG = r"C:\tools\dyhook\forum.local.json"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"


def req(url, data=None, headers=None, method=None):
    h = {"User-Agent": UA, "Accept": "application/json"}
    if headers:
        h.update(headers)
    body = None
    if data is not None:
        body = json.dumps(data).encode("utf-8")
        h["Content-Type"] = "application/json"
    r = urllib.request.Request(url, data=body, headers=h, method=method or ("POST" if body else "GET"))
    ctx = ssl.create_default_context()
    try:
        with urllib.request.urlopen(r, timeout=25, context=ctx) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return -1, repr(e)


def main():
    cfg = json.load(open(CFG, encoding="utf-8"))
    base = cfg["url"].rstrip("/")
    print("论坛:", base)

    print("\n[1] GET /api")
    s, b = req(base + "/api")
    print("  status =", s)
    print("  body[:300] =", b[:300])

    print("\n[2] POST /api/token")
    s, b = req(base + "/api/token",
               {"identification": cfg["username"], "password": cfg["password"]})
    print("  status =", s)
    print("  body[:400] =", b[:400])
    token = None
    if s == 200:
        try:
            token = json.loads(b).get("token")
            print("  ✅ token 长度 =", len(token or ""))
            open(r"C:\tools\dyhook\.token.tmp", "w").write(token or "")
        except Exception as e:
            print("  解析失败:", e)

    if not token:
        return

    print("\n[3] GET /api/tags (找 故人思)")
    s, b = req(base + "/api/tags", headers={"Authorization": "Token " + token})
    print("  status =", s)
    if s == 200:
        d = json.loads(b)
        for t in d.get("data", []):
            a = t.get("attributes", {})
            print(f"    id={t['id']:<4} slug={a.get('slug'):<14} name={a.get('name')}")

    print("\n[4] 当前用户")
    s, b = req(base + "/api/users/" + str(json.loads(open(r"C:\tools\dyhook\.uid.tmp").read() or "1"))
               if False else base + "/api/users/2",
               headers={"Authorization": "Token " + token})
    print("  status =", s, " body[:200] =", b[:200])


main()
