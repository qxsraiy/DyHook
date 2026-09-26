# 抖音文案提取（DyHook）

一个 LSPosed 模块（**LibXposed API 102**）：在抖音里复制长文章的分享口令时，
自动抓取文章正文 + 作者（昵称/抖音号），可选交给 AI 结构化，落盘为 txt 并弹出通知栏。

> 只在**文章详情页**分享才生效，视频链接一律放过。

## 📚 文档

| 文档 | 内容 |
|---|---|
| **[docs/HANDOFF.md](docs/HANDOFF.md)** | **交接清单** —— 环境、构建、安装、配置、验证，按顺序做完就能跑 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 架构与数据流、每个类的职责、输出格式 |
| [docs/PITFALLS.md](docs/PITFALLS.md) | **14 条实测踩坑**（改代码前必读） |
| [docs/DEBUGGING.md](docs/DEBUGGING.md) | 日志位置、排查清单、自测流程、frida 用法 |
| [tools/probes/](tools/probes/) | 逆向过程的探测脚本（含重要结论出处） |

### 快速开始

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/Android/Sdk

./gradlew assembleDebug      # 编译
python tools/package.py      # 注入 META-INF/xposed + 重签名 + arsc 自检
adb install -r -t DyHook.apk
```

> 环境要求：**JDK 21** / **compileSdk 37** / Gradle 9.5.1（已带 wrapper）/ Build Tools 36.1.0

## 当前状态

| 模块 | 状态 |
|---|---|
| 抖音文章文字下载（正文 + 作者/抖音号） | ✅ 完成并真机验证 |
| AI 修正（OpenAI 兼容接口结构化） | ✅ 完成并真机验证 |
| 源文件 + 成品双落盘 | ✅ 完成 |
| 通知栏状态流转（解析完成→发件中→发件成功） | ✅ 完成并真机验证 |
| 系统分享接收（任意文字 → 个人复制） | ✅ 完成 |
| **「发件」按钮 → Flarum 论坛新讨论贴** | ✅ 完成并真机验证 |
| 视频转文字 | ❌ 已按需求移除 |

### 发件功能（Flarum）

点通知栏的「发件」按钮，把成品 txt 发成论坛新讨论贴：

- **标题** = 文章标题
- **正文** = `@作者by抖音` + 空 2 行 + 文章正文
- **标签** = 本地配置的默认标签（**默认留空，需手动填写**；留空则不带标签发帖）

通知栏状态在同一条通知上流转：

```
✅ 解析完成：青空_xxx.txt            [发件]
        ↓ 点击
📤 发件中…                          （按钮消失）
        ↓
✅ 发件成功：https://forum/d/9
```

**每个任务一个独立通知 ID**，新任务发新通知，旧通知保留作发件记录。

**安全**：论坛地址 / 账号 / 密码只存在设备本地
`/sdcard/Documents/dyhooktxt/forum.txt`，**代码与仓库里没有任何凭据**。
模块界面提供论坛配置区（地址 / 账号 / 密码 / 默认标签 + 测试登录）。

`forum.txt` 格式：

```
url=https://your-forum.com
username=xxx
password=xxx
tag_slug=your-tag-slug
tag_id=
```

#### 排版细节（踩坑）

Flarum 的 markdown 渲染有两个坑：

- `<br>` 和 `&nbsp;` 会被**转义成字面文本**显示出来
- 连续换行会被浏览器**折叠**，写 `\n\n\n` 视觉上仍只有 1 个段落间距

所以「作者下面空 2 行」用**全角空格行（U+3000）**实现：

```
@作者by抖音
（空段落 1：全角空格）
（空段落 2：全角空格）
正文……
```

### AI 清洗能力

提示词要求 AI 把原文清洗成正规书面语言：

| 输入 | 输出 |
|---|---|
| `<br/>` `<br>` | 真换行 |
| `<p>` `</p>` `<div>` `<span>` | 删除 |
| `&nbsp;` `&amp;` `&lt;` | 还原成空格 / `&` / `<` |
| `""文字""` `''文字''` | 修正成一对中文引号 |
| 字面 `\n` `\t` `\"` | 还原成正常字符 |
| 分享口令、`复制打开抖音`、`看看【xxx的作品】` | 整行删除 |
| 连续空行、行首尾空格、硬折行 | 合并 / 去除 / 接回 |
| 网络用语、错别字 | 轻度书面化（不改原意） |

## 功能

- **自动抓取**：在抖音长文章页 → 分享 → 复制口令 → 自动解析
- **正文来源**：`com.ss.ugc.aweme.ArticleInfoStruct`（含 markdown 全文）
- **作者来源**：`ArticleDetailData.aweme.author` → 昵称 + 抖音号，输出形如 `昵称（抖音号）by抖音`
- **AI 结构化**（可选）：兼容 OpenAI 的 `/v1/chat/completions` 接口（OpenAI / DeepSeek / NewAPI 等），
  让 AI 把原文整理成 `{title, author, content}` JSON，去掉口令、引流话术等噪声
