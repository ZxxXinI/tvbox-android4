package com.tvbox.android44.feature.settings;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.tvbox.android44.BuildConfig;
import com.tvbox.android44.R;
import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.FocusUtils;
import com.tvbox.android44.common.Result;
import com.tvbox.android44.common.TvDialogs;
import com.tvbox.android44.data.local.SettingsRepository;
import com.tvbox.android44.data.repository.UpdateRepository;
import com.tvbox.android44.domain.model.ApiLine;
import com.tvbox.android44.domain.model.AiProvider;
import com.tvbox.android44.domain.model.AppUpdate;
import com.tvbox.android44.domain.model.LineHealth;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * 设置页（文档 09 §1/§6/§9）：
 * - 主题/字体改动后 recreate 全局生效；视频源切换清空仓库缓存并重建页面。
 * - 自定义接口：名称/URL 校验 + 轻量列表请求测试，测试失败需二次确认才保存。
 * - API Key 只显示掩码，明文仅经扫码会话写入。
 * - 扫码会话只在弹窗期间运行，关闭/停止即释放端口；关闭后焦点回到入口按钮。
 * - OTA：手动检查 → 确认 → 下载进度 → 大小/SHA-256 校验（仓库内）→ FileProvider 安装；
 *   API 26+ 先检查未知来源安装权限。
 */
public class SettingsFragment extends Fragment {

    private TextView btnTheme;
    private TextView btnFont;
    private TextView btnApi;
    private TextView btnAiProvider;
    private TextView btnAiKey;
    private TextView btnAiQr;
    private TextView btnAutoLine;
    private TextView btnCheckToggle;
    private TextView btnUpdateAction;
    private TextView versionView;
    private TextView otaStatus;
    private ProgressBar otaProgress;
    private TextView btnIptv;
    private TextView btnPlatform;

    private ConfigHttpServer configServer;
    private AlertDialog qrDialog;
    private View qrReturnTo;
    private UpdateRepository.CheckHandle updateHandle;
    private File downloadedApk;
    private boolean downloading;
    private AppUpdate pendingStartupUpdate;
    private int viewGeneration;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        final int generation = ++viewGeneration;
        View root = inflater.inflate(R.layout.fragment_settings, container, false);
        btnTheme = root.findViewById(R.id.settings_theme);
        btnFont = root.findViewById(R.id.settings_font);
        btnApi = root.findViewById(R.id.settings_api);
        btnAiProvider = root.findViewById(R.id.settings_ai_provider);
        btnAiKey = root.findViewById(R.id.settings_ai_key);
        btnAiQr = root.findViewById(R.id.settings_ai_qr);
        btnAutoLine = root.findViewById(R.id.settings_auto_line);
        btnCheckToggle = root.findViewById(R.id.settings_check_toggle);
        btnUpdateAction = root.findViewById(R.id.settings_update_action);
        versionView = root.findViewById(R.id.settings_version);
        otaStatus = root.findViewById(R.id.settings_ota_status);
        otaProgress = root.findViewById(R.id.settings_ota_progress);
        btnIptv = root.findViewById(R.id.settings_iptv);
        btnPlatform = root.findViewById(R.id.settings_platform);

