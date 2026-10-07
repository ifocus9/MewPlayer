package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.AdapterEpisodeGridBinding;
import com.fongmi.android.tv.databinding.AdapterEpisodeHoriBinding;
import com.fongmi.android.tv.databinding.AdapterEpisodeRowBinding;
import com.fongmi.android.tv.ui.base.BaseEpisodeHolder;
import com.fongmi.android.tv.ui.base.ViewType;
import com.fongmi.android.tv.ui.holder.EpisodeGridHolder;
import com.fongmi.android.tv.ui.holder.EpisodeHoriHolder;
import com.fongmi.android.tv.ui.holder.EpisodeRowHolder;
import com.fongmi.android.tv.utils.EpisodeTitleCompact;

import java.util.ArrayList;
import java.util.List;

public class EpisodeAdapter extends RecyclerView.Adapter<BaseEpisodeHolder> {

    private final OnClickListener listener;
    private final List<Episode> mItems;
    private final int viewType;
    // 当前展示的分段窗口 [mStart, mEnd)；mEnd < 0 表示展示全部。
    // mItems 始终保存完整列表，getPosition/getActivated/getNext/getPrev/getItems 都基于完整列表。
    private int mStart;
    private int mEnd = -1;
    public static final int LONG_TITLE_LENGTH = 12;
    private boolean mLongTitle;
    // ROW 类型下每张卡片的宽度（px）；<= 0 表示自适应
    private int mItemWidth;

    public EpisodeAdapter(OnClickListener listener, int viewType) {
        this(listener, viewType, new ArrayList<>());
    }

    public EpisodeAdapter(OnClickListener listener, int viewType, ArrayList<Episode> items) {
        this.listener = listener;
        this.viewType = viewType;
        this.mItems = items;
    }

    public interface OnClickListener {

        void onItemClick(Episode item);
    }

    public void addAll(List<Episode> items) {
        EpisodeTitleCompact.apply(items);
        mItems.clear();
        mItems.addAll(items);
        mStart = 0;
        mEnd = -1;
        mLongTitle = hasLongTitle(mItems);
        notifyDataSetChanged();
    }

    /**
     * 是否为长标题列表（与 EpisodeSizing 的长标题阈值一致）：网格按钮改为两行显示。
     */
    public boolean isLongTitle() {
        return mLongTitle;
    }

    /**
     * 标题被精简/还原后重新计算长标题状态（不改变数据与分段窗口）。
     */
    public void refreshTitleMode() {
        mLongTitle = hasLongTitle(mItems);
    }

    /**
     * ROW 类型统一卡片宽度（px），保证横排卡片等宽。
     */
    public void setItemWidth(int width) {
        if (mItemWidth == width) return;
        mItemWidth = width;
        notifyDataSetChanged();
    }

    public int getItemWidth() {
        return mItemWidth;
    }

    private static boolean hasLongTitle(List<Episode> items) {
        if (items.size() < 2) return false;
        for (Episode item : items) if (item.getDisplayName().length() >= LONG_TITLE_LENGTH) return true;
        return false;
    }

    /**
     * 只展示完整列表中 [start, end) 区间的剧集（用于 1-20 / 21-40 分段）。
     */
    public void setRange(int start, int end) {
        int size = mItems.size();
        int s = Math.max(0, Math.min(start, size));
        int e = Math.max(s, Math.min(end, size));
        if (s == 0 && e == size) e = -1;
        if (s == mStart && e == mEnd) return;
        mStart = s;
        mEnd = e;
        notifyDataSetChanged();
    }

    /**
     * 恢复展示完整列表。
     */
    public void clearRange() {
        setRange(0, mItems.size());
    }

    /**
     * 完整列表下标 -> 当前窗口内的适配器位置；不在窗口内返回 -1。
     */
    public int toAdapterPosition(int index) {
        if (index < mStart || index >= mStart + getItemCount()) return -1;
        return index - mStart;
    }

    /**
     * 完整列表的剧集数量（不受分段窗口影响）。
     */
    public int size() {
        return mItems.size();
    }

    public int getPosition() {
        for (int i = 0; i < mItems.size(); i++) if (mItems.get(i).isSelected()) return i;
        return 0;
    }

    public int getPosition(Episode item) {
        return mItems.indexOf(item);
    }

    public Episode getActivated() {
        return mItems.get(getPosition());
    }

    public Episode getNext() {
        int current = getPosition();
        int max = mItems.size() - 1;
        current = ++current > max ? max : current;
        return mItems.get(current);
    }

    public Episode getPrev() {
        int current = getPosition();
        current = --current < 0 ? 0 : current;
        return mItems.get(current);
    }

    public List<Episode> getItems() {
        return mItems;
    }

    public boolean isEmpty() {
        return mItems.isEmpty();
    }

    /**
     * RecyclerView 实际展示的数量（分段窗口内的剧集数）。
     */
    @Override
    public int getItemCount() {
        if (mEnd < 0) return mItems.size() - Math.min(mStart, mItems.size());
        return Math.max(0, Math.min(mEnd, mItems.size()) - mStart);
    }

    @Override
    public int getItemViewType(int position) {
        return viewType;
    }

    @Override
    public void onBindViewHolder(@NonNull BaseEpisodeHolder holder, int position) {
        holder.initView(mItems.get(mStart + position));
    }

    @NonNull
    @Override
    public BaseEpisodeHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == ViewType.HORI) {
            return new EpisodeHoriHolder(AdapterEpisodeHoriBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false), listener);
        } else if (viewType == ViewType.ROW) {
            return new EpisodeRowHolder(AdapterEpisodeRowBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false), listener);
        } else {
            return new EpisodeGridHolder(AdapterEpisodeGridBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false), listener);
        }
    }
}
