# AI 开发日志（Android 4.4 独立工程）

> 当前基线与本轮结果以 docs/13 和 VERIFICATION_REPORT.md 为准。以下历史记录中的豆瓣首页、六入口及设备结论只对应当时版本。

## 2026-08-29 Gate 0~6 部分：工程骨架 + 数据层 + 首页/详情/播放器主链路

### 本轮目标
- 建立独立工程 `Android4.4/project/`（包名 `com.tvbox.android44`，minSdk=19，targetSdk=28，compileSdk=35，AGP 8.7.3 + Gradle 8.10.2 + JDK 17，纯 Java + XML Views，无 Compose/Media3/OkHttp4）。
- 完成通用领域层、数据层、播放器基础设施与首页/详情/播放器主链路的真实实现。
- 部分页面（搜索/推荐/历史/设置/电视直播/平台直播）目前为可编译的占位壳。

### 实际修改/创建
- Gradle：settings.gradle（阿里云镜像优先）、gradle/libs.versions.toml（全部锁版本，无动态依赖）、app/build.gradle（minSdk=19 硬约束、注入式配置、签名只从 local.properties 读取）、proguard-rules.pro。
- 清单：双启动入口（LAUNCHER + LEANBACK_LAUNCHER）、leanback/触摸屏/麦克风均 required=false、FileProvider、全 Activity 横屏 + configChanges。
- common：AppExecutors / Result+ErrorKind / AppConstants（全部阈值单一来源）/ StateLayout / FocusScaler / FocusUtils / FontScale / BaseActivity / QrCode / TvDialogs。
- imageloader：Glide 自定义 OkHttp3.12 网络栈（TvGlideModule + OkHttpUrlLoader + OkHttpStreamFetcher）。
- domain/model：Movie/PlaySource/PlayEpisode/Category/ApiLine/PagedMovies/DoubanHotItem/WatchHistoryItem/LiveChannel(Group/Line)/PlatformLive(嵌套)/AppUpdate/AiRecommendItem/AiProvider/LineHealth。
- domain/parser：NameNormalizer（去重键=规范化名|年份，保留季数）、HtmlCleaner、PlayStringParser（$$$/#/$、Markdown URL、仅 http/https）、ContentFilter（误伤防护）、SearchResultMerger（主来源优先+稳定顺序+增量追加）、IptvTextParser（BOM/CRLF/#genre#/$截断/同组同名合并/连续编号）、AiRecommendParser（代码块容错、≤10条）、OtaManifestParser（BOM/关键字段缺失报错）。
- domain/playback：BufferJudger（点播/直播双阈值、seek 3s 冷却、暂停不判、注入时钟）、AutoSwitchPolicy（近期成功优先、冷却避开、会话尝试集去重）。
- data/local：SettingsRepository（含内置 8 源与 4 个 AI 提供方基线、URL 规范化、Key 掩码）、BuiltInSources、HistoryStore（≤100 条、损坏回退）、HealthStore（30 天/300 条、冷却 30 分钟）、DoubanCache（成功 20min/失败 6min/旧缓存回退）、JsonIo（UTF-8 BOM 写入、U+FFFD 检测、.corrupt 备份）。
- data/remote：HttpClients（单一 OkHttp 3.12.13 栈）、CancelScope（真实取消不计失败）、HttpExecutor、MacCmsClient(+DTO+Mapper)、DoubanClient(+DTO+Mapper)、PlatformLiveClient（数组/对象双形状容错、请求头白名单）、AiClient（Bearer 不落日志）、IptvClient、OtaClient（流式 SHA-256）。
- data/repository：MovieRepository（LRU 5min/80 与 30min/60、2min 冷却、在途合并、主线程回调）、MultiSourceSearch（并发≤3、单源 3s、增量合并、完成进度、来源排序）、DetailSupplement（搜索→最匹配→详情、4s deadline、lineId 去重追加）、DoubanRepository（缓存/旧值/失败分类三层回退）、LiveRepository、PlatformLiveRepository（目录短缓存、resolve 合并）、RecommendRepository、UpdateRepository（版本比较/校验/FileProvider/API26+ 分支）。
- feature/main：MainActivity（六入口固定顺序、数字键 1~6、HOME 默认内容、双击返回退出、show/hide 保状态）。
- feature/home：HomeFragment（热播+豆瓣三分类+父/子分类、分页、断网回退当前接口、豆瓣卡片→当前源搜索→最佳匹配→详情）。
- feature/detail：DetailActivity + EpisodeAdapter（主详情先行、渐进补线不重置选择/焦点、默认 m3u8 线路、历史按线路名+集标题恢复、继续播放）。
- feature/player：PlayerActivity + PlayerControllerView + PlayerGestureHelper + OkHttpDataSource（ExoPlayer 2.19.1 自建 OkHttp 数据源，API19 TLS 兼容；FIT、±10s seek、上/下一集、倍速序列、自动下一集、历史 10s 节流、音频焦点、becoming noisy、常亮、自动/手动换线、seek 冷却、错误覆盖层、触摸手势全套）。

### 已验证
- `JAVA_HOME=/home/zxx/jdk ./gradlew assembleDebug` → BUILD SUCCESSFUL，产出 app-debug.apk（约 7.5MB）。
- `./gradlew compileDebugJavaWithJavac` 通过（89 个 Java 文件、约 9100 行）。
- 依赖树：`com.google.android.exoplayer:exoplayer-*:2.19.1`（旧坐标）、`okhttp 3.12.13`、`glide 4.16.0`、`gson 2.10.1`、`zxing core 3.5.3`、`appcompat 1.6.1`、`recyclerview 1.3.0`、`multidex 2.0.1`；无 Media3/Compose/OkHttp4。
- 外部接口探测（经本机代理）：豆瓣 recent_hot 可用（已按真实返回结构建模）；鸭鸭/非凡 MacCMS 可用；量子/如意/360 当前不可达；platform_live_server(20.205.10.127:8868) 与 IPTV 文本源(8787) 当前离线（按外部依赖降级处理）。

### 仅静态验证
- minSdk=19 合并清单、API19 兼容分支（FontScale 低于 API17 回退、FileProvider 全版本、canRequestPackageInstalls API26+）。
- 播放器资源释放路径、网络离主线程（StrictMode Debug 已开启，需运行验证）。

### 未验证
- API 19 模拟器/实机：安装、冷启动、播放出画、遥控焦点（无 API19 镜像/真机；现有 AVD 为 API30/33）。
- lintDebug、单元测试（测试目录为空，属下一轮 Gate）。
- Release/R8、OTA 全流程、扫码配置、直播、平台直播、AI。

### 已知风险
- ExoPlayer 2.19.1 旧坐标为模块化产物（core 依赖 datasource/container 等 7 个子 AAR），API 与单包时代有差异（Player.Listener、DataSpec.httpMethod 为 int、HttpDataSource.getResponseCode() 抽象等），已按真实 class 签名对齐。
- DoubanClient 请求头基线为主工程快照，API19 真机 TLS 行为待验证。
- targetSdk=28 + usesCleartextTraffic=true（内置含 http 源），文档要求按源地址策略收紧，后续可用 networkSecurityConfig 精细化。

### 回滚方式
单轮提交可整体回退：删除 `Android4.4/project/` 即恢复原状；主工程与 docs 未做任何修改。

## 2026-08-30 Gate 7~9 补记与收尾：发现未记录的实现 + 推荐页/设置页真实实现 + lint 通过

### 本轮目标
- 接手时核实工作树实际进度：SearchFragment/HistoryFragment/LiveTvActivity/PlatformLiveActivity/ConfigHttpServer 已由上一轮实现但未记录（HANDOFF 第 4 节"占位壳"与实际不符）；本轮逐一核对了实现内容与数据层 API 的匹配性。
- 完成 Gate 9 最后两个占位页：RecommendFragment、SettingsFragment 真实实现。
- lintDebug 首次跑通：修复全部 2 个错误后通过。

