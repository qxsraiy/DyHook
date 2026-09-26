# -*- coding: utf-8 -*-
"""在抖音进程里实测文件创建，看真实异常。"""
import time

import frida

DEV = "YOUR_DEVICE_SERIAL"
MAIN = "com.ss.android.ugc.aweme"

JS = r"""
Java.perform(function () {
    var F = Java.use('java.io.File');
    var D = '/storage/emulated/0/Documents/dyhooktxt';
    var d = F.$new(D);
    send('目录 exists=' + d.exists() + ' canRead=' + d.canRead() + ' canWrite=' + d.canWrite());
    send('目录 listFiles=' + (d.listFiles() === null ? 'null' : d.listFiles().length));

    var p = D + '/douyin_cfg.txt';
    var f = F.$new(p);
    send('文件 exists=' + f.exists() + ' canRead=' + f.canRead() + ' canWrite=' + f.canWrite());
    try {
        var ok = f.createNewFile();
        send('createNewFile() = ' + ok + '  之后 exists=' + f.exists());
    } catch (e) {
        send('createNewFile 抛异常: ' + e);
    }

    // 试试换个名字
    var p2 = D + '/dy_test_' + Date.now() + '.txt';
    var f2 = F.$new(p2);
    try {
        var ok2 = f2.createNewFile();
        send('新文件 createNewFile() = ' + ok2 + '  exists=' + f2.exists());
        if (ok2) {
            var FOS = Java.use('java.io.FileOutputStream');
            var os = FOS.$new(f2);
            os.write(Java.use('java.lang.String').$new('test').getBytes('UTF-8'));
            os.close();
            send('写入成功，长度=' + f2.length());
        }
    } catch (e) { send('新文件创建失败: ' + e); }
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
