# OTA 安装器入口与保存安装包回退

日期：2026-10-11  
状态：已包含于 v0.0.6/code6 并发布；专项自动化、签名和线上 OTA 材料核验通过；设备 OTA 操作由用户验收。

## 问题

设置页下载并校验 OTA 安装包后，原实现使用通用 `ACTION_VIEW` 打开 APK。测试设备上该 Intent 同时匹配系统安装器及 WPS、网盘等应用，实际进入“打开方式”选择器，而不是直接交给安装器。

当应用没有“安装未知应用”权限时，原界面只提示前往系统授权，没有“保存安装包”选项，用户无法从应用内便捷地取得已下载 APK。

## 修改

- `UpdateRepository.buildInstallIntent()` 改用安装专用 `ACTION_INSTALL_PACKAGE`，继续通过 FileProvider 提供只读内容 URI。
- 缺少安装权限时，设置页现在提供“保存”“去授权”“取消”三个选项；保存不会消耗或删除应用内已校验的下载文件。
- 保存目标为公开 `Download/TVBox/`：Android 10+ 使用 MediaStore；Android 4.1–9 使用公共下载目录，Android 6–9 申请旧版外部存储权限。保存时校验复制字节数，失败时清理未完成输出。
- 保留原有 OTA 下载、大小与 SHA-256 校验、安装授权流程及包名/版本配置。

## 验证边界

- `UpdateRepositoryTest` 专项 18 项通过，覆盖安装 Intent 与保存副本/源文件保留；`lintRelease` 和 `assembleRelease` 成功。
- 本地测试 APK：`app/build/outputs/apk/release/app-release.apk`。这是同签名、`v0.0.4 / versionCode 4` 的本地测试构建，不是 GitHub 发布包，也不会被 OTA 清单提供。
- ADB 诊断确认旧通用 Intent 会进入应用选择器；修改后的设备安装器点击流程、真实保存目录及无权限回退尚未完成设备验收。用户已要求自行测试，本轮不再操作设备。
- 诊断期间临时修改的 `REQUEST_INSTALL_PACKAGES` AppOp 已恢复为 `allow`。没有卸载应用或清除数据；测试 APK 以相同签名和相同版本码覆盖安装在测试设备上。

## 发布状态

修复版已沿用原签名递增为 v0.0.6/code6，保留 v0.0.5 Release；发布材料及公开下载证据见 [v0.0.6 发布记录](24-v0.0.6-OTA安装修复发布.md)。设备安装器与保存流程仍待用户测试，不将自动验证写成设备验收。
