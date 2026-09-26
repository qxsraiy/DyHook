# -*- coding: utf-8 -*-
"""dump com.ss.ttvideoengine.model.VideoRef 的字段/方法, 找视频 URL。"""
import frida
import time

DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

JS = r"""
Java.perform(function () {
    var out = { videoRefFields: [], videoRefMethods: [], instances: 0, samples: [], playItemFields: [] };

    function fieldsOf(cls) {
        var names = [];
        try {
            var c = cls;
            while (c !== null && names.length < 200) {
                var fs = c.getDeclaredFields();
                for (var i = 0; i < fs.length; i++) names.push(fs[i].getName());
                c = c.getSuperclass();
            }
        } catch (e) {}
        return names;
    }

    function methodsOf(cls) {
        var ms = [];
        try {
            var arr = cls.getDeclaredMethods();
            for (var i = 0; i < arr.length; i++) {
                var n = arr[i].getName();
                if (n.toLowerCase().indexOf('url') >= 0 || n.toLowerCase().indexOf('host') >= 0
                        || n.toLowerCase().indexOf('file') >= 0) ms.push(n);
            }
        } catch (e) {}
        return ms;
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

    try {
        var VR = Java.use('com.ss.ttvideoengine.model.VideoRef');
        out.videoRefFields = fieldsOf(VR.class);
        out.videoRefMethods = methodsOf(VR.class);
    } catch (e) { out.videoRefFields = ['<missing> ' + e]; }

    try {
        var PI = Java.use('com.ss.ttvideoengine.TTVideoEnginePlayItem');
        out.playItemFields = fieldsOf(PI.class);
    } catch (e) {}

    Java.choose('com.ss.ttvideoengine.model.VideoRef', {
        onMatch: function (inst) {
            out.instances++;
            if (out.samples.length < 3) {
                var s = {};
                var names = out.videoRefFields;
                for (var i = 0; i < names.length; i++) {
                    try {
                        var v = get(inst, names[i]);
                        if (v === null) continue;
                        var str = '' + v;
                        if (str.indexOf('http') >= 0 || names[i].toLowerCase().indexOf('url') >= 0) {
                            s[names[i]] = str.substring(0, 200);
                        }
                    } catch (e) {}
                }
                out.samples.push(s);
            }
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