### 实际修改/创建（本轮）
- `feature/recommend/RecommendFragment.java`：输入框 + 8 个快捷词（每次展示 4 个、换一批轮转）+ 换一批；点击条目经 MainActivity.switchToSearchWithQuery 进入普通多来源搜索（AI 不直接给播放 URL）；busy 防重复提交、重提先 cancel 旧 Handle；AI 失败保留上一批结果、状态行非阻断提示；PERMISSION（Key 未配置）清空并引导扫码配置；语音降级：RecognizerIntent 服务不存在则隐藏入口，API 23+ 动态申请 RECORD_AUDIO，识别失败回退文字输入不崩溃。
- `feature/settings/SettingsFragment.java`：主题/字体（singleChoice，改后 recreate MainActivity，页签经 STATE_TAB 恢复）；视频源单选（allApis，切换时 movies().invalidateAll + recreate）；自定义接口新增（名称/URL 校验 → fetchByCategory 轻量测试 → 失败需二次确认才保存，测试用随机临时 ID 避免失败冷却阻塞重试）、管理删除（删当前源回退内置并清缓存）；AI 提供方单选（未配模型时自动填默认值）、模型名编辑、Key 只显掩码（明文仅经扫码会话写入）；播放管家开关 + 线路统计（statsSnapshot，30 天/300 条 + 冷却标记）+ 清空确认；OTA：手动检查 → 更新内容确认 → 下载（不确定/百分比进度、Formatter 可读大小）→ 校验通过后"安装更新"，API 26+ 先走 canRequestInstalls 权限分支，安装走 FileProvider intent；IPTV/平台服务地址编辑（isValidBaseUrl 校验）+ 测试连接（live().load / platformLive().sites 统计分组与平台数）；扫码配置弹窗（dialog_qr_config.xml）：ConfigHttpServer(AI:9978/API:9979 + 端口回退) + QrCode.encode(480px) + 枚举非 loopback IPv4（多候选全部展示）+ no-store 由服务端保证 + 弹窗 dismiss/onStop 必须 stop() 释放端口 + 关闭后焦点回入口按钮；一次性 token 校验逻辑沿用 ConfigHttpServer.validate（AI 表单 provider 白名单 + model/key 非空；API 表单 name 非空 + URL 校验），提交成功保存后主动 dismiss 关会话。
- `common/ui/VerticalSpacingDecoration.java`（新增）：竖向列表条目间距（首条不外扩）。
- `res/layout/dialog_input.xml`（新增）：通用单/双输入弹窗。
- `res/layout/dialog_qr_config.xml`（新增）：扫码配置弹窗（URL + 二维码 + 提示 + 关闭会话按钮）。
- `res/layout/item_recommend.xml`（新增）：推荐条目（标题/理由/搜索词提示）。
- `res/layout/fragment_recommend.xml`、`fragment_settings.xml`：占位壳替换为真实布局。
- `res/values/themes.xml`：新增 SettingsSectionHeader 样式；`strings.xml`：新增 qr_config_desc。
- lint 修复：`AspectImageView` 改继承 `AppCompatImageView`（AppCompatCustomView 错误）；`app/build.gradle` lint 块 disable `ExpiredTargetSdkVersion`（侧载发行 targetSdk=28 为文档 02 §2 有意基线，不上架 Google Play，检查不适用且非掩盖依赖不兼容，已注释说明）。
- 上一轮已实现但未记录、本轮核实过的文件（本轮未改动其逻辑，仅核对）：feature/search/SearchFragment.java、feature/history/HistoryFragment.java、feature/live/LiveTvActivity.java、feature/platformlive/PlatformLiveActivity.java + PlatformAdapters.java、feature/settings/ConfigHttpServer.java 及对应布局。

### 已验证（真实执行）
- `JAVA_HOME=/home/zxx/jdk ./gradlew compileDebugJavaWithJavac` → BUILD SUCCESSFUL。
- `./gradlew assembleDebug` → BUILD SUCCESSFUL，app-debug.apk 约 11.7MB。
- `./gradlew lintDebug` → BUILD SUCCESSFUL（0 错误；159 条警告，多为 deprecation/未使用资源，不阻断）。
- 上述结论对"上一轮未记录的实现"同样给出静态证据：全工程可编译、lint 无阻断。

### 仅静态验证
- 扫码配置全流程（ServerSocket/表单/保存回调/端口释放）：代码路径核对，未真机双端联调。
- OTA 下载→校验→安装、API 26+ 安装权限分支、AI 真实请求与解析：依赖外部服务与真实 Key，未运行。
- IPTV/平台直播测试连接：外部服务当前离线（见 2026-08-29 探测记录），无法端到端验证。
- 语音识别：本机模拟器未验证。

### 未验证
- API 19 实机/模拟器（无 API19 镜像）：全部 UI 的遥控器焦点遍历、recreate 后页签/焦点恢复、扫码弹窗焦点回归。
- 上一轮未记录实现的运行时行为（搜索增量渲染、直播切台、平台直播四级导航等）：仅有编译级证据。

### 已知风险
- apk 从约 7.5MB 增至 11.7MB：主要为本轮与上一轮未记录实现的代码/资源增量，multidex 已开启，无功能影响；如需缩小可在 Gate 10 用 R8 验证 Release。
- recreate() 应用主题/字体/换源：MainActivity 内 Fragment 全部重建，show/hide 保状态逻辑在重建后按 STATE_TAB 恢复，但各页滚动位置会丢失（可接受的已知行为）。
- lint disable ExpiredTargetSdkVersion 已注明理由；后续如需上架商店需另建现代发行变体（文档 02 §2）。

### 回滚方式
本轮改动集中在上述文件；回退到上一轮状态：git/文件级还原 `feature/recommend/`、`feature/settings/SettingsFragment.java`、`common/ui/VerticalSpacingDecoration.java`、4 个新布局、themes.xml/strings.xml、AspectImageView.java、app/build.gradle 的 lint 块。主工程与 docs 未做任何修改。

## 2026-08-30（下）收尾冲刺：单测+实机全链路验证+6 缺陷修复+影院主题+Release R8

### 本轮目标
- 完成 HANDOFF 全部剩余任务；通读 docs/00~12 逐项核对；能完成的完成，不能完成的写入 VERIFICATION_REPORT.md。

### 实际修改/创建
- **单元测试（新增 12 个测试类 69 用例）**：PlayStringParser/HtmlCleaner/NameNormalizer/ContentFilter/SearchResultMerger/IptvTextParser/AiRecommendParser/OtaManifestParser/BufferJudger（假时钟无 sleep）/AutoSwitchPolicy/MacCmsMapper（鸭鸭数字 id+class 数组真实样本形态）/WatchHistoryItem。
- **测试揭出并修复 2 个实现 bug**：① IptvTextParser `$` 语义与文档 08 §5/§6 相反（文档：URL 在 `$` 前、元数据在后；原实现会整条丢弃文档格式频道）→ 改为文档主格式+兼容 `meta$url` 变体+URL 内遇 `$` 截断；② HtmlCleaner 数字实体解码结果未返回（`return s`→`return sb.toString()`）。
- **实机（小米电视 2304FPN6DG / Android 9 / API 28）验证揭出并修复 3 个 bug**：③ Glide 注解处理器缺失（`annotationProcessor libs.glide`→`libs.glide.compiler`），GeneratedAppGlideModule 生成前图片走 HttpURLConnection 且豆瓣 CDN 404；HttpClients 增加豆瓣域名 Referer/浏览器 UA 拦截器；④ OkHttpStreamFetcher 在 Glide 读取前 `response.close()` → 全部图片 IOException: closed → 改为持有响应、cleanup() 释放；⑤ recreate() 后 Fragment 叠加（MainActivity.selectTab 改为按 tag 复用恢复实例 + 隐藏全部现存 fragment）；⑥ 详情默认线路可能为空集线路（onLineAppended 时若当前线路无集数自动切 defaultLineId 非空线路）。
- **影院主题首页**：layout_home_cinema.xml（左侧 rail 六入口 + Hero 背景海报/标题/播放/详情 + 分类行/网格，id 与默认布局兼容）+ Widget.Tv.CinemaRailItem 样式 + bg_hero_scrim + HomeFragment 主题分支/Hero 更新/播放直达（Movie 有线路时 PlayerActivity 直达，否则走解析）。
- **README.md**（构建/注入/验证状态表）、**update.json.example**。
- **lint**：DefaultLocale 13 处修复（内部串 Locale.ROOT/展示 CHINA/US）、UnusedResources 2 处删除、禁用不适用 RTL 检查（单语横屏电视应用，注明理由）；175→116 条，0 错误。
- **Gate 10 部分**：assembleRelease（R8+shrinkResources，11.7MB debug→2.9MB release）并实机验证 Release 包首页/搜索/详情集数/播放/历史恢复全部正常（Gson 反射与 ExoPlayer keep 规则未被误删）；最终 APK（含 API19 修复后重构建）SHA-256=cc2e3748ddc72e5e…b4d637e、2,904,864 字节、debug 证书回退（正式发布需注入签名）。
- **docs 00~12 全文核对**，结论写入 VERIFICATION_REPORT.md。

