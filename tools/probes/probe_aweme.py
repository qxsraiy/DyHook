# -*- coding: utf-8 -*-
"""探测 Aweme 模型的字段名: ID 字段 / video 字段 / play_addr 结构。"""
import frida
import json
import time

DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

JS = r"""
Java.perform(function () {
    var out = { count: 0, sample: null, errors: [] };

    function dump(obj, depth, seen) {
        var res = {};
        try {
            var cls = obj.getClass();
            while (cls !== null && depth > 0) {
                var fields = cls.getDeclaredFields();
                for (var i = 0; i < fields.length; i++) {
                    var f = fields[i];
                    try {
                        f.setAccessible(true);
                        var name = f.getName();
                        if (res.hasOwnProperty(name)) continue;
                        var v = f.get(obj);
                        if (v === null) { res[name] = null; continue; }
                        var tn = f.getType().getName();
                        if (tn === 'java.lang.String' || tn === 'int' || tn === 'long' || tn === 'boolean') {
                            var s = '' + v;
                            res[name] = s.length > 120 ? s.substring(0, 120) + '…' : s;
                        } else if (tn.indexOf('List') >= 0) {
                            res[name] = '<List size=' + ('' + v).length + '>';
                        } else {
                            res[name] = '<' + tn + '>';
                        }
                    } catch (e) {}
                }
                cls = cls.getSuperclass();
                depth--;
            }
        } catch (e) { out.errors.push('' + e); }
        return res;
    }

    Java.choose('com.ss.android.ugc.aweme.feed.model.Aweme', {
        onMatch: function (inst) {
            out.count++;
            if (out.sample === null) {
                out.sample = dump(inst, 3, {});
                // 顺便看 video 对象的字段
                try {
                    var cls = inst.getClass();
                    while (cls !== null) {
                        try {
                            var f = cls.getDeclaredField('video');
                            f.setAccessible(true);
                            var vid = f.get(inst);
                            if (vid !== null) {
                                out.videoSample = dump(vid, 2, {});
                                var pa = null;
                                try {
                                    var pf = vid.getClass().getDeclaredField('play_addr');
                                    pf.setAccessible(true);
                                    pa = pf.get(vid);
                                } catch (e2) {}
                                if (pa !== null) out.playAddrSample = dump(pa, 2, {});
                            }
                            break;
                        } catch (e3) { cls = cls.getSuperclass(); }
                    }
                } catch (e4) { out.errors.push('video: ' + e4); }
            }
        },
        onComplete: function () {
            send(JSON.stringify(out));
        }
    });
});
"""


def main():
    dev = frida.get_device(DEV, timeout=10)
    pid = None
    for a in dev.enumerate_applications():
        if a.identifier == MAIN:
            pid = a.pid
    print("pid", pid, flush=True)
    s = dev.attach(pid)
    sc = s.create_script(JS)
    got = []
    sc.on("message", lambda m, d: got.append(m.get("payload", m)))
    sc.load()
    time.sleep(8)
    for g in got:
        try:
            print(json.dumps(json.loads(g), ensure_ascii=False, indent=1)[:6000], flush=True)
        except Exception:
            print(g, flush=True)


main()
