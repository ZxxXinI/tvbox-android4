package com.tvbox.android44.feature.detail;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.tvbox.android44.R;
import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.BaseActivity;
import com.tvbox.android44.common.FocusScaler;
import com.tvbox.android44.common.Result;
import com.tvbox.android44.common.StateLayout;
import com.tvbox.android44.common.PageFocusState;
import com.tvbox.android44.common.TvDialogs;
import com.tvbox.android44.common.ui.ChipAdapter;
import com.tvbox.android44.common.ui.GridSpacingDecoration;
import com.tvbox.android44.data.local.SettingsRepository;
import com.tvbox.android44.data.repository.DetailSupplement;
import com.tvbox.android44.data.repository.MovieRepository;
import com.tvbox.android44.domain.model.ApiLine;
import com.tvbox.android44.domain.model.Movie;
import com.tvbox.android44.domain.model.PlayEpisode;
import com.tvbox.android44.domain.model.PlaySource;
import com.tvbox.android44.domain.model.WatchHistoryItem;
import com.tvbox.android44.feature.player.PlayerActivity;
import com.tvbox.android44.domain.playback.PlaybackSelection;

import java.util.ArrayList;
import java.util.List;

/**
 * 详情页：主详情先显示，后台渐进补线（追加不重置线路/选集/焦点）；
 * 历史恢复优先按线路名+集标题匹配。
 */
public class DetailActivity extends BaseActivity {

    public static final String EXTRA_API_ID = "apiId";
    public static final String EXTRA_MOVIE_ID = "movieId";

    private static final int REQUEST_DETAIL = 1001;

    public static void startForResult(Activity from, String apiId, String movieId) {
        Intent intent = new Intent(from, DetailActivity.class);
        intent.putExtra(EXTRA_API_ID, apiId);
        intent.putExtra(EXTRA_MOVIE_ID, movieId);
        from.startActivityForResult(intent, REQUEST_DETAIL);
    }

    public static void startForHistory(Activity from, WatchHistoryItem history) {
        Intent intent = new Intent(from, DetailActivity.class);
        intent.putExtra(EXTRA_API_ID, history.apiLineId);
        intent.putExtra(EXTRA_MOVIE_ID, history.movieId);
        intent.putExtra(PlayerActivity.EXTRA_HISTORY_ITEM, new WatchHistoryItem(history));
        from.startActivityForResult(intent, REQUEST_DETAIL);
    }

    private StateLayout state;
    private TextView title;
    private TextView meta;
    private TextView cast;
    private TextView desc;
    private TextView status;
    private RecyclerView linesView;
    private RecyclerView episodesView;
    private TextView continueBtn;
    private TextView playBtn;
    private View poster;

    private String apiId;
    private String movieId;
    private Movie movie;
    private ChipAdapter lineAdapter;
    private EpisodeAdapter episodeAdapter;
    private MovieRepository.Request detailRequest;
    private DetailSupplement.Handle supplementHandle;
    private String selectedLineId;
    private int selectedEpisode = -1;
    private boolean descExpanded;
    private Bundle pendingFocus;
    private String restoredLine;
    private int restoredEpisode = -1;
    private WatchHistoryItem resumeHistory;
    private boolean fallbackOffered;
    private View.OnLayoutChangeListener episodeFocusListener;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        apiId = getIntent().getStringExtra(EXTRA_API_ID);
        movieId = getIntent().getStringExtra(EXTRA_MOVIE_ID);
        resumeHistory = (WatchHistoryItem) getIntent().getSerializableExtra(PlayerActivity.EXTRA_HISTORY_ITEM);
        if (savedInstanceState != null) {
            restoredLine = savedInstanceState.getString("line");
            restoredEpisode = savedInstanceState.getInt("episode", -1);
            pendingFocus = savedInstanceState.getBundle("focus");
            descExpanded = savedInstanceState.getBoolean("expanded");
            fallbackOffered = savedInstanceState.getBoolean("fallbackOffered");
            WatchHistoryItem savedHistory = (WatchHistoryItem) savedInstanceState.getSerializable(PlayerActivity.EXTRA_HISTORY_ITEM);
            if (savedHistory != null) resumeHistory = savedHistory;
        }

        state = findViewById(R.id.detail_state);
        title = findViewById(R.id.detail_title);
        meta = findViewById(R.id.detail_meta);
        cast = findViewById(R.id.detail_cast);
        desc = findViewById(R.id.detail_desc);
        status = findViewById(R.id.detail_status);
        poster = findViewById(R.id.detail_poster);
        linesView = findViewById(R.id.detail_lines);
        episodesView = findViewById(R.id.detail_episodes);
        continueBtn = findViewById(R.id.detail_continue);
        playBtn = findViewById(R.id.detail_play);

