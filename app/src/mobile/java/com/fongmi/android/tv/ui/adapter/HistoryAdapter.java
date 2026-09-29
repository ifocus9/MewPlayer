package com.fongmi.android.tv.ui.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.databinding.AdapterHistoryBinding;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.Locale;

public class HistoryAdapter extends BaseDiffAdapter<History, HistoryAdapter.ViewHolder> {

    private final OnClickListener listener;
    private boolean delete;

    public HistoryAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {

        void onItemClick(History item);

        void onItemDelete(History item);

        boolean onLongClick();
    }

    public boolean isDelete() {
        return delete;
    }

    public void setDelete(boolean delete) {
        this.delete = delete;
        notifyItemRangeChanged(0, getItemCount());
    }

    @Override
    public void clear() {
        super.clear();
        setDelete(false);
        History.deleteAndSync(VodConfig.getCid());
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterHistoryBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        History item = getItem(position);
        holder.binding.name.setText(item.getVodName());

        String siteName = TextUtils.isEmpty(item.getSiteName()) ? "--" : item.getSiteName();
        holder.binding.site.setText(holder.itemView.getContext().getString(R.string.history_item_site, siteName));

        String flag = TextUtils.isEmpty(item.getVodFlag()) ? "--" : item.getVodFlag();
        holder.binding.flag.setText(holder.itemView.getContext().getString(R.string.history_item_flag, flag));

        String remarks = TextUtils.isEmpty(item.getVodRemarks()) ? "--" : item.getVodRemarks();
        holder.binding.remark.setText(holder.itemView.getContext().getString(R.string.history_item_episode, remarks));

        String pos = formatTime(item.getPosition());
        holder.binding.position.setText(holder.itemView.getContext().getString(R.string.history_item_position, pos));

        String dur = formatTime(item.getDuration());
        holder.binding.duration.setText(holder.itemView.getContext().getString(R.string.history_item_duration, dur));

        holder.binding.delete.setVisibility(delete ? View.VISIBLE : View.GONE);
        holder.binding.delete.setOnClickListener(v -> listener.onItemDelete(item));

        ImgUtil.load(item.getVodName(), item.getVodPic(), holder.binding.image);
        setClickListener(holder.binding.getRoot(), item);
    }

    private void setClickListener(View root, History item) {
        root.setOnLongClickListener(view -> listener.onLongClick());
        root.setOnClickListener(view -> {
            if (isDelete()) listener.onItemDelete(item);
            else listener.onItemClick(item);
        });
    }

    public static String formatTime(long timeMs) {
        if (timeMs <= 0) return "00:00";
        long totalSecs = timeMs / 1000;
        long hours = totalSecs / 3600;
        long minutes = (totalSecs % 3600) / 60;
        long seconds = totalSecs % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
        }
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterHistoryBinding binding;

        ViewHolder(@NonNull AdapterHistoryBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
