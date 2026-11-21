package com.tvbox.android44.feature.home;

import android.os.Bundle;
import android.text.format.DateUtils;
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
import com.tvbox.android44.common.ui.AspectImageView;
import com.tvbox.android44.common.ui.GridSpacingDecoration;
import com.tvbox.android44.common.ui.PosterEntry;
import com.tvbox.android44.common.ui.PosterGridAdapter;
import com.tvbox.android44.data.local.SettingsRepository;
import com.tvbox.android44.data.local.HistoryStore;
import com.tvbox.android44.data.repository.MovieRepository;
import com.tvbox.android44.domain.model.ApiLine;
import com.tvbox.android44.domain.model.Category;
import com.tvbox.android44.domain.model.Movie;
import com.tvbox.android44.domain.model.PagedMovies;
import com.tvbox.android44.domain.model.PlaySource;
import com.tvbox.android44.domain.model.WatchHistoryItem;
import com.tvbox.android44.domain.parser.ContentFilter;
import com.tvbox.android44.feature.detail.DetailActivity;
import com.tvbox.android44.feature.main.MainActivity;
import com.tvbox.android44.feature.player.PlayerActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 首页：直接使用当前视频源（线路）的分类与内容，不做豆瓣热播渲染。
 * 「全部」= 当前来源第一页（不按分类过滤）；选择父分类后出现子分类行；
 * 滚动近底部分页追加；失败可重试。
 */
public class HomeFragment extends Fragment implements PageFocusState.Owner, HistoryStore.Listener {

    private static final String ALL_ID = "all";
    private static final int CONTINUE_WATCHING_LIMIT = 6;

    private StateLayout state;
    private RecyclerView tabsView;
    private RecyclerView subTabsView;
    private TextView statusView;
    private RecyclerView grid;
    private View continueSection;
    private RecyclerView continueGrid;
    private PosterGridAdapter continueAdapter;
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
    private ApiLine preferredApi;
    private ApiLine activeApi;
    private boolean categoryLoadFailed;
    private boolean pageRetry;

    private MovieRepository.Request movieRequest;
    private MovieRepository.Request categoryRequest;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        preferredApi = TvBoxApp.get().settings().currentApi();
        activeApi = preferredApi;
        if (savedInstanceState != null && activeApi.id.equals(savedInstanceState.getString("api"))) {
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
        statusView.setOnClickListener(view -> {
            if (!pageRetry || loadingMore) return;
            pageRetry = false;
            loadingMore = true;
            renderStatus();
            loadCategory(requestId, currentTypeIds(), page + 1, false);
        });
        grid = root.findViewById(R.id.home_grid);
        continueSection = root.findViewById(R.id.home_continue_section);
        continueGrid = root.findViewById(R.id.home_continue_grid);
        continueGrid.setLayoutManager(new LinearLayoutManager(getActivity(), LinearLayoutManager.HORIZONTAL, false));
        continueAdapter = new PosterGridAdapter((entry, position) -> {
            if (!(entry.payload instanceof WatchHistoryItem) || getActivity() == null) return;
            Bundle bookmark = PageFocusState.capture(continueGrid);
            if (!bookmark.isEmpty()) pendingFocus = bookmark;
            PlayerActivity.startForResultWithHistory(requireActivity(), (WatchHistoryItem) entry.payload, false);
        }, AspectImageView.RATIO_POSTER, R.layout.item_home_continue);
        continueGrid.setAdapter(continueAdapter);
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

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        TvBoxApp.get().history().addListener(this);
    }

    @Override public void onResume() {
        super.onResume();
        refreshContinueWatching();
    }

    @Override public void onHistoryChanged() {
        TvBoxApp.get().executors().main(this::refreshContinueWatching);
    }

    private void refreshContinueWatching() {
        RecyclerView row = continueGrid;
        if (row == null || !isResumed() || isHidden() || getView() == null) return;
        TvBoxApp app = TvBoxApp.get();
        app.executors().disk().execute(() -> {
            List<WatchHistoryItem> recent = new ArrayList<>();
            for (WatchHistoryItem item : app.history().load()) {
                if (item.position <= 0 || item.progressPercent() >= 95
                        || ContentFilter.isBlocked(item.movieName, item.typeName, item.remarks)) continue;
                recent.add(item);
                if (recent.size() == CONTINUE_WATCHING_LIMIT) break;
            }
            app.executors().main(() -> {
                if (continueGrid == row && isResumed() && !isHidden() && getView() != null) {
                    renderContinueWatching(recent);
                }
            });
        });
    }

