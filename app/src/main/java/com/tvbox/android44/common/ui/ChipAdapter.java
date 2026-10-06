package com.tvbox.android44.common.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.tvbox.android44.R;
import com.tvbox.android44.common.FocusScaler;
import com.tvbox.android44.common.PageFocusState;

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
        setHasStableIds(true);
        setStateRestorationPolicy(StateRestorationPolicy.PREVENT_WHEN_EMPTY);
    }

    public void setChips(List<Chip> list) {
        final List<Chip> old = new ArrayList<Chip>(chips);
        final List<Chip> next = new ArrayList<Chip>();
        if (list != null) {
            for (Chip chip : list) next.add(new Chip(chip.id, chip.label, chip.selected));
        }
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return old.size(); }
            @Override public int getNewListSize() { return next.size(); }
            @Override public boolean areItemsTheSame(int before, int after) {
                return old.get(before).id.equals(next.get(after).id);
            }
            @Override public boolean areContentsTheSame(int before, int after) {
                Chip a = old.get(before), b = next.get(after);
                return a.label.equals(b.label) && a.selected == b.selected;
            }
        });
        chips.clear();
        chips.addAll(next);
        diff.dispatchUpdatesTo(this);
    }

    public void setSelected(String id) {
        for (int i = 0; i < chips.size(); i++) {
            Chip chip = chips.get(i);
            boolean selected = chip.id.equals(id);
            if (chip.selected != selected) {
                chip.selected = selected;
                notifyItemChanged(i);
            }
        }
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
                    int current = holder.getBindingAdapterPosition();
                    if (current != RecyclerView.NO_POSITION) click.onChipClick(chips.get(current), current);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return chips.size();
    }

    @Override public long getItemId(int position) {
        return PageFocusState.stableId(chips.get(position).id);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView label;

        Holder(View itemView) {
            super(itemView);
            label = (TextView) itemView;
        }
    }
}