### 已验证（真实执行）
- `testDebugUnitTest` 69 用例全绿；`lintDebug` 0 错误；`assembleDebug`/`assembleRelease` BUILD SUCCESSFUL。
- API 28 实机：冷启动、豆瓣+海报、搜索（8/8 来源 38 结果）、详情（6 线路补线+HTML 清理+集数）、HLS 播放出画（含快退 10s 提示、DPAD 控制层）、历史写入与"继续播放 第1集"、AI 未配置/错误 Key 401 降级、扫码配置端到端（adb forward 模拟手机：表单→保存→掩码刷新→弹窗自动关→端口 connection refused 确认）、平台直播/IPTV 离线错误态、影院主题切换+Hero 交互、25 次快速按键、Release R8 全链路复测。

### 未验证（详见 VERIFICATION_REPORT.md）
- API 19 实机与模拟器（无设备；镜像下载因代理失效+网络不可达失败）。
- AI 真实成功推荐（无有效 Key）、平台直播/IPTV 真实服务（外部离线）、OTA 真实服务端、双端真手机扫码、音频出声（需人在场）、30/60 分钟长播、正式发布签名。

### 已知风险
- Release 当前为 debug 证书回退签名；versionCode=1 待发布时正式化。
- API19 风险集中在 TLS 旧栈握手、硬解可用性、Multidex 冷启动、遥控器映射（VERIFICATION_REPORT §4）。

### 回滚方式
本轮文件级还原：app/src/test/（新增测试）、gradle/libs.versions.toml（glide-compiler）、app/build.gradle（annotationProcessor+lint disable）、HttpClients/OkHttpStreamFetcher/MainActivity/DetailActivity/IptvTextParser/HtmlCleaner/PlayerControllerView 等 6 bug 修复文件、layout_home_cinema.xml/themes.xml/drawable/bg_hero_scrim/HomeFragment、README.md、update.json.example、VERIFICATION_REPORT.md。主工程与 docs 未修改。

## 2026-08-30（晚）API 19 模拟器验证：镜像安装成功 + 再修 3 个真实缺陷（含 1 个全平台缺陷）

### 本轮目标
- 用户要求重试 API 19 镜像下载；成功后完成 API19 模拟器回归并如实记录结论。

### 实际修改/创建
- **镜像**：代理恢复后 `sdkmanager --proxy=http --proxy_host=127.0.0.1 --proxy_port=7897 --no_https` 成功安装 `system-images;android-19;default;x86` + `platforms;android-19`；创建 AVD `rev19`（tv_720p 1280×720，KVM，15s 完成冷启动）。
- **API19 实测揭出并修复 3 个缺陷**：
  1. `TvBoxApp`：`MultiDex.install()` 从 onCreate 移到 `attachBaseContext()`（API<21 上 FileProvider 先于 onCreate 实例化 → 冷启动必崩 ClassNotFoundException）。
  2. 新增 `data/remote/Tls12SocketFactory.java`：API<22 显式启用 TLSv1.2（Android 4.x 默认关闭、OkHttp 3.12 仅 API20+ 自动启用）；`HttpClients.init` 在 API<22 时安装。实测握手失败 96 → 0，量子源（HTTPS MacCMS）API19 上列表/搜索/详情全部可用。
  3. **全平台缺陷**：`MacCmsMapper.toPagedMovies` 硬编码 `includePlays=false`，详情路径主线路永远丢失（此前所有线路均来自补线，且是"默认线路空集"问题的根源）。新增 `toPagedMovies(apiLine, dto, includePlays)` 重载；`doFetchDetail` 传 true；`MacCmsMapperTest` 补回归用例 `toPagedMovies_playSourcesOnlyWithIncludePlays`。真机复测详情线路 6 → 7（主线路首次出现），播放回归正常。
  - `PlayStringParser` 的 `split` 从 `String.split(regex)` 改为 indexOf 字面量分割（排查过程中的加固，语义不变）。
  - `MacCmsClient.query` 增加 DEBUG-only 诊断日志（路径形态/长度/耗时，不含查询参数与完整响应，符合文档 03 §6 日志红线）。
- **文档**：VERIFICATION_REPORT.md 更新 §0（API19 验证结果）、§2（缺陷 7~9）、§3.1、§4 结论。

### 已验证（真实执行）
- API19 模拟器：安装、冷启动（修复后无崩溃）、默认主题首页、豆瓣失败→回退量子（提示正确）、多来源搜索执行+空态、详情（2 条线路/推荐线路选中/集数渲染）、播放失败错误态（重试/返回按钮）；`SSL handshake aborted` 96→0。
- API28 真机回归：重装后搜索→详情（7 条线路含主线路）→播放出画正常。
- `testDebugUnitTest`（70 用例，0 失败）、`lintDebug`（0 错误/116 警告）、`assembleDebug`、`assembleRelease` 全部 BUILD SUCCESSFUL。

### 未验证 / 系统限制（如实记录）
- API19 **真实视频解码播放**：量子播放 CDN 的 TLS1.2 仅提供 AES-GCM 套件，Android 4.4 系统栈无 GCM 套件（API20+ 引入）→ 该类 CDN 握手必然失败，属系统硬限制。对策=文档 02 §7 允许的服务端受控兼容代理或 http 源；客户端未做任何降级。
- API19 模拟器 NAT 无法访问宿主本地端口（adb reverse 需 API21+），本地 mock 播放闭环不可行。
- 豆瓣在 API19 仍不可达（CDN TLS 要求超出 4.4 能力），回退链路完整可用。
- API19 物理盒子（遥控器/硬解/长播）仍无设备，最终口径保持"已模拟器级验证"。

### 已知风险
- 启用 TLS1.2 后 API19 对"仅 GCM 套件"的现代站点依旧握手失败（无法绕过，未降级）；对支持 CBC 套件或 TLS1.0 兼容的源已可用。
- DEBUG 诊断日志保留于 MacCmsClient（BuildConfig.DEBUG 守卫，Release 不输出）。

### 回滚方式
还原 `TvBoxApp.java`、`HttpClients.java`、删除 `Tls12SocketFactory.java`、`MacCmsMapper.java` 重载与 `MovieRepository.doFetchDetail` 调用、`PlayStringParser.split`、`MacCmsClient` 诊断日志、`MacCmsMapperTest` 回归用例、VERIFICATION_REPORT.md §0/§2/§3.1/§4。主工程与 docs 未修改。

## 2026-08-30（夜）首页改版：去除豆瓣热播渲染，直接使用视频源内容（用户明确要求）

### 需求与依据
- 用户本轮明确要求："首页内容不要热播的渲染，就使用线路的内容"。按文档 00 §1 优先级第 1 条（用户本轮要求 > 模块规则），覆盖 docs/01 §3.1"默认首页展示豆瓣热播"基线；DoubanRepository 数据层保留未删（能力仍在，仅首页 UI 不再使用）。

### 实际修改
- `feature/home/HomeFragment.java` 重写：
  - 移除"热播"tab、豆瓣三分类（剧集/综艺/电影）、loadDouban、豆瓣失败回退逻辑与状态行文案、豆瓣卡片→搜索→bestMatch 解析路径（resolveDoubanToDetail/bestMatch/NameNormalizer 依赖一并移除）。
  - 分类行改为「全部」+ 当前源父分类（class 数据来自 ac=detail 响应）；选中父分类后出现「全部」+ 子分类行；「全部」= fetchByCategory(api, null, 1)。
  - 无分类数据的源（如量子 class=0）只显示「全部」chip，内容为来源首页列表。
  - 保留：分页追加不抢焦点、requestId 防晚到、失败错误态+重试、影院主题（左侧 rail + Hero 播放/详情，Hero 播放在有线路时直达播放器）。
