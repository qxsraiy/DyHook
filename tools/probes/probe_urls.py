# -*- coding: utf-8 -*-
"""在 Video 对象里找任何含 http 的字段，定位真实播放地址所在。"""
import frida
import time

DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

JS = r"""
Java.perform(function () {
    var out = { scanned: 0, hits: [], dashKeys: null };

    function get(obj, name) {
        try {
            var c = obj.getClass();
            while (c !== null) {
                try {
                    var f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (e) { c = c.getSuperclass(); }
            }
        } catch (e) {}
        return null;
    }

    function scanStrings(obj, prefix, depth, acc) {
        if (obj === null || depth > 2) return;
        try {
            var c = obj.getClass();
            while (c !== null && depth <= 2) {
                var fs = c.getDeclaredFields();
                for (var i = 0; i < fs.length && acc.length < 40; i++) {
                    try {
                        fs[i].setAccessible(true);
                        var v = fs[i].get(obj);
                        if (v === null) continue;
                        var tn = fs[i].getType().getName();
                        var nm = prefix + fs[i].getName();
                        if (tn === 'java.lang.String') {
                            var s = '' + v;
                            if (s.indexOf('http') === 0 || s.indexOf('http') > 0) {
                                acc.push(nm + ' = ' + s.substring(0, 120));
                            }
                        } else if (tn.indexOf('List') >= 0) {
                            var l = v;
                            if (l.size() > 0 && l.size() < 50) {
                                var e0 = l.get(0);
                                if (e0 !== null && ('' + e0).indexOf('http') >= 0) {
                                    acc.push(nm + '[0] = ' + ('' + e0).substring(0, 120));
                                }
                            }
                        } else if (tn.indexOf('Map') >= 0) {
                            acc.push(nm + ' = <Map>');
                        }
                    } catch (e) {}
                }
                c = c.getSuperclass();
            }
        } catch (e) {}
    }

    Java.choose('com.ss.android.ugc.aweme.feed.model.Aweme', {
        onMatch: function (inst) {
            out.scanned++;
            if (out.hits.length > 0 || out.scanned > 60) return;
            try {
                var video = get(inst, 'video');
                if (video === null) return;
                var acc = [];
                scanStrings(video, 'video.', 0, acc);
                if (acc.length > 0) {
                    out.hits = acc;
                    out.aid = '' + get(inst, 'aid');
                }
                var dash = get(video, '_dashVideoInfo');
                if (dash !== null) {
                    var acc2 = [];
                    scanStrings(dash, 'dash.', 0, acc2);
                    out.dashKeys = acc2.slice(0, 20);
                }
            } catch (e) {}
        },
        onComplete: function () { send(JSON.stringify(out)); }
    });
});
"""


def main():
    dev = frida.get_device(DEV, timeout=10)
    pid = None
    for a in dev.enumerate_applications():
        if a.identifier == MAIN:
            pid = a.pid
    s = dev.attach(pid)
    sc = s.create_script(JS)
    got = []
    sc.on("message", lambda m, d: got.append(m.get("payload", m)))
    sc.load()
    time.sleep(10)
    for g in got:
        print(g, flush=True)


main()
