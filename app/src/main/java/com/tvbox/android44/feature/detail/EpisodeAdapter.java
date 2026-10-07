package com.tvbox.android44.feature.detail;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.tvbox.android44.R;
import com.tvbox.android44.common.FocusScaler;
import com.tvbox.android44.common.PageFocusState;
import com.tvbox.android44.domain.model.PlayEpisode;

import java.util.ArrayList;
import java.util.List;

/** 选集网格适配器（大量剧集滚动；选中态每次绑定刷新）。 */
public class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.Holder> {

    public interface OnEpisodeClick {
        void onEpisodeClick(int index);
    }

    private final List<PlayEpisode> episodes = new ArrayList<PlayEpisode>();
    private final OnEpisodeClick click;
    private int selected = -1;
    private String lineId = "";

    public EpisodeAdapter(OnEpisodeClick click) {
        this.click = click;
        setHasStableIds(true);
        setStateRestorationPolicy(StateRestorationPolicy.PREVENT_WHEN_EMPTY);
    }

    public void setEpisodes(final String lineId, List<PlayEpisode> list, int selected) {
        final List<PlayEpisode> old = new ArrayList<PlayEpisode>(episodes);
        final List<PlayEpisode> next = list == null
                ? new ArrayList<PlayEpisode>() : new ArrayList<PlayEpisode>(list);
        final String oldLine = this.lineId;
        final int oldSelected = this.selected;
        final int nextSelected = selected >= 0 && selected < next.size() ? selected : -1;
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return old.size(); }
            @Override public int getNewListSize() { return next.size(); }
            @Override public boolean areItemsTheSame(int before, int after) {
                return key(oldLine, old.get(before)).equals(key(lineId, next.get(after)));
            }
            @Override public boolean areContentsTheSame(int before, int after) {
                return old.get(before).url.equals(next.get(after).url)
                        && (before == oldSelected) == (after == nextSelected);
            }
        });
        episodes.clear();
        episodes.addAll(next);
        this.lineId = lineId;
        this.selected = nextSelected;
        diff.dispatchUpdatesTo(this);
    }

    public void setSelected(int selected) {
        int old = this.selected;
        this.selected = selected >= 0 && selected < episodes.size() ? selected : -1;
        if (old == this.selected) return;
        if (old >= 0 && old < episodes.size()) {
            notifyItemChanged(old);
        }
        if (this.selected >= 0) {
            notifyItemChanged(this.selected);
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_episode, parent, false);
        FocusScaler.attach(v);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull final Holder holder, int position) {
        final int index = position;
        holder.label.setText(episodes.get(index).title);
        holder.itemView.setSelected(index == selected);
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (click != null) {
                    int current = holder.getBindingAdapterPosition();
                    if (current != RecyclerView.NO_POSITION) click.onEpisodeClick(current);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return episodes.size();
    }

    String titleAt(int position) {
        return episodes.get(position).title;
    }

    @Override public long getItemId(int position) {
        return PageFocusState.stableId(key(lineId, episodes.get(position)));
    }

    private static String key(String lineId, PlayEpisode episode) {
        return lineId + "\u001f" + episode.index + "\u001f" + episode.title;
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView label;

        Holder(View itemView) {
            super(itemView);
            label = (TextView) itemView;
        }
    }
}
