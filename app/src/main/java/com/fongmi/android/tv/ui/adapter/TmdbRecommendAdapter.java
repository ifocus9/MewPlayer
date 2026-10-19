package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.tmdb.TmdbApi;
import com.fongmi.android.tv.api.tmdb.bean.TmdbMedia;

import java.util.ArrayList;
import java.util.List;

public class TmdbRecommendAdapter extends RecyclerView.Adapter<TmdbRecommendAdapter.ViewHolder> {

    public interface OnItemClickListener {
        void onItemClick(TmdbMedia item);
    }

    private final List<TmdbMedia> mData = new ArrayList<>();
    private OnItemClickListener mListener;

    public void setListener(OnItemClickListener listener) {
        this.mListener = listener;
    }

    public void addAll(List<TmdbMedia> data) {
        mData.clear();
        if (data != null) mData.addAll(data);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_tmdb_recommend, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        TmdbMedia item = mData.get(position);
        holder.title.setText(item.getDisplayTitle());

        String posterUrl = TmdbApi.imageUrl(item.getPosterPath(), "w500");
        Glide.with(holder.poster.getContext())
                .load(posterUrl.isEmpty() ? null : posterUrl)
                .placeholder(R.drawable.tmdb_poster_placeholder)
                .centerCrop()
                .into(holder.poster);

        holder.itemView.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onItemClick(item);
            }
        });
    }

    @Override
    public int getItemCount() {
        return mData.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView poster;
        final TextView title;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            poster = itemView.findViewById(R.id.recommendPoster);
            title = itemView.findViewById(R.id.recommendTitle);
        }
    }
}
