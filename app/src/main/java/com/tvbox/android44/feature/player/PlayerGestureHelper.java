package com.tvbox.android44.feature.player;

import android.content.Context;
import android.graphics.PointF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

/**
 * 手机触摸手势（文档 05 §7）：单击显示/隐藏控制层；双击左/中/右（快退/播放暂停/快进）；
 * 横向滑动调整进度；左半屏纵向滑动亮度、右半屏音量；长按 2 倍速。
 * 手势设置阈值，轻微移动不同时识别为点击与滑动。
 */
public class PlayerGestureHelper implements View.OnTouchListener {

    public interface Callback {
        void onSingleTap();

        void onDoubleTapLeft();

        void onDoubleTapCenter();

        void onDoubleTapRight();

        void onLongPressSpeed();

        void onLongPressUp();

        void onHorizontalDrag(float deltaPx);

        void onHorizontalDragEnd();

        void onVerticalDrag(boolean leftHalf, float deltaPx);
    }

    private static final int TAP_SLOP_DP = 24;

    private final GestureDetector detector;
    private final Callback callback;
    private final float density;
    private PointF downPoint;
    private boolean draggingHorizontal;
    private boolean draggingVertical;
    private boolean longPressed;

    public PlayerGestureHelper(Context context, Callback callback) {
        this.callback = callback;
        this.density = context.getResources().getDisplayMetrics().density;
        this.detector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                callback.onSingleTap();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float x = e.getX();
                int w = ((View) detectorListenerView).getWidth();
                if (x < w / 3f) {
                    callback.onDoubleTapLeft();
                } else if (x > w * 2f / 3f) {
                    callback.onDoubleTapRight();
                } else {
                    callback.onDoubleTapCenter();
                }
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                longPressed = true;
                callback.onLongPressSpeed();
            }
        });
    }

    private View detectorListenerView;

    public void bind(View view) {
        detectorListenerView = view;
        view.setOnTouchListener(this);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        detector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downPoint = new PointF(event.getX(), event.getY());
                longPressed = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (downPoint == null) {
                    break;
                }
                float dx = event.getX() - downPoint.x;
                float dy = event.getY() - downPoint.y;
                float slop = TAP_SLOP_DP * density;
                if (!draggingHorizontal && !draggingVertical) {
                    if (Math.abs(dx) > slop * 2 && Math.abs(dx) > Math.abs(dy)) {
                        draggingHorizontal = true;
                    } else if (Math.abs(dy) > slop * 2 && Math.abs(dy) > Math.abs(dx)) {
                        draggingVertical = true;
                    }
                }
                if (draggingHorizontal) {
                    callback.onHorizontalDrag(dx);
                } else if (draggingVertical) {
                    boolean leftHalf = downPoint.x < ((float) v.getWidth()) / 2f;
                    callback.onVerticalDrag(leftHalf, dy);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (draggingHorizontal) {
                    callback.onHorizontalDragEnd();
                }
                if (longPressed) {
                    callback.onLongPressUp();
                }
                draggingHorizontal = false;
                draggingVertical = false;
                downPoint = null;
                break;
            default:
                break;
        }
        return true;
    }
}
