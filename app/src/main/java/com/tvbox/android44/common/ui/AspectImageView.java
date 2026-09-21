package com.tvbox.android44.common.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatImageView;

/** 固定宽高比 ImageView（海报 2:3 / 封面 16:9）。 */
public class AspectImageView extends AppCompatImageView {

    public static final float RATIO_POSTER = 3f / 2f;   // 高/宽
    public static final float RATIO_COVER = 9f / 16f;

    private float ratio = RATIO_POSTER;

    public AspectImageView(Context context) {
        super(context);
    }

    public AspectImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setRatio(float ratio) {
        this.ratio = ratio;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int width = getMeasuredWidth();
        setMeasuredDimension(width, (int) (width * ratio + 0.5f));
    }
}
