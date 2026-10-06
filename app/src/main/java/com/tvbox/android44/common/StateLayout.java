package com.tvbox.android44.common;

import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.tvbox.android44.R;

/**
 * 页面状态统一组件：加载 / 空 / 错误(带重试) / 内容。
 * 任何页面都必须有明确加载态、空态、错误态、重试入口。
 */
public class StateLayout extends FrameLayout {

    private ProgressBar loading;
    private TextView message;
    private Button retry;
    private View content;

    private OnRetryListener retryListener;

    public StateLayout(Context context) {
        super(context);
        init();
    }

    public StateLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        LayoutInflater.from(getContext()).inflate(R.layout.view_state, this, true);
        loading = findViewById(R.id.state_loading);
        message = findViewById(R.id.state_message);
        retry = findViewById(R.id.state_retry);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (retryListener != null) {
                    retryListener.onRetry();
                }
            }
        });
        showContent();
    }

    /** 设置真实内容视图（状态层覆盖其上）。 */
    public void setContentView(View view) {
        if (content != null) {
            removeView(content);
        }
        content = view;
        addView(view, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    public void showLoading(String text) {
        setState(true, false, false);
        message.setText(text == null ? getContext().getString(R.string.loading) : text);
    }

    public void showEmpty(String text) {
        setState(false, true, false);
        message.setText(text);
    }

    public void showError(String text, OnRetryListener listener) {
        setState(false, true, true);
        message.setText(text);
        this.retryListener = listener;
        if (isShown()) retry.requestFocus();
    }

    public void showContent() {
        setState(false, false, false);
    }

    public boolean isShowingStatus() {
        return loading.getVisibility() == VISIBLE || message.getVisibility() == VISIBLE;
    }

    private void setState(boolean isLoading, boolean showMessage, boolean showRetry) {
        loading.setVisibility(isLoading ? VISIBLE : GONE);
        message.setVisibility(showMessage ? VISIBLE : GONE);
        retry.setVisibility(showRetry ? VISIBLE : GONE);
    }

    public interface OnRetryListener {
        void onRetry();
    }
}
