package com.tvbox.android44.domain.parser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tvbox.android44.domain.model.AppUpdate;

/**
 * OTA 更新清单解析：去 BOM、忽略未知字段；关键字段缺失报告清单错误。
 */
public final class OtaManifestParser {

    public static final class ManifestException extends Exception {
        public ManifestException(String message) {
            super(message);
        }
    }

    private OtaManifestParser() {
    }

    public static AppUpdate parse(String json) throws ManifestException {
        if (json == null) {
            throw new ManifestException("清单为空");
        }
        String s = json;
        if (s.startsWith("\uFEFF")) {
            s = s.substring(1);
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(s).getAsJsonObject();
        } catch (Exception e) {
            throw new ManifestException("清单不是合法 JSON");
        }
        AppUpdate u = new AppUpdate();
        if (!root.has("versionCode")) {
            throw new ManifestException("缺少 versionCode");
        }
        try {
            u.versionCode = root.get("versionCode").getAsInt();
        } catch (Exception e) {
            throw new ManifestException("versionCode 非法");
        }
        u.versionName = optString(root, "versionName");
        if (u.versionName.isEmpty()) {
            throw new ManifestException("缺少 versionName");
        }
        u.apkUrl = optString(root, "apkUrl");
        if (u.apkUrl.isEmpty()) {
            throw new ManifestException("缺少 apkUrl");
        }
        if (!u.apkUrl.startsWith("http://") && !u.apkUrl.startsWith("https://")) {
            throw new ManifestException("apkUrl 非法");
        }
        u.apkSha256 = optString(root, "apkSha256");
        if (root.has("apkSize")) {
            try {
                u.apkSize = root.get("apkSize").getAsLong();
            } catch (Exception ignored) {
                u.apkSize = 0;
            }
        }
        u.force = root.has("force") && root.get("force").getAsBoolean();
        if (root.has("changelog") && root.get("changelog").isJsonArray()) {
            for (com.google.gson.JsonElement el : root.getAsJsonArray("changelog")) {
                if (el.isJsonPrimitive()) {
                    u.changelog.add(el.getAsString());
                }
            }
        }
        return u;
    }

    private static String optString(JsonObject o, String key) {
        if (o.has(key) && o.get(key).isJsonPrimitive()) {
            return o.get(key).getAsString();
        }
        return "";
    }
}
