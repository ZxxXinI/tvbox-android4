package com.tvbox.android44.feature.history;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.tvbox.android44.R;
import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.common.AppConstants;
import com.tvbox.android44.common.FocusScaler;
import com.tvbox.android44.common.StateLayout;
import com.tvbox.android44.common.TvDialogs;
import com.tvbox.android44.common.ui.GridSpacingDecoration;
import com.tvbox.android44.data.local.HistoryStore;
import com.tvbox.android44.domain.model.WatchHistoryItem;
import com.tvbox.android44.domain.parser.ContentFilter;
import com.tvbox.android44.feature.detail.DetailActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 历史页：按更新时间倒序；读取时再次内容过滤、损坏条目单独丢弃；
 * 点击进入详情（详情内完成“重新取详情→线路/集匹配→播放”的恢复链路）；
 * 清空有二次确认，完成后焦点回空态返回。
 */
public class HistoryFragment extends Fragment implements HistoryStore.Listener {

    private StateLayout state;
    private RecyclerView grid;
    private HistoryAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_history, container, false);
        state = root.findViewById(R.id.history_state);
        grid = root.findViewById(R.id.history_grid);
        grid.setLayoutManager(new GridLayoutManager(getActivity(),
                AppConstants.GRID_COLUMNS_LARGE));
        grid.addItemDecoration(new GridSpacingDecoration(
                (int) (getResources().getDisplayMetrics().density * 10)));
        adapter = new HistoryAdapter(new HistoryAdapter.OnHistoryClick() {
            @Override
            public void onHistoryClick(WatchHistoryItem item) {
                // 恢复链路：详情页重新取详情并按线路名+集标题定位；失败时用历史 URL 短期回退
                DetailActivity.startForResult(getActivity(), item.apiLineId, item.movieId);
            }
        });
        grid.setAdapter(adapter);

        root.findViewById(R.id.history_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TvDialogs.confirm(getActivity(), "清空历史",
                        "确定清空全部 " + adapter.getItemCount() + " 条观看历史吗？此操作不可恢复。",
                        new TvDialogs.ConfirmListener() {
                            @Override
                            public void onConfirm() {
                                TvBoxApp.get().history().clear();
                            }
                        });
            }
        });
        TvBoxApp.get().history().addListener(this);
        refresh();
        return root;
    }

    @Override
    public void onHistoryChanged() {
        if (isAdded()) {
            refresh();
        }
    }

    private void refresh() {
        List<WatchHistoryItem> all = TvBoxApp.get().history().load();
        List<WatchHistoryItem> valid = new ArrayList<WatchHistoryItem>();
        for (WatchHistoryItem h : all) {
            // 过滤规则一致作用于历史；关键字段损坏的条目单独丢弃
            if (h.movieId == null || h.movieId.isEmpty()
                    || h.apiLineId == null || h.apiLineId.isEmpty()) {
                continue;
            }
            if (ContentFilter.isBlocked(h.movieName, h.typeName, h.remarks)) {
                continue;
            }
            valid.add(h);
        }
        if (valid.isEmpty()) {
            adapter.setItems(valid);
            state.showEmpty("暂无观看历史，看过的影片会出现在这里");
        } else {
            state.showContent();
            adapter.setItems(valid);
            if (grid.findFocus() == null && grid.getChildCount() > 0) {
                grid.getChildAt(0).requestFocus();
            }
        }
    }

    @Override
    public void onDestroyView() {
        TvBoxApp.get().history().removeListener(this);
        super.onDestroyView();
    }

    static final class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.Holder> {

        interface OnHistoryClick {
            void onHistoryClick(WatchHistoryItem item);
        }

        private final List<WatchHistoryItem> items = new ArrayList<WatchHistoryItem>();
        private final OnHistoryClick click;

        HistoryAdapter(OnHistoryClick click) {
            this.click = click;
        }

        void setItems(List<WatchHistoryItem> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_history, parent, false);
            FocusScaler.attach(v);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull final Holder holder, int position) {
            final WatchHistoryItem h = items.get(position);
            holder.title.setText(h.movieName);
            holder.episode.setText("第" + (h.episodeIndex + 1) + "集 · "
                    + (h.lineName == null || h.lineName.isEmpty() ? "" : h.lineName + " · ")
                    + "看到" + percent(h) + "%");
            holder.progress.setProgress(h.progressPercent());
            if (h.posterUrl != null && !h.posterUrl.isEmpty()) {
                Glide.with(holder.poster.getContext())
                        .load(h.posterUrl)
                        .centerCrop()
                        .placeholder(R.drawable.poster_placeholder)
                        .into(holder.poster);
            } else {
                holder.poster.setImageResource(R.drawable.poster_placeholder);
            }
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    click.onHistoryClick(h);
                }
            });
        }

        private static String percent(WatchHistoryItem h) {
            return String.valueOf(Math.max(0, h.progressPercent()));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final com.tvbox.android44.common.ui.AspectImageView poster;
            final ProgressBar progress;
            final TextView title;
            final TextView episode;

            Holder(View itemView) {
                super(itemView);
                poster = itemView.findViewById(R.id.history_poster);
                progress = itemView.findViewById(R.id.history_progress);
                title = itemView.findViewById(R.id.history_title);
                episode = itemView.findViewById(R.id.history_episode);
            }
        }
    }
}
