package com.tvbox.android44.common;

import android.os.Bundle;
import android.view.View;
import android.view.ViewParent;
import androidx.recyclerview.widget.RecyclerView;

/** Small focus bookmark; data stays in repositories rather than in saved-state bundles. */
public final class PageFocusState {
    public interface Owner { void restorePageFocus(Bundle state); }
    private PageFocusState() {}

    public static Bundle capture(View root) {
        return capture(root, root == null ? null : root.findFocus());
    }

    public static Bundle capture(View root, View focused) {
        Bundle state = new Bundle();
        if (root == null) return state;
        if (focused == null) return state;
        state.putInt("view", focused.getId());
        View child = focused;
        for (ViewParent parent = child.getParent(); parent instanceof View; parent = child.getParent()) {
            if (parent instanceof RecyclerView) {
                RecyclerView list = (RecyclerView) parent;
                RecyclerView.ViewHolder holder = list.findContainingViewHolder(focused);
                if (holder != null) {
                    state.putInt("list", list.getId());
                    state.putInt("position", holder.getAdapterPosition());
                    state.putLong("item", holder.getItemId());
                }
                break;
            }
            child = (View) parent;
        }
        return state;
    }

    public static boolean restore(final View root, Bundle state) {
        if (root == null || state == null || state.isEmpty()) return false;
        int listId = state.getInt("list", View.NO_ID);
        View target = root.findViewById(listId == View.NO_ID ? state.getInt("view", View.NO_ID) : listId);
        if (!(target instanceof RecyclerView)) return target != null && target.requestFocus();
        final RecyclerView list = (RecyclerView) target;
        RecyclerView.Adapter<?> adapter = list.getAdapter();
        if (adapter == null || adapter.getItemCount() == 0) return false;
        int position = Math.max(0, Math.min(state.getInt("position", 0), adapter.getItemCount() - 1));
        if (adapter.hasStableIds()) {
            long id = state.getLong("item", RecyclerView.NO_ID);
            for (int i = 0; i < adapter.getItemCount(); i++) {
                if (adapter.getItemId(i) == id) { position = i; break; }
            }
        }
        final int selected = position;
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(selected);
        if (holder != null && holder.getAdapterPosition() == selected) return holder.itemView.requestFocus();
        final class PendingFocus implements View.OnLayoutChangeListener, View.OnAttachStateChangeListener, Runnable {
            public void clear() {
                list.removeOnLayoutChangeListener(this);
                list.removeOnAttachStateChangeListener(this);
            }
            public void run() {
                if (!root.isShown()) { clear(); return; }
                RecyclerView.ViewHolder item = list.findViewHolderForAdapterPosition(selected);
                if (item != null && item.getAdapterPosition() == selected) { clear(); item.itemView.requestFocus(); }
            }
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) { run(); }
            public void onViewAttachedToWindow(View v) { }
            public void onViewDetachedFromWindow(View v) { clear(); }
        }
        PendingFocus pending = new PendingFocus();
        list.addOnLayoutChangeListener(pending);
        list.addOnAttachStateChangeListener(pending);
        list.scrollToPosition(selected);
        list.post(pending);
        return true;
    }

    public static long stableId(String key) {
        long id = 0xcbf29ce484222325L;
        for (int i = 0; i < key.length(); i++) { id ^= key.charAt(i); id *= 0x100000001b3L; }
        return id;
    }
}
