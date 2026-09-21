package com.tvbox.android44.common;

import android.view.View;
import android.view.ViewGroup;

/** 焦点工具：查找第一个可聚焦控件、安全请求焦点。 */
public final class FocusUtils {

    private FocusUtils() {
    }

    public static View firstFocusable(View root) {
        if (root == null) {
            return null;
        }
        if (root.isFocusable() && root.getVisibility() == View.VISIBLE) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View v = firstFocusable(group.getChildAt(i));
                if (v != null) {
                    return v;
                }
            }
        }
        return null;
    }
}
