package com.tvbox.android44.common.ui;

import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

/** 网格间距装饰。 */
public class GridSpacingDecoration extends RecyclerView.ItemDecoration {

    private final int spacingPx;

    public GridSpacingDecoration(int spacingPx) {
        this.spacingPx = spacingPx;
    }

    @Override
    public void getItemOffsets(Rect outRect, View view, RecyclerView parent,
                               RecyclerView.State state) {
        outRect.left = spacingPx / 2;
        outRect.right = spacingPx / 2;
        outRect.top = spacingPx / 2;
        outRect.bottom = spacingPx / 2;
    }
}