- **输出两个文件**：
  - 源文件：`源_时间戳_标题_作者.txt`（原始抓取内容，AI 失败也不会丢）
  - 成品：`青空_标题_作者_时间戳.txt`（AI 结构化后）
- **通知栏**：处理中 / 完成都有通知，完成通知带「**发件**」按钮（发论坛的接口已预留）
- **系统分享接收**：任意 App 分享文字到本模块，也会按同样格式保存为「个人复制」
- 模块界面可配置 AI 地址 / Key / 模型，并有「测试 AI 连接」按钮

## 目录

```
app/src/main/java/com/dyhook/txt/
├── HookEntry.java             LSPosed 入口（extends XposedModule）
├── ShareInterceptor.java      拦截 ClipboardManager.setPrimaryClip
├── ShareParser.java           只识别「文章口令」
├── Models.java                抓 ArticleInfoStruct + 作者
├── Extractor.java             抖音进程内：先存源文件，再跑 AI
├── AiClient.java              OpenAI 兼容客户端 + 提示词
├── AiProcessService.java      前台服务：AI + 落盘 + 通知
├── Notifier.java              通知栏（含「发件」按钮）
├── ShareReceiverActivity.java 透明 Activity：系统分享接收 / 唤醒通道
├── ProcessReceiver.java       广播兜底入口
├── SendActionReceiver.java    「发件」按钮（待接入论坛）
├── FileSaver.java             落盘 /sdcard/Documents/dyhooktxt/
├── SharedCfg.java             模块 App ↔ 抖音进程 共享配置
├── DyLog.java                 独立日志（不依赖 libxposed）
├── UiCtx.java                 Context / 前台 Activity
└── MainActivity.java          设置界面
```

## 构建

需要：JDK 21、Android SDK（compileSdk 37）、Gradle 9.5+

```bash
export JAVA_HOME=/path/to/jdk-21
gradle assembleDebug
```

> ⚠️ LibXposed API 102 要求 **compileSdk 37**，对应 **AGP 9.3.x + Gradle 9.5.1**。

### 打包（关键）

LibXposed 模块的声明文件必须位于 APK 的 `META-INF/xposed/`，AGP 默认不会打进去，
需要构建后注入并重新签名，见 `tools/package.py`：

```
META-INF/xposed/java_init.list   → 入口类名
META-INF/xposed/module.prop      → minApiVersion/targetApiVersion=102
META-INF/xposed/scope.list       → 作用域
```

## 安装

1. `adb install DyHook.apk`
2. LSPosed 里启用模块，作用域勾选 **抖音**
3. **强制停止抖音再打开**（LSPosed 更新模块后必须重启作用域应用）
4. 打开模块 App，授予存储权限，按需配置 AI

## 输出

`/sdcard/Documents/dyhooktxt/`

```
源_20260924_115550_流水线自测_测试作者（12345678）.txt
青空_流水线自测_测试作者（12345678）by抖音_20260924_115552.txt
```

成品格式：

```
标题：流水线自测
作者：测试作者（12345678）by抖音
时间：2026-09-24 11:55:52
来源：抖音文章
==========

正文……
```

## 踩过的坑（重要）

### 1. 模块 App 自己的进程里不能用 libxposed 的类

`HookEntry extends io.github.libxposed.api.XposedModule`，这个类**只在被 LSPosed 注入的进程里存在**。
模块 App 自己的进程没有被注入 —— 一旦在 Activity/Service/Receiver 里引用 `HookEntry`，
就会：

```
java.lang.NoClassDefFoundError: Lio/github/libxposed/api/XposedModule;
  at android.app.AppComponentFactory.instantiateActivity
```

导致**模块里所有组件都无法实例化**。所以日志必须走独立的 `DyLog`（零 libxposed 依赖）。

### 2. 跨进程唤醒会被 ROM 拦

在加固过的 ROM（如 Flyme）上，抖音进程想唤醒模块 App 会遇到三重拦截：

- `IntentFirewall` 拦跨应用 service 调用（`foreground not allowed as ifw policy[3rd app inter-call]`）
- 模块 App 被 app freezer 冻结后，广播会被 `BroadcastQueue` 直接跳过
- 后台启动 Activity 受 BAL 限制

**本项目最终采用的方案：把整个流程寄生在抖音进程里跑**（AI + 落盘 + 通知都在 hook 进程内完成），
彻底不需要跨进程唤醒。

### 3. 抖音模型字段名

- Java 模型是 camelCase，与接口 JSON 的 snake_case 不同
- `ArticleInfoStruct` 等模型可能由 Gson/Unsafe 反序列化生成，**不走构造函数**，
  所以 hook 构造函数抓不到实例，必须**同时 hook 类里所有方法**（任意调用都能拿到 `this`）
- 字段要在**使用时延迟读取**，构造时往往还是空的

### 4. 明文 HTTP

接本地/内网网关需要 `android:usesCleartextTraffic="true"` +
`res/xml/network_security_config.xml` 的 `cleartextTrafficPermitted="true"`。

## 许可

MIT
