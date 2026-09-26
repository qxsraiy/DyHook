# 踩坑记录（全部实测）

每一条都是真机上撞出来的，改代码前务必先读这一份。

---

## 1. 模块 App 自己的进程**不能引用 libxposed 类** 🔴

**症状**：模块 App 里任何 Activity / Service / Receiver 都无法实例化。

```
java.lang.NoClassDefFoundError: Failed resolution of: Lio/github/libxposed/api/XposedModule;
  at java.lang.Class.newInstance(Native Method)
  at android.app.AppComponentFactory.instantiateActivity
```

**原因**：`HookEntry extends io.github.libxposed.api.XposedModule`，而这个类
**只在被 LSPosed 注入的进程里存在**（`compileOnly`）。模块 App 自己的进程没被注入。

原本所有组件都用 `HookEntry.logi()` 打日志 → 一调用就要加载 `HookEntry`
→ 类找不到 → **组件实例化直接崩**。

**后果**（当时全部现象）：分享后没反应、服务"启动成功"但没处理、
Activity "拉起成功"但进程没起来、广播"被冻结跳过"。

**修复**：日志走独立的 `DyLog`（零 libxposed 依赖）。`HookEntry` 只负责
`DyLog.setSink(...)` 把 LSPosed 日志出口注入进去。

**规则**：模块 App 的组件（Activity/Service/Receiver/Provider）**只许用 `DyLog`**，
永远不要 `import` 或引用 `HookEntry`。

---

## 2. FUSE 沙盒：抖音进程读不到模块创建的文件 🔴

**症状**：真实流程（在抖音里分享）时，`auto_send` / `ai_enabled` 读成默认值 `false`，
论坛凭据读成空 → 表现为「开了自动发件却不发」「发件失败：未配置论坛」。

```
exists=true  canRead=false  canWrite=false
java.io.FileNotFoundException: config.txt: open failed: EACCES (Permission denied)
```

**原因**：Android 11+ 的 FUSE 沙盒规则 ——

> **没有存储权限的 App，只能访问「自己创建」的文件。**

`config.txt` / `forum.txt` / `dyhook.log` 都是**模块创建**的，抖音（无存储权限）读不到。

**修复**：让**抖音先创建**这些文件，之后双方都能访问：

| 模块创建 | 抖音创建的副本 |
|---|---|
| `config.txt` | `douyin_cfg.txt` |
| `forum.txt` | `douyin_forum.txt` |
| `dyhook.log` | `douyin.log` |

- `HookEntry.onPackageReady` 调 `SharedCfg.ensureSharedFile()` 创建
- 模块**写两份**，抖音**优先读**共享那份
- `ensureSharedFile()` 会检查 `canRead()`，读不到就删掉重建
  （被 `adb push` 覆盖过会把 FUSE 归属改成 shell，抖音就再也读不到）

**顺带**：`/sdcard/Android/data/<包名>/` 对其它 App 是**硬隔离**的，
连 `MANAGE_EXTERNAL_STORAGE` 都进不去，所以不能用那个目录。

---

## 3. `resources.arsc` 必须不压缩 + 4 字节对齐 🔴

**症状**：点 APK 安装报

```
Install Failure 1#-124 [-124: Failed parse during installPackageLI:
Targeting R+ (version 30 and above) requires the resources.arsc of installed
APKs to be stored uncompressed and aligned on a 4-byte boundary]
```

**原因**：AGP 默认产物是合规的，但 `tools/package.py` 重打包（注入
`META-INF/xposed/*` + 重签名）时用 `ZIP_DEFLATED` 压缩了**所有**条目，
把 `resources.arsc` 也压了。

> 用 `adb shell pm install` 能装上，是因为那条路径校验宽松，掩盖了问题。

**修复**：`package.py` 逐条保留原始压缩方式，强制 `resources.arsc` 用 `ZIP_STORED`，
并自带自检（压缩方式 + 数据偏移 4 字节对齐）。

---

## 4. 抖音正文是 CRLF，按 `\n` 写的正则全失效 🔴

**症状**：删掉 markdown 图片后留下一堆空行，`\n{3,}` → `\n\n` 折叠规则不生效。

**原因**：抖音正文行尾是 `\r\n`，而 `cleanText()` 里所有正则都按 `\n` 写。

```
od -c 输出:  \n \r \n \r \n \r \n     ← 三个「空行」其实是 \r\n\r\n\r\n
```

**修复**：`cleanText()` **第一步**就归一化换行符：

```java
String t = s.replace("\r\n", "\n").replace("\r", "\n").replace("\u2028", "\n");
```

这一条同时修好所有依赖 `\n` 的规则（空行折叠、行尾空白、`^`/`$` 锚点）。

---

## 5. Flarum 的排版坑

### `<br>` 和 `&nbsp;` 会被转义

```
输入 <br>      → 渲染成字面文本 "<br>"
输入 &nbsp;    → 渲染成 "&amp;nbsp;"
```

### 连续换行会被浏览器折叠