    private void renderContinueWatching(List<WatchHistoryItem> recent) {
        boolean wasFocused = continueGrid.hasFocus();
        List<PosterEntry> entries = new ArrayList<>();
        for (WatchHistoryItem item : recent) {
            String episode = getString(R.string.home_continue_episode, Math.max(0, item.episodeIndex) + 1);
            String progress = item.duration > 0
                    ? getString(R.string.home_continue_percent, item.progressPercent())
                    : DateUtils.formatElapsedTime(item.position / 1000);
            String title = item.movieName == null || item.movieName.isEmpty()
                    ? getString(R.string.history_playback) : item.movieName;
            entries.add(new PosterEntry(item.key(), title,
                    getString(R.string.home_continue_subtitle, episode, progress), item.posterUrl, item));
        }
        continueAdapter.setEntries(entries);
        continueSection.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
        if (heroContainer != null) {
            ViewGroup.LayoutParams params = heroContainer.getLayoutParams();
            int height = entries.isEmpty() ? fullHeroHeight : getResources().getDimensionPixelSize(R.dimen.compact_home_hero_height);
            if (params.height != height) {
                params.height = height;
                heroContainer.setLayoutParams(params);
            }
        }
        if (entries.isEmpty()) {
            if (isContinueBookmark()) pendingFocus = null;
            if (wasFocused) {
                View nav = continueGrid.getRootView().findViewById(R.id.nav_history);
                if (nav != null) nav.requestFocus();
            }
        }
        if (isContinueBookmark()) restorePendingFocus();
    }