- 编译期修正 loadCategory 签名（去掉 fromFallback 参数）。

### 已验证
- `assembleDebug` + `testDebugUnitTest` BUILD SUCCESSFUL（70 用例 0 失败）。
- API19 模拟器实机：新首页直接渲染线路内容（金色2026/仙逆/母狮第三季等，均带"更新至第X集"备注），无热播/豆瓣元素，无崩溃；无分类源仅显示「全部」。

### 未验证
- API28 真机：电视 ADB（192.168.0.5:5555）在验证时段失联（ping 通、5555 超时，疑休眠/ADB 自动关闭），待设备可达后补验证。
- 有完整 class 数据的源的分类 tab 交互（量子 class 为空；待真机验证其他源）。

### 已知影响
- docs/01 §3.1 与 VERIFICATION_REPORT §1 中"豆瓣首页"相关描述自本轮起不再适用（用户要求覆盖）；后续若恢复豆瓣可在 HomeFragment 以 DoubanRepository 重新接入。

### 回滚方式
还原 `feature/home/HomeFragment.java` 到上一版本即可（豆瓣逻辑自包含于该文件）。

## 2026-08-30（夜 II）打包交付：R8 下 sslSocketFactory 单参重载崩溃修复（缺陷 #10）+ dist 产物

### 需求与依据
- 用户要求打包 APK。Release 包在 API19 模拟器冒烟时崩溃：`Unable to extract the trust manager ... sslSocketFactory is class N0.j` —— OkHttp 3.12 的单参数 `sslSocketFactory(SSLSocketFactory)` 依赖反射提取默认 TrustManager，R8 混淆后失败（OkHttp 自身类被重命名）。Debug 不受影响（未混淆），属于仅 Release 暴露的缺陷（第 10 个）。

### 实际修改
- `HttpClients`：TLS1.2 安装路径改用三参数 `sslSocketFactory(factory, trustManager)` 显式传入系统默认 TrustManager（新增 `systemDefaultTrustManager()`；TrustManagerFactory 默认算法，校验行为不变、未引入自定义信任策略）。获取失败时回退系统默认 factory（不崩、仅回退 TLS1.2 能力）。
- dist/ 产出（交付目录）：
  - `TVBox44-v1.0.0-debug.apk`（11,972,348 字节，SHA-256 32287184c547c69f…f9cd0c7）
  - `TVBox44-v1.0.0-release.apk`（2,901,801 字节，SHA-256 5e548cea05210498…c0b4b4d7，R8+shrinkResources，debug 证书回退签名）

### 已验证
- API19 模拟器：修复后 Release 包安装、冷启动、新首页（纯线路内容）渲染，0 崩溃。
- `assembleDebug`/`assembleRelease`/`testDebugUnitTest` BUILD SUCCESSFUL。

### 未验证
- API28 真机（电视 ADB 仍失联）：两个新 APK 的真机复验待设备可达。

### 回滚方式
还原 `HttpClients.java` 的 sslSocketFactory 调用段与 `systemDefaultTrustManager()`；删除 dist/。

## 2026-09-05 首页分类自动化：ac=list 拉取分类表 + 点击分类列出该分类影片

### 需求与依据
- 用户要求：先查看 `https://cj.lziapi.com/api.php/provide/vod/` 的接口字段，首页自动获取字段做分类，点击分类按钮列出该分类影片（如"电影片"下全是电影）。

### 接口字段实测（PC 经代理，2026-09-05）
- `ac=list`：`class` 数组 44 个分类（电影片/连续剧/综艺片/动漫片 4 个父分类，type_pid 表达父子），影片列表无 vod_pic。
- `ac=detail&pg=1`：**不带 class**（量子源实测 class=0）；影片带海报与播放串。
- `ac=detail&pg=1&t={id}`：返回该分类影片（有海报/播放串），如 t=1 电影片 total=1。
- 结论：分类表必须走 ac=list，列表用 ac=detail&t=（现有 fetchByCategory 已支持）。

### 实际修改
- `AppConstants`：新增 `CATEGORY_CACHE_TTL_MS=30min`、`LIST_CALL_TIMEOUT_MS`（=连接10s+读取15s+5s 余量；原 `SEARCH_TIMEOUT_MS*3=9s` 的 callTimeout 比连接+读取还短，慢网下抛裸 `InterruptedIOException` 被归为 OTHER——实机日志定位后修复，列表/分类请求超时改用新常量）。
- `MacCmsClient.listCategories()`：GET `?ac=list&pg=1`。
- `MacCmsMapper.toCategories(dto)`：class→Category（非法 id/空名丢弃）；`toPagedMovies` 增 `includePlays` 重载（沿用）。
- `MovieRepository.fetchCategories(api, cb)`：30 分钟内存缓存（categoryCache，invalidateAll 一并清）、可取消、in-flight 合并；**分类失败不进来源冷却**（不阻塞列表）。
- `HomeFragment`：reload 时并行发起分类请求与内容请求；分类到达后 buildTabs（选中状态保留）；内容响应不再承担分类来源；分类失败仅状态行提示（"分类加载失败，可在搜索中直接找片"），不阻断内容。
- `ContentFilter.TYPE_KEYWORDS` 增加"演员"；`buildTabs` 用 ContentFilter 过滤分类名（"电影解说/新闻资讯/演员"等分类不再出现）。
- 临时诊断：`MovieRepository` 两个 catch 块 DEBUG-only 打印 kind+异常类名（TVBOX_ERR）。

### 已验证
- `assembleDebug`/`lintDebug`/`testDebugUnitTest`（73 用例，含 toCategories 父子/坏行回归）BUILD SUCCESSFUL。
- API19 模拟器实机：分类 tab 出现（全部/电影片/连续剧/综艺片/动漫片/体育/短剧/AI漫剧，解说类已过滤）；点击"电影片"→子分类行出现（全部/动作片/喜剧片/…/预告片）+ 内容切换为该分类（"阿龙2025·HD"，与 PC 端 t=1 total=1 一致）；子分类点击发出 t=6 请求。
- 重新打包 dist（SHA-256 见 dist 内文件，release=94d250eb…f17baa）。

### 未验证 / 已知问题
- API28 真机（电视 ADB 失联待恢复）。
- 模拟器直连 Cloudflare 偶发 30s 超时（"全部/动作片"轮次），PC 端同请求 200 正常——模拟器 NAT 网络波动，真机正常网络不复现；超时错误态+重试 UX 工作正常。
- "体育/短剧/AI漫剧"等分类是否保留属产品决策，当前仅按内容过滤规则剔除。

### 回滚方式
还原 AppConstants/MacCmsClient/MacCmsMapper/MovieRepository/HomeFragment/ContentFilter 对应段；删除 dist/ 后重新按旧版本打包。

## 2026-09-21 18:08 电视直播与平台直播修复

### 实际修改
- `data/remote/PlatformLiveClient.java`：读取 `parentCategories`，并对服务端返回的完整分类表按 `parentId` 过滤，修复平台分类为空。
- `domain/playback/IptvMediaTypeDetector.java`：增加 IPTV URL 类型判断，无扩展名直播端点默认按 HLS，明确的 MP4/FLV/TS 等普通媒体仍走通用播放器。
- `feature/live/LiveTvActivity.java`：使用新的 IPTV 类型判断，修复 `/live/cctv1` 类无扩展名 HLS 地址被误判为普通媒体。
- `app/src/test/`：新增平台分类解析和 IPTV 媒体类型回归测试。

### 缺陷记录
- 时间：2026-09-21 18:08
- 症状：IPTV 首条地址为无 `.m3u8` 后缀但内容为 HLS 播放列表；平台接口返回 `parentCategories` 与完整 `categories`，客户端只读取后者并按空父级过滤。
- 尝试修复：分别在 IPTV 播放器选择阶段和平台分类解析阶段补充兼容逻辑，并增加 JVM 回归测试。
- 临时方案：无；等待模拟器安装和真实接口回归验证。

### 验证
- `testDebugUnitTest assembleDebug`：BUILD SUCCESSFUL。
- 待完成：`emulator-5554` 安装、电视直播和平台直播页面实测。

