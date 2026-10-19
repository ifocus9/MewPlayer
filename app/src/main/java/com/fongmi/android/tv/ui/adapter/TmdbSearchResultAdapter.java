package com.fongmi.android.tv.ui.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.tmdb.TmdbApi;
import com.fongmi.android.tv.api.tmdb.bean.TmdbMedia;
import com.fongmi.android.tv.databinding.ItemTmdbSearchResultBinding;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TmdbSearchResultAdapter extends RecyclerView.Adapter<TmdbSearchResultAdapter.ViewHolder> {

    public interface OnClickListener {
        void onItemClick(TmdbMedia item);
    }

    private final List<TmdbMedia> mItems = new ArrayList<>();
    private OnClickListener mListener;

    public void setListener(OnClickListener listener) {
        this.mListener = listener;
    }

    public void setItems(List<TmdbMedia> items) {
        mItems.clear();
        if (items != null) {
            mItems.addAll(items);
        }
        notifyDataSetChanged();
    }

    public void clear() {
        mItems.clear();
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemTmdbSearchResultBinding binding = ItemTmdbSearchResultBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        TmdbMedia media = mItems.get(position);
        holder.binding.title.setText(media.getDisplayTitle());

        boolean isMovie = "movie".equalsIgnoreCase(media.getMediaType());
        holder.binding.typeBadge.setText(isMovie ? R.string.tmdb_movie : R.string.tmdb_tv);
        holder.binding.typeBadge.setBackgroundResource(isMovie ? R.drawable.shape_tmdb_badge_movie : R.drawable.shape_tmdb_badge_tv);

        String original = media.getOriginalTitle();
        if (!TextUtils.isEmpty(original) && !original.equalsIgnoreCase(media.getDisplayTitle())) {
            holder.binding.originalTitle.setText(original);
            holder.binding.originalTitle.setVisibility(View.VISIBLE);
        } else {
            holder.binding.originalTitle.setVisibility(View.GONE);
        }

        String date = media.getDate();
        holder.binding.date.setText(!TextUtils.isEmpty(date) ? date : "");

        if (media.getVoteAverage() > 0) {
            holder.binding.rating.setVisibility(View.VISIBLE);
            holder.binding.rating.setText(String.format(Locale.US, "★ %.1f", media.getVoteAverage()));
        } else {
            holder.binding.rating.setVisibility(View.GONE);
        }

        if (!TextUtils.isEmpty(media.getOverview())) {
            holder.binding.overview.setVisibility(View.VISIBLE);
            holder.binding.overview.setText(media.getOverview());
        } else {
            holder.binding.overview.setVisibility(View.GONE);
        }

        String posterUrl = TmdbApi.imageUrl(media.getPosterPath(), "w185");
        if (!TextUtils.isEmpty(posterUrl)) {
            Glide.with(holder.itemView.getContext())
                    .load(posterUrl)
                    .transform(new CenterCrop(), new RoundedCorners(ResUtil.dp2px(5)))
                    .placeholder(R.drawable.tmdb_poster_placeholder)
                    .error(R.drawable.tmdb_poster_placeholder)
                    .into(holder.binding.poster);
        } else {
            holder.binding.poster.setImageResource(R.drawable.tmdb_poster_placeholder);
        }

        holder.itemView.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onItemClick(media);
            }
        });
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public final ItemTmdbSearchResultBinding binding;

        public ViewHolder(@NonNull ItemTmdbSearchResultBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
