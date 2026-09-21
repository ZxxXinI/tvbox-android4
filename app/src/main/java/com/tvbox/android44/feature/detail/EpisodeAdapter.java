package com.tvbox.android44.feature.detail;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tvbox.android44.R;
import com.tvbox.android44.common.FocusScaler;
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

    public EpisodeAdapter(OnEpisodeClick click) {
        this.click = click;
    }

    public void setEpisodes(List<PlayEpisode> list, int selected) {
        episodes.clear();
        if (list != null) {
            episodes.addAll(list);
        }
        this.selected = selected;
        notifyDataSetChanged();
    }

    public void setSelected(int selected) {
        int old = this.selected;
        this.selected = selected;
        if (old >= 0) {
            notifyItemChanged(old);
        }
        if (selected >= 0) {
            notifyItemChanged(selected);
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
                    click.onEpisodeClick(index);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return episodes.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView label;

        Holder(View itemView) {
            super(itemView);
            label = (TextView) itemView;
        }
    }
}
