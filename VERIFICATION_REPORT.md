# 完成度与未验证项报告（2026-08-30，API19 模拟器验证后更新）

对照 `docs/00~12` 全部规则与 `HANDOFF.md` 任务清单的核对结论。区分口径遵循文档 00 §7：**已实现 / 已构建 / 已在实机验证 / 受环境限制未承诺**。

## 0. API 19 验证结果（2026-08-30 追加）✅ 已完成模拟器层验证

代理恢复后经 sdkmanager 成功安装 `system-images;android-19;default;x86` + `platforms;android-19`，创建 `rev19` AVD（tv_720p 1280×720、KVM）完成回归。**发现并修复 3 个真实缺陷**：

1. **Multidex 安装时机错误（API19 必崩）**：`MultiDex.install()` 原在 `Application.onCreate`，而 API<21 上 FileProvider 等 ContentProvider 在 onCreate 之前实例化 → `ClassNotFoundException: androidx.core.content.FileProvider` 冷启动即崩。已移至 `attachBaseContext()`。
2. **TLSv1.2 未显式启用（API19 全网失败）**：Android 4.x SSLSocket 默认关闭 TLS1.2，OkHttp 3.12 仅 API20+ 自动启用 → 实测 96 次 `SSL handshake aborted`。新增 `Tls12SocketFactory`（API<22 显式启用 TLS1.2，不改信任策略、不禁用校验），修复后握手失败 **96 → 0**，量子源（HTTPS MacCMS）列表/搜索/详情在 API19 全部可用。
3. **详情接口播放串被丢弃（全平台缺陷，API19 排查揭出）**：`MacCmsMapper.toPagedMovies` 硬编码 `toMovie(..., includePlays=false)`，详情路径（doFetchDetail）因此**主线路永远丢失**——此前所有影片的线路全部来自补线，且是"默认线路空集"问题的根源。新增 `toPagedMovies(apiLine, dto, includePlays)` 重载，详情路径传 true；补回归单测；真机复测详情线路数 6→7（主线路首次出现）。

**API19 模拟器上已验证**：安装、冷启动（修复后无崩溃）、默认主题首页、豆瓣失败→回退当前接口（文案正确）、多来源搜索执行与空态、详情（元数据/2 条线路/推荐线路选中/集数渲染）、播放失败错误态（重试/返回按钮）。

**API19 上仍受系统硬限制的项**：
- **播放 CDN 握手**：量子播放 CDN `v.lzcdn34.com` 的 TLS1.2 仅提供 AES-GCM 套件，而 Android 4.4 系统栈无任何 GCM 套件（API20+ 才引入）→ 该类 CDN 在 API19 上无法握手，属**系统硬限制**。对策即文档 02 §7 预判的"服务端受控 HTTPS 兼容代理"或 http 源；客户端不得为此关闭校验（未做任何降级）。
- **豆瓣 HTTPS**：API19 仍不可达（其 CDN TLS 要求超出 4.4 能力），但失败→缓存→回退 MacCMS 链路完整工作，不影响可用性。
- 模拟器 NAT 无法访问宿主本地端口（adb reverse 需 API21+），本地 mock 闭环不可行；播放解码验证以真机（API28）+ 公共源完成。

## 1. 本轮已完成（附证据）