    /** 分类表（ac=list）与内容并行加载；分类失败不阻断内容，仅无分类可点。 */
    private void loadCategoryTabs() {
        final int rid = requestId;
        final ApiLine api = activeApi;
        categoryRequest = TvBoxApp.get().movies().fetchCategories(api,
                new MovieRepository.Callback<List<Category>>() {
                    @Override
                    public void onResult(Result<List<Category>> result) {
                        if (!isCurrentRequest(rid) || result.isCancelled()) {
                            return;
                        }
                        categoryLoadFailed = !result.isSuccess() || result.data() == null;
                        if (result.isSuccess() && result.data() != null) {
                            categories.clear();
                            categories.addAll(result.data());
                            buildTabs();
                        }
                        renderStatus();
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
    private int fullHeroHeight;
    private PosterEntry heroEntry;

    private void bindCinemaTheme(View root) {
        heroContainer = root.findViewById(R.id.home_hero);
        fullHeroHeight = heroContainer.getLayoutParams().height;
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

    private void reload(boolean allowBackup) {
        cancelRequests();
        final int rid = ++requestId;
        page = 1;
        pageCount = 1;
        loadingMore = false;
        pageRetry = false;
        categoryLoadFailed = false;
        adapter.clear();
        updateHeroFromEntries();
        state.showLoading(null);
        renderStatus();
        loadCategoryTabs();
        loadCategory(rid, currentTypeIds(), 1, allowBackup);
    }

    private void loadCategory(int rid, List<String> typeIds, int pageToLoad, boolean allowBackup) {
        movieRequest = TvBoxApp.get().movies().fetchByCategories(activeApi, typeIds, pageToLoad, result -> {
            if (!isCurrentRequest(rid) || result.isCancelled()) return;
            PagedMovies data = result.data();
            if (data != null) {
                applyPage(rid, data, pageToLoad);
                return;
            }
            loadingMore = false;
            if (pageToLoad > 1) {
                restorePage = 1;
                pageRetry = true;
                renderStatus();
                return;
            }
            if (allowBackup) {
                List<ApiLine> backups = backupOrder();
                if (!backups.isEmpty()) {
                    cancelRequests();
                    tryBackup(++requestId, backups, 0);
                    return;
                }
            }
            String message = result.isFailure() ? result.asFailure().userMessage : getString(R.string.home_load_failed);
            showHomeError(message, allowBackup ? this::retryHome : () -> reload(false));
        });
    }

    private List<ApiLine> backupOrder() {
        List<ApiLine> sources = TvBoxApp.get().settings().allApis();
        int activeIndex = -1;
        for (int i = 0; i < sources.size(); i++) {
            if (activeApi.id.equals(sources.get(i).id)) {
                activeIndex = i;
                break;
            }
        }
        List<ApiLine> backups = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        seenUrls.add(activeApi.baseUrl);
        for (int offset = 1; offset <= sources.size(); offset++) {
            ApiLine candidate = sources.get((activeIndex + offset) % sources.size());
            if (!activeApi.id.equals(candidate.id) && seenUrls.add(candidate.baseUrl)) backups.add(candidate);
        }
        return backups;
    }

    private void tryBackup(int rid, List<ApiLine> backups, int index) {
        if (index == backups.size()) {
            setStatusText(null);
            showHomeError(getString(R.string.home_sources_unavailable), this::retryHome);
            return;
        }
        ApiLine candidate = backups.get(index);
        setStatusText(getString(R.string.home_trying_backup, candidate.name, index + 1, backups.size()));
        movieRequest = TvBoxApp.get().movies().fetchHomepageBackup(candidate, result -> {
            if (!isCurrentRequest(rid) || result.isCancelled()) return;
            PagedMovies data = result.data();
            if (data == null || data.movies.isEmpty() || !isConfigured(candidate)) {
                tryBackup(rid, backups, index + 1);
                return;
            }
            resetSource(candidate);
            applyPage(rid, data, 1);
            loadCategoryTabs();
        });
    }

    private void applyPage(int rid, PagedMovies data, int pageToLoad) {
        page = pageToLoad;
        pageCount = data.pageCount;
        pageRetry = false;
        showMovies(data.movies);
        renderStatus();
        if (page < restorePage && page < pageCount) {
            loadingMore = true;
            loadCategory(rid, currentTypeIds(), page + 1, false);
        } else {
            restorePage = 1;
            restorePendingFocus();
        }
    }

    private void retryHome() {
        preferredApi = TvBoxApp.get().settings().currentApi();
        if (!activeApi.id.equals(preferredApi.id)) resetSource(preferredApi);
        reload(true);
    }

    private void resetSource(ApiLine api) {
        activeApi = api;
        categories.clear();
        selectedTab = ALL_ID;
        selectedSub = ALL_ID;
        if (!isContinueBookmark()) pendingFocus = null;
        restorePage = 1;
        categoryLoadFailed = false;
        buildTabs();
    }

    private boolean isCurrentRequest(int rid) {
        return rid == requestId && isAdded() && getView() != null;
    }

    private boolean isConfigured(ApiLine source) {
        for (ApiLine line : TvBoxApp.get().settings().allApis()) {
            if (source.id.equals(line.id) && source.baseUrl.equals(line.baseUrl)) return true;
        }
        return false;
    }

    private void renderStatus() {
        String text = activeApi.id.equals(preferredApi.id) ? null : getString(R.string.home_using_backup, activeApi.name);
        if (pageRetry || (categoryLoadFailed && ALL_ID.equals(selectedTab))) {
            String detail = getString(pageRetry ? R.string.home_page_retry : R.string.home_categories_failed);
            text = text == null ? detail : text + "\n" + detail;
        }
        setStatusText(text);
    }

    private void setStatusText(@Nullable String text) {
        statusView.setText(text);
        statusView.setFocusable(pageRetry);
        statusView.setClickable(pageRetry);
        statusView.setVisibility(text == null ? View.GONE : View.VISIBLE);
    }

    private void showHomeError(String message, StateLayout.OnRetryListener retry) {
        View focusedCard = continueGrid.findFocus();
        state.showError(message, retry);
        if (focusedCard != null) focusedCard.requestFocus();
    }

    private void showMovies(List<Movie> movies) {
        List<PosterEntry> entries = new ArrayList<PosterEntry>();
        for (Movie m : movies) {
            entries.add(new PosterEntry(m.apiLineId + "-" + m.id,
                    m.name, m.remarks, m.posterUrl, m));
        }
        if (page > 1) {
            adapter.appendEntries(entries);
        } else {
            adapter.setEntries(entries);
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
        if (!isHidden() && pendingFocus == null && grid.getRootView().findFocus() == null
                && grid.getChildCount() > 0) {
            grid.getChildAt(0).requestFocus();
        }
    }

    private void maybeLoadMore() {
        if (loadingMore || pageRetry || page >= pageCount || !isAdded()) {
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
            loadCategory(requestId, currentTypeIds(), page + 1, false);
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
        reload(ALL_ID.equals(selectedTab));
    }

    private void onSubTabSelected(ChipAdapter.Chip chip) {
        if (chip.id.equals(selectedSub)) {
            return;
        }
        restorePage = 1;
        pendingFocus = null;
        selectedSub = chip.id;
        subTabsAdapter.setSelected(chip.id);
        reload(false);
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
        out.putString("api", activeApi == null ? null : activeApi.id);
        out.putString("tab", selectedTab); out.putString("sub", selectedSub);
        out.putInt("page", page);
        out.putString("categories", new com.google.gson.Gson().toJson(categories));
        Bundle focused = PageFocusState.capture(getView());
        out.putBundle("focus", focused.isEmpty() ? pendingFocus : focused);
    }

    @Override public void restorePageFocus(Bundle focused) {
        if (focused != null) pendingFocus = focused;
        restorePendingFocus();
    }

    private boolean isContinueBookmark() {
        return pendingFocus != null && pendingFocus.getInt("list", View.NO_ID) == R.id.home_continue_grid;
    }

    private void restorePendingFocus() {
        if (!isHidden() && (isContinueBookmark() || restorePage <= page)
                && PageFocusState.restore(getView(), pendingFocus)) pendingFocus = null;
    }

    @Override public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && getView() != null) {
            refreshContinueWatching();
            ApiLine currentApi = TvBoxApp.get().settings().currentApi();
            if (!currentApi.id.equals(preferredApi.id) || !isConfigured(activeApi)) {
                preferredApi = currentApi;
                resetSource(preferredApi);
                reload(true);
            } else restorePageFocus(pendingFocus);
        }
    }

    @Override
    public void onDestroyView() {
        ++requestId;
        cancelRequests();
        TvBoxApp.get().history().removeListener(this);
        super.onDestroyView();
        continueGrid = null;
        heroContainer = null;
        grid = null;
        state = null;
    }
}
