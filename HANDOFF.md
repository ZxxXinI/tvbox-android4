# 交接说明（给下一个开发模型）

本文档是 TVBox Android 4.4 独立工程的进度交接。请先完整阅读 `docs/00-总规则与执行协议.md`、`docs/02-兼容性与技术选型规则.md`、`docs/11-分阶段实施任务单.md`，再读本文件。硬约束不变：minSdk=19、XML Views、ExoPlayer 2 旧坐标、单一 OkHttp 3.12 栈、不泄露 Key/Cookie、一次一个 Gate。

## 1. 环境与命令（本机已就绪）

| 项 | 值 |
| --- | --- |
| 工程根 | `/home/zxx/Dev/Tvbox/Android4.4/project/` |
| JDK 17 | `/home/zxx/jdk`（必须 `export JAVA_HOME=/home/zxx/jdk`） |
| Android SDK | `/home/zxx/Android/Sdk`（build-tools 35.0.0、platforms/android-35 已装； licenses 已接受） |
| 本地 Gradle | `~/tools/gradle-8.10.2`（仅用于生成 wrapper，已生成） |
| 网络 | 走代理 `127.0.0.1:7897`；仓库已配置阿里云镜像优先（settings.gradle），wrapper 分发地址指向腾讯镜像 |
| 模拟器 | 已有 AVD `rev30`/`rev33`（API 30/33，google_apis x86_64，KVM 可用）；**无 API19 镜像**，如需可 `sdkmanager "system-images;android-19;default;x86"` 尝试 |

常用命令（在工程根执行）：

```bash
export JAVA_HOME=/home/zxx/jdk
./gradlew assembleDebug --console=plain
./gradlew compileDebugJavaWithJavac
./gradlew testDebugUnitTest lintDebug
./gradlew :app:dependencies --configuration debugRuntimeClasspath
```

当前状态：**Gate 0~9 全部完成 + Gate 10 部分（Release R8 已验证）+ API19 模拟器验证完成（2026-08-30 晚）**。assembleDebug/assembleRelease/lintDebug/testDebugUnitTest(70 用例) 均 BUILD SUCCESSFUL。占位壳为零。运行证据覆盖：小米电视（Android 9/API 28）实机全链路 + **API19 模拟器**（Multidex 时机、TLS1.2 启用两处 API19 专属修复实测生效；另修复全平台缺陷"详情播放串被丢弃"）。**API19 物理盒子验证仍缺**；完整结论见 `VERIFICATION_REPORT.md`。

## 2. 已完成（按 Gate 对照）

- **Gate 0 完成**：独立工程、双启动入口、minSdk=19、锁版本依赖、README 缺失（见待办）、AI_DEV_LOG.md 已建。
- **Gate 1 骨架完成**：六入口导航（历史/搜索/推荐/电视/直播/设置固定顺序）、数字键 1~6、HOME 默认内容页、双击返回退出、StateLayout 加载/空/错/重试组件、焦点缩放双信号（描边+1.05 缩放）、三档字体（FontScale.wrap 于 BaseActivity.attachBaseContext）。
  - 待办：影院主题首页布局（左侧图标导航+Hero）未实现（HomeFragment 目前只有默认主题布局 fragment_home.xml）；弹窗焦点恢复仅部分实现。
- **Gate 2 完成（单源链路）**：MacCMS 客户端/DTO/Mapper/内容过滤/HTML 清理/播放串解析；分类列表、分类影片、搜索、详情（MovieRepository）。
- **Gate 3 完成（代码层面）**：ExoPlayer 2.19.1 core/hls/ui + 自建 OkHttpDataSource（API19 TLS）、FIT、自定义控制层（PlayerControllerView）、±10s seek、上/下一集、自动下一集、倍速序列、音频焦点、becoming noisy、常亮、释放路径。
  - 未验证：真机出画出声（无 API19 设备）。
