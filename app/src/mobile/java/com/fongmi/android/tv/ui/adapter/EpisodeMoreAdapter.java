package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.databinding.AdapterEpisodeMoreBinding;
import com.fongmi.android.tv.ui.holder.EpisodeRowHolder;
import com.fongmi.android.tv.utils.ResUtil;

/**
 * 详情页选集横排末尾的“更多”卡片（0 或 1 项），点击打开全部剧集抽屉。
 * 高度跟随横排卡片（长标题时 52dp，否则 40dp）。
 */
public class EpisodeMoreAdapter extends RecyclerView.Adapter<EpisodeMoreAdapter.ViewHolder> {

    private final Runnable listener;
    private boolean visible;
    private boolean longTitle;

    public EpisodeMoreAdapter(Runnable listener) {
        this.listener = listener;
    }

    public void setVisible(boolean visible) {
        if (this.visible == visible) return;
        this.visible = visible;
        if (visible) notifyItemInserted(0);
        else notifyItemRemoved(0);
    }

    public void setLongTitle(boolean longTitle) {
        if (this.longTitle == longTitle) return;
        this.longTitle = longTitle;
        if (visible) notifyItemChanged(0);
    }

    @Override
    public int getItemCount() {
        return visible ? 1 : 0;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterEpisodeMoreBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false).text);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ViewGroup.LayoutParams params = holder.text.getLayoutParams();
        int height = ResUtil.dp2px(longTitle ? EpisodeRowHolder.LONG_HEIGHT_DP : EpisodeRowHolder.SHORT_HEIGHT_DP);
        if (params.height != height) {
            params.height = height;
            holder.text.setLayoutParams(params);
        }
        holder.text.setOnClickListener(v -> listener.run());
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final TextView text;

        ViewHolder(@NonNull TextView text) {
            super(text);
            this.text = text;
        }
    }
}
