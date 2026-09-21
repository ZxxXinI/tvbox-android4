package com.tvbox.android44.common;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.tvbox.android44.R;

import java.util.List;

/** 电视友好对话框：确认框 / 单选列表；关闭后焦点由调用方恢复。 */
public final class TvDialogs {

    private TvDialogs() {
    }

    public interface ConfirmListener {
        void onConfirm();
    }

    public interface ChoiceListener {
        void onChoice(int index);
    }

    public static AlertDialog confirm(Context context, String title, String message,
                                      final ConfirmListener listener) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_confirm, null);
        TextView tv = view.findViewById(R.id.dialog_message);
        tv.setText(message);
        final AlertDialog dialog = new AlertDialog.Builder(context, R.style.TvDialog)
                .setTitle(title)
                .setView(view)
                .create();
        view.findViewById(R.id.dialog_ok).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                listener.onConfirm();
            }
        });
        view.findViewById(R.id.dialog_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        dialog.show();
        view.findViewById(R.id.dialog_cancel).requestFocus();
        return dialog;
    }

    public static AlertDialog singleChoice(Context context, String title,
                                           List<String> items, int checked,
                                           final ChoiceListener listener) {
        String[] arr = items.toArray(new String[0]);
        final AlertDialog dialog = new AlertDialog.Builder(context, R.style.TvDialog)
                .setTitle(title)
                .setSingleChoiceItems(arr, checked, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        d.dismiss();
                        listener.onChoice(which);
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        dialog.show();
        return dialog;
    }
}
