# 当前验证报告

日期：2026-10-06。范围：P1/P2 规划补全、文案与列表整理及 v0.0.2 发布准备；默认版本 `0.0.2` / code `2`。此前 v0.0.1 验证结果保留在开发日志，原验证包已备份；本报告对应最新候选版本，不代表 GitHub Release 已发布。

## 结论

- Debug 与 Release 各 **181 项测试、31 个测试类**，失败/错误/跳过均为 0；本轮新增 **65 项、10 个测试类**。
- `assembleDebug` 与开启 R8/资源收缩的 `assembleRelease` 成功。
- `lintDebug` 与 `lintRelease` 均为 **0 Error / 0 Fatal / 44 Warning**；上轮为 126 条警告，当前布局 `HardcodedText` 为 0。
- 候选 APK 均为 `com.tvbox.android44`、minSdk **19**、targetSdk **28**、版本 `0.0.2 / 2`。用户上传原 keystore 后，Release 已完成与公开 v0.0.1 相同证书的 v1/v2 签名；先前未签名包仅保留作备份。
- Debug 包含 3 个 DEX，Release 为 1 个；Debug 已启用 AndroidX Multidex 2.0.1，在 attachBaseContext 安装。静态核对主 DEX 包含 TvBoxApp、MultiDex 及构造时引用的 StartupUpdatePolicy。
- 发布工具 **13 项回归通过**，包括上一版版本码递增、证书连续性及用户选定的原调试证书兼容发布模式。实际云环境验证包因与 v0.0.1 证书不同而被正确拒绝，未生成可上传的清单。
- 原签名私钥已取得并核对，真实签名与材料生成通过；GitHub API CONNECT 被代理拒绝，放行配置草稿已保存但尚未应用。尚未创建 v0.0.2 Release 或线上 update.json。
- 无连接 ADB 设备、无 `/dev/kvm`；本轮没有真实设备安装、解码、遥控器、手机局域网或同证书 OTA 升级结果。

## 执行环境与命令

JDK 17.0.20.1、Gradle 8.10.2、AGP 8.7.3、SDK platform 35、build-tools 34.0.0/35.0.0。依赖来自官方 Google、MavenCentral 和 Plugin Portal；Gradle 分发配置 SHA-256。Robolectric 4.10.3 仅为测试依赖，当前用例覆盖 API 19/23/28。

```bash
bash gradlew testDebugUnitTest testReleaseUnitTest lintDebug lintRelease assembleDebug assembleRelease \
  --no-daemon --max-workers=4 --console=plain
```

最终输出：`BUILD SUCCESSFUL in 1m 52s`；`98 actionable tasks: 53 executed, 45 up-to-date`。

最终日志：`/workspace/.cloud-setup/tvbox/logs/release-v0.0.2-validation.log`。本次全量验证使用空默认 OTA 地址，防止测试请求外部服务；之后另行注入正式清单地址构建未签名候选。云环境需先激活 `/workspace/.cloud-setup/tvbox/env.sh`；普通开发机按 README 设置 JDK/SDK。

发布策略回归：`python3 -m unittest discover -s scripts/tests -p 'test_*.py' -v`，13 项全部通过，覆盖递增版本、同证书、明确证书迁移、v1、默认调试证书拒绝，以及沿用原调试证书的独立发布模式。新增 7 项不修改 Android 源码或 APK，未因此重复全量 Android 构建。

未签名候选使用本地 `unsigned.init.gradle` 在 Android DSL 完成前移除 Release 签名配置，并注入 `https://github.com/ZxxXinI/tvbox-android4/releases/latest/download/update.json`。构建日志 `release-v0.0.2-unsigned-final.log` 为 `BUILD SUCCESSFUL in 11s`，39 个任务；实际 APK 经 aapt、ZIP 和 apksigner 核对，版本/SDK 正确、含该清单地址且确实未签名。正式发布必须另用受控私钥完成签名并重新计算哈希。

原私钥签名初次构建日志 `release-v0.0.2-original-signing.log` 为 `BUILD SUCCESSFUL in 10s`，40 个任务、退出码 0。启用 TVBOX_REQUIRE_RELEASE_SIGNING=true，从受控环境注入签名并构建；实际 v1/v2 和证书连续性通过，材料副本/哈希/大小一致。源码提交后将固定最终构建及其 version-control-info，最终摘要以 docs/15 为准。

## 本轮新增回归