## 2026-09-21 18:24 电视直播与平台直播入口暂时隐藏

### 实际修改
- `app/src/main/res/layout/activity_main.xml`：将“电视”和“直播”两个顶部入口设置为 `gone`，界面不再显示。
- `feature/main/MainActivity.java`：不再为两个隐藏入口绑定点击事件；数字键 4/5 不再映射到直播页；若恢复到旧的直播状态，自动回到首页，避免通过状态恢复绕过隐藏。
- `data/remote/HttpClients.java`、`res/xml/network_security_config.xml`、`AndroidManifest.xml`：保留此前为 HTTP 服务兼容性增加的明文连接配置；本次不再继续修复直播播放逻辑。

### 验证
- `testDebugUnitTest assembleDebug`：BUILD SUCCESSFUL。
- 已安装最新 Debug APK 到 `emulator-5554`，安装成功。
- UIAutomator 检查：`电视`、`直播`均不在可见导航树中；发送数字键 4/5 后仍停留在 `MainActivity`。

### 当前边界
- 电视直播和平台直播功能本体暂不继续处理；当前只保证入口隐藏、焦点导航不可达、数字键不可达。

## 2026-09-21 18:39 首页父分类“全部”结果过少修复

### 实测与根因
- 默认量子接口全量请求 `ac=detail&pg=1` 返回 20 条，`total=155650`，说明线路本身不是只提供一部影片。
- 同一接口请求 `t=1`（“电影片”父分类）只返回 1 条；动作片、喜剧片、爱情片等子分类各自有独立数据。
- `HomeFragment` 原先把父分类“全部”直接作为 `t=1` 查询，因而只显示父级直挂影片，没有合并子分类。

### 实际修改
- `feature/home/HomeFragment.java`：父分类选择“全部”时收集全部子分类 ID；选择具体子分类时仍只请求该子分类。
- `data/repository/MovieRepository.java`：新增多分类查询，子分类请求并发执行，在 30 秒整体限时内合并结果；按 `apiLineId + movieId` 去重，分页取最大值，总量汇总；保留原有单分类和顶层“全部”路径。
- `app/src/test/java/com/tvbox/android44/data/repository/MovieRepositoryTest.java`：增加聚合计数与重复影片去重回归测试。

### 验证
- `testDebugUnitTest assembleDebug`：BUILD SUCCESSFUL。
- APK 已安装到 `emulator-5554`。
- 实机点击“电影片”后，UIAutomator 可见海报数为 6（当前屏幕可见列），无“当前分类暂无内容/内容加载失败/来源冷却中”提示；此前单部结果问题不再出现。

### 当前边界
- 仅修复父分类“全部”的查询聚合；顶层“全部”仍表示全来源内容，具体子分类仍按单分类查询。

## 2026-09-21 19:10 设置扩展服务隐藏与默认 TVBox UI 优化

### 实际修改
- `app/src/main/res/layout/fragment_settings.xml`：将“扩展服务”整块容器设为 `gone`，其 IPTV/平台直播控件不显示且不进入焦点导航。
- `app/src/main/res/layout/activity_main.xml`、`values/strings.xml`：增加 TVBox 品牌标题、副标题和历史/搜索/推荐/设置快捷键提示，头部布局参考默认 TVBox。
- `values/colors.xml`、`values/themes.xml`、`drawable/bg_nav_button.xml`、`bg_chip.xml`、`bg_card.xml`：统一深色背景、绿色强调、圆角卡片、选中态和白色焦点环。
- `feature/main/MainActivity.java`：导航选中态同步背景状态，统一使用主题强调色。
- `res/layout/layout_home_cinema.xml`：影院主题侧栏同步隐藏电视/直播入口，并收窄侧栏宽度。

### 验证
- `testDebugUnitTest assembleDebug`：BUILD SUCCESSFUL。
- APK 已安装到 `emulator-5554`。
- 首页截图确认 TVBox 品牌头部、快捷导航、绿色分类选中态和圆角海报卡片正常。
- 设置页 UIAutomator 确认“扩展服务”、`IPTV 地址`、`平台直播服务`均不可见；设置页仍可正常进入。

### 编码兼容补充
- 含中文的 XML/Markdown 文件已验证为 UTF-8 with BOM，且无 U+FFFD。
- Java 源文件保持无 BOM，因为 Android 工具链的 `javac` 会将 Java 文件首 BOM 判为非法字符；源码内容未改变。
- 编码修正后重新执行 `testDebugUnitTest assembleDebug` 并冷启动 `emulator-5554`，均通过。

## 2026-09-21 19:30 创建 Android4+ 独立发布仓库并准备 v0.0.1

### 实际修改
- `README.md`：增加醒目的 AI 参考来源声明、独立兼容版定位、v0.0.1 发布信息和 Android 4.4+ 支持范围。
- `app/build.gradle`：将应用版本名设置为 `0.0.1`，对应首个公开标签 `v0.0.1`。
- `.gitignore`：忽略本地构建缓存、签名文件、机器配置和 APK 输出，避免敏感信息及生成物进入仓库。

### 发布记录
- 目标仓库：`https://github.com/ZxxXinI/tvbox-android4`
- 发布标签：`v0.0.1`
- 项目关系：本仓库为 `ZxxXinI/tvbox` 的 Android 4.4+ 独立兼容版。


## 2026-10-05 当前视频源首页基线与业务补全

### 范围与决定

用户确认不要求豆瓣首页，采用当前视频源内容，并授权补全代码与文档。保留独立包名、minSdk 19、Java/XML、ExoPlayer 2/OkHttp 3.12；直播入口保持隐藏。本轮未提交、推送或发布线上清单。

### 分阶段修改

1. OTA：增加 REQUEST_INSTALL_PACKAGES；强制 64 位 SHA-256；严格版本/大小/force 解析；唯一临时文件、成功后改名、失败与取消清理；流关闭后取消句柄注销；取消阻止已排队进度/完成回调；API 19/28 真实 HTTP 与安装 Intent 回归。
2. 请求与业务：分类父/子线程池分离；共享在途任务改为独立订阅取消；MacCMS 请求全局上限 3 且排队计入超时；主来源晚到替换同位置卡片；进度按完成顺序通知；补线在主线程追加并尊重取消、稳定线路去重和精确年份匹配。
3. 历史：快照复制、坏条目过滤、排序去重、100 条上限；JsonIo 同步临时文件后原子替换，失败保留原文件；写成功才替换缓存/通知；磁盘队列顺序执行播放器保存，历史 UI 异步读取和清空。
4. 页面：PageFocusState 记录稳定列表卡片与位置；海报/历史稳定 ID；恢复分类、搜索词、已加载页数、详情线路/选集与焦点；生命周期晚到保护、隐藏页面不抢焦点；分页成功后才递增、失败重试、追加去重。测试带出 API 19 卡片未显式可聚焦，修复 FocusScaler；主导航接入 BaseActivity 字体缩放，重建回归验证生效。
5. 工程与文档：官方 Gradle/Google/MavenCentral/PluginPortal，Gradle ZIP 校验和；Robolectric 测试依赖保留 API 19 支持，测试缓存放在 app/build/test-home；CI 构建/测试/Lint/产物；版本、签名支持环境注入与正式签名强检查；prepare_release.py 验证 APK 生成清单且默认拒绝调试证书；设备 smoke 脚本及验收矩阵；统一 docs/00~12、根 README/HANDOFF，新增 docs/13 与 CHANGELOG，保留原始上传 zip 归档。

### 实际验证

- 最终命令：bash gradlew testDebugUnitTest testReleaseUnitTest lintDebug lintRelease assembleDebug assembleRelease --no-daemon --max-workers=4 --console=plain。
- BUILD SUCCESSFUL in 2m 1s；98 actionable tasks: 30 executed, 68 up-to-date。
- Debug/Release 各 21 类、116 项，失败/错误/跳过均 0；基线 81 项，新增 35 项。
- Lint 两种类型均 0 Error、0 Fatal、126 Warning。警告类别与后续项见 VERIFICATION_REPORT.md。
- APK 包名 com.tvbox.android44、minSdk 19、targetSdk 28、v0.0.1/code1；v1/v2 签名验证通过，均为调试证书验证包；字节数和实际 SHA-256 已写入报告。
- 发布生成器验证两个真实 APK，清单与副本哈希一致；默认拒绝调试签名；TVBOX_REQUIRE_RELEASE_SIGNING=true 无密钥时拒绝配置。设备脚本 bash -n、Python 编译、CI YAML 解析、文档路径/U+FFFD 与 git diff --check 通过。
- 回归发现并修正 OkHttp 整体 deadline 的 InterruptedIOException(timeout) 分类；HTTP 状态错误映射为 HTTP。测试 HTTP 夹具使用合法数值影片 ID/type_id，没有放宽业务解析器来通过测试。

