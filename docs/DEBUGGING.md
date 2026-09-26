# 调试与自测

## 日志在哪

**两个文件都在** `/sdcard/Documents/dyhooktxt/`：

| 文件 | 谁写的 | 说明 |
|---|---|---|
| `dyhook.log` | 模块进程 | 模块 App 自己的日志 |
| `douyin.log` | 抖音进程 | **寄生流程的日志只在这里** |

> ⚠ 抖音进程**写不进** `dyhook.log`（FUSE 沙盒，见 PITFALLS #2），
> 所以排查寄生流程的问题**必须看 `douyin.log`**。

拉日志：

```bash
adb shell su -c "tail -40 /storage/emulated/0/Documents/dyhooktxt/douyin.log"
adb shell su -c "tail -40 /storage/emulated/0/Documents/dyhooktxt/dyhook.log"
```

同时也能用 logcat（tag 是 `DyHook`）：

```bash
adb shell logcat -s DyHook:*
```

LSPosed 侧日志（含 `HookEntry` 的输出）：

```bash
adb shell su -c "grep -ah DyHook /data/adb/lspd/log/verbose_*.log | tail -20"
```

## 关键日志关键字

| 关键字 | 含义 |
|---|---|
| `[拦截] 在文章页命中口令` | hook 命中，开始处理 |
| `[拦截] 不在文章页，忽略本次分享` | 页面校验没过 |
| `[提取] 抓到文章: 标题 / 作者` | 抓到正文和作者 |
| `[提取] 源文件已保存` | 源文件落盘成功 |
| `[服务] 来源=... 强制AI=... AI开关=...` | 本次走哪条路径 |
| `[文章] feData 摘要` | 读 feData |
| `[服务] 本地洗稿完成` | 本地洗稿跑完 |
| `[发件] 第 N 次尝试…` | 发件重试进度 |
| `[论坛] 复用已缓存 token` | token 缓存生效 |
| `共享配置文件可用=true auto_send=...` | **抖音进程能否读到配置**（关键！） |

## 排查清单

**问题：分享后完全没反应**

1. `douyin.log` 里有没有 `[拦截]`？
   - 没有 → hook 没装上，看 LSPosed 里模块是否启用、作用域是否勾了抖音
   - 有 `不在文章页` → 必须在**文章详情页**分享
2. `共享配置文件可用=true`？→ 否则配置读不到，功能全部降级

**问题：开了自动发件却不发**

1. 看 `douyin.log` 的 `共享配置文件可用=` 和 `auto_send=`
2. `false` → FUSE 沙盒问题，见 PITFALLS #2
   - 解决：打开一次模块界面（写共享文件）→ 强制停止抖音 → 重开

**问题：发件失败**

| 错误 | 原因 |
|---|---|
| `未配置论坛` | `forum.txt` 读不到（FUSE）或没填 |
| `HTTP 422 title 不得少于 3 个字符` | 标题太短（已有自动补 `by抖音`） |
| `HTTP 429 too_many_requests` | 限流（已有串行队列 + 幂等锁） |
| `connection closed` | 网络抖动（已有 10 次重试） |
| `找不到标签「xxx」` | 论坛里没有该 slug 的标签 |

**问题：论坛帖子里有代码框**

行首缩进没洗干净，检查 `cleanText()` 的 `stripLeadingIndent()`。

**问题：正文重复两遍**

`long_article_abstract` 被当摘要拼了，见 PITFALLS #8。

**问题：作者名串成上一条视频**

见 PITFALLS #7。

## 自测流程

### 1. 不依赖抖音的快速自测

用模块自带的透明 Activity 投递测试负载：

```bash
# 抖音分享路径（模拟）
adb shell am start -n com.dyhook.txt/.ShareReceiverActivity \
  --es text '测试正文<br/>第二段。' \
  --es source article \
  --es title '测试标题' \
  --es author '测试作者（123456）'

# 第三方分享路径（强制走 AI）
adb shell am start -n com.dyhook.txt/.ShareReceiverActivity \
  --es text '在吗？[微笑] 乱七八糟的内容...' \
  --es source personal
```

然后看落盘结果：

```bash
adb shell su -c "ls -1t /storage/emulated/0/Documents/dyhooktxt/青空_*.txt | head -3"
adb shell su -c "head -20 \$(ls -1t /storage/emulated/0/Documents/dyhooktxt/青空_*.txt | head -1)"
```

### 2. 测串行队列 + token 缓存

造 3 个成品文件后同时触发：

```bash
for i in 1 2 3; do
  adb shell am start -n com.dyhook.txt/.ShareReceiverActivity \
    --es text "第 $i 篇正文" --es source article \
    --es title "队列测试$i" --es author '测试'
  sleep 3
done

F=$(adb shell su -c "ls -1 /storage/emulated/0/Documents/dyhooktxt/青空_队列测试1*.txt")
adb shell am broadcast -a com.dyhook.txt.SEND_OUT \
  -n com.dyhook.txt/.SendActionReceiver --es path "$F" --ei notif_id 7001
```

看日志应该是：`登录成功` → `复用已缓存 token` → `复用已缓存 token`（只登录 1 次）。

### 3. 端到端（需要抖音）

1. 打开抖音 → 进一篇文章
2. 分享 → 复制口令
3. 看 `douyin.log` 的完整链路

### 4. 直接打开指定文章（调试用）

抖音支持 deep link：

```bash
adb shell am start -a android.intent.action.VIEW \
  -d "snssdk1128://article/detail/<article_id>"
```

`article_id` 可以从 `douyin.log` 的 `[文章] articleId=` 拿到。

## 用 frida 探测（可选）

`tools/probes/` 里有探测脚本，需要在设备上跑 `frida-server`（16.x）：

```bash
# 启动 frida-server
adb shell su -c "/data/local/tmp/frida-server &"

# 例：列出文章模型的全部字段
python tools/probes/probe_fields.py
```

脚本里的设备序列号需要改成你自己的。

## 构建自检

`tools/package.py` 打包后会自检 `resources.arsc`：

```
自检:
  resources.arsc  压缩方式 = STORED ✓
  resources.arsc  数据偏移 = 788048 -> 4字节对齐 ✓
```

这两项必须都是 ✓，否则普通安装会报 `Install Failure 1#-124`。

再跑一次官方校验：

```bash
$ANDROID_HOME/build-tools/36.1.0/zipalign -c -v 4 DyHook.apk
# 应输出: resources.arsc (OK)  Verification successful
```
