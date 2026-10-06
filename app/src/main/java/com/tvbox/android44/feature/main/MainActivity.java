package com.tvbox.android44.feature.main;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.tvbox.android44.R;
import com.tvbox.android44.BuildConfig;
import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.Result;
import com.tvbox.android44.common.TvDialogs;
import com.tvbox.android44.data.repository.UpdateRepository;
import com.tvbox.android44.domain.model.AppUpdate;
import com.tvbox.android44.common.FocusUtils;
import com.tvbox.android44.common.PageFocusState;
import com.tvbox.android44.common.BaseActivity;
import com.tvbox.android44.feature.history.HistoryFragment;
import com.tvbox.android44.feature.home.HomeFragment;
import com.tvbox.android44.feature.recommend.RecommendFragment;
import com.tvbox.android44.feature.search.SearchFragment;
import com.tvbox.android44.feature.settings.SettingsFragment;

import java.util.HashMap;
import java.util.Map;

/**
 * 主导航容器：顶部可用入口 + 内容区。
 * 默认内容为首页（热播/分类/海报网格）；数字键 1~6 快速导航；
 * 返回键逐级回退到首页；show/hide 保留各页状态与焦点。
 */
public class MainActivity extends BaseActivity {

    /** 内容页：HOME 为默认首页内容，其余对应顶部六个入口。 */
    public enum Tab {
        HOME, HISTORY, SEARCH, RECOMMEND, LIVE_TV, PLATFORM_LIVE, SETTINGS
    }

    private static final String STATE_TAB = "main_tab";