| 测试类 | 项数 | 场景 |
| --- | ---: | --- |
| HealthStoreTest | 6 | 后台初始化、顺序写入/清空、重启、独立快照、坏记录/损坏备份、30 天/300 条、慢缓冲时间、写失败与计数边界 |
| PlaybackSelectionTest | 6 | 线路标识变化、同名线路、剧集重排、标题匹配、索引兜底、近片尾下一集/最后一集与返回策略 |
| PlayerFlowTest | 14 | 实际 Activity 启动/结果/详情焦点；阻塞磁盘仍返回最新状态；持续/频繁缓冲、暂停/seek/开关、用尽线路、停止释放、一次格式回退；旧 URL、自然下一集/最后一集、暂停保存与重建 |
| VodMediaSourcesTest | 2 | URL path 和查询参数区分、实际响应 MIME、无额外探测请求 |
| StartupUpdatePolicyTest | 4 | 开关/空地址、并发去重、15 分钟节流、旧 token 取消隔离与新版本单次提示 |
| StartupUpdateFlowTest | 6 | 本地真实清单 HTTP；启动开启/关闭/空地址/500 静默、重建去重、停止取消；确认仅进入设置，无安装授权 |
| ConfigHttpServerTest | 8 | 真实回环 Socket，UTF-8 字节长度与成功响应、一次性提交、主动到期、旧回调/新模式隔离、5 次失败、非法请求、端口占用、连续开关 10 次 |
| AiClientTest | 5 | 四提供方 URL、授权头/模型/messages、HTTP 401/403/429、空/异常结构、URL 规范化、真实读取超时和取消 |
| RecommendRepositoryTest | 5 | 单工作线程完成请求、错误分类、取消在途/已排队/未配置结果、新请求可继续 |
| RecommendFlowTest | 9 | 新问题取消旧结果、重复提交、错误 Key 保留上一批、隐藏/重建、不抢导航焦点、重复/重排卡片、无语音服务、API 23 权限和销毁后语音结果 |

这 65 项连同此前 116 项一起通过 Debug/Release。既有 OTA 下载校验与安装 Intent、分类聚合/共享请求/并发 3、多源搜索、详情补线、历史存储、导航焦点与领域解析回归继续通过。测试只使用本地夹具与测试 Key，不请求用户源或真实模型。

播放器用例使用受控 ExoPlayer 接口与假时钟，验证 Activity 接入和决策；媒体用例验证创建/响应头/回退策略，不能证明真实 MP4 或无扩展名 HLS 已出画出声。Robolectric 不能验证设备解码器、固件安装器、真实语音服务、遥控器或渲染裁切。Release 单元测试运行 Release 源集，R8 APK 的实际运行仍需设备。

## 实际改动入口

| 范围 | 主要文件 |
| --- | --- |
| 播放与详情 | `feature/player/PlayerActivity.java`、`VodMediaSources.java`、`feature/detail/DetailActivity.java`、`EpisodeAdapter.java`、`feature/history/HistoryFragment.java`、`domain/playback/PlaybackSelection.java`、`WatchHistoryItem.java` |
| 健康存储与组装 | `data/local/HealthStore.java`、`domain/model/LineHealth.java`、`app/TvBoxApp.java`、`feature/settings/SettingsFragment.java` |
| AI 与页面 | `data/remote/AiClient.java`、`data/repository/RecommendRepository.java`、`feature/recommend/RecommendFragment.java` |
| 更新与扫码 | `feature/main/MainActivity.java`、`domain/update/StartupUpdatePolicy.java`、`feature/settings/ConfigHttpServer.java`、`SettingsFragment.java`、`SettingsRepository.java`、`common/AppConstants.java` |
| 文案与列表 | `common/ui/ChipAdapter.java`、`EpisodeAdapter.java`、推荐列表、`res/values/strings.xml` 与 13 个布局 |
| 回归与文档 | 上述 10 个新增测试类；根 README/HANDOFF/CHANGELOG/开发日志/本报告，docs/06、07、09、10、13、14 与文档入口 |
| 发布准备 | `app/build.gradle` 版本 0.0.2/2；`scripts/prepare_release.py` 与 `scripts/tests/test_prepare_release.py`；CI 发布策略检查；docs/15 |

Java 路径相对 `app/src/main/java/com/tvbox/android44/`；详细行为与验收条件见 [规划状态](docs/14-文档规划复查与待补任务.md)。R8 规则继续保留 Gson 存储/领域字段和 Serializable 历史模型；已核对 HealthMap 和 WatchHistoryItem 的映射。

