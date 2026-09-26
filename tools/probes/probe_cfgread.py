# -*- coding: utf-8 -*-
"""在抖音进程里直接测试：能否读 config.txt / 能否写文件。"""
import time

import frida

DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

JS = r"""
Java.perform(function () {
    var F = Java.use('java.io.File');
    var p = '/storage/emulated/0/Documents/dyhooktxt/config.txt';

    try {
        var f = F.$new(p);
        send('exists=' + f.exists() + ' canRead=' + f.canRead() + ' canWrite=' + f.canWrite());
    } catch (e) { send('File 检测失败: ' + e); }

    try {
        var FR = Java.use('java.io.FileReader');
        var r = FR.$new(p);
        var BR = Java.use('java.io.BufferedReader');
        var br = BR.$new(r);
        var line, out = [];
        while ((line = br.readLine()) !== null) out.push(String(line));
        br.close();
        send('读取成功，共 ' + out.length + ' 行:\n' + out.join('\n'));
    } catch (e) { send('读取失败: ' + e); }

    // 用模块自己的 SharedCfg 试试
    try {
        var CL = Java.classFactory.loader;
        var Cfg = Java.use('com.dyhook.txt.SharedCfg');
        send('SharedCfg.getBool(auto_send) = ' + Cfg.getBool('auto_send', false));
        send('SharedCfg.getBool(ai_enabled) = ' + Cfg.getBool('ai_enabled', false));
    } catch (e) { send('调用 SharedCfg 失败: ' + e); }
});
"""


def main():
    dev = frida.get_device(DEV, timeout=10)
    pid = None
    for a in dev.enumerate_applications():
        if a.identifier == MAIN:
            pid = a.pid
    if not pid:
        print("抖音没运行"); return
    s = dev.attach(pid)
    sc = s.create_script(JS)
    sc.on("message", lambda m, d: print(m.get("payload", m), flush=True))
    sc.load()
    time.sleep(8)


main()