- **Gate 4 完成（代码层面）**：HistoryStore（≤100、覆盖置顶、损坏回退）、HealthStore（30 天/300 条、30min 冷却）、BufferJudger（注入时钟）、AutoSwitchPolicy、播放器自动/手动换线、历史 10s 节流保存、片尾过近重看规则。
  - 待办：坏线可重复测试夹具（单测）。
- **Gate 5 完成（数据层）**：MultiSourceSearch（并发≤3 信号量、主源第一、增量合并、完成进度、取消隔离）；DetailSupplement（4s deadline、lineId 去重追加、失败冷却、取消不冷却）。
  - 待办：**SearchFragment 真实 UI 未写**（现在是占位壳，见 §4）。
- **Gate 6 完成（数据层）**：DoubanRepository 成功 20min/失败 6min/旧缓存回退；HomeFragment 已接入豆瓣热播+分类回退+豆瓣卡片→当前源搜索→最佳匹配→详情。
  - 已用真实豆瓣响应验证结构（items[].title/pic.normal/rating.value/card_subtitle/episodes_info）。
- **Gate 5/7/8 页面层完成（上一轮实现、2026-08-30 核实补记，仅编译级证据）**：SearchFragment（多来源增量渲染+状态行+requestId 防晚到）、HistoryFragment（过滤+进度条卡片+清空确认+Listener 刷新）、LiveTvActivity（全屏播放+频道列表+切台/切线+数字选台+看门狗）、PlatformLiveActivity + PlatformAdapters（四级导航+分页+CDN 候选）、ConfigHttpServer（9978/9979+端口回退+一次性 token+no-store+失败≥5 次失效）。
- **Gate 9 页面层完成（2026-08-30）**：
  - RecommendFragment：输入+快捷词（8 词轮转 4 个）+换一批+点击→switchToSearchWithQuery；busy 防重复+重提 cancel；失败保留上一批；Key 未配置引导扫码；语音降级（无服务隐藏入口、API23+ 动态权限）。
  - SettingsFragment：主题/字体（recreate+STATE_TAB 恢复）、视频源单选（invalidateAll+recreate）、自定义源新增（URL 校验+轻量测试+失败二次确认）/管理删除、AI 提供方/模型/Key 掩码、播放管家开关+statsSnapshot+清空、OTA 手动检查→确认→进度→安装（API26+ 权限分支）、IPTV/平台地址编辑+测试连接、扫码配置弹窗（QrCode+多 IPv4 候选+dismiss/onStop 释放端口+焦点回入口）。
  - 配套：VerticalSpacingDecoration、dialog_input.xml、dialog_qr_config.xml、item_recommend.xml、SettingsSectionHeader 样式。
- **lintDebug 首次通过（2026-08-30）**：修复 AspectImageView→AppCompatImageView；build.gradle disable ExpiredTargetSdkVersion（侧载 targetSdk=28 为文档基线，已注释理由）。159 条警告待 Gate 10 清理。
- **收尾冲刺（2026-08-30 下，详见 AI_DEV_LOG 同日第二篇 + VERIFICATION_REPORT.md）**：
  - 单元测试 12 类 69 用例全绿；测试揭出并修复 IPTV `$` 语义反文档（08 §5/§6）、HtmlCleaner 实体解码未返回 2 个实现 bug。
  - 实机（小米电视 API28）全链路验证；揭出并修复 Glide 注解器缺失（海报全 404）、OkHttpStreamFetcher 提前关流、recreate 后 Fragment 叠加、详情空集默认线路 4 个 bug。
  - 影院主题首页（rail+Hero）实机验证；README/update.json.example 完成。
  - lint 175→116（修 DefaultLocale/UnusedResources，禁用不适用 RTL）；Release R8 2.9MB 实机验证（Gson/ExoPlayer keep 生效）。

## 3. 未完成（下一步按优先级）

