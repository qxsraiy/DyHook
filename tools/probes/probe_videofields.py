# -*- coding: utf-8 -*-
"""dump Aweme.video 对象的真实字段名 (防御式)。"""
import frida
import time

DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

JS = r"""
Java.perform(function () {
    var out = { videoFields: [], playFields: [], brFields: [], urlListStr: null, errors: [] };

    function fieldsOf(obj) {
        var names = [];
        try {
            var c = obj.getClass();
            while (c !== null && names.length < 500) {
                var fs = c.getDeclaredFields();
                for (var i = 0; i < fs.length; i++) names.push(fs[i].getName());
                c = c.getSuperclass();
            }
        } catch (e) { out.errors.push('fieldsOf: ' + e); }
        return names;
    }

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

    function listToArray(l) {
        try {
            if (l === null) return null;
            var cn = l.getClass().getName();
            if (cn.indexOf('List') < 0) return null;
            var n = l.size();
            var arr = [];
            for (var i = 0; i < n && i < 3; i++) arr.push('' + l.get(i));
            return arr;
        } catch (e) { out.errors.push('listToArray: ' + e); return null; }
    }

    Java.choose('com.ss.android.ugc.aweme.feed.model.Aweme', {
        onMatch: function (inst) {
            if (out.videoFields.length > 0) return;
            try {
                var video = get(inst, 'video');
                if (video === null) return;
                out.videoFields = fieldsOf(video);
                var pa = get(video, 'playAddr');
                if (pa === null) pa = get(video, 'play_addr');
                if (pa !== null) {
                    out.playFields = fieldsOf(pa);
                    var ul = get(pa, 'urlList');
                    if (ul === null) ul = get(pa, 'url_list');
                    out.urlListStr = JSON.stringify(listToArray(ul));
                }
            } catch (e) { out.errors.push('onMatch: ' + e); }
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
    time.sleep(8)
    for g in got:
        print(g, flush=True)


main()
