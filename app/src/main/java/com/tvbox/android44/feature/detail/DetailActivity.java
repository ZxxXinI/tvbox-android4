package com.tvbox.android44.feature.detail;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.tvbox.android44.R;
import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.BaseActivity;
import com.tvbox.android44.common.FocusScaler;
import com.tvbox.android44.common.Result;
import com.tvbox.android44.common.StateLayout;
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

import java.util.ArrayList;
import java.util.List;

/**
 * 详情页：主详情先显示，后台渐进补线（追加不重置线路/选集/焦点）；
 * 历史恢复优先按线路名+集标题匹配。
 */
public class DetailActivity extends BaseActivity {

    public static final String EXTRA_API_ID = "apiId";
    public static final String EXTRA_MOVIE_ID = "movieId";

    private static final int REQ_PLAYER = 1001;

    public static void startForResult(Activity from, String apiId, String movieId) {
        Intent intent = new Intent(from, DetailActivity.class);
        intent.putExtra(EXTRA_API_ID, apiId);
        intent.putExtra(EXTRA_MOVIE_ID, movieId);
        from.startActivityForResult(intent, REQ_PLAYER);
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

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        apiId = getIntent().getStringExtra(EXTRA_API_ID);
        movieId = getIntent().getStringExtra(EXTRA_MOVIE_ID);

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

        episodesView.setLayoutManager(new GridLayoutManager(this, 6));
        episodesView.addItemDecoration(new GridSpacingDecoration(
                (int) (getResources().getDisplayMetrics().density * 8)));
        episodeAdapter = new EpisodeAdapter(new EpisodeAdapter.OnEpisodeClick() {
            @Override
            public void onEpisodeClick(int index) {
                playEpisode(index);
            }
        });
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

        loadDetail();
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
                        if (!isFinishing()) {
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
            return;
        }
        movie = result.data();
        renderDetail();
        state.showContent();
        startSupplement();
        if (movie.playSources.isEmpty()) {
            status.setText("暂无可播放线路，可稍后重试或换接口");
            status.setVisibility(View.VISIBLE);
        }
        playBtn.requestFocus();
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
        WatchHistoryItem h = TvBoxApp.get().history().find(movie.apiLineId, movie.id);
        String preferLine = h != null ? h.lineId : null;
        int preferEpisode = h != null ? h.episodeIndex : 0;

        List<ChipAdapter.Chip> chips = new ArrayList<ChipAdapter.Chip>();
        String defaultLine = defaultLineId(preferLine);
        for (PlaySource ps : movie.playSources) {
            chips.add(new ChipAdapter.Chip(ps.lineId, lineLabel(ps), ps.lineId.equals(defaultLine)));
        }
        lineAdapter.setChips(chips);
        selectedLineId = defaultLine;
        selectLine(defaultLine, false);
        selectedEpisode = preferEpisode;
        if (h != null) {
            // 按集标题匹配
            PlaySource source = findSource(selectedLineId);
            if (source != null && h.episodeTitle != null) {
                for (int i = 0; i < source.episodes.size(); i++) {
                    if (h.episodeTitle.equals(source.episodes.get(i).title)) {
                        selectedEpisode = i;
                        break;
                    }
                }
                if (selectedEpisode >= source.episodes.size()) {
                    selectedEpisode = 0;
                }
            }
            continueBtn.setText("继续播放 第" + (selectedEpisode + 1) + "集");
        } else {
            continueBtn.setText("从第1集播放");
        }
        episodeAdapter.setSelected(selectedEpisode);
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
        episodeAdapter.setEpisodes(source.episodes, selectedEpisode);
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
                status.setText("已找到 " + movie.playSources.size() + " 条线路（新增 " + appendedLineCount + "）");
                status.setVisibility(View.VISIBLE);
            }

            @Override
            public void onProgress(int completedSources, int totalSources) {
                if (!isFinishing()) {
                    status.setText("已找到 " + movie.playSources.size() + " 条线路 · 补线中 "
                            + completedSources + "/" + totalSources);
                    status.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onDone() {
                if (!isFinishing()) {
                    if (movie.playSources.size() > 1) {
                        status.setText("共 " + movie.playSources.size() + " 条线路");
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
        WatchHistoryItem h = TvBoxApp.get().history().find(apiId, movie.id);
        if (h != null) {
            PlayerActivity.startForResultWithHistory(this, apiId, movieId, h.lineId,
                    h.episodeIndex, h.position, h.episodeUrl, h.episodeTitle);
        } else {
            playEpisode(0);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PLAYER) {
            // 播放器返回：恢复选集焦点
            if (episodeAdapter != null && selectedEpisode >= 0) {
                RecyclerView.ViewHolder vh =
                        episodesView.findViewHolderForAdapterPosition(selectedEpisode);
                if (vh != null) {
                    vh.itemView.requestFocus();
                } else {
                    episodesView.scrollToPosition(selectedEpisode);
                }
            }
            // 刷新历史继续播放按钮
            WatchHistoryItem h = TvBoxApp.get().history().find(apiId, movie.id);
            if (h != null && movie != null) {
                continueBtn.setText("继续播放 第" + (h.episodeIndex + 1) + "集");
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (detailRequest != null) {
            detailRequest.cancel();
        }
        if (supplementHandle != null) {
            supplementHandle.cancel();
        }
        super.onDestroy();
    }
}
