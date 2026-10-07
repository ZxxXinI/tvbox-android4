# Android 4.1 / API 16 兼容扩展

日期：2026-10-07 13:07。用户明确授权“先扩展到 Android 4.1+”。

## 当前范围

最低安装版本由 Android 4.4 / API 19 降为 Android 4.1 / API 16。包名仍为 `com.tvbox.android44`，保留现有签名和 ExoPlayer 2.19.1，使用本仓库独立 APK。此次是本地兼容测试构建，版本仍为 0.0.2/code2；没有创建新 Release 或线上更新清单。

## 文件变更与原因

- `app/build.gradle`：minSdk 16；targetSdk 28、compileSdk 35 保持现有基线。
- `common/BaseActivity.java`、首页/详情/播放器 Activity：用销毁标志和 API 17 条件调用替代无保护的 `Activity.isDestroyed()`。播放器继续使用原基类，在本类记录状态。
- `common/FontScale.java`：API 17+ 沿用配置上下文；API 16 使用独立 Resources/Theme 和克隆的 LayoutInflater，支持三档字体而不改变 Application 的资源配置。
- `feature/settings/SettingsFragment.java`：扫码弹窗关闭后的焦点恢复改用 `ViewCompat.isAttachedToWindow`。
- `feature/settings/ConfigHttpServer.java`：直接关闭 Socket/ServerSocket；避免 API 19 才成立的 Closeable 接口转换，覆盖正常关闭、失败清理和会话到期路径。
- `gradle/libs.versions.toml`：ZXing core 固定为 3.3.3。3.5.3 的二维码编码初始化直接引用 API 19 的 `StandardCharsets`，不适合此最低版本。二维码仅作本地编码，UTF-8 回读测试覆盖中文链接。
- `res/values/strings.xml`：首页兼容范围标注 Android 4.1+。
- `app/src/test/`：已有根证书、选集、首页、播放器、AI、启动更新、安装流程加入 API 16；新增生命周期/字体/二维码回读与 API 16 扫码 HTTP 场景。
- `scripts/prepare_release.py` 与其回归：从真实 APK 读取 minSdk，接受当前 16 和历史 19，按对应最低版本校验 v1 签名；元数据写实际 SDK，保留版本递增和同证书策略，支持 Windows 工具扩展名及 UTF-8 BOM 输出。
- `scripts/device_smoke.sh`、`.github/workflows/android.yml`：安装辅助和 CI 校验标注更新至 API 16，脚本用 ASCII 文案以保持 Bash shebang 可执行。
- README、docs 入口、00/02/10/13、HANDOFF、CHANGELOG 与开发日志：同步当前最低边界；历史设备结果不重写为本轮 API 16 证据。

## 缺陷记录

- 发现时间：2026-10-07 12:57。
- 初次降低 minSdk 后 Lint 找到 12 个 NewApi 错误：Socket/ServerSocket 到 Closeable 的 5 处转换、6 处 Activity 销毁调用、1 处 View 附着调用。已实际替换，没有屏蔽 NewApi 或使用 overrideLibrary。
- 另发现二维码库的 API 19 类初始化依赖，以及 API 16 原先忽略自定义字体的路径，均补充适配。
- 临时方案：无；用兼容构建测试。

## 验证证据

- `lintDebug lintRelease assembleDebug assembleRelease`：BUILD SUCCESSFUL。两种 Lint 都是 0 Error / 0 Fatal，Debug 61 Warning、Release 56 Warning，未新增兼容错误屏蔽。
- `testDebugUnitTest testReleaseUnitTest --continue`：两种构建各 261 项，256 通过、5 失败、0 错误；其中 API 16 各 56 项全部通过。测试包括根证书加载/拒绝、资源/字体/选集、自定义源首页、焦点、播放返回/缓冲换线/释放、AI/语音降级、扫码 HTTP/到期/端口释放、启动 OTA/校验/安装 Intent。
- 5 个失败是此前 Windows 的 HistoryStore/HealthStore 覆盖已有文件问题，未修改基线已复现相同失败，详见 [上一轮记录](16-Android6图片与选集适配.md)。不将全量命令报告为通过。
- 新字体断言最初将 XML 像素尺寸 18px 与未取整的 17.68px 比较，修正为系统取整后期望值；API 16/19/28 均通过，并校验基础资源配置未改变。
- `python -m unittest discover -s scripts/tests -p 'test_*.py' -v`：15 项全部通过，覆盖 minSdk 元数据、旧 19 版本比较、v1、同证书及版本码规则。
- 用 `aapt` 读取两个实际 APK：包名 com.tvbox.android44，版本 0.0.2/code2，minSdk 16，targetSdk 28；`apksigner verify --min-sdk-version 16` 核对 v1/v2 有效。
- 新旧证书一致：`d507b831ebd7af498c550d0e24b84d1a10f218132ec9e6ce4c43d19f15c1e2d6`。
- Android API 16 Robolectric 运行库从 Maven Central 下载，并核对 SHA-512。Robolectric 是 JVM 回归，不是真实 Android 解码器或固件验证。

| APK | 字节数 | SHA-256 |
| --- | --- | --- |
| Debug | 8,267,281 | `5c6c4c457e3296e7e2608649c9e98955b76e0e1110f80591abc733c33760e36c` |
| Release（R8） | 2,976,276 | `485b978e54a17c16e4daea0b983b7d3b55563a6810b87bc9c2c250104c963837` |

[Release 测试 APK](../app/build/outputs/apk/release/app-release.apk) · [Debug APK](../app/build/outputs/apk/debug/app-debug.apk)

## 待设备验收

当前没有连接 Android 4.1 设备。尚未验证 API 16 固件的安装/冷启动、真实图片解码、H.264/AAC MP4/HLS 出画出声、遥控器、扫码双端提交和系统安装器。API 16 加载现代 HTTPS 仍可能受固件密码套件、证书链、设备时间和服务器限制；应用保留正常证书/域名校验。

先在 Android 4.1 设备安装上面的原签名 Release 测试包，检查上述流程；再对 Android 4.4、6、9 进行实际回归。待发布新版时须递增版本码并生成实际 APK 对应的 OTA 材料，本轮不冒充线上新版。

## 导航

[开发日志](../AI_DEV_LOG.md) · [当前基线](13-当前版本基线与验收清单.md) · [产品说明](../README.md)
