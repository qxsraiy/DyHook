package com.dyhook.txt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * 「发件」按钮的接收器 —— 目前只做出接口，具体发到论坛的逻辑后续再接。
 * 收到的参数：path = 已保存的 txt 文件绝对路径。
 */
public class SendActionReceiver extends BroadcastReceiver {

    public static final String ACTION_SEND_OUT = "com.dyhook.txt.SEND_OUT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_SEND_OUT.equals(intent.getAction())) return;
        String path = intent.getStringExtra("path");
        DyLog.i("[发件] 待发送文件: " + path);

        // TODO: 后续在这里接入论坛发布接口（上传 txt 内容 / 标题 / 作者）
        try {
            Toast.makeText(context, "发件接口已就绪，待接入论坛\n" + path,
                    Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }
}
