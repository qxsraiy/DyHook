# 架构说明

## 一句话

LSPosed 模块（LibXposed API 102），**全流程寄生在抖音进程内执行**：
拦截剪贴板里的文章分享口令 → 抓取正文与作者 → 洗稿 → 落盘 / 发到论坛。

## 为什么「寄生在抖音进程」

这是本项目的核心设计决定，原因是加固 ROM 的三重拦截。

抖音进程要唤醒模块 App（`com.dyhook.txt`）时，会遇到：

| 拦截机制 | 表现 |
|---|---|
| **Flyme IntentFirewall** | `SecurityException: foreground not allowed as ifw policy[3rd app inter-call]` |
| **App Freezer** | `BroadcastQueue: Process cur, app is freeze, skip schedule Receiver` —— 广播被直接丢弃 |
| **BAL（后台启动 Activity 限制）** | `startActivity` 不报错但 Activity 静默不启动 |

**所以干脆不跨进程**：AI 调用、落盘、通知全部在抖音进程里完成。
抖音进程在用户分享时必然是活的，不存在被冻结的问题。

> 唯一需要跨进程的是「点通知栏按钮」——那是系统发 PendingIntent，
> 会附带临时白名单，能解冻模块 App。所以手动发件走模块进程是可行的。

## 数据流

```
┌─────────────────────────── 抖音进程 ───────────────────────────┐
│                                                                 │
│  用户在文章页 → 分享 → 复制口令                                   │
│         ↓                                                       │
│  ShareInterceptor  hook ClipboardManager.setPrimaryClip         │
│         ├─ ShareParser.isArticleShare(text)  只认文章口令         │
│         └─ UiCtx.isOnArticlePage()           必须在文章页         │
│         ↓                                                       │
│  Extractor.run()                                                │
│         ├─ Models.latestArticle()  从缓存的模型实例读正文+作者      │
│         ├─ FileSaver.saveRaw()     先落盘源文件（源_*.txt）        │
│         └─ AiProcessService.run(ctx, ...)  同一进程内直接调用      │
│                  ↓                                              │
│           洗稿（见下）→ FileSaver.saveNamed()（青空_*.txt）        │
│                  ↓                                              │
│         auto_send 开？→ SendActionReceiver.sendNow() 直接发        │
│         否则          → Notifier.done()  通知栏「解析完成 + 发件」  │
└─────────────────────────────────────────────────────────────────┘

点通知栏「发件」→ PendingIntent → 模块进程
                                    ↓
                    SendActionReceiver → 串行队列 → ForumClient → Flarum
```

## 洗稿流程（两条路径）

```
抖音分享（source=article）
├─ AI 关：原文 → cleanText() 本地洗稿
└─ AI 开：原文 → AiClient.analyze() → cleanText() 本地洗稿

第三方分享（source=personal）
└─ 不论开关：原文 → AiClient.analyzeMessy() 强制 AI 重写 → cleanText() 本地洗稿
```

**本地洗稿是独立一层**，不论走没走 AI，保存/发件前都统一过一遍。

## 类职责

| 类 | 职责 | 运行进程 |
|---|---|---|
| `HookEntry` | LSPosed 入口，`extends XposedModule` | 抖音 |
| `ShareInterceptor` | hook `setPrimaryClip`，命中文章口令才处理 | 抖音 |
| `ShareParser` | 判断是不是「文章分享口令」 | 抖音 |
| `Models` | 抓 `ArticleInfoStruct` / `ArticleDetailInfo` / `ArticleDetailResponse`，读正文与作者 | 抖音 |
| `Extractor` | 抖音侧编排：抓取 → 存源文件 → 启动洗稿 | 抖音 |
| `AiClient` | OpenAI 兼容接口 + **`cleanText()` 本地洗稿** | 两者 |
| `AiProcessService` | 洗稿编排 + 落盘 + 通知；也可作为前台服务跑 | 两者 |
| `ForumClient` | Flarum JSON:API 客户端（登录/标签/发帖）+ token 缓存 | 两者 |
| `SendActionReceiver` | 发件入口，**串行队列** + 重试 + 幂等 | 两者 |
| `Notifier` | 通知栏（每任务独立 ID，状态流转） | 两者 |
| `FileSaver` | 落盘 `/sdcard/Documents/dyhooktxt/` | 两者 |
| `SharedCfg` | 跨进程共享配置（见 PITFALLS） | 两者 |
| `DyLog` | 独立日志（**不依赖 libxposed**，见 PITFALLS） | 两者 |
| `UiCtx` | 拿 Context / 当前 Activity / Toast | 抖音 |
| `ShareReceiverActivity` | 透明 Activity：系统分享接收 + 唤醒通道 | 模块 |
| `ProcessReceiver` | 广播兜底入口 | 模块 |
| `MainActivity` | 设置界面 + 待发件列表 | 模块 |

## 输出格式

**源文件** `源_<yyyyMMdd>_<HHmmss>_<标题>_<作者>.txt`

```
【源文件】
标题：xxx
作者：xxx
来源：抖音文章
时间：2026-09-25 15:12:21
==========

（原始抓取内容，未洗稿）
```

**成品** `青空_<标题>_<作者>_<yyyyMMdd>_<HHmmss>.txt`

```
标题：xxx
作者：昵称（抖音号）by抖音
时间：2026-09-25 15:12:21
来源：抖音文章
==========

（洗稿后的正文）
```

**发到论坛的正文**

```
@作者by抖音

（空 1 行）
正文
```

> 空行用**全角空格行**实现 —— Flarum 会把 `<br>` / `&nbsp;` 转义成字面文本，
> 连续换行又会被浏览器折叠，只有全角空格段落才渲染成真正的空行。

## 配置

设备本地 `/sdcard/Documents/dyhooktxt/`：

| 文件 | 内容 |
|---|---|
| `config.txt` | `enabled` / `ai_enabled` / `ai_base_url` / `ai_key` / `ai_model` / `auto_send` |
| `forum.txt` | 论坛地址 / 账号 / 密码 / 默认标签 |

⚠ 这两个文件是**模块创建**的，抖音进程读不到（见 PITFALLS 的 FUSE 沙盒），
所以还有抖音创建的副本：`douyin_cfg.txt` / `douyin_forum.txt` / `douyin.log`。

**凭据绝不进仓库**，只在设备本地。
