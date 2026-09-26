# -*- coding: utf-8 -*-
"""验证 urlFromAweme 逻辑能否从活体 Aweme 里挖出可下载地址。"""
import frida
import time

DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

JS = r"""
Java.perform(function () {
    var out = { total: 0, withUrl: 0, samples: [], fieldHit: {} };

    function get(o, name) {
        try {
            var c = o.getClass();
            while (c !== null) {
                try {
                    var f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(o);
                } catch (e) { c = c.getSuperclass(); }
            }
        } catch (e) {}
        return null;
    }

    function firstUrlInText(text) {
        if (text === null) return null;
        var s = '' + text;
        var i = s.indexOf('http');
        if (i < 0) return null;
        var end = i;
        while (end < s.length) {
            var c = s.charAt(end);
            if (c === '"' || c === '\\' || c === "'" || c === ' ' || c === '\n' || c === '\r' || c === ',') break;
            end++;
        }
        return s.substring(i, end).replace(/\\\//g, '/');
    }

    function pickUrl(o) {
        if (o === null) return null;
        var names = ['_urlList', 'urlList', 'url_list'];
        for (var i = 0; i < names.length; i++) {
            var ul = get(o, names[i]);
            if (ul === null) continue;
            // List
            try {
                var n = ul.size();
                for (var k = 0; k < n; k++) {
                    var u = '' + ul.get(k);
                    if (u.indexOf('http') === 0) return u;
                }
            } catch (e) {}
            // Array
            try {
                var len = java.lang.reflect.Array.getLength(ul);
                for (var j = 0; j < len; j++) {
                    var v = java.lang.reflect.Array.get(ul, j);
                    if (v !== null && ('' + v).indexOf('http') === 0) return '' + v;
                }
            } catch (e2) {}
            // 直接是字符串
            try {
                var s = '' + ul;
                if (s.indexOf('http') === 0) return s;
            } catch (e3) {}
        }
        return null;
    }

    function urlFromAweme(aweme) {
        var video = get(aweme, 'video');
        if (video === null) return null;
        var misc = get(video, 'miscDownloadAddrs');
        if (misc !== null) {
            var u0 = firstUrlInText(misc);
            if (u0 !== null) { out.fieldHit['miscDownloadAddrs'] = (out.fieldHit['miscDownloadAddrs']||0)+1; return u0; }
        }
        var direct = ['downloadAddr','_downloadAddr','download_addr','newDownloadAddr','_newDownloadAddr',
                      'playAddr','_playAddr','play_addr','h264PlayAddr','_h264PlayAddr',
                      'playAddrH265','_playAddrH265'];
        for (var i = 0; i < direct.length; i++) {
            try {
                var o = get(video, direct[i]);
                if (o === null) continue;
                var u = null;
                try { u = '' + o; } catch (e) {}
                if (u === null || u.indexOf('http') !== 0) u = pickUrl(o);
                if (u !== null && ('' + u).indexOf('http') === 0) {
                    out.fieldHit[direct[i]] = (out.fieldHit[direct[i]]||0)+1;
                    return '' + u;
                }
            } catch (e) { out.fieldHit['ERR_' + direct[i]] = '' + e; }
        }
        try {
            var brs = get(video, 'bitRate');
            if (brs !== null) {
                var n = -1;
                try { n = brs.size(); } catch (e) { n = -1; }
                if (n > 0) {
                    for (var j = 0; j < n; j++) {
                        var u2 = pickUrl(get(brs.get(j), 'playAddr'));
                        if (u2 !== null) { out.fieldHit['bitRate.playAddr'] = (out.fieldHit['bitRate.playAddr']||0)+1; return u2; }
                    }
                }
            }
        } catch (e) { out.fieldHit['ERR_bitRate'] = '' + e; }
        return null;
    }

    Java.choose('com.ss.android.ugc.aweme.feed.model.Aweme', {
        onMatch: function (inst) {
            out.total++;
            var u = urlFromAweme(inst);
            if (u !== null) {
                out.withUrl++;
                if (out.samples.length < 3) {
                    out.samples.push({
                        aid: '' + get(inst, 'aid'),
                        desc: ('' + get(inst, 'desc')).substring(0, 50),
                        url: u.substring(0, 170)
                    });
                }
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