`\n\n\n` 在 HTML 里保留了空行，但浏览器把连续空白折叠成 1 个段落间距。

### 代码框：行首 4+ 空格 / Tab

markdown 把行首 4 个以上空格渲染成**可复制的代码块**。抖音正文经常带缩进，
必须剥掉。

### 结论：空行要用「全角空格行」

```
@作者by抖音
\n\n\u3000\n\n        ← 一个含全角空格的段落，渲染成真正的空行
正文
```

---

## 6. 抖音模型不走构造函数

`ArticleInfoStruct` 等模型由 **Gson / Unsafe 反序列化**生成，**不走构造函数**，
所以只 hook 构造函数抓不到实例。

**修复**：`Models.hookClass()` **同时 hook 构造函数 + 类内所有方法**，
任意方法被调用都能拿到 `this`。

**另外**：字段要在**使用时延迟读取**，构造时往往还是空的。

---

## 7. 作者字段在父类 / 历史缓存会串号

**症状**：作者名串成上一条看过的视频。

**原因**：`Aweme.getAuthor()` 等 getter 在**父类**里（要遍历类继承链），
而且原来有个「从 `awemeList` 随便挑一个 Aweme 兜底」的逻辑 —— 会匹配到视频。

**修复**：五级兜底，按优先级：

1. `ArticleDetailInfo.aweme.author`（当前文章详情）
2. `ArticleDetailResponse.d.author`（API 响应）
3. `awemeList` 里 `articleInfo.articleId` **严格匹配**的
4. `awemeList` 里**带 `articleInfo` 的文章类** Aweme（← 之前能拿到作者的关键路径，不能砍）
5. `ArticleInfoStruct.feData` / `articleExtra` JSON 里递归找 `nickname` / `unique_id`

**只排除「任意 Aweme（含视频）」那一层** —— 那才是串号元凶。

---

## 8. `long_article_abstract` 不是摘要，是正文预览

抖音 `articleContent` JSON 里只有两个 key：

```json
{"long_article_abstract": "...(同一篇文章的紧凑版，无空行、被截断)...",
 "markdown": "...(完整排版正文)...)"}
```

`long_article_abstract` 是**正文的预览节选**，不是摘要。当摘要拼到正文首行
会导致**正文重复两遍**。

**当前策略**：不提取摘要，成品只有「作者 + 内容」。

---

## 9. Flyme 的应用冻结会让广播丢失

```
BroadcastQueue: Process cur, app is freeze, skip schedule Receiver
```

用 `adb shell am broadcast` 测发件时必被丢弃（shell 没有临时白名单）。

但**点通知栏按钮**是系统发 PendingIntent，附带临时白名单，能解冻模块 App ——
所以手动发件走模块进程是可行的，自动化测试要注意这个差别。

电池优化白名单（`dumpsys deviceidle whitelist +com.dyhook.txt`）加了也仍会冻结。

---

## 10. 卸载重装会重置存储权限

卸载重装 `com.dyhook.txt` 后，`MANAGE_EXTERNAL_STORAGE` 授权被重置，
模块读不到 `config.txt` → 表现为「AI 未开启」。

**重装后必须重新授权**：

```bash
appops set com.dyhook.txt MANAGE_EXTERNAL_STORAGE allow
pm grant com.dyhook.txt android.permission.POST_NOTIFICATIONS
appops set com.dyhook.txt POST_NOTIFICATION allow
```

---

## 11. 通知重复 / 通知卡住

**通知重复**：解析在抖音进程发通知（显示为「抖音」），点发件后模块进程
又发一条（显示为「抖音文案提取」）→ 两条。

**修复**：模块发「发件中」时广播给抖音进程，让抖音撤掉自己那条
（`HookEntry` 注册接收器，`SendActionReceiver.cancelDouyinNotif()` 发广播）。

**通知卡住**：寄生路径没有前台服务，没人调 `stopForeground()`，
进度通知永远挂着。

**修复**：`AiProcessService.run()` 的 `finally` 里显式 `Notifier.cancelProgress(ctx)`。

---

## 12. 明文 HTTP 需要额外配置

接本地 / 内网网关（`http://...`）需要：

- `AndroidManifest.xml` 的 `android:usesCleartextTraffic="true"`
- `res/xml/network_security_config.xml` 里 `cleartextTrafficPermitted="true"`

---

## 13. 广播重投会导致重复发帖

同一条 `am broadcast` 被系统重投 3 次 → 连发 3 贴（后两次撞 429）。

**修复**：`SendActionReceiver` 加**幂等锁**（同一文件 60 秒内只发一次）。

---

## 14. 并行发件会撞 429

每个发件入口各开一条线程 → 点 10 个就是 10 条线程同时登录同时发。

**修复**：统一走**单线程串行队列**（`Executors.newSingleThreadExecutor`），
一次只发一篇，失败重试也排着来。

**顺带加 token 缓存**：Flarum token 长效，登录一次复用，
遇 401/403 才清缓存重登 —— 发 10 篇从 10 次登录降到 1 次。
