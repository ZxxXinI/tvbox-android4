package com.tvbox.android44.feature.home;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.tvbox.android44.R;
import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.AppConstants;
import com.tvbox.android44.common.Result;
import com.tvbox.android44.common.StateLayout;
import com.tvbox.android44.common.PageFocusState;
import com.tvbox.android44.common.ui.ChipAdapter;
import com.tvbox.android44.common.ui.GridSpacingDecoration;
import com.tvbox.android44.common.ui.PosterEntry;
import com.tvbox.android44.common.ui.PosterGridAdapter;
import com.tvbox.android44.data.local.SettingsRepository;
import com.tvbox.android44.data.repository.MovieRepository;
import com.tvbox.android44.domain.model.ApiLine;
import com.tvbox.android44.domain.model.Category;
import com.tvbox.android44.domain.model.Movie;
import com.tvbox.android44.domain.model.PagedMovies;
import com.tvbox.android44.domain.model.PlaySource;
import com.tvbox.android44.domain.parser.ContentFilter;
import com.tvbox.android44.feature.detail.DetailActivity;
import com.tvbox.android44.feature.main.MainActivity;
import com.tvbox.android44.feature.player.PlayerActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页：直接使用当前视频源（线路）的分类与内容，不做豆瓣热播渲染。
 * 「全部」= 当前来源第一页（不按分类过滤）；选择父分类后出现子分类行；
 * 滚动近底部分页追加；失败可重试。
 */
public class HomeFragment extends Fragment implements PageFocusState.Owner {

    private static final String ALL_ID = "all";

    private StateLayout state;
    private RecyclerView tabsView;
    private RecyclerView subTabsView;
    private TextView statusView;
    private RecyclerView grid;
    private PosterGridAdapter adapter;
    private ChipAdapter tabsAdapter;
    private ChipAdapter subTabsAdapter;

    private List<Category> categories = new ArrayList<Category>();
    private String selectedTab = ALL_ID;
    private String selectedSub = ALL_ID;
    private int page = 1;
    private int pageCount = 1;
    private boolean loadingMore;
    private int requestId;
    private int restorePage = 1;
    private Bundle pendingFocus;
    private String loadedApiId;

    private MovieRepository.Request movieRequest;
    private MovieRepository.Request categoryRequest;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        loadedApiId = TvBoxApp.get().settings().currentApi().id;
        if (savedInstanceState != null && loadedApiId.equals(savedInstanceState.getString("api"))) {
            selectedTab = savedInstanceState.getString("tab", ALL_ID);
            selectedSub = savedInstanceState.getString("sub", ALL_ID);
            restorePage = Math.max(1, Math.min(20, savedInstanceState.getInt("page", 1)));
            pendingFocus = savedInstanceState.getBundle("focus");
            String json = savedInstanceState.getString("categories");
            if (json != null) {
                try { categories = new ArrayList<Category>(java.util.Arrays.asList(
                        new com.google.gson.Gson().fromJson(json, Category[].class))); }
                catch (Exception ignored) { categories.clear(); }
            }
        }
        boolean cinema = SettingsRepository.THEME_CINEMA.equals(
                TvBoxApp.get().settings().theme());
        View root = inflater.inflate(
                cinema ? R.layout.layout_home_cinema : R.layout.fragment_home,
                container, false);
        state = root.findViewById(R.id.home_state);
        tabsView = root.findViewById(R.id.home_tabs);
        subTabsView = root.findViewById(R.id.home_sub_tabs);
        statusView = root.findViewById(R.id.home_status);
        grid = root.findViewById(R.id.home_grid);
        if (cinema) {
            bindCinemaTheme(root);
        }

        tabsView.setLayoutManager(new LinearLayoutManager(
                getActivity(), LinearLayoutManager.HORIZONTAL, false));
        subTabsView.setLayoutManager(new LinearLayoutManager(
                getActivity(), LinearLayoutManager.HORIZONTAL, false));

        int columns = columnsForScale();
        grid.setLayoutManager(new GridLayoutManager(getActivity(), columns));
        int spacing = (int) (getResources().getDisplayMetrics().density * 10);
        grid.addItemDecoration(new GridSpacingDecoration(spacing));