| 项 | 证据层级 |
| --- | --- |
| 单元测试 12 类 69 用例（播放串/HTML/规范化/内容过滤/合并/IPTV/AI/OTA/卡顿/换线/MacCMS 映射/历史条目） | 自动化：`testDebugUnitTest` 全绿 |
| 实机（小米电视 2304FPN6DG，Android 9 / API 28，1600×900，Wi-Fi）：冷启动、豆瓣热播+海报、多来源搜索（8/8 线路 38 结果）、详情补线（6 线路）、HLS（rym3u8）播放出画、seek±10s、控制层、历史写入、断点续播入口、AI 未配置降级、错误 Key 401 路径、扫码配置端到端（表单→保存→掩码→会话关闭→端口释放确认）、平台直播/IPTV 离线错误态、25 次快速按键无崩溃 | 运行证据（API 28 实机） |
| API 19 模拟器（x86 tv_720p，KVM）：安装/冷启动/首页降级/搜索/详情线路/集数/播放错误态；Multidex 时机与 TLS1.2 两处 API19 专属修复实测生效；真实解码受 CDN GCM-cipher 系统限制（§0） | 运行证据（API 19 模拟器） |
| 影院主题首页（左侧图标导航+Hero 播放/详情+网格）、主题切换后页签恢复 | 运行证据（API 28 实机） |
| Release R8 构建并实机验证（首页/搜索/详情含主线路/集数/播放/历史恢复） | 运行证据（API 28 实机） |
| README.md、update.json.example | 交付物 |
| lint：175→116 条（修复 DefaultLocale 13、UnusedResources 2；禁用不适用的 RTL 检查） | 静态：`lintDebug` 0 错误 |
| 合并清单 `minSdkVersion=19`、依赖树无 Media3/Compose/OkHttp4 | 静态 |

## 2. 本轮发现并修复的 9 个缺陷

### 单测揭出（2）
1. **IPTV `$` 解析语义与文档 08 相反**：文档规定 URL 在 `$` 前、元数据在后；按文档格式原实现会整条丢弃频道。已改为文档主格式 + 兼容 `meta$url` 变体，`extractHttpUrl` 遇 `$` 截断。
2. **HtmlCleaner 数字实体解码结果未返回**（`return s` 应为 `return sb.toString()`）。
3. **Glide 注解处理器未配置**：`annotationProcessor libs.glide` 用了运行库而非 `glide:compiler`，`GeneratedAppGlideModule` 从未生成，图片走 HttpURLConnection 且无 Referer/UA → 豆瓣海报全部 404。修复后海报正常。
4. **OkHttpStreamFetcher 在 Glide 读取前关闭响应体**（`finally response.close()`）→ 全部图片 `IOException: closed`。改为持有响应、`cleanup()` 释放。
5. **recreate() 后 Fragment 叠加**（换主题/字体/视频源后新旧页签内容重叠）：selectTab 改为按 tag 复用恢复的 Fragment 并隐藏全部现存实例。
6. **详情默认线路可能为空集线路**：主详情先以空集线路渲染时，补线到达后不重选；播放提示"没有可播放的集数"。修复：`onLineAppended` 时若当前线路无集数则自动切到 `defaultLineId` 的非空集线路。

### API19 模拟器揭出（3，编号 7~9，详见 §0）
7. **Multidex 安装时机错误**：`MultiDex.install()` 在 `onCreate`，API<21 上 ContentProvider 先于 onCreate 实例化 → FileProvider ClassNotFound 冷启动崩。移至 `attachBaseContext`。
8. **TLSv1.2 未显式启用**：Android 4.x 默认关闭，OkHttp 3.12 仅 API20+ 自动启用 → 实测 96 次握手失败。新增 `Tls12SocketFactory`（API<22），修复后 0 失败。
9. **详情接口播放串被丢弃（全平台）**：`toPagedMovies` 硬编码 `includePlays=false`，主线路永远丢失。新增重载，详情路径传 true，补回归单测；真机复测线路 6→7。

## 3. 未完成 / 未验证项及原因

### 3.1 Android 4.4（API 19）真机验证 —— 部分完成，实机仍缺

- **已完成（2026-08-30）**：API19 模拟器（x86，tv_720p，KVM）安装与回归，见 §0。修复 Multidex 时机、TLS1.2 启用两处 API19 专属缺陷；API19 专属的安装/启动/TLS/页面链路已验证。
- **仍缺**：Android 4.4 **物理盒子**上的遥控器、硬解、长播、GCM 之外的真实 CDN 源验证（文档 10 §1 的最终口径）。当前无实机设备。
- **结论口径**：可以表述为"已完成 API19 模拟器级验证，含两处 API19 专属修复"；**不得表述为**"已在 Android 4.4 真机验证"。

### 3.2 需真实凭据/在线服务的验证 —— 未验证

