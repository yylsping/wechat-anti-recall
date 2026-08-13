# WeChat Anti-Recall

面向微信 `8.0.69`（versionCode `3022`）的 LSPosed 防撤回模块，基于 libxposed Modern API 102。

## 功能

- 他人撤回消息时保留原消息，并插入“xxx 尝试撤回一条消息”的系统提示。
- 提示中的“一条消息”使用微信原生可点击样式，可定位到原消息。
- 自己撤回消息时完整保留微信原生流程，包括“重新编辑”等功能。
- 仅在微信主进程和受支持版本中安装业务 Hook。
- 不联网，不包含后台服务、轮询或常驻任务。

## 兼容性

| 项目 | 要求 |
| --- | --- |
| 目标应用 | 微信 8.0.69（3022） |
| Android | 8.1（API 27）及以上 |
| 框架 | 支持 libxposed Modern API 102 的 LSPosed |
| 模块版本 | 1.4.1（versionCode 15） |

模块依赖微信内部类与方法签名，不支持其他微信版本。版本不匹配时不会安装业务 Hook。

## 数据说明

模块使用微信自身的消息记录和 UI 组件，不读取或上传聊天内容。为了在微信重启后继续定位，模块会把原消息的本地 ID 写入新增撤回提示的内部保留字段；该元数据保存在微信本地数据库中，不会显示在聊天界面。

## 安装

1. 从 GitHub Releases 下载并安装 APK。
2. 在 LSPosed 中启用模块，作用域只选择“微信”。
3. 强制停止微信后重新打开。

需要回滚时，在 LSPosed 中停用模块并重启微信，或直接卸载模块。

## 构建

需要 JDK 17、Android SDK 36，并可从 Maven Central 获取 `io.github.libxposed:api:102.0.0`。

```powershell
.\gradlew.bat clean :app:assembleRelease
```

APK 输出到 `app/build/outputs/apk/release/`。当前本地 `release` 任务使用 Android 调试签名；正式发布时应使用独立、妥善保管的发布密钥重新签名。

## 相关项目

- [QQ Recall Guard](https://github.com/yylsping/qq-recall-guard)：面向 QQ 9.2.60 的独立防撤回模块，并为 ColorOS/HeyTap 场景提供基于推送快照的冷启动文本恢复。

两个项目分别适配微信与 QQ，没有运行时或构建依赖。

## 许可证

本项目采用 [MIT License](LICENSE)。

## 免责声明

本项目仅供学习、研究和个人设备使用，与腾讯、微信或 LSPosed 项目无隶属或认可关系。使用前请确认符合当地法律及相关服务条款。
