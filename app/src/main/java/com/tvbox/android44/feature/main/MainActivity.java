package com.tvbox.android44.feature.main;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.tvbox.android44.R;
import com.tvbox.android44.common.FocusUtils;
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
public class MainActivity extends AppCompatActivity {

    /** 内容页：HOME 为默认首页内容，其余对应顶部六个入口。 */
    public enum Tab {
        HOME, HISTORY, SEARCH, RECOMMEND, LIVE_TV, PLATFORM_LIVE, SETTINGS
    }

    private static final String STATE_TAB = "main_tab";

    private final Map<Tab, Fragment> fragments = new HashMap<Tab, Fragment>();
    private Tab current = Tab.HOME;
    private long lastBackAt;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindNav(R.id.nav_history, Tab.HISTORY);
        bindNav(R.id.nav_search, Tab.SEARCH);
        bindNav(R.id.nav_recommend, Tab.RECOMMEND);
        bindNav(R.id.nav_settings, Tab.SETTINGS);

        Tab restore = Tab.HOME;
        if (savedInstanceState != null && savedInstanceState.containsKey(STATE_TAB)) {
            try {
                restore = Tab.valueOf(savedInstanceState.getString(STATE_TAB));
            } catch (IllegalArgumentException ignored) {
            }
        }
        selectTab(restore, false);
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
        ft.commitAllowingStateLoss();

        if (fromUser) {
            final Fragment shown = f;
            // 显示后恢复焦点：若当前无焦点则落到该页第一个可聚焦控件
            getSupportFragmentManager().executePendingTransactions();
            if (shown.getView() != null && shown.getView().findFocus() == null) {
                View first = FocusUtils.firstFocusable(shown.getView());
                if (first != null) {
                    first.requestFocus();
                }
            }
        }
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
        Fragment f = fragments.get(Tab.SEARCH);
        if (f instanceof SearchFragment) {
            ((SearchFragment) f).presetQuery(query);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_TAB, current.name());
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
