package com.tvbox.android44.feature.detail;

import android.content.Context;
import android.view.LayoutInflater;
import android.widget.TextView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.tvbox.android44.R;

/** Size columns using the rendered label font (including the user's font scale). */
public final class EpisodeGridLayoutManager extends GridLayoutManager {
    private final TextView label;
    private final float density;
    private final EpisodeAdapter episodes;

    public EpisodeGridLayoutManager(Context context, EpisodeAdapter episodes) {
        super(context, 1);
        this.episodes = episodes;
        density = context.getResources().getDisplayMetrics().density;
        label = (TextView) LayoutInflater.from(context).inflate(R.layout.item_episode, null, false);
    }

    @Override public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        if (width > 0 && !state.isPreLayout()) {
            float textWidth = label.getPaint().measureText("第9999集");
            // Long programme descriptions may still ellipsize; numbered episodes must fit.
            for (int i = 0; i < episodes.getItemCount(); i++) {
                textWidth = Math.max(textWidth, Math.min(160 * density,
                        label.getPaint().measureText(episodes.titleAt(i))));
            }
            int minimum = (int) Math.ceil(Math.max(80 * density,
                    textWidth + label.getPaddingLeft() + label.getPaddingRight() + 12 * density));
            int columns = Math.max(1, Math.min(6, width / minimum));
            if (getSpanCount() != columns) setSpanCount(columns);
        }
        super.onLayoutChildren(recycler, state);
    }
}