1. **API 19 物理盒子验证**：模拟器级已完成（含 2 处 API19 专属修复，见 VERIFICATION_REPORT §0）；剩遥控器/硬解/长播等真机项。已知系统限制：仅 GCM cipher 的 HTTPS CDN 在 4.4 无法握手（需兼容代理或 http 源）。
2. **需外部条件的验证**：AI 有效 Key 真实推荐、平台直播/IPTV 真实服务（当前离线）、OTA 真实服务端、真手机双端扫码、音频出声确认、30/60 分钟长播（docs/10 §6）。
3. **仓库层单测**：MultiSourceSearch 并发上限/超时/取消不记失败、DetailSupplement 追加不重置、HistoryStore 排序/覆盖/上限/损坏回退——需 Robolectric 或抽接口重构后才能 JVM 测（缓解：均有实机运行证据）。
4. **发布材料**：正式签名（当前 release 为 debug 证书回退）、versionCode 正式化并同步 update.json、CHANGELOG 独立成文、回滚旧版托管。
5. **lint 剩余 116 条**（HardcodedText 48 / NotifyDataSetChanged 11 等）：质量提示不阻断，维护 Gate 分批处理。
6. **依赖树核验**已通过（ExoPlayer 2.19.1 旧坐标 + OkHttp 3.12.13，无 Media3/Compose/OkHttp4），每次升级依赖后需复跑。

## 4. 占位壳

已全部替换完毕（2026-08-30）。原 fragment_search/recommend/history/settings.xml 占位与 RecommendFragment/SettingsFragment 空壳均为真实实现。

## 5. 数据层 API 速查（可直接调用）

全部经 `TvBoxApp.get()` 单例获取：

- `settings()`：currentApi()/allApis()/customApis()/addCustomApi(name,url)/removeCustomApi(id)/setCurrentApi(id)/theme()/fontScale()/aiProvider()/aiModel()/aiApiKey()/autoLineSwitch()/checkUpdateOnStart()/iptvUrl()/platformLiveUrl()；静态 AI_PROVIDERS、maskKey、isValidBaseUrl、normalizeBaseUrl。
- `movies()`：fetchHome(api,page,cb)/fetchByCategory(api,typeId,page,cb)/fetchDetail(api,movieId,cb)/searchSync(api,keyword,page,scope,timeoutMs)（同步，供编排器）/isCoolingDown/invalidateAll。返回 `MovieRepository.Request`（可 cancel）。回调主线程，Result<T> 三态。
- `search()`：search(query, Listener{onIncremental(merged,done,total,found)/onFinished(merged,anySuccess)}) → Handle.cancel()。
- `supplement()`：start(mainDetail, Listener{onLineAppended(detail,count)/onProgress(done,total)/onDone()}) → Handle.cancel()；静态 bestMatch(target,candidates) 可复用。
- `douban()`：hot(category∈{tv,show,movie}, page, cb) → RequestHandle；失败自动回退旧缓存（fromCache=true）或 Failure。
- `live()`：load(forceRefresh, cb) → Result<List<LiveChannelGroup>>。
- `platformLive()`：sites(cb)/categories(site,parentId,cb)/rooms(site,catId,page,cb)/resolve(site,roomId,refresh,cb)。
- `recommend()`：ask(query, cb)；Key 未配置返回 Failure(PERMISSION, 提示文案)。
- `updates()`：check(cb)/download(update, DownloadCallback)/canRequestInstalls()/buildInstallIntent(file)/manifestUrl()。
- `history()`：load()/find(apiId,movieId)/addOrUpdate(item)/clear()/addListener。
- `health()`：get(key)/recordSuccess|Fail|Slow(key,now)/statsSnapshot()/clearAll()；key 格式 `apiLineId|movieId|lineId`（PlayerActivity.healthKey）。
- `executors()`：network()/disk()/scheduler()/main(r)。

领域模型在 `domain/model/*`（字段公开、无 Android 依赖）；解析器在 `domain/parser/*`（全部纯函数可 JVM 测）；常量全在 `common/AppConstants`。

## 6. 踩坑记录（下一个模型必读，省时间）