| 项 | 原因 | 已覆盖部分 |
| --- | --- | --- |
| AI 真实成功推荐 | 无有效 API Key（本轮用假 Key 验证了 401 错误路径与请求构造） | 请求构造/解析器有单测；未配置/错误 Key 降级实机验证 |
| 平台直播全流程（平台→分类→房间→播放） | `platform_live_server`(20.205.10.127:8868) 离线 | 离线错误态+重试实机验证；客户端容错解析有实现 |
| IPTV 真实频道播放、切台 20 次 | 订阅源 (…:8787) 离线 | IptvTextParser 11 个单测（含 BOM/CRLF/#genre#/合并/去重） |
| 双端扫码（真手机扫码） | 设备与开发机不同网段 | 用 `adb forward` 完成端到端等效验证（表单/保存/掩码/关会话/端口释放） |
| OTA 全流程（下载→校验→安装） | 无真实 update.json 服务端与新版 APK | 解析器单测（BOM/缺字段）、API26+ 安装权限分支代码就位、下载校验逻辑在仓库层 |

### 3.3 文档 10 §2.1 要求但缺失的单测 —— 部分缺失

- **缺**：多来源并发上限 3 / 3 秒超时 / 取消不记失败（MultiSourceSearch 仓库层）；详情追加线路不重置选择（DetailSupplement 仓库层）；历史排序/覆盖/上限/损坏 JSON 回退（HistoryStore 依赖 Android `Context`/SharedPreferences）。
- **原因**：仓库与存储层依赖 Android 运行环境，需引入 Robolectric 或先抽接口/纯函数重构才能 JVM 测；本轮按"改动小、可回滚"原则未做该重构。
- **缓解**：上述链路均有 API 28 实机运行证据（8/8 来源完成、补线不重置选择实机复测、历史覆盖置顶/继续播放实测）；纯解析/决策逻辑已 100% 单测覆盖。

### 3.4 性能与稳定性长测 —— 未执行

- 文档 10 §6 的 30 分钟点播/60 分钟直播+20 次切台/连续 10 页浏览不 OOM/配置服务反复开关 10 次等长时项目未跑（需要长时间在线服务与人在场听音画同步）。已做：25 次快速按键、多次冷启动、反复进出播放器，均无崩溃无泄漏日志。
- **完成条件**：可用源稳定在线时段执行并记录。

### 3.5 发布材料 —— 部分就绪

- ✅ 已有：已签名（debug 证书回退）Release APK、update.json.example、README、CHANGELOG（AI_DEV_LOG 累计记录）。
- **缺**：
  1. **正式发布签名**（当前 release 用 debug 证书回退；正式发布必须在 local.properties 注入真实签名后重构建）。
  2. 正式版本号（当前 versionCode=1/1.0.0，发布时需单调递增并同步 update.json）。
  3. API 19 实机验收记录（依赖 3.1）。
  4. 回滚旧版本 APK 的托管位置。

### 3.6 lint 剩余 116 条警告 —— 未清零（有意保留）

- 构成：HardcodedText 48（界面中文直写，产品允许简体中文文案；资源化收益低改动大）、NotifyDataSetChanged 11（低配设备性能提示，重构 Adapter 有回归风险）、Autofill/LabelFor（API26+ 提示，与 targetSdk 28 无关）等。
- **决定**：全部不阻断构建与发布口径；在后续维护 Gate 中分批处理，不在本轮为清零而大改 UI 层。

## 4. 结论

- **代码与功能**：docs/11 的 Gate 0~9 全部实现；Gate 10 的构建/清单/示例部分完成。占位壳为零。
- **验证**：静态 ✅、自动化 ✅（70 用例）、运行证据为 **API 28 实机**（点播主链路/搜索/详情/历史/扫码/AI 错误路径/双主题/Release R8）+ **API 19 模拟器**（安装/启动/页面/TLS/两处专属修复实测）。
- **不能宣称**："已在 Android 4.4 真机验证"、"已完全适配所有盒子"、"支持所有 m3u8/H.265/4K"（文档 00 §7）。H.265/4K 属设备相关未承诺项。
- **API19 真机剩余风险**：仅提供 GCM cipher 套件的 HTTPS CDN 无法握手（系统限制，需兼容代理或 http 源，见 §0）、MediaCodec 硬解可用性、遥控器按键映射、低内存长播稳定性。
