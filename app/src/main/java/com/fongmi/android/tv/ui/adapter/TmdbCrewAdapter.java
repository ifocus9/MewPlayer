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

import java.util.ArrayList;
import java.util.List;

public class TmdbCrewAdapter extends RecyclerView.Adapter<TmdbCrewAdapter.ViewHolder> {

    private final List<TmdbDetail.CrewMember> mData = new ArrayList<>();

    public void addAll(List<TmdbDetail.CrewMember> data) {
        mData.clear();
        if (data != null) mData.addAll(data);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_tmdb_crew, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        TmdbDetail.CrewMember member = mData.get(position);
        holder.name.setText(member.name);
        holder.job.setText(member.job != null ? member.job : "");

        String avatarUrl = TmdbApi.imageUrl(member.profilePath, "w185");
        Glide.with(holder.avatar.getContext())
                .load(avatarUrl.isEmpty() ? null : avatarUrl)
                .placeholder(R.drawable.tmdb_avatar_placeholder)
                .transform(new CircleCrop())
                .into(holder.avatar);
    }

    @Override
    public int getItemCount() {
        return mData.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView avatar;
        final TextView name;
        final TextView job;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.crewAvatar);
            name = itemView.findViewById(R.id.crewName);
            job = itemView.findViewById(R.id.crewJob);
        }
    }
}