1. **ExoPlayer 2.19.1 旧坐标是模块化的**：`exoplayer-core` 只是壳，实际类分布在 common/container/datasource/decoder/extractor/database 等 AAR。真实 API 与旧文档有差异：监听器是 `Player.Listener`（不是 EventListener）；`onPlayerError(PlaybackException)`；`DataSpec.httpMethod` 是 **int**，常量 `DataSpec.HTTP_METHOD_GET/POST/HEAD`；`HttpDataSource` 新增抽象 `getResponseCode()` 和 `clearAllRequestProperties()`；`InvalidResponseCodeException(int,String,IOException,Map,DataSpec,byte[])`；`DefaultDataSourceFactory(Context, DataSource.Factory)` 用这个构造。已全部按 javap 实测签名实现（见 feature/player/OkHttpDataSource.java）。
2. **Glide 4.16 没有 `GlideBuilder.setDefaultDecodeFormat`**（已删除该调用）；`RequestBuilder.into()` 需要 ImageView 不是 View。
3. AAPT 会把点分 style 名当隐式父级：`Widget.Tv.NavButton` 必须 `parent=""`。
4. `plugins{}` 块前不能有任何语句（app/build.gradle 的 localProps 读取放在其后）。
5. Gradle wrapper 生成时 `--distribution-url` 会做连通性测试，gradle.org 直连 TLS 失败，已用腾讯镜像 URL 生成。
6. styles/dialog 用 `Theme.AppCompat.Dialog.Alert` + 自定义 windowBackground。
7. 字体缩放用 `attachBaseContext`+`createConfigurationContext`（API17+，minSdk19 安全），改设置后需 recreate。
8. Result.Cancelled 用 `Result.Cancelled.INSTANCE` 单例 + 泛型强转工具方法 `cancelledResult()`。
9. 当前 `MainActivity` 的 Tab 枚举含 HOME（默认内容页），六入口不含"首页"；数字键映射在 digitToTab()。
10. 豆瓣响应已实测：`{"category","total","items":[{id,title,pic:{large,normal},rating:{value},uri,card_subtitle,episodes_info,is_new}]}`；需要 UA/Referer/Origin（DoubanClient 已带）。
11. 外部服务现状（2026-08-29 探测）：豆瓣✅、鸭鸭✅、非凡✅、量子/如意/360❌、platform_live_server:8868❌离线、IPTV 源:8787❌离线——都是外部依赖，代码已按"单源失败不整页错误"处理，不要为通过测试硬编码替代源。
12. 自定义接口"轻量测试"用 `fetchByCategory(随机临时 ID)` 而非真实 ID：失败会写入 2min 冷却，若用真实新增 ID 会阻塞用户立即重试；URL 校验先行（SettingsRepository.isValidBaseUrl）。
13. lint `ExpiredTargetSdkVersion` 属 Google Play 商店政策检查，与侧载 targetSdk=28 基线（文档 02 §2）冲突，已在 app/build.gradle lint 块 disable 并注释理由；勿用同样方式掩盖 API21+ 依赖问题（协议禁止）。
14. 设置页 recreate() 重建 MainActivity 后 Fragment 全部重建：各页滚动位置丢失、焦点按页签重新落到首个可聚焦控件，属已知可接受行为；页签恢复依赖 onSaveInstanceState 的 STATE_TAB。

## 7. 验收口径提醒（来自 docs，不可省）

- 每个 Gate 完成：真实执行命令并贴输出；区分"已实现/已构建/已在 API19 验证"。
- 媒体、遥控器、TLS 结论最终以 Android 4.4 真机为准；模拟器只能做安装/基础回归。
- 中文 md/源文件 UTF-8 with BOM（现有 docs 是 BOM 的；Java 源暂未加 BOM，如需补齐：`sed -i '1s/^/\xef\xbb\xbf/' <file>`，注意 gradle/*.gradle/properties 不要加）。
- 禁止把 Key/Cookie/私有地址写进日志与 Git；AI_DEV_LOG.md 每轮更新。
