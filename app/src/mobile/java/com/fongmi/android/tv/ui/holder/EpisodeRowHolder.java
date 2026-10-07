package com.fongmi.android.tv.ui.holder;

import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.AdapterEpisodeRowBinding;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.base.BaseEpisodeHolder;
import com.fongmi.android.tv.utils.ResUtil;

/**
 * 详情页选集横排卡片。
 * 同一列表所有卡片等宽（宽度由 EpisodeAdapter.getItemWidth 决定），长标题列表两行显示、结尾省略。
 */
public class EpisodeRowHolder extends BaseEpisodeHolder {

    public static final int SHORT_HEIGHT_DP = 40;
    public static final int LONG_HEIGHT_DP = 52;

    private final EpisodeAdapter.OnClickListener listener;
    private final AdapterEpisodeRowBinding binding;
    private final int horizontalPadding;
    private final int minSingleWidth;
    private final int maxSingleWidth;

    public EpisodeRowHolder(@NonNull AdapterEpisodeRowBinding binding, EpisodeAdapter.OnClickListener listener) {
        super(binding.getRoot());
        this.binding = binding;
        this.listener = listener;
        this.horizontalPadding = ResUtil.dp2px(10);
        this.minSingleWidth = ResUtil.dp2px(72);
        this.maxSingleWidth = ResUtil.getScreenWidth() - ResUtil.dp2px(32);
    }

    @Override
    public void initView(Episode item) {
        EpisodeAdapter adapter = getBindingAdapter() instanceof EpisodeAdapter a ? a : null;
        boolean longTitle = adapter != null && adapter.isLongTitle();
        updateLayout(adapter, longTitle);
        boolean selected = item.isSelected();
        binding.text.setActivated(selected);
        binding.text.setText(item.getDisplayName());
        binding.text.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        // 当前集左侧显示“正在播放”标记
        binding.text.setCompoundDrawablesRelativeWithIntrinsicBounds(selected ? R.drawable.ic_episode_playing : 0, 0, 0, 0);
        binding.text.setCompoundDrawablePadding(selected ? ResUtil.dp2px(4) : 0);
        binding.text.setOnClickListener(v -> listener.onItemClick(item));
        // 当前集文字放不下时跑马灯，其余截断尾部，保证开头的集数可见
        boolean marquee = selected && !longTitle;
        binding.text.setEllipsize(marquee ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
        binding.text.setSelected(marquee);
    }

    private void updateLayout(EpisodeAdapter adapter, boolean longTitle) {
        boolean single = adapter != null && adapter.size() == 1;
        int itemWidth = adapter == null ? 0 : adapter.getItemWidth();
        int width = single || itemWidth <= 0 ? ViewGroup.LayoutParams.WRAP_CONTENT : itemWidth;
        int height = ResUtil.dp2px(longTitle ? LONG_HEIGHT_DP : SHORT_HEIGHT_DP);
        ViewGroup.LayoutParams params = binding.text.getLayoutParams();
        if (params.width != width || params.height != height) {
            params.width = width;
            params.height = height;
            binding.text.setLayoutParams(params);
        }
        binding.text.setMinWidth(width == ViewGroup.LayoutParams.WRAP_CONTENT ? minSingleWidth : 0);
        binding.text.setMaxWidth(width == ViewGroup.LayoutParams.WRAP_CONTENT ? maxSingleWidth : Integer.MAX_VALUE);
        if (longTitle) {
            binding.text.setSingleLine(false);
            binding.text.setHorizontallyScrolling(false);
            binding.text.setMaxLines(2);
            binding.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        } else {
            binding.text.setSingleLine(true);
            binding.text.setHorizontallyScrolling(true);
            binding.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        }
        binding.text.setPadding(horizontalPadding, 0, horizontalPadding, 0);
    }
}