## APK 与发布材料

| APK | 字节数 | SHA-256 |
| --- | ---: | --- |
| Debug（调试签名） | 7,648,138 | `2029b54bccbcf149c80d7cab4fd94f030feb64c04d81cb24446dd96e13fdc88e` |
| Release（先前未签名备份，不能安装） | 2,909,854 | `0b5622920dba59bbf22e25f7ed85b36d923b595c744eba88bda3e9d863946c5f` |
| Release（原证书签名初次验证包） | 2,972,831 | `02eca43605318681a1497ad9d6bd2bbfd7959b4eaf642cc38e656f939c0f0c2d` |

- [Debug APK](app/build/outputs/apk/debug/app-debug.apk)
- [当前原证书签名 Release APK](app/build/outputs/apk/release/app-release.apk)
- [Debug 测试报告](app/build/reports/tests/testDebugUnitTest/index.html)
- [Release 测试报告](app/build/reports/tests/testReleaseUnitTest/index.html)
- [Debug Lint](app/build/reports/lint-results-debug.html)
- [Release Lint](app/build/reports/lint-results-release.html)

上一轮 v0.0.1 验证包及报告保存在 `/workspace/.cloud-setup/tvbox/release-v0.0.2/baseline/`；先前未签名包备份在 `/workspace/.cloud-setup/tvbox/release-v0.0.2/`，初次原证书签名材料在其 `initial-signed/`。表中哈希各自对应这些字节；最终源码构建后的摘要将写入 docs/15 并用于实际 update.json。

已下载公开 v0.0.1 APK，并核对其证书摘要为 `d507b831ebd7af498c550d0e24b84d1a10f218132ec9e6ce4c43d19f15c1e2d6`（Android Debug）。云环境 Debug 证书为 `0e64f475052556db51387d103a43f11072afb5b676c03eeb7486737b90886e2a`，不能覆盖旧安装。先前云环境证书 v0.0.2 APK 在传入 --previous-apk 后被生成器拒绝，退出码 1、无输出目录；用户上传原 keystore 后的新签名 APK 则与公开上一版一致，生成器已接受并记录可覆盖升级条件。

用户明确选择沿用原证书后，发布工具增加 --allow-legacy-debug-upgrade：要求新旧 APK 使用相同的原调试证书、版本递增，并禁止证书迁移/CI 验证参数混用；签名类别如实记录 legacy-debug-release。实际云环境证书 APK 被拒绝、无输出目录；实际原证书 APK 被接受，两个摘要均为 d507b831…c1e2d6、版本 2 大于 1、清单哈希/大小与副本一致。不能把签名条件核对当成物理设备升级实测。

静态检查包括 `git diff --check`、22 个 Markdown UTF-8/BOM/U+FFFD/64 个本地链接、68 个变更代码文件无 BOM、35 个 XML 解析及 138 条字符串资源引用、设备脚本语法、发布脚本 Python 语法和 CI YAML 解析。业务源码 4543901 已推送 main；首次 [GitHub Actions](https://github.com/ZxxXinI/tvbox-android4/actions/runs/37434928294) 为 Failure、无产物，日志要求登录；API 放行后需读取真实日志、修复并取得线上通过结果。源码、证书与发布进度见 [发布记录](docs/15-v0.0.2发布准备与记录.md)。

## 剩余警告与验收

44 条非阻断警告包括 SetTextI18n 8、NotifyDataSetChanged 7、DiscouragedApi 5、布局/资源/自动填充建议及保留模块提示；完整报告保留逐条位置。允许用户配置 HTTP 视频接口的网络基线提示仍存在，HTTPS 证书验证没有放宽；API 19 空间检查保留 UsableSpace 建议。无 NewApi/InlinedApi 阻断，未提高 minSdk 或新增关闭 Lint 来通过检查。

设备和正式发布按 [验收清单](docs/13-当前版本基线与验收清单.md) 执行：API 19 冷启动/Multidex、真实标准/无扩展名 HLS 与 MP4、遥控器、慢流换线、持续使用、手机扫码、实际模型、API 26+ 安装授权及同证书升级。版本码已递增至 2；正式签名、公开下载与线上 CI 结果仍待完成。

JSON 模型与现有版本兼容，未做破坏性迁移；源码回滚保留用户文档与既有数据。正式安装包回滚仍受证书与 Android 版本码限制，应保留原发布 APK/清单；当前 Debug 与未签名候选均不能作为旧用户的覆盖升级包。
