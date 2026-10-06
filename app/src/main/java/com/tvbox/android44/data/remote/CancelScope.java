package com.tvbox.android44.data.remote;

import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;

/**
 * 取消作用域：页面销毁/新请求时取消所有在途 okhttp Call。
 * 取消不计入来源失败，不触发冷却。
 */
public class CancelScope {

    private final List<Call> calls = new ArrayList<Call>();
    private volatile boolean cancelled;
    private final Runnable onCancel;

    public CancelScope() {
        this(null);
    }

    public CancelScope(Runnable onCancel) {
        this.onCancel = onCancel;
    }

    public synchronized void register(Call call) {
        if (cancelled) {
            call.cancel();
            return;
        }
        calls.add(call);
    }

    public synchronized void unregister(Call call) {
        calls.remove(call);
    }

    public void cancel() {
        List<Call> toCancel;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            toCancel = new ArrayList<Call>(calls);
            calls.clear();
        }
        for (Call c : toCancel) {
            c.cancel();
        }
        if (onCancel != null) {
            onCancel.run();
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
