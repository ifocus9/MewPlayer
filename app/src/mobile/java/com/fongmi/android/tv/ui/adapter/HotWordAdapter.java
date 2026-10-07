package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Word;
import com.fongmi.android.tv.databinding.AdapterSearchHotWordBinding;
import com.google.android.material.color.MaterialColors;

import java.util.List;

public class HotWordAdapter extends BaseDiffAdapter<Word.Data, HotWordAdapter.ViewHolder> {

    private static final Object PAYLOAD_RANK = new Object();
    // 前三名序号高亮：红 / 橙红 / 橙
    private static final int[] TOP_RANK_COLORS = {0xFFF5483B, 0xFFFF7A45, 0xFFFFA940};

    private final WordAdapter.OnClickListener listener;

    public HotWordAdapter(WordAdapter.OnClickListener listener) {
        this.listener = listener;
    }

    @Override
    public void setItems(List<Word.Data> items, Runnable runnable) {
        // 序号取决于位置，DiffUtil 只做移动时不会重新绑定，提交完成后统一刷新序号
        super.setItems(items, () -> {
            notifyItemRangeChanged(0, getItemCount(), PAYLOAD_RANK);
            if (runnable != null) runnable.run();
        });
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterSearchHotWordBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.contains(PAYLOAD_RANK)) bindRank(holder, position);
        else onBindViewHolder(holder, position);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Word.Data item = getItem(position);
        bindRank(holder, position);
        holder.binding.text.setText(item.getTitle());
        holder.binding.getRoot().setOnClickListener(v -> listener.onItemClick(item.getTitle()));
    }

    private void bindRank(ViewHolder holder, int position) {
        int rank = position + 1;
        int color = position < TOP_RANK_COLORS.length ? TOP_RANK_COLORS[position] : MaterialColors.getColor(holder.binding.rank, com.google.android.material.R.attr.colorOnSurfaceVariant);
        holder.binding.rank.setText(String.valueOf(rank));
        holder.binding.rank.setTextColor(color);
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterSearchHotWordBinding binding;

        public ViewHolder(@NonNull AdapterSearchHotWordBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