### 尚未验证

本轮 adb devices 为空、无 /dev/kvm。Robolectric API 19/28 不替代设备证据；真实 API 19/26+ 安装器、媒体解码、遥控器、同证书升级、真实 AI/手机扫码仍待填写 docs/13。没有正式发布密钥，没有创建 GitHub Release，也没有 GitHub Actions 在线运行结果。

## 2026-10-05 文档规划复查

按用户要求对照模块规则、源码及测试目录复查规划。新增 `docs/14-文档规划复查与待补任务.md`，将实现状态、自动化证据与设备验收分开记录；按优先级列出播放器返回请求编号不一致、缓冲判定事件接入不足、线路健康主线程 I/O、历史恢复分支、媒体类型回退、启动更新开关未接入、扫码超时/旧会话回调以及 AI 取消保护与请求测试缺口。

更新文档入口、当前基线、Gate 任务、接手说明和验证报告链接；统一 OTA 强制哈希、JSON 存储位置及暂缓直播验收的表述。这些源码缺口尚未修复；本轮交付为静态复查及规划更新，未重新构建 APK、运行 Gradle 测试或执行设备验收。`git diff --check` 通过；21 个 Markdown 的 UTF-8/BOM、替换字符、行末空白及 54 个本地链接检查通过。

## 2026-10-06 按规划补齐 P1/P2 与相关 UI 整理

### 范围与实现

用户授权开始完善 `docs/14` 的待补任务。保持当前 MacCMS 视频源首页、四入口、minSdk 19、Java/XML、ExoPlayer 2/OkHttp 3.12，直播 Gate 7/8 暂缓。本轮没有提交、推送、线上发布或真实设备结果。

1. 播放返回：统一 REQUEST_PLAYBACK，退出直接回传历史快照；详情更新线路/集/进度和继续播放，布局后恢复当前集焦点。磁盘队列阻塞时仍正确返回。
2. 缓冲与媒体：BUFFERING + 500ms 轮询，READY 结束窗口；暂停依据 playWhenReady，seek/开关/用尽线路保护；IDLE/ENDED 清理，停止释放；URL path、实际 MIME 与一次受控类型回退，无额外探测。
3. 历史与健康：共享 PlaybackSelection 匹配线路 ID/名称与集标题，近片尾下一集清零，首次详情失败确认旧 URL；自然末集、暂停保存和重建修正。HealthStore 顺序队列初始化/写入/清空，成功才发布快照，30 天/300 条、坏数据、写失败和计数溢出保护。
4. 更新/扫码/AI：启动开关与清单接入，15 分钟节流、同版本提示去重、错误静默、仅引导设置；扫码固定模式/单局域网地址、主动 5 分钟到期、5 次失败、一次成功、旧回调丢弃，回复手机后关闭，断连也释放；AI 请求规范化/错误分类/60 秒整体上限、取消队列保护、View 代次/重建、语音权限与错误 Key 保留上一批。
5. UI 与文档：13 个布局文案和相关动态文案资源化，Chip/Episode/Recommend 稳定 ID 与 DiffUtil，重复推荐去重、重排保留焦点；更新 docs/06/07/09/10/13/14、入口、HANDOFF、CHANGELOG 与本报告，保持实现/自动化/设备三维状态。

主要源码与 10 个新增测试类见 VERIFICATION_REPORT.md 的入口表及回归表；先前 10 月 5 日记录保留为历史。

### 实际验证

- 最终命令：bash gradlew testDebugUnitTest testReleaseUnitTest lintDebug lintRelease assembleDebug assembleRelease --no-daemon --max-workers=4 --console=plain。
- BUILD SUCCESSFUL in 2m 14s；98 actionable tasks: 30 executed, 68 up-to-date。最终日志 planning-verified-final.log。
- Debug/Release 各 31 类、181 项，失败/错误/跳过均 0；相对上轮 116 项新增 65 项/10 类；包含 Robolectric API 19/23/28、真实本地 HTTP/Socket、受控 ExoPlayer 与假时钟。
- Lint 两类型均 0 Error、0 Fatal、44 Warning，126 → 44；HardcodedText 为 0，剩余类别见当前报告。
- 两个 APK 包名/版本/minSdk19/targetSdk28 与 v1/v2 签名验证通过，均为同一调试证书；Debug 3 DEX、Release 1 DEX，主 DEX 启动类存在，Multidex 在 attachBaseContext 安装。R8 保留 HealthMap 与 WatchHistoryItem 字段。
- Debug 12013822 字节，SHA-256 f23271d2ea7dbec1d6d29bb9259d08a1b08b6bf91f629229446f1117f38cc8d5；Release 2972708 字节，SHA-256 19852d7509e2ad7b9ee793818fa14a156b391c2a6d32f6a5816252ab0b65121e。
- 发布材料生成器重新验证实际 APK、副本/清单/大小/哈希一致；默认拒绝当前调试签名包，未上传。Markdown/编码/资源、git diff --check、Python/设备脚本和 CI YAML 静态检查通过。

### 限制与接手

当前 adb devices 为空、无 /dev/kvm。播放器测试证明事件与状态流程，不能代替 HLS/MP4 出画出声；实际模型、手机路由/扫码、固件安装器、遥控器、同证书升级和持续使用仍填 docs/13。正式密钥、递增版本、真实 URL 与线上 CI 结果待验收。回滚只撤销本轮对应差异，保留原工作区与用户文档，不做清空历史或破坏性迁移。

## 2026-10-06 v0.0.2 正式发布准备

用户授权发布正式版本。默认版本升级至 0.0.2/code2，保留包名和 API 19 基线；准备发布说明，新增 docs/15 记录实际证据与阻断。

- 从原 GitHub v0.0.1 Release 下载实际 APK，确认 code1、SDK19/28、有效 v1 签名和 Android Debug 证书。原证书摘要 d507b831ebd7af498c550d0e24b84d1a10f218132ec9e6ce4c43d19f15c1e2d6，与云环境验证证书不同，原私钥未找到；已询问沿用原密钥或明确的新证书迁移方案，不以未回复为授权。
- prepare_release.py 新增 --previous-apk / --allow-certificate-change；默认拒绝非递增版本与异证书，明确迁移才记录无法覆盖旧版。新增 6 项 Python 回归，全部通过，接入 CI。
- v0.0.2 全量命令 testDebugUnitTest/testReleaseUnitTest/lintDebug/lintRelease/assembleDebug/assembleRelease 通过：1m52s、98 个任务，Debug/Release 各 181 项/31 类，0 失败/错误/跳过；两种 Lint 均 0 Error/0 Fatal/44 Warning。
- 之后注入 GitHub 稳定 OTA 清单地址，通过本地 finalizeDsl 初始化脚本构建真正未签名候选，日志 BUILD SUCCESSFUL in 11s。实际 aapt/ZIP/apksigner 检查版本、SDK、清单地址、1 个 DEX 和无签名状态；2,909,854 字节，SHA-256 0b5622920dba59bbf22e25f7ed85b36d923b595c744eba88bda3e9d863946c5f。不能安装，签名后哈希需重算。
- 实际调试签名 v0.0.2 在与上一版比对时被正确拒绝，未生成可误上传的 OTA 材料。原验证包与报告已备份，最新 Debug/未签名包证据更新到 VERIFICATION_REPORT.md。
- Git 读取正常，main dry-run 无差异；GitHub API CONNECT 在 TLS 前返回代理 403。按云环境技能保存 api.github.com / uploads.github.com 网络配置草稿，保留原环境配置，尚待环境设置保存并应用；没有绕过代理、放宽 TLS 或索要新 GitHub token。

