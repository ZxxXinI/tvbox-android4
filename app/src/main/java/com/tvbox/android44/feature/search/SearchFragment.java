package com.tvbox.android44.feature.search;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tvbox.android44.R;
import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.AppConstants;
import com.tvbox.android44.common.StateLayout;
import com.tvbox.android44.common.PageFocusState;
import com.tvbox.android44.domain.parser.NameNormalizer;
import com.tvbox.android44.common.ui.GridSpacingDecoration;
import com.tvbox.android44.common.ui.PosterEntry;
import com.tvbox.android44.common.ui.PosterGridAdapter;
import com.tvbox.android44.data.local.SettingsRepository;
import com.tvbox.android44.data.repository.MultiSourceSearch;
import com.tvbox.android44.domain.model.Movie;
import com.tvbox.android44.feature.detail.DetailActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索页：多来源增量结果（主来源优先、并发≤3、单源 3s 超时）。
 * 任一来源返回立即展示；单源失败只计完成数；全部完成且为空才空态。
 */
public class SearchFragment extends Fragment implements PageFocusState.Owner {

    private EditText input;
    private TextView status;
    private StateLayout state;
    private RecyclerView grid;
    private PosterGridAdapter adapter;
    private MultiSourceSearch.Handle searchHandle;
    private int requestId;
    private String lastQuery;
    private String pendingQuery;
    private Bundle pendingFocus;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_search, container, false);
        input = root.findViewById(R.id.search_input);
        status = root.findViewById(R.id.search_status);
        state = root.findViewById(R.id.search_state);
        grid = root.findViewById(R.id.search_grid);

        int columns = columnsForScale();
        grid.setLayoutManager(new GridLayoutManager(getActivity(), columns));
        grid.addItemDecoration(new GridSpacingDecoration(
                (int) (getResources().getDisplayMetrics().density * 10)));
        adapter = new PosterGridAdapter(new PosterGridAdapter.OnPosterClick() {
            @Override
            public void onPosterClick(PosterEntry entry, int position) {
                if (entry.payload instanceof Movie) {
                    Movie m = (Movie) entry.payload;
                    DetailActivity.startForResult(getActivity(), m.apiLineId, m.id);
                }
            }
        });
        grid.setAdapter(adapter);
        if (savedInstanceState != null) {
            lastQuery = savedInstanceState.getString("query");
            pendingFocus = savedInstanceState.getBundle("focus");
        }

        root.findViewById(R.id.search_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSearch(input.getText().toString());
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    startSearch(input.getText().toString());
                    return true;
                }
                return false;
            }
        });
        return root;
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        String query = pendingQuery != null ? pendingQuery : lastQuery;
        pendingQuery = null;
        if (query != null && !query.isEmpty()) { input.setText(query); startSearch(query); }
    }

    @Override public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("query", lastQuery);
        Bundle focused = PageFocusState.capture(getView());
        out.putBundle("focus", focused.isEmpty() ? pendingFocus : focused);
    }

    @Override public void restorePageFocus(Bundle focused) {
        pendingFocus = focused;
        if (!isHidden() && PageFocusState.restore(getView(), pendingFocus)) pendingFocus = null;
    }

    @Override public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) restorePageFocus(pendingFocus);
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

    /** 从推荐页带词跳转。 */
    public void presetQuery(String query) {
        if (query == null || query.trim().isEmpty()) {
            return;
        }
        if (input == null || getView() == null) { pendingQuery = query.trim(); return; }
        input.setText(query.trim());
        startSearch(query.trim());
    }

    private void startSearch(String rawQuery) {
        final String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) {
            if (isAdded()) {
                android.widget.Toast.makeText(getActivity(),
                        "请输入搜索关键词", android.widget.Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (query.length() > AppConstants.SEARCH_QUERY_MAX_LENGTH) {
            query2 = query.substring(0, AppConstants.SEARCH_QUERY_MAX_LENGTH);
        } else {
            query2 = query;
        }
        // 新搜索：取消旧搜索；离开页面导致的取消不计失败
        if (searchHandle != null) {
            searchHandle.cancel();
        }
        final int rid = ++requestId;
        lastQuery = query2;
        adapter.clear();
        state.showLoading("正在搜索…");
        status.setVisibility(View.GONE);
        hideKeyboard();

        searchHandle = TvBoxApp.get().search().search(query2, new MultiSourceSearch.Listener() {
            @Override
            public void onIncremental(List<Movie> merged, int completed, int totalSources,
                                      int foundCount) {
                if (rid != requestId || !isAdded() || getView() == null) {
                    return;
                }
                renderResults(merged, false);
                status.setText("已完成 " + completed + "/" + totalSources
                        + " 条线路 · 找到 " + foundCount + " 个结果");
                status.setVisibility(View.VISIBLE);
                if (!merged.isEmpty()) {
                    state.showContent();
                    if (!isHidden() && PageFocusState.restore(getView(), pendingFocus)) pendingFocus = null;
                }
            }

            @Override
            public void onFinished(List<Movie> merged, boolean anySuccess) {
                if (rid != requestId || !isAdded() || getView() == null) {
                    return;
                }
                if (merged.isEmpty()) {
                    state.showEmpty(anySuccess
                            ? "没有找到相关影片，可以换个关键词或视频接口。"
                            : "搜索失败，请检查网络或切换视频接口。");
                } else {
                    renderResults(merged, false);
                    state.showContent();
                }
            }
        });
    }

    private String query2;

    /** 增量渲染：按 key 全量比对，避免追加重复（merger 已去重，这里防御）。 */
    private void renderResults(List<Movie> merged, boolean appendOnly) {
        List<PosterEntry> entries = new ArrayList<PosterEntry>();
        for (Movie m : merged) {
            entries.add(new PosterEntry(NameNormalizer.dedupeKey(m.name, m.year), m.name,
                    m.remarks + (m.apiLineName.isEmpty() ? "" : " · " + m.apiLineName),
                    m.posterUrl, m));
        }
        adapter.updateEntries(entries);
    }

    private void hideKeyboard() {
        if (getActivity() == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager)
                getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && input.getWindowToken() != null) {
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
    }

    @Override
    public void onDestroyView() {
        ++requestId;
        // 离开页面取消搜索；取消不计失败、不显示错误
        if (searchHandle != null) {
            searchHandle.cancel();
        }
        super.onDestroyView();
        input = null;
        grid = null;
    }
}