        btnTheme.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickTheme();
            }
        });
        btnFont.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFont();
            }
        });
        btnApi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickApi();
            }
        });
        root.findViewById(R.id.settings_api_manage).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manageCustomApis();
            }
        });
        root.findViewById(R.id.settings_api_qr).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showQrDialog(ConfigHttpServer.Mode.API, v);
            }
        });
        btnAiProvider.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickAiProvider();
            }
        });
        btnAiKey.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showKeyInfo();
            }
        });
        btnAiQr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showQrDialog(ConfigHttpServer.Mode.AI, v);
            }
        });
        btnAutoLine.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SettingsRepository s = TvBoxApp.get().settings();
                s.setAutoLineSwitch(!s.autoLineSwitch());
                refresh();
            }
        });
        root.findViewById(R.id.settings_stats).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showStats();
            }
        });
        root.findViewById(R.id.settings_stats_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TvDialogs.confirm(getActivity(), "清空线路统计",
                        "确定清空全部线路健康统计吗？清空后播放管家需要重新积累数据。",
                        new TvDialogs.ConfirmListener() {
                            @Override
                            public void onConfirm() {
                                TvBoxApp.get().health().clearAll(new com.tvbox.android44.data.local.HealthStore.Callback() {
                                    @Override public void onComplete(boolean success) {
                                        if (isAdded() && getView() != null && !isHidden() && generation == viewGeneration) toast(getString(success
                                                ? R.string.health_cleared : R.string.health_clear_failed));
                                    }
                                });
                            }
                        });
            }
        });
        btnCheckToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SettingsRepository s = TvBoxApp.get().settings();
                s.setCheckUpdateOnStart(!s.checkUpdateOnStart());
                refresh();
            }
        });
        btnUpdateAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onUpdateActionClicked();
            }
        });
        btnIptv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editUrl("IPTV 地址", TvBoxApp.get().settings().iptvUrl(),
                        new OnUrlSaved() {
                            @Override
                            public void onSaved(String url) {
                                TvBoxApp.get().settings().setIptvUrl(url);
                                refresh();
                            }
                        });
            }
        });
        root.findViewById(R.id.settings_iptv_test).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testIptv();
            }
        });
        btnPlatform.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editUrl("平台直播服务地址", TvBoxApp.get().settings().platformLiveUrl(),
                        new OnUrlSaved() {
                            @Override
                            public void onSaved(String url) {
                                TvBoxApp.get().settings().setPlatformLiveUrl(url);
                                refresh();
                            }
                        });
            }
        });
        root.findViewById(R.id.settings_platform_test).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testPlatform();
            }
        });

        versionView.setText(getString(R.string.current_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
        if (downloadedApk != null) {
            btnUpdateAction.setText(R.string.install_update);
        }
        refresh();

        // 遥控器兜底：进入设置页时若无焦点则落到第一个可操作控件
        root.post(new Runnable() {
            @Override
            public void run() {
                View root = getView();
                if (root != null && root.findFocus() == null) {
                    View first = FocusUtils.firstFocusable(root);
                    if (first != null) {
                        first.requestFocus();
                    }
                }
            }
        });
        return root;
    }

    private void refresh() {
        SettingsRepository s = TvBoxApp.get().settings();
        btnTheme.setText(getString(R.string.theme_label, getString(SettingsRepository.THEME_CINEMA.equals(s.theme())
                ? R.string.theme_cinema : R.string.theme_default)));
        String font = s.fontScale();
        btnFont.setText(getString(R.string.font_label, getString(SettingsRepository.FONT_LARGE.equals(font) ? R.string.font_large
                : (SettingsRepository.FONT_XLARGE.equals(font) ? R.string.font_xlarge : R.string.font_normal))));
        btnApi.setText(getString(R.string.current_source_label, s.currentApi().name));
        AiProvider provider = s.aiProvider();
        btnAiProvider.setText(getString(R.string.ai_provider_label, provider == null ? getString(R.string.not_selected) : provider.name));
        btnAiKey.setText(getString(R.string.api_key_label, SettingsRepository.maskKey(s.aiApiKey())));
        btnAutoLine.setText(getString(R.string.auto_line_label, getString(s.autoLineSwitch() ? R.string.enabled : R.string.disabled)));
        btnCheckToggle.setText(getString(R.string.startup_update_label, getString(s.checkUpdateOnStart() ? R.string.enabled : R.string.disabled)));
        btnIptv.setText(getString(R.string.iptv_url_label, s.iptvUrl()));
        btnPlatform.setText(getString(R.string.platform_url_label, s.platformLiveUrl()));
    }

    // ===== 外观 =====

    private void pickTheme() {
        final String[] values = {SettingsRepository.THEME_DEFAULT, SettingsRepository.THEME_CINEMA};
        String current = TvBoxApp.get().settings().theme();
        int checked = SettingsRepository.THEME_CINEMA.equals(current) ? 1 : 0;
        TvDialogs.singleChoice(getActivity(), "主题", listOf("默认", "影院"), checked,
                new TvDialogs.ChoiceListener() {
                    @Override
                    public void onChoice(int index) {
                        TvBoxApp.get().settings().setTheme(values[index]);
                        recreateActivity();
                    }
                });
    }

    private void pickFont() {
        final String[] values = {SettingsRepository.FONT_NORMAL, SettingsRepository.FONT_LARGE,
                SettingsRepository.FONT_XLARGE};
        String current = TvBoxApp.get().settings().fontScale();
        int checked = SettingsRepository.FONT_LARGE.equals(current) ? 1
                : (SettingsRepository.FONT_XLARGE.equals(current) ? 2 : 0);
        TvDialogs.singleChoice(getActivity(), "字体", listOf("正常", "大", "超大"), checked,
                new TvDialogs.ChoiceListener() {
                    @Override
                    public void onChoice(int index) {
                        TvBoxApp.get().settings().setFontScale(values[index]);
                        recreateActivity();
                    }
                });
    }

    /** 主题/字体经 attachBaseContext 生效，需重建 Activity；设置页所在页签会被恢复。 */
    private void recreateActivity() {
        Activity activity = getActivity();
        if (activity != null) {
            activity.recreate();
        }
    }

    // ===== 视频源 =====

    private void pickApi() {
        final List<ApiLine> apis = TvBoxApp.get().settings().allApis();
        List<String> names = new ArrayList<String>();
        int checked = 0;
        String currentId = TvBoxApp.get().settings().currentApi().id;
        for (int i = 0; i < apis.size(); i++) {
            ApiLine a = apis.get(i);
            names.add(a.name + (a.builtIn ? "" : "（自定义）"));
            if (a.id.equals(currentId)) {
                checked = i;
            }
        }
        TvDialogs.singleChoice(getActivity(), "选择视频源", names, checked,
                new TvDialogs.ChoiceListener() {
                    @Override
                    public void onChoice(int index) {
                        ApiLine picked = apis.get(index);
                        if (picked.id.equals(TvBoxApp.get().settings().currentApi().id)) {
                            return;
                        }
                        TvBoxApp.get().settings().setCurrentApi(picked.id);
                        // 首页等页面持有旧数据，清缓存后重建保证立即生效
                        TvBoxApp.get().movies().invalidateAll();
                        refresh();
                        recreateActivity();
                    }
                });
    }

    private void manageCustomApis() {
        final List<ApiLine> customs = TvBoxApp.get().settings().customApis();
        if (customs.isEmpty()) {
            toast("暂无自定义接口，请先扫码添加接口");
            return;
        }
        List<String> names = new ArrayList<String>();
        for (ApiLine a : customs) {
            names.add(a.name + "\n" + a.baseUrl);
        }
        TvDialogs.singleChoice(getActivity(), "选择要删除的自定义接口", names, 0,
                new TvDialogs.ChoiceListener() {
                    @Override
                    public void onChoice(int index) {
                        final ApiLine target = customs.get(index);
                        boolean wasCurrent =
                                target.id.equals(TvBoxApp.get().settings().currentApi().id);
                        TvDialogs.confirm(getActivity(), "删除自定义接口",
                                "确定删除「" + target.name + "」吗？"
                                        + (wasCurrent ? "该接口当前正在使用，删除后回退默认内置源。" : ""),
                                new TvDialogs.ConfirmListener() {
                                    @Override
                                    public void onConfirm() {
                                        TvBoxApp.get().settings().removeCustomApi(target.id);
                                        if (wasCurrent) {
                                            TvBoxApp.get().movies().invalidateAll();
                                            recreateActivity();
                                        } else {
                                            refresh();
                                        }
                                    }
                                });
                    }
                });
    }

    // ===== AI =====

    private void pickAiProvider() {
        final List<AiProvider> providers = SettingsRepository.AI_PROVIDERS;
        List<String> names = new ArrayList<String>();
        int checked = -1;
        String currentId = TvBoxApp.get().settings().aiProviderId();
        for (int i = 0; i < providers.size(); i++) {
            names.add(providers.get(i).name);
            if (providers.get(i).id.equals(currentId)) {
                checked = i;
            }
        }
        final int finalChecked = Math.max(0, checked);
        TvDialogs.singleChoice(getActivity(), "AI 提供方", names, finalChecked,
                new TvDialogs.ChoiceListener() {
                    @Override
                    public void onChoice(int index) {
                        SettingsRepository s = TvBoxApp.get().settings();
                        AiProvider picked = providers.get(index);
                        s.setAiProvider(picked.id);
                        if (s.aiModel().isEmpty()) {
                            s.setAiModel(picked.defaultModel);
                        }
                        refresh();
                    }
                });
    }

    private void showKeyInfo() {
        String masked = SettingsRepository.maskKey(TvBoxApp.get().settings().aiApiKey());
        new AlertDialog.Builder(getActivity(), R.style.TvDialog)
                .setTitle("API Key")
                .setMessage("当前：" + masked + "\n\n出于安全考虑，电视端不提供明文输入和展示，"
                        + "请使用「扫码配置 AI」在手机端填写。")
                .setPositiveButton("知道了", null)
                .setNeutralButton("扫码配置", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (isAdded() && btnAiQr != null) {
                            showQrDialog(ConfigHttpServer.Mode.AI, btnAiQr);
                        }
                    }
                })
                .show();
    }

    // ===== 播放管家 =====

    private void showStats() {
        final int generation = viewGeneration;
        TvBoxApp.get().health().readStats(new com.tvbox.android44.data.local.HealthStore.StatsCallback() {
            @Override public void onStats(List<LineHealth> stats) {
                if (isAdded() && getView() != null && !isHidden() && generation == viewGeneration) renderStats(stats);
            }
        });
    }

    private void renderStats(List<LineHealth> stats) {
        StringBuilder sb = new StringBuilder();
        if (stats.isEmpty()) {
            sb.append("暂无线路统计，播放后自动积累。");
        } else {
            for (LineHealth h : stats) {
                sb.append(h.key).append('\n');
                sb.append("  成功 ").append(h.successCount)
                        .append(" · 失败 ").append(h.failCount)
                        .append(" · 卡顿 ").append(h.slowCount);
                double rate = h.successRate();
                if (rate >= 0) {
                    sb.append(" · 成功率 ").append((int) Math.round(rate * 100)).append('%');
                }
                if (h.cooldownUntil > System.currentTimeMillis()) {
                    sb.append("（冷却中）");
                }
                sb.append('\n');
            }
        }
        TextView text = new TextView(getActivity());
        text.setText(sb.toString().trim());
        text.setTextColor(0xFFF4F5F7);
        text.setTextSize(12);
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        text.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(getActivity());
        scroll.addView(text);
        new AlertDialog.Builder(getActivity(), R.style.TvDialog)
                .setTitle("线路统计（最近 30 天，最多 300 条）")
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .show();
    }

    // ===== OTA =====

    public void offerStartupUpdate(AppUpdate update) {
        pendingStartupUpdate = update;
        if (isAdded() && getView() != null && !isHidden()) {
            pendingStartupUpdate = null;
            confirmUpdate(update);
        }
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        if (pendingStartupUpdate != null) offerStartupUpdate(pendingStartupUpdate);
    }

    private void onUpdateActionClicked() {
        if (downloading) {
            toast("正在下载中，请稍候");
            return;
        }
        if (downloadedApk != null) {
            installApk();
            return;
        }
        checkUpdate();
    }

    private void checkUpdate() {
        if (updateHandle != null) updateHandle.cancel();
        setOtaStatus("正在检查更新…", true);
        btnUpdateAction.setText(R.string.checking_update);
        updateHandle = TvBoxApp.get().updates().check(new UpdateRepository.CheckCallback() {
            @Override
            public void onResult(Result<AppUpdate> result) {
                if (!isAdded() || getView() == null) {
                    return;
                }
                if (result.isSuccess() && result.data() != null) {
                    setOtaStatus(null, false);
                    btnUpdateAction.setText(R.string.check_update);
                    confirmUpdate(result.data());
                    return;
                }
                btnUpdateAction.setText(R.string.check_update);
                String message = result.asFailure() != null
                        ? result.asFailure().userMessage : "检查失败";
                // 手动检查失败显示明确错误（含“已是最新版本”提示），无需常驻状态行
                setOtaStatus(null, false);
                toast(message);
            }
        });
    }

    private void confirmUpdate(final AppUpdate update) {
        StringBuilder message = new StringBuilder();
        message.append("发现新版本 v").append(update.versionName)
                .append("（内部版本 ").append(update.versionCode).append("）\n");
        if (update.apkSize > 0) {
            message.append("大小：").append(Formatter.formatFileSize(getActivity(),
                    update.apkSize)).append('\n');
        }
        if (!update.changelog.isEmpty()) {
            message.append("更新内容：\n");
            for (String line : update.changelog) {
                message.append("· ").append(line).append('\n');
            }
        }
        message.append("\n是否下载并安装？");
        TvDialogs.confirm(getActivity(), "版本更新", message.toString(),
                new TvDialogs.ConfirmListener() {
                    @Override
                    public void onConfirm() {
                        downloadUpdate(update);
                    }
                });
    }

    private void downloadUpdate(final AppUpdate update) {
        downloading = true;
        btnUpdateAction.setText(R.string.downloading_update);
        otaProgress.setIndeterminate(update.apkSize <= 0);
        otaProgress.setProgress(0);
        otaProgress.setVisibility(View.VISIBLE);
        setOtaStatus("开始下载…", true);
        updateHandle = TvBoxApp.get().updates().download(update,
                new UpdateRepository.DownloadCallback() {
                    @Override
                    public void onProgress(long downloaded, long total) {
                        if (!isAdded() || getView() == null) {
                            return;
                        }
                        if (total > 0) {
                            int percent = (int) Math.min(100, 100.0 * ((double) downloaded / total));
                            otaProgress.setIndeterminate(false);
                            otaProgress.setProgress(percent);
                            setOtaStatus("下载中 " + percent + "%（"
                                    + Formatter.formatFileSize(getActivity(), downloaded) + " / "
                                    + Formatter.formatFileSize(getActivity(), total) + "）", true);
                        } else {
                            setOtaStatus("下载中…（"
                                    + Formatter.formatFileSize(getActivity(), downloaded) + "）", true);
                        }
                    }

                    @Override
                    public void onDone(Result<File> result) {
                        if (!isAdded() || getView() == null) {
                            return;
                        }
                        downloading = false;
                        otaProgress.setVisibility(View.GONE);
                        if (result.isSuccess() && result.data() != null) {
                            downloadedApk = result.data();
                            btnUpdateAction.setText(R.string.install_update);
                            setOtaStatus("下载完成，校验通过，等待安装", false);
                            installApk();
                            return;
                        }
                        btnUpdateAction.setText(R.string.check_update);
                        if (result.isCancelled()) {
                            setOtaStatus(null, false);
                        } else {
                            String message = result.asFailure() != null
                                    ? result.asFailure().userMessage : "下载失败";
                            setOtaStatus(message, false);
                        }
                    }
                });
    }

    private void installApk() {
        if (downloadedApk == null || !downloadedApk.exists()) {
            downloadedApk = null;
            btnUpdateAction.setText(R.string.check_update);
            toast("安装包不存在，请重新检查更新");
            return;
        }
        UpdateRepository updates = TvBoxApp.get().updates();
        if (!updates.canRequestInstalls()) {
            TvDialogs.confirm(getActivity(), "需要安装权限",
                    "Android 8.0 及以上需要授予「安装未知应用」权限。\n"
                            + "去系统设置授予权限后，回到这里点击「安装更新」。",
                    new TvDialogs.ConfirmListener() {
                        @Override
                        public void onConfirm() {
                            Intent intent = TvBoxApp.get().updates()
                                    .buildInstallsPermissionIntent();
                            if (intent != null) {
                                try {
                                    startActivity(intent);
                                } catch (ActivityNotFoundException e) {
                                    toast("无法打开系统设置，请手动前往应用详情授权");
                                }
                            }
                        }
                    });
            return;
        }
        try {
            startActivity(updates.buildInstallIntent(downloadedApk));
        } catch (ActivityNotFoundException e) {
            toast("系统没有可用的安装器");
        } catch (SecurityException e) {
            toast("系统拒绝安装，请检查安装权限");
        }
    }

    private void setOtaStatus(String text, boolean ongoing) {
        if (text == null) {
            otaStatus.setVisibility(View.GONE);
            return;
        }
        otaStatus.setText(text);
        otaStatus.setVisibility(View.VISIBLE);
    }

    // ===== 扩展地址 =====

    private interface OnUrlSaved {
        void onSaved(String url);
    }

    private void editUrl(String title, String current, final OnUrlSaved saver) {
        showInput(title, "http:// 或 https:// 开头", current, null, null,
                new OnInputSubmit() {
                    @Override
                    public void onSubmit(String value, String unused) {
                        if (!SettingsRepository.isValidBaseUrl(value)) {
                            toast("地址不合法：必须以 http:// 或 https:// 开头");
                            return;
                        }
                        saver.onSaved(value.trim());
                        toast("已保存");
                    }
                });
    }

    private void testIptv() {
        toast("正在测试 IPTV 地址…");
        TvBoxApp.get().live().load(true, new com.tvbox.android44.data.repository.LiveRepository.Callback() {
            @Override
            public void onResult(Result<List<com.tvbox.android44.domain.model.LiveChannelGroup>> result) {
                if (!isAdded() || getView() == null) {
                    return;
                }
                if (result.isSuccess() && result.data() != null) {
                    int channels = 0;
                    for (com.tvbox.android44.domain.model.LiveChannelGroup g : result.data()) {
                        channels += g.channels.size();
                    }
                    toast("IPTV 地址可用：" + result.data().size() + " 个分组 / "
                            + channels + " 个频道");
                } else {
                    toast("测试失败：" + (result.asFailure() != null
                            ? result.asFailure().userMessage : "未知错误"));
                }
            }
        });
    }

    private void testPlatform() {
        toast("正在测试平台直播服务…");
        TvBoxApp.get().platformLive().sites(
                new com.tvbox.android44.data.repository.PlatformLiveRepository.Callback<List<com.tvbox.android44.domain.model.PlatformLive.Site>>() {
                    @Override
                    public void onResult(Result<List<com.tvbox.android44.domain.model.PlatformLive.Site>> result) {
                        if (!isAdded() || getView() == null) {
                            return;
                        }
                        if (result.isSuccess() && result.data() != null) {
                            toast("服务可用：" + result.data().size() + " 个平台");
                        } else {
                            toast("测试失败：" + (result.asFailure() != null
                                    ? result.asFailure().userMessage : "未知错误"));
                        }
                    }
                });
    }

    // ===== 扫码配置（文档 09 §5） =====

    private void showQrDialog(final ConfigHttpServer.Mode mode, final View returnTo) {
        stopConfigServer();
        final List<String> ips = localIpv4s();
        if (ips.isEmpty()) { toast(getString(R.string.qr_no_lan)); return; }
        if (ips.size() == 1) showQrAtAddress(mode, returnTo, ips.get(0));
        else TvDialogs.singleChoice(getActivity(), getString(R.string.qr_choose_network), ips, 0,
                new TvDialogs.ChoiceListener() {
                    @Override public void onChoice(int index) { showQrAtAddress(mode, returnTo, ips.get(index)); }
                });
    }

    private void showQrAtAddress(final ConfigHttpServer.Mode mode, View returnTo, String ip) {
        if (!isAdded() || getView() == null || isHidden()) return;
        stopConfigServer();
        qrReturnTo = returnTo;
        try {
            configServer = new ConfigHttpServer(mode, InetAddress.getByName(ip),
                    new ConfigHttpServer.SubmitListener() {
                        @Override public boolean onSubmit(ConfigHttpServer session, java.util.Map<String, String> fields) {
                            return onConfigSubmitted(session, fields);
                        }
                    }, new ConfigHttpServer.CloseListener() {
                        @Override public void onClosed(ConfigHttpServer session, ConfigHttpServer.CloseReason reason) {
                            if (configServer != session) return;
                            if (qrDialog != null && qrDialog.isShowing()) qrDialog.dismiss();
                            stopConfigServer();
                            if (isAdded() && getView() != null && !isHidden()) {
                                if (reason == ConfigHttpServer.CloseReason.EXPIRED) toast(getString(R.string.qr_expired));
                                else if (reason == ConfigHttpServer.CloseReason.FAILURES) toast(getString(R.string.qr_failures));
                            }
                        }
                    });
        } catch (java.net.UnknownHostException | IllegalArgumentException error) {
            toast(getString(R.string.qr_no_lan));
            return;
        }
        if (!configServer.start()) {
            toast(getString(R.string.qr_start_failed));
            configServer = null;
            return;
        }
        String url = "http://" + ip + ":" + configServer.port() + "/" + configServer.token();
        View view = LayoutInflater.from(getActivity()).inflate(R.layout.dialog_qr_config, null);
        TextView urlView = view.findViewById(R.id.qr_url);
        final TextView hintView = view.findViewById(R.id.qr_hint);
        com.tvbox.android44.common.ui.QrImageView image = view.findViewById(R.id.qr_image);
        urlView.setText(url);
        hintView.setText(R.string.qr_session_hint);
        image.setCode(url, new com.tvbox.android44.common.ui.QrImageView.ReadyListener() {
            @Override public void onReady(boolean success) {
                hintView.setText(success ? R.string.qr_session_hint : R.string.qr_manual_hint);
            }
        });
        qrDialog = new AlertDialog.Builder(getActivity(), R.style.TvDialog)
                .setTitle(mode == ConfigHttpServer.Mode.AI ? R.string.qr_ai_title : R.string.qr_api_title)
                .setView(view).setCancelable(true).create();
        qrDialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface dialog) {
                stopConfigServer();
                if (qrReturnTo != null && androidx.core.view.ViewCompat.isAttachedToWindow(qrReturnTo)) {
                    qrReturnTo.requestFocus();
                }
                qrReturnTo = null;
            }
        });
        view.findViewById(R.id.qr_close).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { qrDialog.dismiss(); }
        });
        qrDialog.show();
        image.requestFocus();
    }

    /** 手机端提交回调（主线程）：保存后关会话；返回 false 会向手机端提示保存失败。 */
    private boolean onConfigSubmitted(ConfigHttpServer session, java.util.Map<String, String> fields) {
        if (configServer != session || !session.isRunning() || !isAdded() || getView() == null || isHidden()) return false;
        SettingsRepository s = TvBoxApp.get().settings();
        boolean saved;
        if (session.mode() == ConfigHttpServer.Mode.AI) {
            saved = s.saveAiConfiguration(orEmpty(fields.get("provider")),
                    orEmpty(fields.get("model")), orEmpty(fields.get("apiKey")));
        } else {
            String name = orEmpty(fields.get("name"));
            String url = orEmpty(fields.get("baseUrl"));
            if (name.isEmpty() || !SettingsRepository.isValidBaseUrl(url)) {
                return false;
            }
            s.addCustomApi(name, url);
            saved = true;
        }
        if (saved) {
            refresh();
            toast("配置已保存");
            // The server responds to the phone before its close callback dismisses the dialog.
        }
        return saved;
    }

    private void stopConfigServer() {
        if (configServer != null) {
            configServer.stop();
            configServer = null;
        }
    }

    /** 枚举非 loopback、已启用的 IPv4 地址（文档 09 §5：多地址展示候选）。 */
    private List<String> localIpv4s() {
        List<String> ips = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress() && !addr.isLoopbackAddress()
                            && !addr.isLinkLocalAddress()) {
                        ips.add(addr.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
            // 无权限或枚举失败：按无地址处理
        }
        return ips;
    }

    // ===== 通用输入弹窗 =====

    private interface OnInputSubmit {
        void onSubmit(String value1, String value2);
    }

    private void showInput(String title, String hint1, String init1,
                           @Nullable String hint2, @Nullable String init2,
                           final OnInputSubmit listener) {
        View view = LayoutInflater.from(getActivity()).inflate(R.layout.dialog_input, null);
        final EditText field1 = view.findViewById(R.id.dialog_input1);
        final EditText field2 = view.findViewById(R.id.dialog_input2);
        field1.setHint(hint1);
        if (init1 != null) {
            field1.setText(init1);
        }
        boolean twoFields = hint2 != null;
        if (twoFields) {
            field2.setHint(hint2);
            field2.setVisibility(View.VISIBLE);
            if (init2 != null) {
                field2.setText(init2);
            }
        }
        final AlertDialog dialog = new AlertDialog.Builder(getActivity(), R.style.TvDialog)
                .setTitle(title)
                .setView(view)
                .create();
        view.findViewById(R.id.dialog_input_ok).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String v1 = field1.getText().toString().trim();
                String v2 = field2.getVisibility() == View.VISIBLE
                        ? field2.getText().toString().trim() : null;
                dialog.dismiss();
                listener.onSubmit(v1, v2);
            }
        });
        view.findViewById(R.id.dialog_input_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        dialog.show();
        field1.requestFocus();
    }

    // ===== 小工具 =====

    private static List<String> listOf(String... values) {
        List<String> list = new ArrayList<String>();
        for (String v : values) {
            list.add(v);
        }
        return list;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private void toast(String message) {
        if (isAdded()) {
            Toast.makeText(getActivity(), message, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onStop() {
        // Activity 不可见时必须释放配置会话（文档 09 §5）
        stopConfigServer();
        if (qrDialog != null && qrDialog.isShowing()) {
            qrDialog.dismiss();
        }
        cancelUpdateWork();
        super.onStop();
    }

    @Override public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            stopConfigServer();
            if (qrDialog != null && qrDialog.isShowing()) qrDialog.dismiss();
            cancelUpdateWork();
        } else if (pendingStartupUpdate != null) offerStartupUpdate(pendingStartupUpdate);
    }

    private void cancelUpdateWork() {
        if (updateHandle != null) updateHandle.cancel();
        updateHandle = null;
        downloading = false;
        if (getView() != null) {
            otaProgress.setVisibility(View.GONE);
            setOtaStatus(null, false);
            btnUpdateAction.setText(downloadedApk == null ? R.string.check_update : R.string.install_update);
        }
    }

    @Override
    public void onDestroyView() {
        ++viewGeneration;
        if (updateHandle != null) {
            updateHandle.cancel();
        }
        downloading = false;
        super.onDestroyView();
    }
}