尚未创建 v0.0.2 Release、正式签名 APK 或线上 update.json；缺少原私钥/明确证书方案及已应用的 API 网络配置。设备验收仍按 docs/13 如实记录，源码提交和后续发布以 docs/15 的最新记录为准。

后续提交与推送：业务源码、回归、CI 及文档提交 454390141b5cae612368b4842877a7cfad89b903，原生 Git 推送 main 成功，远端引用一致；没有创建 v0.0.2 标签。首次在线运行 https://github.com/ZxxXinI/tvbox-android4/actions/runs/37434928294 显示 Failure、32 秒、无 APK 产物；公开任务页要求登录查看日志，当前 API 网络未恢复，未认定具体根因。已更新当前报告和规划状态，后续需在 API 恢复后读取真实日志并修复。

## 2026-10-06 确定沿用原证书的发布方案

用户明确选择沿用 v0.0.1 的原证书、保留覆盖升级。原证书为调试证书；本轮不新建或迁移证书，仍需原 keystore 的可访问路径或已有 GitHub Actions 签名 Secrets。当前默认用户目录和工作区 keystore 的证书摘要均为云环境 0e64f475…886e2a，与公开上一版 d507b831…c1e2d6 不同；已核对公开证书信息，没有导出私钥或在日志/仓库存放口令。

- 发布工具新增 --allow-legacy-debug-upgrade，仅接受提供上一版、版本递增、新旧 APK 同一调试证书；与验证模式互斥，并拒绝 --allow-certificate-change。成功材料标注 legacy-debug-release，保留上一版摘要及可覆盖安装标记，不误称新生产证书。
- 新增 7 项策略回归，连同已有 6 项共 13 项通过。使用实际 v0.0.2 Debug APK 与公开 v0.0.1 APK 验证原证书模式，异证书被正确拒绝，未生成输出目录。Android 源码与候选 APK 未变，因此未重复全量 Android 构建。
- 更新 docs/13、docs/15、HANDOFF 与验证报告的证书方案和继续命令。口令应配置在未提交的 local.properties 或已有 GitHub Actions Secrets；云环境网络 Secret 不能直接用于本地签名。

尚未签名或发布：原 keystore/签名 Secrets 信息未提供，API 网络草稿仍待应用，首次在线 CI 失败也待 API 可用后读取日志修复。既有发布授权保留，不重复请求发布许可。

### 收到原 keystore 并完成初次原证书签名

用户上传 debug.keystore 并提供别名和受控签名配置。keytool 核对 androiddebugkey / PrivateKeyEntry、证书摘要 d507b831ebd7af498c550d0e24b84d1a10f218132ec9e6ce4c43d19f15c1e2d6，与公开 v0.0.1 完全相同。私钥在仓库之外保存为 0600、目录 0700，未提交、未作为发布资产、未记录口令；不再请求补充私钥信息。

启用 TVBOX_REQUIRE_RELEASE_SIGNING=true，注入稳定 OTA URL，以原私钥 assembleRelease：BUILD SUCCESSFUL in 10s，40 个任务、退出码 0。实际 v1/v2、版本/SDK 和原证书连续性检查通过；prepare_release.py 的原调试证书模式接受该 APK，metadata 为 legacy-debug-release、canUpgradePreviousInstallation=true，清单、副本、大小和哈希一致。初次材料保存在 release-v0.0.2/initial-signed；源码提交后会重构建，使 APK version-control-info 指向实际用于发布的源码提交，再固定最终材料。

此时原私钥阻断已解决；网络规格仍为 3，API/上传域名未应用，尚未创建 v0.0.2 Release 或更新线上清单。在线 CI 的真实错误仍需 API 可用后读取并修复。

### 固定最终原证书 APK 与发布材料

发布工具、13 项策略回归与证书方案提交 551c6e166d3b07f8dc690b146c795e5a756e5cd7，已推送 main。从该干净提交再次构建，使实际 APK version-control-info 对应该提交；最终原签名日志 BUILD SUCCESSFUL in 10s、40 个任务（2 执行、38 up-to-date）、退出码 0。

最终 app-release.apk 2,972,833 字节，SHA-256 ddcee0399a6c3ab1eb927a343e6f1e2216605dfe613eac7c181beed2cdaf6616；SDK19/28、0.0.2/code2、1 DEX、release/运行时不可调试、原证书 v1/v2 有效。发布模式与上一版证书相同、版本递增，材料记录 legacy-debug-release 和可覆盖安装条件。实际稳定 OTA URL、源码提交、APK 副本字节、清单大小/哈希、SHA256SUMS 核对全部通过；publish/ 仅有 APK、update.json、apk-info.json、signature.txt、SHA256SUMS 五个公开文件，不含私钥或口令。

当前源码的在线运行 https://github.com/ZxxXinI/tvbox-android4/actions/runs/37445773903 显示 Failure、20 秒、无产物。原生 gh 日志读取仍为 Forbidden；再次读取运行配置确认规格 3、API/上传域名未应用。原签名和本地材料已完成，尚未创建 v0.0.2 标签、Release 或更新线上清单；待在环境设置保存并发布网络草稿后，读取真实失败日志并修复，再完成公开发布及下载校验。后续文档记录提交不改变已固定 APK 的源码 551c6e1。

### 按用户要求交接手动发布

用户明确表示由其自行更新与发布，本轮转为完整材料交接，不再等待网络配置执行自动发布。已整理可直接使用的 release-notes.md、五个公开发布文件和手动发布说明，打包 tvbox-android4-v0.0.2-release.zip；包内仅这七个文件，解压 APK 与最终签名字节及 SHA-256 核对一致，未包含 keystore、口令或云环境调试包。说明包含 v0.0.2 标签、构建提交 551c6e1、保持附件文件名、正式/最新 Release、下载哈希校验，以及当前线上 CI 失败和设备验收未完成的真实限制。GitHub Release/标签/线上清单仍由用户后续创建。

## 2026-10-06 19:22 Android 6 图片与选集兼容修复

- 用户授权修复：Android 6 投影设备海报不显示、选集按钮仅显示省略号。
- `LegacyCertificateTrust.java`、`HttpClients.java`、`Tls12SocketFactory.java`、`res/raw/isrg_root_x1.pem`：在旧系统应用内补充经过指纹核验的公开根证书，保留完整证书链及域名验证。
- `EpisodeGridLayoutManager.java`、`EpisodeAdapter.java`、`DetailActivity.java`、详情和选集 XML：动态列数、小内边距、比例布局，解决窄屏/大字体文字空间不足。
- 新增证书和布局回归，API 19/23/28 验证待补录。设备当前未连接 ADB。
- 缺陷时间：2026-10-06 19:22；图片根因高度疑似系统 CA 缺失，待投影日志确认；选集固定 6 列和大内边距已从代码定位。临时方案：无，使用修复候选 APK 测试。
- 分支记录：[Android6 图片与选集适配](docs/16-Android6图片与选集适配.md)。

### 2026-10-06 19:35 验证完成

- 新增 18 项回归在 Debug / Release 均通过（API 19/23/28）；Debug、Release APK 构建成功；两种 Lint 均无 Error/Fatal。
- 完整回归各 199 项，各有 5 项 Windows 存储失败；未修改基线复验相同 5 项失败，保留证据并未扩大修改范围。详细步骤见分支记录。
- 原证书签名的本地 Release 测试包：2,975,761 字节，SHA-256 `f302eed9cecee22a0f95c65501c39b2365398d4496abf5d44f7632fd627ea46b`。仍为 0.0.2/code2，待用户在投影设备验收；本轮未执行 GitHub 发布。

## 2026-10-07 12:55 提交已完成的兼容修复

- 用户已反馈投影应用恢复正常，图片波动可能与信号有关，并要求停止追加诊断。设备无法连接 ADB，未将图片根因记录为已确认。
- 按用户要求将既有根证书兼容、选集自适应、18 项回归和文档提交至 `main` 并推送 `origin`；保持最低系统 Android 4.4，没有开展 Android 4.0/4.1 适配。
- `docs/16-Android6图片与选集适配.md`：补充用户反馈；本次没有新增业务代码，也未重跑先前已完成的构建和设备测试。
- 中文文档 UTF-8 BOM 回读与 `git diff --check` 通过；APK、缓存、本地配置和签名私钥保持在 Git 忽略范围内。

