# 探测脚本（逆向过程记录）

这些是开发过程中用来**摸清抖音内部结构**的脚本，不是构建/运行的一部分。
留着是为了：需要重新探测时能直接改，以及给后来者看「这些结论是怎么得出来的」。

## 使用前提

1. 设备上跑 `frida-server`（**16.x**；frida 17 没有内置 Java bridge，会报 `Java is not defined`）
   ```bash
   adb push frida-server-16.x-android-arm64 /data/local/tmp/frida-server
   adb shell su -c "chmod 755 /data/local/tmp/frida-server && /data/local/tmp/frida-server &"
   ```
2. **改脚本里的设备序列号**：`DEV = "YOUR_DEVICE_SERIAL"` → 你的设备
3. Python 依赖：`frida`、`frida-tools`

## 脚本分类

### 枚举抖音类与方法

| 脚本 | 用途 |
|---|---|
| `probe_fields.py` | 列出文章相关模型的全部字段 |
| `probe_api.py` | 从 Retrofit 注解读出 API 地址（`/aweme/v1/aweme/detail/` 就是这么找到的） |
| `probe_awememethods.py` | 枚举 Aweme 的方法（找作者 getter） |
| `probe_author_src.py` | 找作者信息的来源 |

### 抓模型数据

| 脚本 | 用途 |
|---|---|
| `probe_abs2.py` / `probe_abs3.py` / `probe_abs4.py` | 读 `ArticleInfoStruct` 的原始 JSON（结论：`long_article_abstract` 是正文预览，不是摘要） |
| `probe_fedata.py` | 列出 `feData` 的全部 key |
| `probe_abstract.py` | 摘要字段定位 |
| `probe_preload.py` | 验证推荐流是否预加载正文（结论：**不预加载**） |
| `probe_feed_article.py` | 推荐流里的文章卡片探测 |
| `probe_share_feed.py` | 推荐流分享剪贴板内容 |

### 环境 / 权限探测

| 脚本 | 用途 |
|---|---|
| `probe_cfgread.py` | **验证抖音进程能否读 `config.txt`**（发现 FUSE 沙盒问题的关键脚本） |
| `probe_filecreate.py` | 验证抖音进程能否创建文件 |
| `probe_xproc.py` | 验证跨进程启动服务（发现 `ifw policy[3rd app inter-call]`） |
| `probe_net.py` / `probe_urls*.py` / `probe_cronet.py` | 网络栈探测 |

### 端到端测试

| 脚本 | 用途 |
|---|---|
| `selftest.py` | 完整自测：打开文章 → 触发 hook → 检查产物 |
| `test_queue.py` | 验证**串行队列 + token 缓存**（同时触发 3 个发件） |
| `test_flow.py` | 验证两条洗稿路径（抖音 / 第三方） |
| `test_abstract.py` | 文章正文抓取验证 |
| `test_modes.py` | 三种 AI 模式耗时对比 |
| `forum_probe.py` | 论坛 API 侦察（登录 / 标签 / 发帖） |
| `scan_dup.py` | 扫描论坛全部帖子找正文重复 |

## 几个重要结论（都是这些脚本测出来的）

| 结论 | 出自 |
|---|---|
| 文章详情 API = `GET /aweme/v1/aweme/detail/` | `probe_api.py` |
| 推荐流**不预加载**文章正文 | `probe_preload.py` |
| `long_article_abstract` 是**正文预览**，不是摘要 | `probe_abs3.py` |
| 抖音进程**读不到**模块创建的 `config.txt`（FUSE 沙盒） | `probe_cfgread.py` |
| 抖音进程**可以**创建自己的文件 | `probe_filecreate.py` |
| 跨进程启动服务被 `ifw policy[3rd app inter-call]` 拦 | `probe_xproc.py` |
| 抖音的网络栈 hook 不进 `CronetUrlRequest.start()` | `probe_urls2.py` |

详细说明见 `docs/PITFALLS.md`。