        adapter = new PosterGridAdapter(new PosterGridAdapter.OnPosterClick() {
            @Override
            public void onPosterClick(PosterEntry entry, int position) {
                onCardClick(entry);
            }
        });
        grid.setAdapter(adapter);
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                maybeLoadMore();
            }
        });

        tabsAdapter = new ChipAdapter(new ChipAdapter.OnChipClick() {
            @Override
            public void onChipClick(ChipAdapter.Chip chip, int position) {
                onTabSelected(chip);
            }
        });
        tabsView.setAdapter(tabsAdapter);
        subTabsAdapter = new ChipAdapter(new ChipAdapter.OnChipClick() {
            @Override
            public void onChipClick(ChipAdapter.Chip chip, int position) {
                onSubTabSelected(chip);
            }
        });
        subTabsView.setAdapter(subTabsAdapter);

        buildTabs();
        reload(true);
        return root;
    }

    /** 分类表（ac=list）与内容并行加载；分类失败不阻断内容，仅无分类可点。 */
    private void loadCategoryTabs() {
        final int rid = requestId;
        final ApiLine api = TvBoxApp.get().settings().currentApi();
        categoryRequest = TvBoxApp.get().movies().fetchCategories(api,
                new MovieRepository.Callback<List<Category>>() {
                    @Override
                    public void onResult(Result<List<Category>> result) {
                        if (rid != requestId || !isAdded() || getView() == null) {
                            return;
                        }
                        if (result.isSuccess() && result.data() != null) {
                            categories.clear();
                            categories.addAll(result.data());
                            buildTabs();
                        } else if (ALL_ID.equals(selectedTab)) {
                            // 仅提示，不影响内容展示
                            statusView.setText("分类加载失败，可在搜索中直接找片");
                            statusView.setVisibility(View.VISIBLE);
                        }
                    }
                });
    }

    private int columnsForScale() {
        String f = TvBoxApp.get().settings().fontScale();
        if (SettingsRepository.FONT_XLARGE.equals(f)) {
            return AppConstants.GRID_COLUMNS_XLARGE;
        }
        if (SettingsRepository.FONT_LARGE.equals(f)) {
            return AppConstants.GRID_COLUMNS_LARGE;
        }
        return AppConstants.GRID_COLUMNS_NORMAL;
    }

    // ===== 影院主题（文档 05 §4.2）=====

    private android.widget.ImageView heroBackdrop;
    private android.widget.TextView heroTitle;
    private android.widget.TextView heroMeta;
    private View heroContainer;
    private PosterEntry heroEntry;

    private void bindCinemaTheme(View root) {
        heroContainer = root.findViewById(R.id.home_hero);
        heroBackdrop = root.findViewById(R.id.home_hero_backdrop);
        heroTitle = root.findViewById(R.id.home_hero_title);
        heroMeta = root.findViewById(R.id.home_hero_meta);
        bindRail(root, R.id.cinema_nav_history, MainActivity.Tab.HISTORY);
        bindRail(root, R.id.cinema_nav_search, MainActivity.Tab.SEARCH);
        bindRail(root, R.id.cinema_nav_recommend, MainActivity.Tab.RECOMMEND);
        bindRail(root, R.id.cinema_nav_live_tv, MainActivity.Tab.LIVE_TV);
        bindRail(root, R.id.cinema_nav_platform_live, MainActivity.Tab.PLATFORM_LIVE);
        bindRail(root, R.id.cinema_nav_settings, MainActivity.Tab.SETTINGS);
        root.findViewById(R.id.home_hero_play).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playHero();
            }
        });
        root.findViewById(R.id.home_hero_detail).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (heroEntry != null) {
                    onCardClick(heroEntry);
                }
            }
        });
    }

    private void bindRail(View root, int viewId, final MainActivity.Tab tab) {
        root.findViewById(viewId).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.app.Activity activity = getActivity();
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).selectTab(tab, true);
                }
            }
        });
    }

    /** Hero“播放”：有直接线路时直达播放器，否则走详情。 */
    private void playHero() {
        if (heroEntry == null) {
            return;
        }
        if (heroEntry.payload instanceof Movie) {
            Movie m = (Movie) heroEntry.payload;
            if (!m.playSources.isEmpty()) {
                PlaySource line = m.playSources.get(0);
                PlayerActivity.startForResult(getActivity(), m.apiLineId, m.id,
                        line.lineId, 0);
                return;
            }
        }
        onCardClick(heroEntry);
    }

    /** 内容刷新后取首个条目填充 Hero；无内容时隐藏。 */
    private void updateHeroFromEntries() {
        if (heroContainer == null) {
            return;
        }
        if (adapter.entries().isEmpty()) {
            heroContainer.setVisibility(View.GONE);
            heroEntry = null;
            return;
        }
        heroEntry = adapter.entries().get(0);
        heroContainer.setVisibility(View.VISIBLE);
        heroTitle.setText(heroEntry.title);
        heroMeta.setText(heroEntry.subtitle == null ? "" : heroEntry.subtitle);
        if (heroEntry.posterUrl != null && !heroEntry.posterUrl.isEmpty()) {
            Glide.with(heroBackdrop.getContext())
                    .load(heroEntry.posterUrl)
                    .centerCrop()
                    .placeholder(R.drawable.poster_placeholder)
                    .into(heroBackdrop);
        } else {
            Glide.with(heroBackdrop.getContext()).clear(heroBackdrop);
            heroBackdrop.setImageResource(R.drawable.poster_placeholder);
        }
    }

    // ===== 分类行 =====

    private void buildTabs() {
        List<ChipAdapter.Chip> chips = new ArrayList<ChipAdapter.Chip>();
        chips.add(new ChipAdapter.Chip(ALL_ID, "全部", ALL_ID.equals(selectedTab)));
        for (Category c : parentCategories()) {
            // 内容过滤一致作用于分类名（解说/资讯/演员等分类不出现）
            if (ContentFilter.isBlocked(null, c.name, null)) {
                continue;
            }
            chips.add(new ChipAdapter.Chip(c.id, c.name, c.id.equals(selectedTab)));
        }
        tabsAdapter.setChips(chips);
        rebuildSubTabs();
    }

    private List<Category> parentCategories() {
        List<Category> parents = new ArrayList<Category>();
        for (Category c : categories) {
            if (c.parentId == null || c.parentId.isEmpty() || "0".equals(c.parentId)) {
                parents.add(c);
            }
        }
        return parents;
    }

    private void rebuildSubTabs() {
        List<ChipAdapter.Chip> subs = new ArrayList<ChipAdapter.Chip>();
        if (!ALL_ID.equals(selectedTab)) {
            for (Category c : categories) {
                if (selectedTab.equals(c.parentId)) {
                    subs.add(new ChipAdapter.Chip(c.id, c.name, c.id.equals(selectedSub)));
                }
            }
            subs.add(0, new ChipAdapter.Chip(ALL_ID, "全部", ALL_ID.equals(selectedSub)));
        }
        subTabsAdapter.setChips(subs);
        subTabsView.setVisibility(subs.size() > 1 ? View.VISIBLE : View.GONE);
    }

    /** 当前请求的分类集合：父分类“全部”展开为所有子分类，避免源只返回父级直挂数据。 */
    @Nullable
    private List<String> currentTypeIds() {
        if (ALL_ID.equals(selectedTab)) {
            return null;
        }
        if (!ALL_ID.equals(selectedSub)) {
            List<String> one = new ArrayList<String>();
            one.add(selectedSub);
            return one;
        }
        List<String> children = new ArrayList<String>();
        for (Category c : categories) {
            if (selectedTab.equals(c.parentId)) {
                children.add(c.id);
            }
        }
        if (children.isEmpty()) {
            children.add(selectedTab);
        }
        return children;
    }

    // ===== 数据加载 =====

    private void reload(boolean showLoading) {
        cancelRequests();
        final int rid = ++requestId;
        page = 1;
        pageCount = 1;
        loadingMore = false;
        adapter.clear();
        updateHeroFromEntries();
        if (showLoading) {
            state.showLoading(null);
        }
        loadCategoryTabs();
        loadCategory(rid, currentTypeIds(), 1);
    }

    private void loadCategory(final int rid, List<String> typeIds, final int pageToLoad) {
        ApiLine api = TvBoxApp.get().settings().currentApi();
        movieRequest = TvBoxApp.get().movies().fetchByCategories(api, typeIds, pageToLoad,
                new MovieRepository.Callback<PagedMovies>() {
                    @Override
                    public void onResult(Result<PagedMovies> result) {
                        if (rid != requestId || !isAdded() || getView() == null) {
                            return;
                        }
                        if (result.isSuccess() && result.data() != null) {
                            PagedMovies data = result.data();
                            page = pageToLoad;
                            pageCount = data.pageCount;
                            showMovies(data.movies);
                            if (page < restorePage && page < pageCount) {
                                loadingMore = true;
                                loadCategory(rid, currentTypeIds(), page + 1);
                            } else {
                                restorePage = 1;
                                if (!isHidden() && PageFocusState.restore(getView(), pendingFocus)) pendingFocus = null;
                            }
                        } else if (adapter.entries().isEmpty()) {
                            loadingMore = false;
                            String msg = result.asFailure() != null
                                    ? result.asFailure().userMessage : "内容加载失败";
                            state.showError(msg + "。请检查网络或切换视频接口。",
                                    new StateLayout.OnRetryListener() {
                                        @Override
                                        public void onRetry() {
                                            reload(true);
                                        }
                                    });
                        } else {
                            loadingMore = false;
                            restorePage = 1;
                            statusView.setText("下一页加载失败，点击重试");
                            statusView.setVisibility(View.VISIBLE);
                            statusView.setFocusable(true);
                            statusView.setOnClickListener(new View.OnClickListener() {
                                public void onClick(View view) {
                                    if (!loadingMore) {
                                        loadingMore = true;
                                        loadCategory(requestId, currentTypeIds(), page + 1);
                                    }
                                }
                            });
                        }
                    }
                });
    }

    private void showMovies(List<Movie> movies) {
        List<PosterEntry> entries = new ArrayList<PosterEntry>();
        for (Movie m : movies) {
            entries.add(new PosterEntry(m.apiLineId + "-" + m.id,
                    m.name, m.remarks, m.posterUrl, m));
        }
        if (page > 1) {
            adapter.appendEntries(entries);
            statusView.setVisibility(View.GONE);
        } else {
            adapter.setEntries(entries);
            statusView.setVisibility(View.GONE);
            if (entries.isEmpty()) {
                state.showEmpty("当前分类暂无内容");
            } else {
                state.showContent();
                updateHeroFromEntries();
                focusFirstPosterIfNeeded();
            }
        }
        loadingMore = false;
    }

    private void focusFirstPosterIfNeeded() {
        if (!isHidden() && pendingFocus == null && getActivity().getCurrentFocus() == null
                && grid.getChildCount() > 0) {
            grid.getChildAt(0).requestFocus();
        }
    }

    private void maybeLoadMore() {
        if (loadingMore || page >= pageCount || !isAdded()) {
            return;
        }
        GridLayoutManager lm = (GridLayoutManager) grid.getLayoutManager();
        if (lm == null) {
            return;
        }
        int lastVisible = lm.findLastVisibleItemPosition();
        int total = adapter.getItemCount();
        if (total > 0 && lastVisible >= total - 6) {
            loadingMore = true;
            loadCategory(requestId, currentTypeIds(), page + 1);
        }
    }

    // ===== 交互 =====

    private void onTabSelected(ChipAdapter.Chip chip) {
        if (chip.id.equals(selectedTab)) {
            return;
        }
        restorePage = 1;
        pendingFocus = null;
        selectedTab = chip.id;
        selectedSub = ALL_ID;
        tabsAdapter.setSelected(chip.id);
        rebuildSubTabs();
        reload(true);
    }

    private void onSubTabSelected(ChipAdapter.Chip chip) {
        if (chip.id.equals(selectedSub)) {
            return;
        }
        restorePage = 1;
        pendingFocus = null;
        selectedSub = chip.id;
        subTabsAdapter.setSelected(chip.id);
        reload(true);
    }

    private void onCardClick(PosterEntry entry) {
        if (entry.payload instanceof Movie) {
            Movie m = (Movie) entry.payload;
            DetailActivity.startForResult(getActivity(), m.apiLineId, m.id);
        }
    }

    private void cancelRequests() {
        if (movieRequest != null) {
            movieRequest.cancel();
        }
        if (categoryRequest != null) {
            categoryRequest.cancel();
        }
    }

    @Override public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("api", loadedApiId);
        out.putString("tab", selectedTab); out.putString("sub", selectedSub);
        out.putInt("page", page);
        out.putString("categories", new com.google.gson.Gson().toJson(categories));
        Bundle focused = PageFocusState.capture(getView());
        out.putBundle("focus", focused.isEmpty() ? pendingFocus : focused);
    }

    @Override public void restorePageFocus(Bundle focused) {
        pendingFocus = focused;
        if (!isHidden() && restorePage <= page && PageFocusState.restore(getView(), pendingFocus)) pendingFocus = null;
    }

    @Override public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && getView() != null) {
            String currentApi = TvBoxApp.get().settings().currentApi().id;
            if (!currentApi.equals(loadedApiId)) {
                loadedApiId = currentApi;
                categories.clear(); selectedTab = ALL_ID; selectedSub = ALL_ID;
                pendingFocus = null; restorePage = 1;
                buildTabs(); reload(true);
            } else restorePageFocus(pendingFocus);
        }
    }

    @Override
    public void onDestroyView() {
        ++requestId;
        cancelRequests();
        super.onDestroyView();
        grid = null;
        state = null;
    }
}
