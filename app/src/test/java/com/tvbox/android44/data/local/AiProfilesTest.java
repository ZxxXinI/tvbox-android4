package com.tvbox.android44.data.local;

import android.content.Context;
import android.content.SharedPreferences;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {16, 19, 23, 28})
public class AiProfilesTest {
    private Context context;
    private SharedPreferences prefs;
    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
    }
    @Test public void upgradeMigratesLegacyOnlyToItsOriginalProvider() {
        prefs.edit().putString("ai_provider", "agnes").putString("ai_model", "old-model")
                .putString("ai_key", "old-test-key").commit();
        SettingsRepository settings = new SettingsRepository(context);
        assertEquals("agnes", settings.aiProviderId()); assertEquals("old-test-key", settings.aiApiKey());
        settings.setAiProvider("deepseek");
        assertEquals("", settings.aiApiKey()); assertEquals("", settings.aiModel());
        settings.setAiProvider("agnes");
        assertEquals("old-model", settings.aiModel()); assertEquals("old-test-key", settings.aiApiKey());
        assertEquals("old-test-key", new SettingsRepository(context).aiApiKey());
    }
    @Test public void completeProfilesSaveAndSwitchIndependently() {
        SettingsRepository settings = new SettingsRepository(context);
        assertTrue(settings.saveAiConfiguration("deepseek", "model-d", "key-d"));
        assertTrue(settings.saveAiConfiguration("mimo", "model-m", "key-m"));
        settings.setAiProvider("deepseek");
        assertEquals("key-d", settings.aiConfiguration().key); assertEquals("model-d", settings.aiModel());
        settings.setAiProvider("mimo");
        assertEquals("key-m", settings.aiApiKey()); assertEquals("model-m", settings.aiModel());
        assertFalse(settings.saveAiConfiguration("unknown", "model", "key"));
        assertFalse(settings.saveAiConfiguration("qwen", "", "key"));
        assertFalse(settings.saveAiConfiguration("qwen", "model", "bad\nprivate-key-marker"));
        assertEquals("mimo", settings.aiProviderId());
    }
}
