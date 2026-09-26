# -*- coding: utf-8 -*-
"""验证新的字段链能否提取 play url。"""
import frida
import time

DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

JS = r"""
Java.perform(function () {
    var out = { withPlayUrl: 0, total: 0, sample: null, tried: {} };

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

    function pickUrl(pa) {
        if (pa === null) return null;
        var names = ['_urlList', 'urlList', 'url_list'];
        for (var i = 0; i < names.length; i++) {
            var ul = get(pa, names[i]);
            if (ul !== null && ul.getClass().getName().indexOf('List') >= 0 && ul.size() > 0) {
                var u = '' + ul.get(0);
                if (u.indexOf('http') === 0) return u;
            }
        }
        return null;
    }

    function readPlayUrl(video) {
        var addrFields = ['_playAddr','playAddr','play_addr','_h264PlayAddr','h264PlayAddr',
                          '_playAddrH265','playAddrH265','_downloadAddr','downloadAddr'];
        for (var i = 0; i < addrFields.length; i++) {
            var u = pickUrl(get(video, addrFields[i]));
            if (u !== null) { out.tried[addrFields[i]] = (out.tried[addrFields[i]]||0)+1; return u; }
        }
        var brs = get(video, 'bitRate');
        if (brs !== null && brs.getClass().getName().indexOf('List') >= 0) {
            for (var j = 0; j < brs.size(); j++) {
                var u2 = pickUrl(get(brs.get(j), '_playAddr'));
                if (u2 !== null) return u2;
            }
        }
        return null;
    }

    Java.choose('com.ss.android.ugc.aweme.feed.model.Aweme', {
        onMatch: function (inst) {
            out.total++;
            try {
                var video = get(inst, 'video');
                if (video === null) return;
                var u = readPlayUrl(video);
                if (u !== null) {
                    out.withPlayUrl++;
                    if (out.sample === null) {
                        out.sample = {
                            aid: '' + get(inst, 'aid'),
                            desc: ('' + get(inst, 'desc')).substring(0, 60),
                            url: u.substring(0, 180)
                        };
                    }
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
    time.sleep(8)
    for g in got:
        print(g, flush=True)


main()
