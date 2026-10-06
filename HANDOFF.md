# 当前接手说明

更新：2026-10-06。工程为 `ZxxXinI/tvbox-android4`，独立包 `com.tvbox.android44`；允许维护本仓库 `app/`，不依赖上游 Compose/Media3 模块。

先读 [docs/13-当前版本基线与验收清单.md](docs/13-当前版本基线与验收清单.md) 和 [VERIFICATION_REPORT.md](VERIFICATION_REPORT.md)。首页为当前视频源，直播入口保持隐藏；不要按旧文档恢复豆瓣首页或六入口导航。

规划复查、已补实现与剩余验收见 [docs/14-文档规划复查与待补任务.md](docs/14-文档规划复查与待补任务.md)。本轮 P1/P2 源码缺口及流程回归已补齐；设备和正式发布仍待验收，P3 剩余布局建议见验证报告。

## 已补全

- OTA：安装权限、清单校验、流式下载校验、失败/取消清理与回调取消保护。
- 请求：分类聚合独立子线程池、共享请求订阅取消隔离、视频源全局并发 3、正确超时分类。
- 搜索/详情：晚到主源优先，稳定结果位置与卡片点击目标；补线在主线程追加、去重并尊重取消。
- 历史：独立快照、排序去重、上限 100、损坏备份、原子替换、失败保留旧数据、播放器后台顺序保存。
- 页面：卡片显式焦点、主页面字体缩放、分类/搜索词/列表焦点/详情选集保存，销毁回调保护，分页失败重试。
- 播放闭环：统一返回请求与状态快照、详情选集/线路/焦点同步；真实缓冲事件与轮询接入、暂停/seek/开关保护；一次受控媒体类型回退。
- 历史恢复与健康：线路/集标题匹配、近片尾从零恢复、首次详情失败确认旧 URL；健康初始化/写入/清空使用顺序磁盘队列与独立快照。
- 启动更新：开关与清单接入、错误静默、进程内 15 分钟节流和同版本提示去重，仅确认后进入设置。
- AI/扫码：请求与错误分类、取消后排队结果保护、推荐 View 代次和语音权限分支；扫码固定模式/单地址、主动到期关闭、一次成功与失败上限，响应后再关闭弹窗。
- 文案与列表：布局资源化、相关动态文案格式化，分类/线路/选集/推荐列表使用稳定 ID 和差异更新。
- 工程：官方仓库与 wrapper 校验和、Debug/Release 自动测试和 Lint、CI、发布材料生成器及正式签名检查。

## 源码入口

| 范围 | 路径（相对 `app/src/main/java/com/tvbox/android44/`） |
| --- | --- |
| 组装与线程 | `app/TvBoxApp.java`、`common/AppExecutors.java` |
| 视频请求与取消 | `data/repository/MovieRepository.java`、`data/remote/SourceRequestGate.java`、`CancelScope.java` |
| 搜索与补线 | `MultiSourceSearch.java`、`DetailSupplement.java`（在 `data/repository/`） |
| 首页与导航 | `feature/home/HomeFragment.java`、`feature/main/MainActivity.java` |
| 焦点与卡片 | `common/PageFocusState.java`、`FocusScaler.java`、`common/ui/PosterGridAdapter.java` |
| 历史 | `data/local/HistoryStore.java`、`JsonIo.java`、`feature/history/HistoryFragment.java` |
| 播放与历史恢复 | `feature/player/PlayerActivity.java`、`VodMediaSources.java`、`domain/playback/PlaybackSelection.java` |
| 健康 | `data/local/HealthStore.java`、`domain/model/LineHealth.java` |
| 推荐与配置 | `data/remote/AiClient.java`、`data/repository/RecommendRepository.java`、`feature/settings/ConfigHttpServer.java` |
| 更新 | `data/repository/UpdateRepository.java`、`data/remote/OtaClient.java`、`domain/parser/OtaManifestParser.java`、`domain/update/StartupUpdatePolicy.java` |

测试在 `app/src/test/java/`；使用本地 MockWebServer，不请求生产内置源或真实模型。Robolectric 4.10.3 保留 API 19 支持，当前测试包含 API 19/23/28，仅用于测试。

## v0.0.2 发布准备

当前源码默认 0.0.2 / code 2，原公开版本为 0.0.1 / code 1。原版使用调试证书，与当前云环境证书不同；原私钥和正式签名配置尚未提供，GitHub API 网络草稿尚待应用。发布工具默认检查上一版版本码与证书一致性；不要上传当前验证包替代正式发布。实际证据、签名选项与继续命令见 [发布记录](docs/15-v0.0.2发布准备与记录.md)。

## 下一步设备与发布验收

代码和云环境能完成的验证详见报告；以下需要实际设备/配置：

1. API 19 物理盒子冷启动、遥控器全流程、真实 MP4/HLS 解码、播放管家与持续使用。
2. API 19 与 API 26+ 的系统安装器、未知来源授权、同证书 OTA 升级。
3. 用实际模型、局域网手机和视频接口验收 AI 推荐与扫码配置。
4. 正式发布密钥、递增版本号、正式 URL 与线上清单；与上一版本证书比对。
5. 源码已推送 main，首次线上 CI 失败、无产物，链接与具体限制见 docs/15；API 网络恢复后读取日志并修复，不能将本地通过替代线上结果。

不要把上述“待验收”写成兼容完成。原始 `docs/Android4.4.zip` 保留不再同步，维护 Markdown 即可。

## 构建、产物与回滚

执行 README 中的一组完整命令；产物与报告在 `app/build/`。正式构建启用 `TVBOX_REQUIRE_RELEASE_SIGNING=true`；发布生成器默认拒绝调试签名。回滚使用已保留的上一版 APK 与清单，保持发布证书，不删除用户历史。

源码提交与发布记录以 docs/15 的最新证据为准；根目录开发日志按日期保留历史过程，其旧设备结论不用于当前版本。
