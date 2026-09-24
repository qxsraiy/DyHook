package com.dyhook.txt;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * 透明 Activity，两个职责：
 *  1) 系统分享接收（ACTION_SEND / ACTION_PROCESS_TEXT）
 *  2) 被拉起时作为唤醒模块进程的通道
 * 无界面，投递完立即退出。
 */
public class ShareReceiverActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            handle(getIntent());
        } catch (Throwable t) {
            DyLog.e("ShareReceiverActivity 处理失败: " + t);
        }
        finish();
        overridePendingTransition(0, 0);
    }

    private void handle(Intent intent) {
        if (intent == null) {
            return;
        }

        // 路径 2：外部带我们的 extras 拉起
        String text = intent.getStringExtra(AiProcessService.EXTRA_TEXT);
        String source = intent.getStringExtra(AiProcessService.EXTRA_SOURCE);
        if (text != null && !text.trim().isEmpty()) {
            String title = intent.getStringExtra(AiProcessService.EXTRA_TITLE);
            String author = intent.getStringExtra(AiProcessService.EXTRA_AUTHOR);
            String rawPath = intent.getStringExtra(ProcessReceiver.EXTRA_RAWPATH);
            DyLog.i("Activity 被拉起，转交服务 source=" + source);
            startServiceHere(text.trim(), source, title, author, rawPath);
            return;
        }

        // 路径 1：系统分享
        String action = intent.getAction();
        String shared = null;
        if (Intent.ACTION_SEND.equals(action)) {
            shared = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (shared == null) {
                CharSequence cs = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (cs != null) shared = cs.toString();
            }
        } else if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
            CharSequence cs = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
            if (cs != null) shared = cs.toString();
        }

        if (shared == null || shared.trim().isEmpty()) {
            toast("没有可保存的文字");
            return;
        }
        toast("已收到，开始分析…");
        startServiceHere(shared.trim(), AiProcessService.SRC_PERSONAL, null, null, null);
    }

    /** 在自己 App 内启动前台服务（此时 App 处于前台，合法）。 */
    private void startServiceHere(String text, String source, String title,
                                  String author, String rawPath) {
        if (source == null) source = AiProcessService.SRC_PERSONAL;
        Intent svc = new Intent(this, AiProcessService.class);
        svc.putExtra(AiProcessService.EXTRA_TEXT, text);
        svc.putExtra(AiProcessService.EXTRA_SOURCE, source);
        svc.putExtra(AiProcessService.EXTRA_TITLE, title);
        svc.putExtra(AiProcessService.EXTRA_AUTHOR, author);
        svc.putExtra(ProcessReceiver.EXTRA_RAWPATH, rawPath);
        try {
            startForegroundService(svc);
            DyLog.i("已启动 AI 处理服务 source=" + source);
        } catch (Throwable t) {
            DyLog.e("启动服务失败，直接同步处理: " + t);
            final String ft = text, fs = source, fti = title, fau = author, frp = rawPath;
            new Thread(() -> AiProcessService.run(getApplicationContext(), ft, fs, fti, fau, frp),
                    "dyhook-sync").start();
        }
    }

    private void toast(String msg) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
