# TVBox4.1+（Android 4.1+ 兼容版）

> 本软件由 AI 参考 [ZxxXinI/tvbox](https://github.com/ZxxXinI/tvbox) 编写，是独立维护的兼容版，不代表上游官方发布。

当前公开最新版：**v0.0.4 / code4**，显示名称 **TVBox4.1+**，包名 `com.tvbox.android44`。最低 Android 4.1 / API 16；侧载发行，targetSdk 28、compileSdk 35。使用 Java + XML Views、ExoPlayer 2 和 OkHttp 3.12。沿用原签名，支持已有正式版本的覆盖升级和远程 OTA。

扫码和手机端 AI 配置的改动、验证及测试步骤见 [v0.0.4 测试记录](docs/19-v0.0.4扫码与模型配置.md)。用户已反馈本轮测试没有问题；正式发布与 OTA 核验见 [v0.0.4 发布记录](docs/20-v0.0.4发布记录.md)。

[下载最新版](https://github.com/ZxxXinI/tvbox-android4/releases/latest) · [v0.0.4 发布记录](docs/20-v0.0.4发布记录.md)

Android 4.1 扩展的改动与验证见 [兼容记录](docs/17-Android4.1兼容扩展.md)。最低安装版本已下调并加入 API 16 回归，真实设备播放与遥控器仍需验收。仓库、目录和包名保留原标识，以保持既有安装与签名连续性。

## 当前功能

- **视频源首页**：直接展示当前 MacCMS 来源的内容、父/子分类、父分类全部聚合与海报分页；两套主题和三档字体。
- **点播**：多源增量搜索、晚到主源优先、详情渐进补线、H.264/AAC MP4 与未加密 HLS 播放、倍速/seek/上下集（媒体能力需目标盒子验证）。
- **历史与播放管家**：最多 100 条历史、线路/集标题恢复、详情失败时确认尝试旧地址、后台顺序保存；缓冲轮询与受控换线、异步线路健康记录。
- **AI 与扫码配置**：默认 DeepSeek、Qwen、GLM、KIMI、MIMO，普通中国区 API 地址自动填写。手机填 Key → 获取模型列表 → 选模型 → 保存；查询失败可用明确标注的候选或手动输入。按提供方分别保存模型和 Key，保留旧配置。两类二维码按实际像素生成、留白四模块，会话到期/成功后关闭并释放端口。
- **OTA**：可关闭的启动检查与提示去重、独立清单、强制 SHA-256、下载进度与取消、FileProvider 和系统安装器、API 26+ 未知来源授权。
- **精简设置**：删除电视端“添加自定义源”和“模型”按钮及对应遥控器焦点；视频源新增与 AI 模型选择统一通过扫码网页，保留接口管理、提供方和 Key 状态。
- 导航为历史、搜索、推荐、设置；数字键 **1 / 2 / 3 / 6**。电视和平台直播入口暂时隐藏。

首页不要求豆瓣。当前约定与设备验收见 [docs/13-当前版本基线与验收清单.md](docs/13-当前版本基线与验收清单.md)。

## 构建与验证

环境：JDK 17、Android SDK platform 35、build-tools 34.0.0 与 35.0.0；Gradle 8.10.2 的官方分发文件带 SHA-256 校验。

```bash
bash gradlew testDebugUnitTest testReleaseUnitTest lintDebug lintRelease assembleDebug assembleRelease \
  --no-daemon --max-workers=4 --console=plain -PTVBOX_OTA_MANIFEST_URL=
```

APK 位于 `app/build/outputs/apk/debug/` 和 `release/`。测试覆盖纯领域逻辑及 Robolectric API 16/19/23/28；CI 在 `.github/workflows/android.yml`，上传 APK、测试、Lint 和验证清单材料。

上方验证命令显式清空 OTA 地址，避免测试访问线上服务。普通构建默认使用 `https://github.com/ZxxXinI/tvbox-android4/releases/latest/download/update.json`；正式发布不要使用清空地址的参数。设置 → 检查更新可手动触发；打开启动检查后，发现新版本会提示，下载通过大小/哈希校验后交给系统安装器，不会静默安装。

设置项可通过 Gradle 属性、环境变量或未提交的 `local.properties` 注入，参考 [local.properties.example](local.properties.example)。正式发布配置原签名，递增 `TVBOX_VERSION_CODE`，启用 `TVBOX_REQUIRE_RELEASE_SIGNING=true`；未配置签名的 Release 仅为调试签名验证构建。

发布材料由 [scripts/prepare_release.py](scripts/prepare_release.py) 从实际 APK 生成；默认拒绝调试证书，不执行上传或线上发布。完整命令与发布顺序见 `docs/13`。

## 安装与使用

1. 侧载 APK 到 API 16 或以上测试设备。
2. 设置中选择可用视频接口，或扫码添加自定义 MacCMS 接口。
3. 首页直接浏览该源内容；AI 推荐需配置实际可用的模型服务。

旧正式版可在设置中检查更新。若已装本地 v0.0.4/code4 测试包，同版本不会再次提示升级，请手动覆盖安装正式 APK。

当前版本的发布与签名核验见 [v0.0.4 发布记录](docs/20-v0.0.4发布记录.md)；上一轮过程保留在 [v0.0.3 历史记录](docs/18-v0.0.3发布记录.md)。

## 验证与接手

当前 Android 4.1 扩展的构建、测试、签名和 Lint 结果见 [兼容记录](docs/17-Android4.1兼容扩展.md)；此前验证见 [VERIFICATION_REPORT.md](VERIFICATION_REPORT.md)。本机未连接 API 16 设备，真实安装、出画出声与遥控器结论仍待设备验收。

- [文档入口](docs/README.md)
- [接手说明](HANDOFF.md)
- [开发日志](AI_DEV_LOG.md)
- [变更记录](CHANGELOG.md)

历史开发日志中的设备结果仅对应当时版本，不替代本轮验收。
