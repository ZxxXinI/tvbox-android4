package com.tvbox.android44.data.local;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tvbox.android44.BuildConfig;
import com.tvbox.android44.domain.model.AiProvider;
import com.tvbox.android44.domain.model.ApiLine;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 设置仓库：每个 key 有安全默认值；未知枚举回退默认；
 * 内置来源只读，自定义来源单独保存、可删除。
 */
public class SettingsRepository {

    /** AI 提供方（可配置数据，不在页面写分支）。 */
    public static final List<AiProvider> AI_PROVIDERS = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList(
                    new AiProvider("deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-flash"),
                    new AiProvider("qwen", "Qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
                    new AiProvider("glm", "GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-5.3"),
                    new AiProvider("kimi", "KIMI", "https://api.moonshot.cn/v1", "kimi-k2.5"),
                    new AiProvider("mimo", "MIMO", "https://api.xiaomimimo.com/v1", "mimo-v2.5")));

    private static final List<AiProvider> LEGACY_AI_PROVIDERS = java.util.Arrays.asList(
            new AiProvider("agnes", "Agnes", "https://apihub.agnes-ai.com/v1/chat/completions", "agnes-2.5-flash"),
            new AiProvider("siliconflow", "SiliconFlow", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct"));

    public static AiProvider findAiProvider(String id) {
        for (AiProvider p : AI_PROVIDERS) if (p.id.equals(id)) return p;
        for (AiProvider p : LEGACY_AI_PROVIDERS) if (p.id.equals(id)) return p;
        return null;
    }

    public static final String THEME_DEFAULT = "default";
    public static final String THEME_CINEMA = "cinema";
    public static final String FONT_NORMAL = "normal";
    public static final String FONT_LARGE = "large";
    public static final String FONT_XLARGE = "xlarge";

    private static final String PREFS = "settings";
    private static final String KEY_CURRENT_API = "current_api_id";
    private static final String KEY_CUSTOM_APIS = "custom_apis";
    private static final String KEY_THEME = "theme";
    private static final String KEY_FONT = "font";
    private static final String KEY_AI_PROVIDER = "ai_provider";
    private static final String KEY_AI_MODEL = "ai_model";
    private static final String KEY_AI_KEY = "ai_key";
    private static final String KEY_AI_MIGRATED = "ai_profiles_migrated";
    private static final String MODEL_PREFIX = "ai_profile_model_";
    private static final String KEY_PREFIX = "ai_profile_key_";
    private static final String KEY_AUTO_LINE = "auto_line_switch";
    private static final String KEY_CHECK_UPDATE = "check_update_on_start";
    private static final String KEY_IPTV_URL = "iptv_url";
    private static final String KEY_PLATFORM_URL = "platform_live_url";

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();
    private final Type apiListType = new TypeToken<List<ApiLine>>() {
    }.getType();

    public SettingsRepository(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        migrateAiProfile();
    }

    private synchronized void migrateAiProfile() {
        if (prefs.getBoolean(KEY_AI_MIGRATED, false)) return;
        String id = prefs.getString(KEY_AI_PROVIDER, "");
        SharedPreferences.Editor edit = prefs.edit();
        if (findAiProvider(id) != null) {
            if (!prefs.contains(MODEL_PREFIX + id)) edit.putString(MODEL_PREFIX + id, prefs.getString(KEY_AI_MODEL, ""));
            if (!prefs.contains(KEY_PREFIX + id)) edit.putString(KEY_PREFIX + id, prefs.getString(KEY_AI_KEY, ""));
        }
        edit.putBoolean(KEY_AI_MIGRATED, true).commit();
    }

    // ===== 视频来源 =====

    /** 全部可用来源：内置在前 + 自定义在后。 */
    public List<ApiLine> allApis() {
        List<ApiLine> all = BuiltInSources.all();
        all.addAll(customApis());
        return all;
    }

    public List<ApiLine> customApis() {
        String json = prefs.getString(KEY_CUSTOM_APIS, "[]");
        try {
            List<ApiLine> list = gson.fromJson(json, apiListType);
            if (list != null) {
                return list;
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<ApiLine>();
    }

    /** 当前主来源；未知 ID 回退第一个内置来源。 */
    public ApiLine currentApi() {
        String id = prefs.getString(KEY_CURRENT_API, "liangzi");
        for (ApiLine l : allApis()) {
            if (l.id.equals(id)) {
                return l;
            }
        }
        return BuiltInSources.all().get(0);
    }

    public void setCurrentApi(String id) {
        for (ApiLine l : allApis()) {
            if (l.id.equals(id)) {
                prefs.edit().putString(KEY_CURRENT_API, id).apply();
                return;
            }
        }
    }

    /** 新增自定义来源：随机稳定 ID；URL 规范化；名称去重靠 ID 不靠名称。 */
    public ApiLine addCustomApi(String name, String baseUrl) {
        String normalUrl = normalizeBaseUrl(baseUrl);
        ApiLine line = new ApiLine("custom-" + UUID.randomUUID().toString().substring(0, 8),
                name.trim(), normalUrl, false);
        List<ApiLine> customs = customApis();
        customs.add(line);
        prefs.edit().putString(KEY_CUSTOM_APIS, gson.toJson(customs)).apply();
        return line;
    }

    public void removeCustomApi(String id) {
        List<ApiLine> customs = customApis();
        List<ApiLine> next = new ArrayList<ApiLine>();
        for (ApiLine l : customs) {
            if (!l.id.equals(id)) {
                next.add(l);
            }
        }
        prefs.edit().putString(KEY_CUSTOM_APIS, gson.toJson(next)).apply();
        if (currentApi().id.equals(id)) {
            setCurrentApi("liangzi");
        }
    }

    /** URL 规范化：trim、补全末尾 /、限制 http/https。 */
    public static String normalizeBaseUrl(String raw) {
        if (raw == null) {
            return "";
        }
        okhttp3.HttpUrl url = okhttp3.HttpUrl.parse(raw.trim());
        if (url == null) return "";
        String path = url.encodedPath();
        if (!path.endsWith("/")) path += "/";
        return url.newBuilder().encodedPath(path).fragment(null).build().toString();
    }

    /** URL 基本合法性（保存前校验用）。 */
    public static boolean isValidBaseUrl(String raw) {
        return !normalizeBaseUrl(raw).isEmpty();
    }

    // ===== 主题 / 字体 =====

    public String theme() {
        String t = prefs.getString(KEY_THEME, THEME_DEFAULT);
        return THEME_CINEMA.equals(t) ? THEME_CINEMA : THEME_DEFAULT;
    }

    public void setTheme(String theme) {
        prefs.edit().putString(KEY_THEME, THEME_CINEMA.equals(theme) ? THEME_CINEMA : THEME_DEFAULT)
                .apply();
    }

    public String fontScale() {
        String f = prefs.getString(KEY_FONT, FONT_NORMAL);
        if (FONT_LARGE.equals(f) || FONT_XLARGE.equals(f)) {
            return f;
        }
        return FONT_NORMAL;
    }

    public void setFontScale(String font) {
        String f = FONT_LARGE.equals(font) ? FONT_LARGE
                : (FONT_XLARGE.equals(font) ? FONT_XLARGE : FONT_NORMAL);
        prefs.edit().putString(KEY_FONT, f).apply();
    }

    // ===== AI =====

    public synchronized String aiProviderId() {
        String id = prefs.getString(KEY_AI_PROVIDER, "");
        return findAiProvider(id) == null ? "" : id;
    }

    public synchronized void setAiProvider(String id) {
        prefs.edit().putString(KEY_AI_PROVIDER, findAiProvider(id) == null ? "" : id).apply();
    }

    public synchronized AiProvider aiProvider() {
        return findAiProvider(aiProviderId());
    }

    public synchronized String aiModel() {
        return prefs.getString(MODEL_PREFIX + aiProviderId(), "");
    }

    public synchronized void setAiModel(String model) {
        if (!aiProviderId().isEmpty()) prefs.edit().putString(MODEL_PREFIX + aiProviderId(),
                model == null ? "" : model.trim()).apply();
    }

    public synchronized String aiApiKey() {
        return prefs.getString(KEY_PREFIX + aiProviderId(), "");
    }

    public synchronized void setAiApiKey(String key) {
        if (!aiProviderId().isEmpty()) prefs.edit().putString(KEY_PREFIX + aiProviderId(),
                key == null ? "" : key.trim()).apply();
    }

    /** Save the complete configuration once; a model-list request never invokes this. */
    public synchronized boolean saveAiConfiguration(String id, String model, String key) {
        if (findAiProvider(id) == null || model == null || model.trim().isEmpty()
                || !AiProvider.validApiKey(key)) return false;
        return prefs.edit().putString(KEY_AI_PROVIDER, id)
                .putString(MODEL_PREFIX + id, model.trim())
                .putString(KEY_PREFIX + id, key.trim()).commit();
    }

    public static final class AiConfiguration {
        public final AiProvider provider;
        public final String model;
        public final String key;
        AiConfiguration(AiProvider provider, String model, String key) {
            this.provider = provider; this.model = model; this.key = key;
        }
    }

    public synchronized AiConfiguration aiConfiguration() {
        // Use the public accessors to retain compatibility with injected test settings.
        return new AiConfiguration(aiProvider(), aiModel(), aiApiKey());
    }

    /** 掩码展示。 */
    public static String maskKey(String key) {
        if (key == null || key.isEmpty()) {
            return "未配置";
        }
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    // ===== 播放管家 / 更新 =====

    public boolean autoLineSwitch() {
        return prefs.getBoolean(KEY_AUTO_LINE, true);
    }

    public void setAutoLineSwitch(boolean value) {
        prefs.edit().putBoolean(KEY_AUTO_LINE, value).apply();
    }

    public boolean checkUpdateOnStart() {
        return prefs.getBoolean(KEY_CHECK_UPDATE, true);
    }

    public void setCheckUpdateOnStart(boolean value) {
        prefs.edit().putBoolean(KEY_CHECK_UPDATE, value).apply();
    }

    // ===== 扩展地址 =====

    public String iptvUrl() {
        return prefs.getString(KEY_IPTV_URL, BuildConfig.IPTV_SOURCE_URL);
    }

    public void setIptvUrl(String url) {
        prefs.edit().putString(KEY_IPTV_URL, url == null ? "" : url.trim()).apply();
    }

    public String platformLiveUrl() {
        return prefs.getString(KEY_PLATFORM_URL, BuildConfig.PLATFORM_LIVE_URL);
    }

    public void setPlatformLiveUrl(String url) {
        prefs.edit().putString(KEY_PLATFORM_URL, url == null ? "" : url.trim()).apply();
    }
}