## 2026-10-07 12:57 扩展至 Android 4.1 / API 16

用户授权将最低版本扩展至 Android 4.1+，覆盖原 API 19 最低基线。修改 minSdk、生命周期状态、扫码焦点与 Socket 关闭、API 16 字体资源封装和二维码依赖；保留包名、原证书、ExoPlayer 2.19.1 与现有业务行为。

### 2026-10-07 13:07 验证与文档

- 修复初次降级 Lint 找到的 12 个 NewApi 错误，补齐 ZXing StandardCharsets 隐含要求和 API 16 字体设置。
- Debug/Release 构建及 Lint 均通过；各 261 项回归中 256 通过、5 个既有 Windows 存储失败；各 56 项 API 16 回归全部通过。发布脚本 15 项回归通过。
- aapt 核对实际 APK minSdk 16；apksigner 按 API 16 核对有效 v1/v2 与原证书。Release 2,976,276 字节，SHA-256 `485b978e54a17c16e4daea0b983b7d3b55563a6810b87bc9c2c250104c963837`。
- 同步 README、当前规则/基线、HANDOFF、CHANGELOG、CI、设备辅助和发布工具。源码与中文文档编码按文件类型验证；历史 API 19 证据保留。当前仍是 0.0.2/code2 的本地兼容测试包。
- 没有 API 16 实机连接，真实安装与媒体效果待验收。缺陷原因、全部文件用途与证据见 [Android 4.1 兼容扩展](docs/17-Android4.1兼容扩展.md)。

## 2026-10-07 19:08 准备发布 v0.0.3

- 用户明确授权发布。`app/build.gradle` 默认版本递增到 0.0.3/code3；README、CHANGELOG、当前基线与文档入口同步。
- 将已完成的 API 16 源码、回归和文档一并提交，之后从固定源码提交构建原证书 APK；启用独立 GitHub OTA 清单，核对公开上一版证书与版本。
- 沿用公开上一版的原调试证书；私钥与配置只在本机受控位置，不提交、不上传。
- 完整流程与后续核验见 [v0.0.3 发布记录](docs/18-v0.0.3发布记录.md)。

发布前验证：0.0.3/code3 的版本/OTA/旧 API/根证书/选集专项 60 项通过，两种 Lint 通过；发布工具 15 项回归通过。沿用既有完整回归记录，不将 5 项 Windows 存储失败抹去。

发布时查明线上 CI 初始化失败：运行 37612569688 的 sdkmanager 找不到 setup-android 默认的旧 `tools` 包。显式改为 `packages: platform-tools`；本地原证书 Release 构建已成功，最终发布包会从包含 CI 修正的提交重构建。

发布材料复核时修正 ASCII 校验和文件的 BOM，确保标准 sha256sum 可读；中文清单仍按 UTF-8 BOM 输出。发布工具增加回归，共 16 项通过。

使用标准 sha256sum 实测捕获 Windows CRLF 文件名尾 CR 问题，发布工具改为显式 LF 输出，并补字节级断言；重新生成并检查最终材料。

### 2026-10-07 19:19 v0.0.3 已公开

- 构建/tag 源码固定为 43f918648c955e4430ab7d24044d2ec2dcd39371。原证书 Release 构建、API 16 v1/v2、上一版签名/版本连续性、标准 SHA256SUMS 校验通过。
- GitHub Release 已公开并设 Latest，上传 APK、独立 update.json 和三项校验附件。APK 2,976,258 字节，SHA-256 `8fb8a4c26f0f9f07824455082b84f43c529e8c033e9eeaa232f7c87e4d029313`。
- 未带鉴权从公开 latest 清单和实际 APK URL 下载，确认版本、文件大小、哈希和本地产物完全一致。编译 DEX 中已核对稳定 OTA 地址。
- CI 初始化修正后的运行 37612774100 成功；发布源码运行 37613089882 当时尚在执行，状态如实记录。真实 API 16 设备验收仍待完成。
- 当前基线、接手记录和 CHANGELOG 同步发布完成状态；详细证据见 [发布记录](docs/18-v0.0.3发布记录.md)。

## 2026-10-08 08:59 开始实现扫码与手机端 AI 配置方案

- 用户明确要求实施已确认方案，只改扫码网页的模型获取，交付原签名 v0.0.4/code4 测试 APK，不发布 Release 或线上 OTA。
- `QrCode`、`QrImageView`、扫码弹窗和 SettingsFragment：四模块留白、M 级纠错、最终像素渲染、无插值显示、小屏滚动与关闭/过期手动地址。
- `SettingsRepository`、`AiProvider`、`AiClient`、RecommendRepository：五家默认、提供方隔离 Key/模型、旧配置迁移、原子保存、请求配置快照、MIMO api-key 和不强制统一温度。
- `AiModelsClient`、`AiConfigPage`、ConfigHttpServer：手机模型选择流程、20 秒查询预算、分页/过滤、候选/手动兜底、会话鉴权和取消，模型查询不保存；并发查询会取消旧查询，有限线程和队列避免新 Key 请求被旧请求阻塞。
- 改名为 TVBox4.1+；保留原包名、最低 API 16、原证书和 OTA 地址。新增 Java/网页状态回归并加入 CI，详细文件与验收见 [v0.0.4 测试记录](docs/19-v0.0.4扫码与模型配置.md)。
- 缺陷时间：2026-10-08 08:59；相机无法识别两类二维码，原留白只有一个模块且生成后缩放。此为代码风险定位，实际投影识别仍待用户验收。临时方式：手机浏览器手动输入弹窗地址。

### 2026-10-08 09:40 测试包与验证

- 新增测试类在 Debug/Release 下各 69 项全部通过；完整各 331 项中 326 通过、5 项既有 Windows 存储失败、0 错误。两种 Lint 0 Error/Fatal，网页实际 JS 状态测试通过，既有发布工具 16 项通过。
- Release 为 R8 构建，显式保留模型目录 JSON 字段并在 seeds 核对。曾遇 DEX 占用，停止本任务 daemon 后构建恢复；未删除用户数据。
- 原证书 v1/v2 和 API16 校验通过，实际名称 TVBox4.1+、版本 0.0.4/code4。测试包 `dist/TVBox4.1+-v0.0.4-test.apk`，2,984,062 字节，SHA-256 `c6a9c81f59c45ba79319e904bc62b8cc4bfcbbe14068c934838d2eeb31139543`。
- 编译 DEX 中 OTA 地址保持原值；本轮没有推送、创建 Release 或修改线上清单。真实投影相机和有效 Key 仍待用户验收。
- 全部文件用途、问题边界与无需 ADB 的验收步骤见 [v0.0.4 测试记录](docs/19-v0.0.4扫码与模型配置.md)。

## 2026-10-08 10:12 精简设置并准备发布 v0.0.4 / OTA

- 用户已反馈本地测试没有问题，明确授权删除截图中两个按钮并发布 v0.0.4；本轮覆盖此前“仅测试、不发布”的阶段限制。
- `fragment_settings.xml`、`SettingsFragment.java`、`strings.xml`：删除“添加自定义源”和“模型”按钮、点击处理及原生编辑代码；移除首项残留边距。扫码添加接口、管理接口和扫码 AI 保存逻辑保留，空接口管理提示改为扫码添加。
- `app/build.gradle`：正式普通构建默认注入原稳定 GitHub OTA 清单；`.github/workflows/android.yml`：验证构建显式清空地址，不访问生产服务。
- `NavigationStateTest.java`：新增 API16/19/23/28 回归，检查两个入口消失且剩余扫码/管理/Key 控件仍可聚焦。
- 本轮导航/启动更新/更新仓库专项 Debug/Release 各 43 项全部通过；Debug/Release Lint 0 Error/Fatal（63/58 Warning）；手机网页 JS 状态回归通过，发布工具 16 项通过。此前完整各 331 项中的五项 Windows 存储失败保留，不宣称全量通过。
- README、CHANGELOG、当前基线、HANDOFF、文档入口和本地配置示例同步；详细发布材料与后续线上证据见 [v0.0.4 发布记录](docs/20-v0.0.4发布记录.md)。原私钥不提交、不上传；从固定源码提交构建签名 APK，再创建标签和 Release。
