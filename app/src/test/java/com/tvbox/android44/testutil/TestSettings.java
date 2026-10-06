package com.tvbox.android44.testutil;

import com.tvbox.android44.data.local.SettingsRepository;
import com.tvbox.android44.domain.model.ApiLine;
import org.robolectric.RuntimeEnvironment;
import java.util.*;

/** Local mock sources only; no requests to production built-in sources. */
public class TestSettings extends SettingsRepository {
    private final List<ApiLine> sources;
    public TestSettings(ApiLine... sources) {
        super(RuntimeEnvironment.getApplication());
        this.sources = Arrays.asList(sources);
    }
    @Override public List<ApiLine> allApis() { return new ArrayList<>(sources); }
    @Override public ApiLine currentApi() { return sources.get(0); }
}
