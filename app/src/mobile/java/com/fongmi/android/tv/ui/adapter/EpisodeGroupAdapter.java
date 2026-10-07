package com.fongmi.android.tv.ui.adapter;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.databinding.AdapterEpisodeGroupBinding;
import com.fongmi.android.tv.databinding.AdapterEpisodeGroupTabBinding;

import java.util.ArrayList;
import java.util.List;

public class EpisodeGroupAdapter extends RecyclerView.Adapter<EpisodeGroupAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Group> items;
    // true：文字 + 下划线标签（详情页抽屉、全屏选集面板）；false：胶囊按钮
    private final boolean tab;

    public EpisodeGroupAdapter(OnClickListener listener) {
        this(listener, false);
    }

    public EpisodeGroupAdapter(OnClickListener listener, boolean tab) {
        this.listener = listener;
        this.items = new ArrayList<>();
        this.tab = tab;
    }

    public interface OnClickListener {

        void onItemClick(Group item);
    }

    public void addAll(List<Group> items) {
        this.items.clear();
        this.items.addAll(items);
        notifyDataSetChanged();
    }

    public List<Group> getItems() {
        return items;
    }

    public int getPosition() {
        for (int i = 0; i < items.size(); i++) if (items.get(i).selected) return i;
        return 0;
    }

    public void setSelected(Group group) {
        for (Group item : items) item.selected = item == group;
        notifyItemRangeChanged(0, getItemCount());
    }

    public boolean isEmpty() {
        return getItemCount() == 0;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (tab) return new ViewHolder(AdapterEpisodeGroupTabBinding.inflate(inflater, parent, false).text);
        return new ViewHolder(AdapterEpisodeGroupBinding.inflate(inflater, parent, false).text);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Group item = items.get(position);
        holder.text.setText(item.name);
        holder.text.setSelected(item.selected);
        if (tab) holder.text.setTypeface(Typeface.DEFAULT, item.selected ? Typeface.BOLD : Typeface.NORMAL);
        holder.text.setOnClickListener(v -> listener.onItemClick(item));
    }

    public static List<Group> build(int size, int selectedIndex, boolean reverse) {
        List<Group> groups = new ArrayList<>();
        if (size <= 0) return groups;
        int groupSize = getGroupSize(size);
        int count = (int) Math.ceil(size / (float) groupSize);
        for (int i = 0; i < count; i++) {
            int start = i * groupSize;
            int end = Math.min(start + groupSize, size);
            int labelStart = reverse ? size - start : start + 1;
            int labelEnd = reverse ? size - end + 1 : end;
            Group group = new Group(Math.max(labelStart, labelEnd) == Math.min(labelStart, labelEnd) ? String.valueOf(labelStart) : labelStart + "-" + labelEnd, start, end);
            group.selected = selectedIndex >= start && selectedIndex < end;
            groups.add(group);
        }
        if (groups.stream().noneMatch(group -> group.selected)) groups.get(0).selected = true;
        return groups;
    }

    private static int getGroupSize(int size) {
        if (size <= 60) return 20;
        if (size > 2500) return 300;
        if (size > 1500) return 200;
        if (size > 1000) return 150;
        if (size > 500) return 100;
        if (size > 300) return 50;
        return 40;
    }

    public static class Group {

        public final String name;
        public final int start;
        public final int end;
        public boolean selected;

        public Group(String name, int start, int end) {
            this.name = name;
            this.start = start;
            this.end = end;
        }
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final TextView text;

        ViewHolder(@NonNull TextView text) {
            super(text);
            this.text = text;
        }
    }
}
