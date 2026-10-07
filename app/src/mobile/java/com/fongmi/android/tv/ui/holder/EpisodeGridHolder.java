package com.fongmi.android.tv.ui.holder;

import androidx.annotation.NonNull;

import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.ViewGroup;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.AdapterEpisodeGridBinding;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.base.BaseEpisodeHolder;
import com.fongmi.android.tv.utils.ResUtil;

public class EpisodeGridHolder extends BaseEpisodeHolder {

    private static final int SHORT_HEIGHT_DP = 40;
    private static final int LONG_HEIGHT_DP = 52;

    private final EpisodeAdapter.OnClickListener listener;
    private final AdapterEpisodeGridBinding binding;
    private final int maxSingleWidth;
    private final int horizontalPadding;

    public EpisodeGridHolder(@NonNull AdapterEpisodeGridBinding binding, EpisodeAdapter.OnClickListener listener) {
        super(binding.getRoot());
        this.binding = binding;
        this.listener = listener;
        this.maxSingleWidth = ResUtil.getScreenWidth();
        this.horizontalPadding = ResUtil.dp2px(12);
    }

    @Override
    public void initView(Episode item) {
        boolean longTitle = isLongTitle();
        updateLayout(longTitle);
        boolean selected = item.isSelected();
        binding.text.setActivated(selected);
        binding.text.setText(item.getDisplayName());
        binding.text.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        // 当前集左侧显示“正在播放”标记
        binding.text.setCompoundDrawablesRelativeWithIntrinsicBounds(selected ? R.drawable.ic_episode_playing : 0, 0, 0, 0);
        binding.text.setCompoundDrawablePadding(selected ? ResUtil.dp2px(4) : 0);
        binding.text.setOnClickListener(v -> listener.onItemClick(item));
        if (longTitle) {
            // 长标题：两行显示，结尾省略，不再跑马灯
            binding.text.setOnFocusChangeListener(null);
            binding.text.setSelected(false);
            binding.text.setEllipsize(TextUtils.TruncateAt.END);
            return;
        }
        setMarquee(binding.text.hasFocus() || selected);
        binding.text.setOnFocusChangeListener((view, hasFocus) -> setMarquee(hasFocus || binding.text.isActivated()));
        binding.text.post(() -> setMarquee(binding.text.hasFocus() || binding.text.isActivated()));
    }

    private boolean isLongTitle() {
        return getBindingAdapter() instanceof EpisodeAdapter adapter && adapter.isLongTitle();
    }

    private void updateLayout(boolean longTitle) {
        // 按完整剧集数判断“只有一集”，避免分段后最后一段只剩 1 集时被误当成单集布局
        boolean single = getBindingAdapter() instanceof EpisodeAdapter adapter ? adapter.size() == 1 : getBindingAdapter() != null && getBindingAdapter().getItemCount() == 1;
        ViewGroup.LayoutParams params = binding.text.getLayoutParams();
        int width = single ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT;
        int height = ResUtil.dp2px(longTitle ? LONG_HEIGHT_DP : SHORT_HEIGHT_DP);
        if (params.width != width || params.height != height) {
            params.width = width;
            params.height = height;
            binding.text.setLayoutParams(params);
        }
        if (longTitle) {
            binding.text.setSingleLine(false);
            binding.text.setHorizontallyScrolling(false);
            binding.text.setMaxLines(2);
            binding.text.setLineSpacing(0, 1.0f);
            binding.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        } else {
            binding.text.setSingleLine(true);
            binding.text.setHorizontallyScrolling(true);
            binding.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        }
        binding.text.setMaxWidth(single ? maxSingleWidth : Integer.MAX_VALUE);
        binding.text.setPadding(horizontalPadding, 0, horizontalPadding, 0);
    }

    private void setMarquee(boolean focused) {
        // 非焦点/非当前集时截断尾部，保证开头的集数（第 N 集 / E01 …）可见
        binding.text.setEllipsize(focused ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
        binding.text.setSelected(focused);
    }
}
