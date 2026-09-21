package com.tvbox.android44.common.ui;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** 竖向列表条目间距（顶部/底部各半，首尾不外扩）。 */
public class VerticalSpacingDecoration extends RecyclerView.ItemDecoration {

    private final int spacingPx;

    public VerticalSpacingDecoration(int spacingPx) {
        this.spacingPx = Math.max(0, spacingPx);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                               @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int position = parent.getChildAdapterPosition(view);
        int half = spacingPx / 2;
        outRect.top = position == 0 ? 0 : half;
        outRect.bottom = half;
    }
}
