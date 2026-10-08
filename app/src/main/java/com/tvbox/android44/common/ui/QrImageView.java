package com.tvbox.android44.common.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.util.AttributeSet;
import android.view.View;
import androidx.appcompat.widget.AppCompatImageView;
import com.tvbox.android44.common.QrCode;

/** Generate at the final physical pixel size, with no ImageView resampling. */
public class QrImageView extends AppCompatImageView {
    public interface ReadyListener { void onReady(boolean success); }
    private String code;
    private ReadyListener listener;
    private int renderedSide;

    public QrImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.CENTER);
        setBackgroundColor(Color.WHITE);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    public void setCode(String code, ReadyListener listener) {
        this.code = code; this.listener = listener; renderedSide = 0;
        render();
        requestLayout();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int side = Math.min((int) (320 * getResources().getDisplayMetrics().density),
                getResources().getDisplayMetrics().heightPixels / 2);
        if (View.MeasureSpec.getMode(widthSpec) != View.MeasureSpec.UNSPECIFIED) {
            side = Math.min(side, View.MeasureSpec.getSize(widthSpec));
        }
        if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.UNSPECIFIED) {
            side = Math.min(side, View.MeasureSpec.getSize(heightSpec));
        }
        side = Math.max(1, side);
        setMeasuredDimension(resolveSize(side, widthSpec), resolveSize(side, heightSpec));
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        render();
    }

    private void render() {
        int side = Math.min(getWidth(), getHeight());
        if (code == null || side <= 0 || side == renderedSide) return;
        renderedSide = side;
        Bitmap bitmap = QrCode.encode(code, side);
        setImageBitmap(bitmap);
        if (getDrawable() instanceof BitmapDrawable) {
            BitmapDrawable drawable = (BitmapDrawable) getDrawable();
            drawable.setFilterBitmap(false);
            drawable.setDither(false);
            drawable.getPaint().setFilterBitmap(false);
            drawable.getPaint().setDither(false);
            drawable.getPaint().setFlags(drawable.getPaint().getFlags()
                    & ~(android.graphics.Paint.FILTER_BITMAP_FLAG | android.graphics.Paint.DITHER_FLAG));
        }
        if (listener != null) listener.onReady(bitmap != null);
    }
}
