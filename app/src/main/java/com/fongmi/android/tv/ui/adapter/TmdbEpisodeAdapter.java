package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Episode;

import java.util.ArrayList;
import java.util.List;

public class TmdbEpisodeAdapter extends RecyclerView.Adapter<TmdbEpisodeAdapter.ViewHolder> {

    public interface OnEpisodeClickListener {
        void onEpisodeClick(int position, Episode episode);
    }

    private final List<Episode> mEpisodes;
    private int mSelectedIndex = 0;
    private OnEpisodeClickListener mListener;

    public TmdbEpisodeAdapter() {
        this.mEpisodes = new ArrayList<>();
    }

    public TmdbEpisodeAdapter(List<Episode> episodes) {
        this.mEpisodes = episodes != null ? episodes : new ArrayList<>();
    }

    public TmdbEpisodeAdapter setListener(OnEpisodeClickListener listener) {
        mListener = listener;
        return this;
    }

    public void addAll(List<Episode> episodes) {
        mEpisodes.clear();
        if (episodes != null) mEpisodes.addAll(episodes);
        notifyDataSetChanged();
    }

    public Episode getItem(int position) {
        return position >= 0 && position < mEpisodes.size() ? mEpisodes.get(position) : null;
    }

    public Episode getSelectedEpisode() {
        return getItem(mSelectedIndex);
    }

    public int getSelectedIndex() {
        return mSelectedIndex;
    }

    public void setSelectedIndex(int index) {
        int prev = mSelectedIndex;
        mSelectedIndex = index;
        notifyItemChanged(prev);
        notifyItemChanged(index);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_tmdb_episode, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Episode episode = mEpisodes.get(position);
        holder.button.setText(episode.getName());
        boolean selected = position == mSelectedIndex;
        holder.button.setSelected(selected);
        holder.button.setTextColor(ContextCompat.getColor(holder.button.getContext(),
                selected ? R.color.black : R.color.tmdb_text_primary));

        holder.button.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_ID) return;
            setSelectedIndex(pos);
            if (mListener != null) mListener.onEpisodeClick(pos, mEpisodes.get(pos));
        });
    }

    @Override
    public int getItemCount() {
        return mEpisodes.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView button;
        ViewHolder(@NonNull View itemView) {
            super(itemView);
            button = itemView.findViewById(R.id.episodeButton);
        }
    }
}
