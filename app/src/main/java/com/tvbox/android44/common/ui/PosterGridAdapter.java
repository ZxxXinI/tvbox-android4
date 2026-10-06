package com.tvbox.android44.common.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.tvbox.android44.R;
import com.tvbox.android44.common.FocusScaler;

import java.util.ArrayList;
import java.util.List;

/**
 * 海报网格适配器：增量追加不重置焦点（notifyItemRangeInserted）；
 * 复用后不残留焦点样式（绑定态每次刷新）。
 */
public class PosterGridAdapter extends RecyclerView.Adapter<PosterGridAdapter.Holder> {

    private final List<PosterEntry> entries = new ArrayList<PosterEntry>();
    private final OnPosterClick click;
    private final float posterRatio;

    public interface OnPosterClick {
        void onPosterClick(PosterEntry entry, int position);
    }

    public PosterGridAdapter(OnPosterClick click) {
        this(click, AspectImageView.RATIO_POSTER);
    }

    public PosterGridAdapter(OnPosterClick click, float posterRatio) {
        this.click = click;
        this.posterRatio = posterRatio;
        setHasStableIds(true);
        setStateRestorationPolicy(StateRestorationPolicy.PREVENT_WHEN_EMPTY);
    }

    public void setEntries(List<PosterEntry> list) {
        entries.clear();
        if (list != null) {
            entries.addAll(list);
        }
        notifyDataSetChanged();
    }

    public void appendEntries(List<PosterEntry> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        int start = entries.size();
        for (PosterEntry entry : list) {
            if (indexOfKey(entry.key) < 0) entries.add(entry);
        }
        notifyItemRangeInserted(start, entries.size() - start);
    }

    /** Stable positions allow a late preferred source to update a card without replacing focus. */
    public void updateEntries(List<PosterEntry> list) {
        int existing = entries.size();
        int shared = Math.min(existing, list.size());
        for (int i = 0; i < shared; i++) {
            entries.set(i, list.get(i));
            notifyItemChanged(i, "metadata");
        }
        if (list.size() > existing) appendEntries(list.subList(existing, list.size()));
        else if (existing > list.size()) {
            entries.subList(list.size(), existing).clear();
            notifyItemRangeRemoved(list.size(), existing - list.size());
        }
    }

    @Override public long getItemId(int position) {
        return com.tvbox.android44.common.PageFocusState.stableId(entries.get(position).key);
    }

    public void clear() {
        entries.clear();
        notifyDataSetChanged();
    }

    public List<PosterEntry> entries() {
        return entries;
    }

    public int indexOfKey(String key) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).key.equals(key)) {
                return i;
            }
        }
        return -1;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_poster, parent, false);
        Holder h = new Holder(v);
        ((AspectImageView) h.image).setRatio(posterRatio);
        FocusScaler.attach(v);
        return h;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        final PosterEntry e = entries.get(position);
        holder.title.setText(e.title);
        holder.subtitle.setText(e.subtitle == null ? "" : e.subtitle);
        holder.subtitle.setVisibility(e.subtitle == null || e.subtitle.isEmpty()
                ? View.GONE : View.VISIBLE);
        if (e.posterUrl != null && !e.posterUrl.isEmpty()) {
            Glide.with(holder.image.getContext())
                    .load(e.posterUrl)
                    .centerCrop()
                    .placeholder(R.drawable.poster_placeholder)
                    .into(holder.image);
        } else {
            Glide.with(holder.image.getContext()).clear(holder.image);
            holder.image.setImageResource(R.drawable.poster_placeholder);
        }
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (click != null) {
                    click.onPosterClick(e, entries.indexOf(e));
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final android.widget.ImageView image;
        final TextView title;
        final TextView subtitle;

        Holder(View itemView) {
            super(itemView);
            image = (android.widget.ImageView) itemView.findViewById(R.id.poster_image);
            title = itemView.findViewById(R.id.poster_title);
            subtitle = itemView.findViewById(R.id.poster_subtitle);
        }
    }
}
