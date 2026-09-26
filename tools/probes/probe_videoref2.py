# -*- coding: utf-8 -*-
"""检查当前 VideoRef / PlayItem 实例里到底有没有 URL。"""
import frida
import time

DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

JS = r"""
Java.perform(function () {
    var out = { videoRefs: [], playItems: [], counts: {} };

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

    function arr0(a) {
        try {
            if (a === null) return null;
            var cn = a.getClass().getName();
            if (cn.indexOf('[') < 0) return null;
            var len = java.lang.reflect.Array.getLength(a);
            for (var i = 0; i < len; i++) {
                var v = java.lang.reflect.Array.get(a, i);
                if (v !== null && ('' + v).indexOf('http') === 0) return '' + v;
            }
        } catch (e) { return 'ERR ' + e; }
        return null;
    }

    Java.choose('com.ss.ttvideoengine.model.VideoRef', {
        onMatch: function (inst) {
            out.counts.videoRef = (out.counts.videoRef || 0) + 1;
            if (out.videoRefs.length < 4) {
                var s = {};
                s.mMainURL = '' + get(inst, 'mMainURL');
                s.mURLs0 = '' + arr0(get(inst, 'mURLs'));
                s.mBackup0 = '' + arr0(get(inst, 'mBackupURL'));
                var bash = get(inst, 'mBashString');
                s.hasBash = bash !== null;
                if (bash !== null) {
                    var b = '' + bash;
                    var i = b.indexOf('main_url');
                    s.bashSnippet = i > 0 ? b.substring(i, i + 140) : b.substring(0, 100);
                }
                out.videoRefs.push(s);
            }
        },
        onComplete: function () {}
    });

    Java.choose('com.ss.ttvideoengine.TTVideoEnginePlayItem', {
        onMatch: function (inst) {
            out.counts.playItem = (out.counts.playItem || 0) + 1;
            if (out.playItems.length < 4) {
                out.playItems.push({
                    playURL: '' + get(inst, 'playURL'),
                    vid: '' + get(inst, 'vid')
                });
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
    time.sleep(9)
    for g in got:
        print(g, flush=True)


main()
