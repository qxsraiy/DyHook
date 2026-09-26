# 交接清单

给「删掉本地、换台机器、换个 AI」继续开发用。按顺序做完就能跑起来。

---

## 1. 环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | **21** | AGP 9.3.x 要求 |
| Android SDK | **compileSdk 37** | LibXposed API 102 硬要求 |
| Gradle | **9.5.1** | 已带 wrapper，用 `./gradlew` 即可 |
| Android Build Tools | 36.1.0 | `package.py` 用它的 `zipalign` / `apksigner` |
| 设备 | Android 8.0+，已装 LSPosed | 支持 LibXposed API 102 |
| 抖音 | 38.x | 实测版本 |

**`local.properties`**（不入库，需自己建）：

```properties
sdk.dir=C\:\\Users\\<你>\\AppData\\Local\\Android\\Sdk
```

---

## 2. 构建

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/Android/Sdk

# 编译
./gradlew assembleDebug

# 打包（注入 META-INF/xposed + 重签名 + arsc 对齐自检）
python tools/package.py
```

产出 `DyHook.apk`，`package.py` 会打印：

```
自检:
  resources.arsc  压缩方式 = STORED ✓
  resources.arsc  数据偏移 = 788048 -> 4字节对齐 ✓
```

> `package.py` 依赖 `ANDROID_HOME` 找 `build-tools/36.1.0`。
> 签名用的是 `~/.android/debug.keystore`（密码 `android`），没有的话先生成一个：
> ```bash
> keytool -genkey -v -keystore ~/.android/debug.keystore -storepass android \
>   -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
>   -dname "CN=Android Debug,O=Android,C=US"
> ```

### LibXposed 声明文件（关键）

`app/src/main/xposed/META-INF/xposed/` 下三个文件，**AGP 不会自动打进 APK**，
必须靠 `package.py` 注入：

| 文件 | 内容 |
|---|---|
| `java_init.list` | 入口类名 `com.dyhook.txt.HookEntry` |
| `module.prop` | `minApiVersion=102` / `targetApiVersion=102` / `staticScope=false` |
| `scope.list` | 作用域 `com.ss.android.ugc.aweme` |

---

## 3. 安装与授权

```bash
adb install -r -t DyHook.apk

# 存储权限 + 通知权限（卸载重装后必须重新执行！）
adb shell su -c "appops set com.dyhook.txt MANAGE_EXTERNAL_STORAGE allow"
adb shell su -c "pm grant com.dyhook.txt android.permission.POST_NOTIFICATIONS"
adb shell su -c "appops set com.dyhook.txt POST_NOTIFICATION allow"
```

**LSPosed 里**：

1. 启用模块
2. 作用域勾选 **抖音**（`com.ss.android.ugc.aweme`）
3. **强制停止抖音再打开**（每次更新模块都要做）

---

## 4. 配置

打开模块 App，设置页填：

| 项 | 说明 |
|---|---|
| 启用自动提取 | 保持开 |
| 自动发件 | 开 = 解析完直接发，只弹提示条；关 = 通知栏手动发 |
| AI 模式 | 接口地址 / API Key / 模型（OpenAI 兼容 `/v1/chat/completions`） |
| 论坛 | 地址 / 账号 / 密码 / 默认标签 slug |

**凭据只存在设备本地**，不入库：

```
/sdcard/Documents/dyhooktxt/config.txt
/sdcard/Documents/dyhooktxt/forum.txt
```

⚠ 填完配置后：**打开一次模块界面**（写共享副本）→ **强制停止抖音** → **重开**。
原因见 `docs/PITFALLS.md` #2。

---

## 5. 验证

### 快速验证（不需要抖音）

```bash
adb shell am start -n com.dyhook.txt/.ShareReceiverActivity \
  --es text '测试正文<br/>第二段。' \
  --es source article --es title '测试标题' --es author '测试作者（123456）'

sleep 20
adb shell su -c "ls -1t /storage/emulated/0/Documents/dyhooktxt/青空_*.txt | head -1"
```

预期：产出 `青空_测试标题_测试作者（123456）by抖音_<时间戳>.txt`，
内容为「标题/作者/时间/来源 + 分隔线 + 洗好的正文」。

### 端到端验证

抖音 → 文章页 → 分享 → 复制口令 → 看 `douyin.log`：

```
[拦截] 在文章页命中口令，开始处理
[提取] 抓到文章: 标题 / 作者（抖音号） len=xxxx
[提取] 源文件已保存: .../源_...txt
[服务] 来源=抖音文章 | 强制AI=false | AI开关=true | 本次走AI=true
[服务] AI 完成: title=... author=... len=xxxx 耗时 xxxms
[服务] 本地洗稿完成，正文长度=xxxx
[服务] 成品已保存: .../青空_...txt
```

---

## 6. 代码结构

见 `docs/ARCHITECTURE.md`。速查：

```
app/src/main/java/com/dyhook/txt/
├── HookEntry.java             LSPosed 入口（唯一 extends XposedModule 的类）
├── ShareInterceptor.java      拦截剪贴板
├── ShareParser.java           只认文章口令
├── Models.java                抓文章模型（正文 + 作者五级兜底）
├── Extractor.java             抖音侧编排
├── AiClient.java              AI 客户端 + cleanText() 本地洗稿
├── AiProcessService.java      洗稿编排 + 落盘 + 通知
├── ForumClient.java           Flarum JSON:API + token 缓存
├── SendActionReceiver.java    发件（串行队列 + 重试 + 幂等）
├── Notifier.java              通知栏
├── FileSaver.java             落盘
├── SharedCfg.java             跨进程共享配置
├── DyLog.java                 独立日志（零 libxposed 依赖）
├── UiCtx.java                 Context / Activity / Toast
├── ShareReceiverActivity.java 透明 Activity（系统分享 + 唤醒）
├── ProcessReceiver.java       广播兜底
└── MainActivity.java          设置 + 待发件列表
```

---

## 7. 改代码前必读

**`docs/PITFALLS.md`** —— 14 条实测踩坑，每条都会让你少走几小时弯路。
最关键的三条：

1. **模块 App 组件不能引用 `HookEntry`**（会 `NoClassDefFoundError`，组件全崩）
2. **FUSE 沙盒**：抖音读不到模块创建的文件，必须用「抖音先创建」的副本
3. **`resources.arsc` 必须不压缩 + 4 字节对齐**，否则点 APK 装不上

---

## 8. 开发约定

- **日志一律用 `DyLog`**，不要用 `HookEntry.logi()`（见 PITFALLS #1）
- **凭据绝不出现在代码里**，只从设备本地文件读
- 改完 `package.py` 相关的东西，记得跑一次 arsc 自检
- 每次发版：`git tag` + GitHub Release + 上传 APK

## 9. 探测脚本（`tools/probes/`）

逆向过程留下的 frida / adb 脚本，用于：

- 枚举抖音类与方法（`probe_fields.py`、`probe_api.py`）
- 抓模型字段（`probe_abs*.py`、`probe_fedata.py`）
- 网络与文件权限探测（`probe_cfgread.py`、`probe_filecreate.py`）

**用之前要改**：脚本里的设备序列号（`DEV = "..."`）。
需要设备上跑 `frida-server`（16.x，frida 17 没有内置 Java bridge）。
