package com.tvbox.android44.common.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tvbox.android44.R;
import com.tvbox.android44.common.FocusScaler;

import java.util.ArrayList;
import java.util.List;

/** 分类/线路 chip 行适配器（横向）。 */
public class ChipAdapter extends RecyclerView.Adapter<ChipAdapter.Holder> {

    public static final class Chip {
        public final String id;
        public final String label;
        public boolean selected;

        public Chip(String id, String label, boolean selected) {
            this.id = id;
            this.label = label;
            this.selected = selected;
        }
    }

    private final List<Chip> chips = new ArrayList<Chip>();
    private final OnChipClick click;

    public interface OnChipClick {
        void onChipClick(Chip chip, int position);
    }

    public ChipAdapter(OnChipClick click) {
        this.click = click;
    }

    public void setChips(List<Chip> list) {
        chips.clear();
        if (list != null) {
            chips.addAll(list);
        }
        notifyDataSetChanged();
    }

    public void setSelected(String id) {
        for (Chip c : chips) {
            c.selected = c.id.equals(id);
        }
        notifyDataSetChanged();
    }

    public int indexOf(String id) {
        for (int i = 0; i < chips.size(); i++) {
            if (chips.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    public List<Chip> chips() {
        return chips;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_chip, parent, false);
        FocusScaler.attach(v);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull final Holder holder, int position) {
        final Chip c = chips.get(position);
        holder.label.setText(c.label);
        holder.itemView.setSelected(c.selected);
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (click != null) {
                    click.onChipClick(c, chips.indexOf(c));
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return chips.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView label;

        Holder(View itemView) {
            super(itemView);
            label = (TextView) itemView;
        }
    }
}
