package com.tvbox.android44.domain.model;

/** AI 提供方（OpenAI Chat Completions 兼容）。 */
public class AiProvider {
    public final String id;
    public final String name;
    public final String apiBase;
    public final String defaultModel;

    public AiProvider(String id, String name, String apiBase, String defaultModel) {
        this.id = id;
        this.name = name;
        this.apiBase = apiBase;
        this.defaultModel = defaultModel;
    }

    public static boolean validApiKey(String key) {
        if (key == null || key.trim().isEmpty()) return false;
        String value = key.trim();
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) <= 32 || value.charAt(i) >= 127) return false;
        }
        return true;
    }
}