    private final Map<Tab, Fragment> fragments = new HashMap<Tab, Fragment>();
    private final Map<Tab, Bundle> focusStates = new HashMap<Tab, Bundle>();
    private Tab current = Tab.HOME;
    private long lastBackAt;
    private UpdateRepository.CheckHandle startupCheck;
    private long startupToken;
    private androidx.appcompat.app.AlertDialog startupPrompt;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindNav(R.id.nav_history, Tab.HISTORY);
        bindNav(R.id.nav_search, Tab.SEARCH);
        bindNav(R.id.nav_recommend, Tab.RECOMMEND);
        bindNav(R.id.nav_settings, Tab.SETTINGS);
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener(
                new android.view.ViewTreeObserver.OnGlobalFocusChangeListener() {
                    public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                        Fragment page = fragments.get(current);
                        if (page != null && page.getView() != null && page.getView().findFocus() == newFocus) {
                            Bundle bookmark = PageFocusState.capture(page.getView(), newFocus);
                            if (!bookmark.isEmpty()) focusStates.put(current, bookmark);
                        }
                    }
                });

        Tab restore = Tab.HOME;
        if (savedInstanceState != null && savedInstanceState.containsKey(STATE_TAB)) {
            for (Tab tab : Tab.values()) {
                Bundle bookmark = savedInstanceState.getBundle("focus_" + tab.name());
                if (bookmark != null) focusStates.put(tab, bookmark);
            }
            try {
                restore = Tab.valueOf(savedInstanceState.getString(STATE_TAB));
            } catch (IllegalArgumentException ignored) {
            }
        }
        selectTab(restore, false);
    }

    @Override protected void onStart() {
        super.onStart();
        final TvBoxApp app = TvBoxApp.get();
        final long token = app.startupUpdates().begin(app.settings().checkUpdateOnStart(),
                app.updates().manifestUrl(), android.os.SystemClock.elapsedRealtime());
        if (token == 0) return;
        startupToken = token;
        startupCheck = app.updates().check(new UpdateRepository.CheckCallback() {
            @Override public void onResult(Result<AppUpdate> result) {
                if (startupToken != token || isFinishing() || isDestroyed()) return;
                app.startupUpdates().complete(token, android.os.SystemClock.elapsedRealtime());
                startupToken = 0;
                startupCheck = null;
                final AppUpdate update = result.data();
                if (!result.isSuccess() || update == null || update.versionCode <= BuildConfig.VERSION_CODE
                        || !app.startupUpdates().claimPrompt(update.versionCode)) return;
                startupPrompt = TvDialogs.confirm(MainActivity.this, getString(R.string.update_available_title),
                        getString(R.string.startup_update_message, update.versionName), new TvDialogs.ConfirmListener() {
                            @Override public void onConfirm() {
                                selectTab(Tab.SETTINGS, false);
                                getSupportFragmentManager().executePendingTransactions();
                                Fragment page = fragments.get(Tab.SETTINGS);
                                if (page instanceof SettingsFragment) ((SettingsFragment) page).offerStartupUpdate(update);
                            }
                        });
            }
        });
    }

    @Override protected void onStop() {
        if (startupCheck != null) startupCheck.cancel();
        startupCheck = null;
        TvBoxApp.get().startupUpdates().cancel(startupToken);
        startupToken = 0;
        if (startupPrompt != null) startupPrompt.dismiss();
        startupPrompt = null;
        super.onStop();
    }

    private void bindNav(int viewId, final Tab tab) {
        TextView tv = findViewById(viewId);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(tab, true);
            }
        });
        tv.setTag(tab);
    }

    public void selectTab(Tab tab, boolean fromUser) {
        if (tab == Tab.LIVE_TV || tab == Tab.PLATFORM_LIVE) {
            // 直播功能暂时隐藏，防止恢复状态、数字键或其他调用绕过界面入口。
            selectTab(Tab.HOME, false);
            return;
        }
        current = tab;
        updateNavHighlight(tab);
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        // 隐藏全部现存 fragment（含 recreate 后由 FragmentManager 恢复、
        // 不在 fragments map 中的实例），避免新旧实例叠加显示
        for (Fragment existing : fm.getFragments()) {
            ft.hide(existing);
        }
        Fragment f = fragments.get(tab);
        if (f == null) {
            // recreate 后优先复用按 tag 恢复的 fragment，防止重复 add
            f = fm.findFragmentByTag(tab.name());
        }
        if (f == null) {
            f = createTab(tab);
            ft.add(R.id.content, f, tab.name());
        } else {
            ft.show(f);
        }
        fragments.put(tab, f);
        final Fragment shown = f;
        final Tab selected = tab;
        ft.runOnCommit(new Runnable() {
            public void run() {
                if (current != selected || shown.getView() == null) return;
                Bundle bookmark = focusStates.get(selected);
                if (shown instanceof PageFocusState.Owner && bookmark != null) {
                    ((PageFocusState.Owner) shown).restorePageFocus(bookmark);
                } else if (!PageFocusState.restore(shown.getView(), bookmark) && fromUser) {
                    View first = FocusUtils.firstFocusable(shown.getView());
                    if (first != null) first.requestFocus();
                }
            }
        });
        ft.commitAllowingStateLoss();
    }

    private Fragment createTab(Tab tab) {
        switch (tab) {
            case SEARCH:
                return new SearchFragment();
            case RECOMMEND:
                return new RecommendFragment();
            case SETTINGS:
                return new SettingsFragment();
            case HISTORY:
                return new HistoryFragment();
            default:
                return new HomeFragment();
        }
    }

    private void updateNavHighlight(Tab tab) {
        int[] ids = {R.id.nav_history, R.id.nav_search, R.id.nav_recommend,
                R.id.nav_settings};
        for (int id : ids) {
            TextView tv = findViewById(id);
            boolean selected = tv.getTag() == tab;
            tv.setSelected(selected);
            tv.setTextColor(selected ? getResources().getColor(R.color.accent)
                    : getResources().getColor(R.color.text_primary));
        }
    }

    /** 从推荐页携带查询词跳转搜索。 */
    public void switchToSearchWithQuery(String query) {
        selectTab(Tab.SEARCH, false);
        getSupportFragmentManager().executePendingTransactions();
        Fragment f = fragments.get(Tab.SEARCH);
        if (f instanceof SearchFragment) {
            ((SearchFragment) f).presetQuery(query);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_TAB, current.name());
        for (Map.Entry<Tab, Bundle> entry : focusStates.entrySet()) {
            outState.putBundle("focus_" + entry.getKey().name(), entry.getValue());
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            Tab t = digitToTab(event.getKeyCode());
            if (t != null) {
                selectTab(t, true);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private static Tab digitToTab(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_1:
            case KeyEvent.KEYCODE_NUMPAD_1:
                return Tab.HISTORY;
            case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_NUMPAD_2:
                return Tab.SEARCH;
            case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_NUMPAD_3:
                return Tab.RECOMMEND;
            case KeyEvent.KEYCODE_6:
            case KeyEvent.KEYCODE_NUMPAD_6:
                return Tab.SETTINGS;
            default:
                return null;
        }
    }

    @Override
    public void onBackPressed() {
        if (current != Tab.HOME) {
            selectTab(Tab.HOME, true);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackAt < 2000) {
            super.onBackPressed();
        } else {
            lastBackAt = now;
            Toast.makeText(this, R.string.back_exit_tip, Toast.LENGTH_SHORT).show();
        }
    }
}