        linesView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        lineAdapter = new ChipAdapter(new ChipAdapter.OnChipClick() {
            @Override
            public void onChipClick(ChipAdapter.Chip chip, int position) {
                selectLine(chip.id, true);
            }
        });
        linesView.setAdapter(lineAdapter);

        episodesView.addItemDecoration(new GridSpacingDecoration(
                (int) (getResources().getDisplayMetrics().density * 8)));
        episodeAdapter = new EpisodeAdapter(new EpisodeAdapter.OnEpisodeClick() {
            @Override
            public void onEpisodeClick(int index) {
                playEpisode(index);
            }
        });
        episodesView.setLayoutManager(new EpisodeGridLayoutManager(this, episodeAdapter));
        episodesView.setAdapter(episodeAdapter);

        // 展开/收起：聚焦或点击展开长简介，不让页面无限增高
        desc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                descExpanded = !descExpanded;
                desc.setMaxLines(descExpanded ? Integer.MAX_VALUE : 3);
            }
        });
        desc.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus && !descExpanded) {
                    descExpanded = true;
                    desc.setMaxLines(Integer.MAX_VALUE);
                }
            }
        });

        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playEpisode(selectedEpisode >= 0 ? selectedEpisode : 0);
            }
        });
        continueBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playFromHistory();
            }
        });

        if (resumeHistory != null) loadDetail();
        else {
            state.showLoading(null);
            TvBoxApp.get().executors().disk().execute(new Runnable() {
                @Override public void run() {
                    final WatchHistoryItem history = TvBoxApp.get().history().find(apiId, movieId);
                    TvBoxApp.get().executors().main(new Runnable() {
                        @Override public void run() {
                            if (isFinishing() || isActivityDestroyed()) return;
                            resumeHistory = history;
                            loadDetail();
                        }
                    });
                }
            });
        }
    }

    private void loadDetail() {
        state.showLoading(null);
        ApiLine api = findApi(apiId);
        if (api == null) {
            state.showError("视频接口不可用", new StateLayout.OnRetryListener() {
                @Override
                public void onRetry() {
                    loadDetail();
                }
            });
            return;
        }
        detailRequest = TvBoxApp.get().movies().fetchDetail(api, movieId,
                new MovieRepository.Callback<Movie>() {
                    @Override
                    public void onResult(Result<Movie> result) {
                        if (!isFinishing() && !isActivityDestroyed()) {
                            onMainDetail(result);
                        }
                    }
                });
    }

    private ApiLine findApi(String id) {
        for (ApiLine l : TvBoxApp.get().settings().allApis()) {
            if (l.id.equals(id)) {
                return l;
            }
        }
        return null;
    }

    private void onMainDetail(Result<Movie> result) {
        if (result.isFailure()) {
            state.showError("影片详情加载失败，请重试或返回搜索结果。",
                    new StateLayout.OnRetryListener() {
                        @Override
                        public void onRetry() {
                            loadDetail();
                        }
                    });
            if (!fallbackOffered && resumeHistory != null && resumeHistory.episodeUrl != null
                    && okhttp3.HttpUrl.parse(resumeHistory.episodeUrl) != null) {
                fallbackOffered = true;
                TvDialogs.confirm(this, getString(R.string.history_fallback_title),
                        getString(R.string.history_fallback_message), new TvDialogs.ConfirmListener() {
                            @Override public void onConfirm() {
                                if (!isFinishing() && !isActivityDestroyed()) {
                                    PlayerActivity.startForResultWithHistory(DetailActivity.this, resumeHistory, true);
                                }
                            }
                        });
            }
            return;
        }
        movie = result.data();
        renderDetail();
        state.showContent();
        startSupplement();
        if (movie.playSources.isEmpty()) {
            status.setText(R.string.detail_no_lines);
            status.setVisibility(View.VISIBLE);
        }
        if (!PageFocusState.restore(findViewById(android.R.id.content), pendingFocus)) playBtn.requestFocus();
        pendingFocus = null;
    }

    private void renderDetail() {
        title.setText(movie.name);
        StringBuilder m = new StringBuilder();
        if (movie.typeName != null && !movie.typeName.isEmpty()) m.append(movie.typeName).append(" · ");
        if (movie.year != null && !movie.year.isEmpty()) m.append(movie.year).append(" · ");
        if (movie.area != null && !movie.area.isEmpty()) m.append(movie.area).append(" · ");
        if (movie.language != null && !movie.language.isEmpty()) m.append(movie.language);
        meta.setText(m.toString());
        StringBuilder c = new StringBuilder();
        if (movie.director != null && !movie.director.isEmpty()) {
            c.append("导演：").append(movie.director).append('\n');
        }
        if (movie.actor != null && !movie.actor.isEmpty()) {
            c.append("演员：").append(movie.actor);
        }
        cast.setText(c.toString());
        desc.setText(movie.description == null || movie.description.isEmpty()
                ? "暂无简介" : movie.description);
        if (movie.posterUrl != null && !movie.posterUrl.isEmpty()) {
            Glide.with(this).load(movie.posterUrl)
                    .placeholder(R.drawable.poster_placeholder)
                    .into((android.widget.ImageView) poster);
        }

        // 历史恢复：同名线路 + 同标题集优先
        WatchHistoryItem h = resumeHistory;
        WatchHistoryItem preference = h;
        if (restoredLine != null || restoredEpisode >= 0) {
            preference = new WatchHistoryItem();
            preference.lineId = restoredLine;
            preference.episodeIndex = Math.max(0, restoredEpisode);
        }
        PlaybackSelection selection = PlaybackSelection.resolve(movie, preference, false);
        desc.setMaxLines(descExpanded ? Integer.MAX_VALUE : 3);

        List<ChipAdapter.Chip> chips = new ArrayList<ChipAdapter.Chip>();
        String defaultLine = selection == null ? "" : selection.source.lineId;
        for (PlaySource ps : movie.playSources) {
            chips.add(new ChipAdapter.Chip(ps.lineId, lineLabel(ps), ps.lineId.equals(defaultLine)));
        }
        lineAdapter.setChips(chips);
        selectedLineId = defaultLine;
        selectedEpisode = selection == null ? -1 : selection.episodeIndex;
        selectLine(defaultLine, false);
        continueBtn.setText(h == null ? getString(R.string.play_first_episode)
                : getString(R.string.continue_episode, Math.max(0, selectedEpisode) + 1));
    }

    private String lineLabel(PlaySource ps) {
        // 线路名含 m3u8 的标注推荐
        boolean recommend = ps.lineName != null && ps.lineName.toLowerCase(java.util.Locale.ROOT).contains("m3u8");
        return ps.sourceName + "·" + ps.lineName + (recommend ? "（推荐）" : "");
    }

    /** 默认线路：优先名称含 m3u8 且有选集，否则第一个有选集线路。 */
    private String defaultLineId(String preferLineId) {
        if (preferLineId != null) {
            for (PlaySource ps : movie.playSources) {
                if (ps.lineId.equals(preferLineId) && !ps.episodes.isEmpty()) {
                    return ps.lineId;
                }
            }
        }
        for (PlaySource ps : movie.playSources) {
            if (ps.lineName != null && ps.lineName.toLowerCase(java.util.Locale.ROOT).contains("m3u8")
                    && !ps.episodes.isEmpty()) {
                return ps.lineId;
            }
        }
        for (PlaySource ps : movie.playSources) {
            if (!ps.episodes.isEmpty()) {
                return ps.lineId;
            }
        }
        return movie.playSources.isEmpty() ? "" : movie.playSources.get(0).lineId;
    }

    private PlaySource findSource(String lineId) {
        for (PlaySource ps : movie.playSources) {
            if (ps.lineId.equals(lineId)) {
                return ps;
            }
        }
        return null;
    }

    /** 选择线路：优先按当前集标题匹配新线路，无法匹配限制到合法索引。 */
    private void selectLine(String lineId, boolean fromUser) {
        PlaySource source = findSource(lineId);
        if (source == null) {
            return;
        }
        String currentTitle = null;
        PlayEpisode current = null;
        if (selectedEpisode >= 0 && selectedEpisode < source.episodes.size()) {
            current = source.episodes.get(selectedEpisode);
        }
        if (selectedLineId != null && !selectedLineId.equals(lineId)) {
            PlaySource old = findSource(selectedLineId);
            if (old != null && selectedEpisode >= 0 && selectedEpisode < old.episodes.size()) {
                currentTitle = old.episodes.get(selectedEpisode).title;
            }
        }
        selectedLineId = lineId;
        lineAdapter.setSelected(lineId);
        episodeAdapter.setEpisodes(lineId, source.episodes, selectedEpisode);
        if (fromUser && currentTitle != null) {
            for (int i = 0; i < source.episodes.size(); i++) {
                if (source.episodes.get(i).title.equals(currentTitle)) {
                    selectedEpisode = i;
                    episodeAdapter.setSelected(i);
                    return;
                }
            }
            if (selectedEpisode >= source.episodes.size()) {
                selectedEpisode = source.episodes.size() - 1;
                episodeAdapter.setSelected(selectedEpisode);
                Toast.makeText(this, "新线路没有当前集数，已切换到最近的集", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startSupplement() {
        supplementHandle = TvBoxApp.get().supplement().start(movie, new DetailSupplement.Listener() {
            @Override
            public void onLineAppended(Movie detail, int appendedLineCount) {
                if (isFinishing() || detail != movie) {
                    return;
                }
                // 主详情可能先以空集线路渲染；补线带来带集数的线路后重选默认线路
                PlaySource current = findSource(selectedLineId);
                if (current == null || current.episodes.isEmpty()) {
                    PlaySource better = findSource(defaultLineId(null));
                    if (better != null && !better.episodes.isEmpty()) {
                        selectLine(better.lineId, false);
                    }
                }
                List<ChipAdapter.Chip> chips = new ArrayList<ChipAdapter.Chip>();
                for (PlaySource ps : movie.playSources) {
                    chips.add(new ChipAdapter.Chip(ps.lineId, lineLabel(ps),
                            ps.lineId.equals(selectedLineId)));
                }
                lineAdapter.setChips(chips);
                status.setText(getString(R.string.detail_lines_added, movie.playSources.size(), appendedLineCount));
                status.setVisibility(View.VISIBLE);
            }

            @Override
            public void onProgress(int completedSources, int totalSources) {
                if (!isFinishing()) {
                    status.setText(getString(R.string.detail_lines_progress, movie.playSources.size(), completedSources, totalSources));
                    status.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onDone() {
                if (!isFinishing()) {
                    if (movie.playSources.size() > 1) {
                        status.setText(getString(R.string.detail_lines_total, movie.playSources.size()));
                    }
                }
            }
        });
    }

    private void playEpisode(int index) {
        if (movie == null) {
            return;
        }
        PlaySource source = findSource(selectedLineId);
        if (source == null || index < 0 || index >= source.episodes.size()) {
            Toast.makeText(this, "当前线路没有可播放的集数，请先换线路", Toast.LENGTH_SHORT).show();
            return;
        }
        selectedEpisode = index;
        episodeAdapter.setSelected(index);
        PlayerActivity.startForResult(this, apiId, movieId, selectedLineId, index);
    }

    private void playFromHistory() {
        WatchHistoryItem h = resumeHistory;
        if (h != null) {
            PlayerActivity.startForResultWithHistory(this, h, false);
        } else {
            playEpisode(0);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PlayerActivity.REQUEST_PLAYBACK && movie != null) {
            WatchHistoryItem returned = data == null ? null
                    : (WatchHistoryItem) data.getSerializableExtra(PlayerActivity.EXTRA_HISTORY_ITEM);
            if (resultCode == RESULT_OK && returned != null
                    && apiId.equals(returned.apiLineId) && movieId.equals(returned.movieId)) {
                resumeHistory = new WatchHistoryItem(returned);
                PlaybackSelection selection = PlaybackSelection.resolve(movie, returned, false);
                if (selection != null) {
                    selectedEpisode = selection.episodeIndex;
                    selectLine(selection.source.lineId, false);
                    continueBtn.setText(getString(R.string.continue_episode, selectedEpisode + 1));
                }
            }
            restoreEpisodeFocus();
        }
    }

    private void restoreEpisodeFocus() {
        if (selectedEpisode < 0) return;
        if (episodeFocusListener != null) episodesView.removeOnLayoutChangeListener(episodeFocusListener);
        final int target = selectedEpisode;
        final Runnable focus = new Runnable() {
            @Override public void run() {
                if (isFinishing() || isActivityDestroyed() || selectedEpisode != target) return;
                RecyclerView.ViewHolder holder = episodesView.findViewHolderForAdapterPosition(target);
                if (holder != null) {
                    holder.itemView.requestFocus();
                    if (episodeFocusListener != null) episodesView.removeOnLayoutChangeListener(episodeFocusListener);
                    episodeFocusListener = null;
                }
            }
        };
        episodeFocusListener = new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view, int l, int t, int r, int b, int ol, int ot, int or, int ob) { focus.run(); }
        };
        episodesView.addOnLayoutChangeListener(episodeFocusListener);
        episodesView.scrollToPosition(target);
        episodesView.post(focus);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("line", selectedLineId);
        out.putInt("episode", selectedEpisode);
        out.putBoolean("expanded", descExpanded);
        out.putBundle("focus", PageFocusState.capture(findViewById(android.R.id.content)));
        out.putSerializable(PlayerActivity.EXTRA_HISTORY_ITEM, resumeHistory);
        out.putBoolean("fallbackOffered", fallbackOffered);
    }

    @Override
    protected void onDestroy() {
        if (episodeFocusListener != null) episodesView.removeOnLayoutChangeListener(episodeFocusListener);
        if (detailRequest != null) {
            detailRequest.cancel();
        }
        if (supplementHandle != null) {
            supplementHandle.cancel();
        }
        super.onDestroy();
    }
}
