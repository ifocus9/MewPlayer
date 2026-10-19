package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.tmdb.TmdbApi;
import com.fongmi.android.tv.api.tmdb.bean.TmdbDetail;

import java.util.List;

public class TmdbCastAdapter extends RecyclerView.Adapter<TmdbCastAdapter.ViewHolder> {

    private final List<TmdbDetail.CastMember> mData;

    public TmdbCastAdapter() {
        this.mData = new java.util.ArrayList<>();
    }

    public TmdbCastAdapter(List<TmdbDetail.CastMember> data) {
        this.mData = data != null ? data : new java.util.ArrayList<>();
    }

    public void addAll(List<TmdbDetail.CastMember> data) {
        mData.clear();
        if (data != null) mData.addAll(data);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_tmdb_cast, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        TmdbDetail.CastMember member = mData.get(position);
        holder.name.setText(member.name);
        holder.character.setText(member.character != null ? member.character : "");

        String avatarUrl = TmdbApi.imageUrl(member.profilePath, "w185");
        Glide.with(holder.avatar.getContext())
                .load(avatarUrl.isEmpty() ? null : avatarUrl)
                .placeholder(R.drawable.tmdb_avatar_placeholder)
                .transform(new CircleCrop())
                .into(holder.avatar);
    }

    @Override
    public int getItemCount() {
        return mData != null ? mData.size() : 0;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView avatar;
        final TextView name;
        final TextView character;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.castAvatar);
            name = itemView.findViewById(R.id.castName);
            character = itemView.findViewById(R.id.castCharacter);
        }
    }
}
