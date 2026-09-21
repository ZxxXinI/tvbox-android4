package com.tvbox.android44.feature.platformlive;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.tvbox.android44.R;
import com.tvbox.android44.common.FocusScaler;
import com.tvbox.android44.common.ui.AspectImageView;
import com.tvbox.android44.domain.model.PlatformLive;
import com.tvbox.android44.domain.model.WatchHistoryItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 平台直播通用行/网格适配器。 */
public class PlatformAdapters {

    private PlatformAdapters() {
    }

    /** 平台 / 分类行。 */
    public static final class RowAdapter extends RecyclerView.Adapter<RowAdapter.Holder> {

        public interface OnRowClick {
            void onRowClick(String id, String label);
        }

        public static final class Row {
            public final String id;
            public final String label;

            public Row(String id, String label) {
                this.id = id;
                this.label = label;
            }
        }

        private final List<Row> rows = new ArrayList<Row>();
        private final OnRowClick click;

        public RowAdapter(OnRowClick click) {
            this.click = click;
        }

        public void setRows(List<Row> list) {
            rows.clear();
            rows.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_menu_row, parent, false);
            FocusScaler.attach(v);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull final Holder holder, int position) {
            final Row r = rows.get(position);
            ((TextView) holder.itemView).setText(r.label);
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    click.onRowClick(r.id, r.label);
                }
            });
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        static final class Holder extends RecyclerView.ViewHolder {
            Holder(View itemView) {
                super(itemView);
            }
        }
    }

    /** 房间网格（16:9 封面，按 site+roomId 去重保持顺序）。 */
    public static final class RoomAdapter extends RecyclerView.Adapter<RoomAdapter.Holder> {

        public interface OnRoomClick {
            void onRoomClick(PlatformLive.Room room);
        }

        private final List<PlatformLive.Room> rooms = new ArrayList<PlatformLive.Room>();
        private final Set<String> keys = new HashSet<String>();
        private final OnRoomClick click;

        public RoomAdapter(OnRoomClick click) {
            this.click = click;
        }

        /** 返回新增条数（分页去重）。 */
        public int appendRooms(List<PlatformLive.Room> list) {
            int added = 0;
            for (PlatformLive.Room r : list) {
                if (keys.add(r.dedupeKey())) {
                    rooms.add(r);
                    added++;
                }
            }
            if (added > 0) {
                notifyItemRangeInserted(rooms.size() - added, added);
            }
            return added;
        }

        public void clear() {
            rooms.clear();
            keys.clear();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_room, parent, false);
            ((AspectImageView) v.findViewById(R.id.room_cover)).setRatio(AspectImageView.RATIO_COVER);
            FocusScaler.attach(v);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull final Holder holder, int position) {
            final PlatformLive.Room r = rooms.get(position);
            holder.title.setText(r.title == null || r.title.isEmpty() ? r.roomId : r.title);
            holder.anchor.setText((r.living ? "直播中" : "未开播")
                    + (r.anchorName == null || r.anchorName.isEmpty() ? "" : " · " + r.anchorName)
                    + (r.viewers >= 0 ? " · " + formatCount(r.viewers) : ""));
            if (r.coverUrl != null && !r.coverUrl.isEmpty()) {
                Glide.with(holder.cover.getContext())
                        .load(r.coverUrl)
                        .centerCrop()
                        .placeholder(R.drawable.poster_placeholder)
                        .into(holder.cover);
            } else {
                holder.cover.setImageResource(R.drawable.poster_placeholder);
            }
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    click.onRoomClick(r);
                }
            });
        }

        private static String formatCount(int n) {
            if (n >= 10000) {
                return String.format(java.util.Locale.CHINA, "%.1f万", n / 10000f);
            }
            return String.valueOf(n);
        }

        @Override
        public int getItemCount() {
            return rooms.size();
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final AspectImageView cover;
            final TextView title;
            final TextView anchor;

            Holder(View itemView) {
                super(itemView);
                cover = itemView.findViewById(R.id.room_cover);
                title = itemView.findViewById(R.id.room_title);
                anchor = itemView.findViewById(R.id.room_anchor);
            }
        }
    }
}
