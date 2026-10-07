# Android 6 图片与选集适配

日期：2026-10-06。

## 缺陷与修复

### 图片证书兼容

用户反馈 Android 9 正常，Android 6 投影设备首页海报不显示。默认量子接口返回独立海报域名 img.lzipic.com；电脑实测图片 HTTP 200，其证书链终止于 ISRG Root X1。未更新证书库的 Android 6 通常缺少该根证书。投影设备尚未通过 ADB 连接，此项根因仍需设备日志确认。

- `LegacyCertificateTrust.java`：Android 8 以下优先使用系统信任，系统校验失败后使用仅含 ISRG Root X1 的证书库进行完整链校验。客户端证书仍只使用系统信任。
- `HttpClients.java`：统一网络入口接入补充信任；Glide 图片、接口等共享该入口，正常证书校验与默认域名校验保留。Android 8 及以上保持系统默认配置。
- `Tls12SocketFactory.java`：包装同一个 SSLContext 的工厂，避免旧系统协议处理丢失补充信任。
- `res/raw/isrg_root_x1.pem`：公开根证书，来源为 Let's Encrypt 官方网站仓库。加载时强制核对 SHA-256、自签名及有效期，不含私钥。

来源：https://raw.githubusercontent.com/letsencrypt/website/main/static/certs/isrgrootx1.pem

证书 SHA-256：`96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6`。

该修复不改变设备的系统证书库，也不放行自签名、陌生根证书或错误域名。固件时间异常、其他 CA 缺失、网络拦截及其他图片格式问题须另行定位。

### 选集文字省略

原详情页海报固定 220dp，选集固定 6 列；每个按钮左右共 28dp 内边距，字体为 13sp，单行省略。窄屏、高显示密度或大字体压缩实际文字区域，导致“第 x 集”仅显示省略号。

- `EpisodeGridLayoutManager.java`：按实际可用宽度、字体及剧集标题测量动态选择 1～6 列；编号文字预留到“第9999集”，超长描述允许原有省略。
- `EpisodeAdapter.java`、`DetailActivity.java`：接入动态网格，保留选中态、点击与遥控器焦点。
- `activity_detail.xml`：海报/内容按比例分配，播放按钮并入内容区，释放原右侧固定占用。
- `item_episode.xml`：左右内边距分别缩至 6dp。

## 验证记录

新增证书和详情布局回归，覆盖 API 19 / 23 / 28、窄屏/宽屏、普通/超大字体、选中态和点击。

2026-10-06 19:35 验证结果：

- `testDebugUnitTest` 专项回归：18 项全部通过；覆盖 API 19/23/28 资源证书加载、指纹替换拒绝、系统缺失根证书的后备信任、无关证书/空链/客户端信任拒绝，以及窄屏/宽屏、普通/超大字体、宽度变化、遥控器焦点和选集点击。
- `assembleDebug assembleRelease`：BUILD SUCCESSFUL，Release 完成 R8 和资源收缩。实际 APK 内公开 PEM 与源码一致，签名证书 SHA-256 为原 `d507b831ebd7af498c550d0e24b84d1a10f218132ec9e6ce4c43d19f15c1e2d6`。
- 完整 Debug / Release 回归各 199 项：各通过 194 项、失败 5 项、错误 0 项；新增专项各 18 项全部通过。
- 5 项失败来自 `HistoryStoreTest`（3 项）和 `HealthStoreTest`（2 项），涉及覆盖已有存储文件。Windows 的 `File.renameTo` 无法按 Android/Linux 的语义替换已有文件。以 `git archive HEAD` 导出未修改基线到 `.gradle/legacy-baseline`，仅执行这两类原有测试，共 10 项、相同 5 项失败，确认此项独立于本轮修改。未将完整测试记为全通过。
- `lintDebug` / `lintRelease` 均完成，0 Error / 0 Fatal；分别 58 / 54 Warning。包含委托标准 TrustManager 的自定义信任提示、测量用 inflate 无父视图和布局权重等提示，没有禁用这些检查。
- 初次运行因 Robolectric 运行库的 Java HTTPS 下载中断而失败，后经 Maven Central 下载所缺运行库并核对 SHA-512，恢复测试；没有修改应用的校验来绕过测试下载。
- 中文 Markdown 为 UTF-8 with BOM，严格回读无 U+FFFD；Java 保持 UTF-8 无 BOM。`git diff --check` 通过。

测试 Release APK：`../app/build/outputs/apk/release/app-release.apk`。包名 `com.tvbox.android44`、versionName `0.0.2`、versionCode `2`、minSdk `19`；本轮为本地修复测试包，未递增发行版本。

APK：2,975,761 字节；SHA-256 `f302eed9cecee22a0f95c65501c39b2365398d4496abf5d44f7632fd627ea46b`。

Android 6 投影设备实际图片、文字与遥控器效果待用户安装测试；自动化不代表固件实测。建议先打开原问题影片核对海报与集数，再分别切换普通/超大字体，最后检查 Android 9 的既有功能。

## 用户反馈与提交

2026-10-06 用户确认安装的是本项目生成的 `app-release.apk`，随后反馈图片恢复、应用已正常，怀疑信号波动，并要求停止后续诊断。投影设备不能连接 ADB，没有取得设备日志，因此图片问题仍不认定为已证实的证书故障。本轮没有增加图片诊断入口。

2026-10-07 用户要求先提交已完成的修复。本次提交包含根证书补充信任、选集布局、对应回归和验证记录；最低版本仍为 Android 4.4，不包含 Android 4.0/4.1 适配或新版本发布。

## 导航

[开发日志](../AI_DEV_LOG.md)
