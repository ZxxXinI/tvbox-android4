# TVBox 4.4（Android 4.4 兼容版）

> **重要说明：本软件由 AI 参考 [ZxxXinI/tvbox](https://github.com/ZxxXinI/tvbox) 编写。**
>
> 本项目是面向 Android 4.4（API 19）及以上设备的独立兼容版，与现代版 TVBox 分开维护；不代表上游项目的官方发布或官方背书。

当前版本：**v0.0.1**

面向 Android 4.4（API 19）及以上老电视/盒子的独立影视应用：传统 XML Views + ExoPlayer 2 旧坐标 + 单一 OkHttp 3.12 网络栈，无 Compose / Media3 / OkHttp4。

## 发布信息

- 首个公开版本：`v0.0.1`
- 最低系统：Android 4.4 / API 19
- 发布类型：侧载 APK，不面向 Google Play
- 应用包名：`com.tvbox.android44`
- 原始参考项目：[https://github.com/ZxxXinI/tvbox](https://github.com/ZxxXinI/tvbox)

## 功能

- **点播**：MacCMS 多来源（8 内置 + 自定义）聚合搜索（并发≤3、主源优先、增量展示）、分类浏览、详情多线路补线、ExoPlayer 播放（H.264/AAC/MP4/未加密 HLS）、倍速/seek/上下集。
- **首页**：豆瓣热播（剧集/综艺/电影）+ 失败回退当前视频接口；点击豆瓣卡片后按片名搜索真实资源。
- **历史与播放管家**：100 条观看历史、断点续播、线路健康统计（30 天）、连续/频繁/累计卡顿判定与自动换线。
- **电视直播（IPTV）**：文本订阅、分组频道、多线路、数字选台、4 秒无进度看门狗换线。
- **平台直播**：连接 `platform_live_server` 统一接口浏览平台/分类/房间（服务端另行部署）。
- **AI 推荐**：OpenAI Chat Completions 兼容提供方（Agnes/DeepSeek/SiliconFlow/Qwen），推荐条目点击后进入普通搜索。
- **局域网扫码配置**：电视生成二维码（AI 配置 :9978 / 自定义接口 :9979），手机扫码填写、一次性 token、会话自动关闭。
- **OTA**：独立 update.json 清单、SHA-256 校验、FileProvider 调系统安装器。
- 双主题（默认/影院）、三档字体、遥控器数字键 1~6 快速导航。

## 构建

环境：JDK 17 + Android SDK（compileSdk 35）。

```bash
export JAVA_HOME=/path/to/jdk17
./gradlew assembleDebug          # 调试包
./gradlew assembleRelease        # 发布包（签名参数经 local.properties 注入）
./gradlew testDebugUnitTest      # 纯逻辑单元测试
./gradlew lintDebug              # Lint
```

`local.properties` 可注入（均可选，参考 `local.properties.example`）：

- `TVBOX_OTA_MANIFEST_URL`：更新清单地址（默认空 = 关闭检查）
- `TVBOX_IPTV_SOURCE_URL`：IPTV 订阅默认地址
- `TVBOX_PLATFORM_LIVE_SERVICE_URL`：平台直播服务默认地址
- `TVBOX_STORE_FILE / TVBOX_STORE_PASSWORD / TVBOX_KEY_ALIAS / TVBOX_KEY_PASSWORD`：Release 签名

## 安装与使用

1. 侧载 APK 到电视/盒子（minSdk 19，targetSdk 28，支持普通与 Leanback 桌面入口）。
2. 首次使用建议先「设置 → 视频源」确认/切换可用接口，或「扫码添加接口」自定义源。
3. AI 推荐、IPTV、平台直播均为外部服务：不可用时会降级提示，不影响点播。

## 验证状态（证据分层）

| 层级 | 状态 |
| --- | --- |
| 静态（构建/Lint/依赖树） | ✅ assembleDebug / lintDebug 通过，依赖树无 API21+ 阻断 |
| 自动化（单元测试） | ✅ 69 个纯 JVM 用例（解析器/合并/换线/卡顿判定/OTA）全部通过 |
| 运行（真机 Android 9 API28 小米电视） | ✅ 冷启动、豆瓣首页、多来源搜索、详情补线、H.264 播放出画、seek、历史、扫码配置端到端、AI 错误路径 |
| 运行（API 19 实机） | ❌ 暂无 API 19 设备，TLS/媒体/遥控器最终结论以 API 19 实机为准 |

## 目录

- `app/src/main/java/com/tvbox/android44/`：common（UI/常量/执行器）、domain（模型/解析器/播放逻辑）、data（本地存储/远端客户端/仓库）、feature（页面）。
- `docs/`（工程根上级）：产品与规则文档（总规则、兼容性、各模块规则、任务单）。
- `AI_DEV_LOG.md`：逐轮开发日志；`HANDOFF.md`：交接与剩余任务。

## 许可与内容声明

本工程不打包任何影视内容、直播源凭据或 API Key；所有内容由使用者自行配置并确认授权。仅供学习交流。
