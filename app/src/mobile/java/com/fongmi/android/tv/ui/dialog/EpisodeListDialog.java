package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDialogFragment;
import androidx.core.view.WindowCompat;
import androidx.core.widget.TextViewCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.databinding.DialogEpisodeListBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.adapter.EpisodeGroupAdapter;
import com.fongmi.android.tv.ui.adapter.EpisodeSizing;
import com.fongmi.android.tv.ui.adapter.FlagAdapter;
import com.fongmi.android.tv.ui.base.ViewType;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;

import java.util.Collections;
import java.util.List;

/**
 * 全屏播放时的右侧选集面板。结构与详情页抽屉 EpisodeSheetDialog 一致：
 * 选集头部（全N集 / 精简 / 倒序）、文字 + 下划线分段标签（只有一段时隐藏）、按标题长度自适应列数的网格，
 * 打开时当前集滚到列表上方约 1/3 处。额外保留线路切换。配色与 LUT 调色面板（LutQuickPanel）一致：
 * 近黑半透明底、半透明深灰细边按钮、选中蓝底白边、白色文字。
 * 数据直接取宿主 Activity（线路、当前线路剧集、倒序状态），宿主数据变化时调用 refresh() 同步。
 */
public class EpisodeListDialog extends AppCompatDialogFragment implements FlagAdapter.OnClickListener, EpisodeGroupAdapter.OnClickListener, EpisodeAdapter.OnClickListener {

    public interface Host extends EpisodeSheetDialog.Host {

        List<Flag> getEpisodePanelFlags();
    }

    private static final int PANEL_MAX_SPAN = 5;
    private static final int KEEP_PADDING = -1;

    private DialogEpisodeListBinding binding;
    private EpisodeGroupAdapter groupAdapter;
    private EpisodeAdapter episodeAdapter;
    private SpaceItemDecoration episodeDecoration;
    private FlagAdapter flagAdapter;
    private int spanCount = -1;
    private int measuredWidth;

    public static EpisodeListDialog create() {
        return new EpisodeListDialog();
    }

    public void show(FragmentActivity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || activity.getSupportFragmentManager().isStateSaved()) return;
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof EpisodeListDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        configureWindow(dialog);
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        configureWindow(getDialog());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = DialogEpisodeListBinding.inflate(inflater, container, false);
        FrameLayout overlay = new FrameLayout(requireContext());
        overlay.setBackgroundColor(Color.TRANSPARENT);
        overlay.setOnClickListener(view -> dismiss());
        binding.getRoot().setClickable(true);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(getWidth(), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        overlay.addView(binding.getRoot(), params);
        return overlay;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initView();
        initEvent();
        refresh();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private int getWidth() {
        int screen = ResUtil.getScreenWidth(requireContext());
        return Math.max(ResUtil.dp2px(360), Math.min(ResUtil.dp2px(560), Math.round(screen * 0.44f)));
    }

    private void configureWindow(Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return;
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.setDimAmount(0f);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        Util.hideSystemUI(window);
    }

    private void initView() {
        // 线路 / 分段 / 剧集按钮的布局与详情页共用，只在本面板内换成 LUT 调色面板的配色
        stylePanelItems(binding.flag, R.drawable.selector_player_panel_group_tab, 12, 6);
        stylePanelItems(binding.group, R.drawable.selector_player_panel_group_tab, 10, 0);
        stylePanelItems(binding.episode, R.drawable.selector_player_panel_episode, KEEP_PADDING, KEEP_PADDING);
        binding.flag.setHasFixedSize(true);
        binding.flag.setItemAnimator(null);
        binding.flag.addItemDecoration(new SpaceItemDecoration(8));
        binding.flag.setAdapter(flagAdapter = new FlagAdapter(this));
        binding.group.setHasFixedSize(true);
        binding.group.setItemAnimator(null);
        binding.group.setAdapter(groupAdapter = new EpisodeGroupAdapter(this, true));
        binding.episode.setHasFixedSize(true);
        binding.episode.setItemAnimator(null);
        binding.episode.setAdapter(episodeAdapter = new EpisodeAdapter(this, ViewType.GRID));
        binding.episode.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                syncGroupByScroll();
            }
        });
        // 首次刷新时还拿不到网格真实宽度，布局完成后按实际宽度重算列数
        binding.episode.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = right - left;
            if (width <= 0 || width == measuredWidth) return;
            measuredWidth = width;
            view.post(() -> {
                if (binding != null) updateSpan(episodeAdapter.getItems());
            });
        });
    }

    /**
     * 子项挂到列表上时换成本面板配色：背景、白色文字、白色图标（当前集“正在播放”标记默认是蓝色，放在蓝底上会看不见）。
     * paddingH / paddingV 为 KEEP_PADDING 时保留原内边距（剧集网格的内边距由 EpisodeGridHolder 控制）。
     */
    private void stylePanelItems(RecyclerView recycler, @DrawableRes int background, int paddingH, int paddingV) {
        recycler.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View view) {
                if (view instanceof TextView text) stylePanelItem(text, background, paddingH, paddingV);
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View view) {
            }
        });
    }

    private void stylePanelItem(TextView text, @DrawableRes int background, int paddingH, int paddingV) {
        int start = text.getPaddingStart();
        int top = text.getPaddingTop();
        int end = text.getPaddingEnd();
        int bottom = text.getPaddingBottom();
        // setBackgroundResource 会把内边距重置成背景自带的值，这里按需恢复 / 改写
        text.setBackgroundResource(background);
        if (paddingH == KEEP_PADDING) {
            text.setPaddingRelative(start, top, end, bottom);
        } else {
            int h = ResUtil.dp2px(paddingH);
            int v = ResUtil.dp2px(paddingV);
            text.setPaddingRelative(h, v, h, v);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        }
        text.setTextColor(Color.WHITE);
        TextViewCompat.setCompoundDrawableTintList(text, ColorStateList.valueOf(Color.WHITE));
    }

    private void initEvent() {
        binding.compact.setOnClickListener(view -> {
            Host host = getPanelHost();
            if (host != null) host.onEpisodeSheetCompact();
        });
        binding.reverse.setOnClickListener(view -> {
            Host host = getPanelHost();
            if (host != null) host.onEpisodeSheetReverse();
        });
    }

    @Nullable
    private Host getPanelHost() {
        return getActivity() instanceof Host host ? host : null;
    }

    /**
     * 按宿主当前数据重建：线路、剧集、分段、开关状态、列数，并滚到当前集。
     */
    public void refresh() {
        if (binding == null || !isAdded()) return;
        Host host = getPanelHost();
        if (host == null) return;
        List<Flag> flags = host.getEpisodePanelFlags();
        List<Episode> items = host.getEpisodeSheetItems();
        boolean hasFlags = flags != null && !flags.isEmpty();
        boolean hasItems = items != null && !items.isEmpty();
        if (!hasFlags && !hasItems) {
            dismissAllowingStateLoss();
            return;
        }
        if (hasFlags) flagAdapter.addAll(flags);
        if (hasFlags) binding.flagCount.setText(getString(R.string.detail_flag_count, flags.size()));
        binding.flagHeader.setVisibility(hasFlags ? View.VISIBLE : View.GONE);
        binding.flag.setVisibility(hasFlags ? View.VISIBLE : View.GONE);
        if (hasFlags) binding.flag.scrollToPosition(flagAdapter.getPosition());
        if (!hasItems) {
            episodeAdapter.addAll(Collections.emptyList());
            groupAdapter.addAll(Collections.emptyList());
            binding.group.setVisibility(View.GONE);
            binding.header.setVisibility(View.GONE);
            return;
        }
        binding.header.setVisibility(View.VISIBLE);
        episodeAdapter.addAll(items);
        int size = episodeAdapter.size();
        int selected = episodeAdapter.getPosition();
        binding.count.setText(getString(R.string.detail_episode_count, size));
        binding.compact.setSelected(Setting.isCompactEpisodeTitle());
        binding.reverse.setSelected(host.isEpisodeSheetReverse());
        binding.reverse.setVisibility(size < 2 ? View.GONE : View.VISIBLE);
        List<EpisodeGroupAdapter.Group> groups = EpisodeGroupAdapter.build(size, selected, host.isEpisodeSheetReverse());
        groupAdapter.addAll(groups);
        binding.group.setVisibility(groups.size() > 1 ? View.VISIBLE : View.GONE);
        binding.group.scrollToPosition(groupAdapter.getPosition());
        updateSpan(episodeAdapter.getItems());
        binding.episode.post(() -> scrollToSelected(selected));
    }

    private void updateSpan(List<Episode> items) {
        Host host = getPanelHost();
        // 侧边面板最宽 560dp，列数再多卡片会过窄，最多 5 列
        int maxSpan = Math.min(PANEL_MAX_SPAN, host == null ? 4 : host.getEpisodeSheetMaxSpan());
        int span = EpisodeSizing.getGridSpan(items, getAvailableWidth(), maxSpan);
        if (span == spanCount) return;
        spanCount = span;
        binding.episode.setLayoutManager(new GridLayoutManager(requireContext(), spanCount));
        if (episodeDecoration != null) binding.episode.removeItemDecoration(episodeDecoration);
        binding.episode.addItemDecoration(episodeDecoration = new SpaceItemDecoration(spanCount, 8));
    }

    private int getAvailableWidth() {
        int padding = binding.episode.getPaddingStart() + binding.episode.getPaddingEnd();
        int width = binding.episode.getWidth();
        if (width > 0) return Math.max(1, width - padding);
        // 尚未布局：面板宽度减去面板左右内边距（12dp × 2）
        return Math.max(1, getWidth() - ResUtil.dp2px(24));
    }

    /**
     * 当前集所在行滚到列表上方约 1/3 处，上方留出前几集方便回看。
     */
    private void scrollToSelected(int position) {
        if (binding == null) return;
        RecyclerView.LayoutManager manager = binding.episode.getLayoutManager();
        if (!(manager instanceof GridLayoutManager grid)) return;
        int span = Math.max(1, grid.getSpanCount());
        int rowStart = Math.max(0, position - position % span);
        int height = binding.episode.getHeight();
        grid.scrollToPositionWithOffset(rowStart, height > 0 ? height / 3 : ResUtil.dp2px(80));
    }

    private void syncGroupByScroll() {
        if (binding == null || groupAdapter == null || groupAdapter.getItemCount() < 2) return;
        RecyclerView.LayoutManager manager = binding.episode.getLayoutManager();
        if (!(manager instanceof GridLayoutManager grid)) return;
        // 已滚到底时用最后一个可见项，保证最后一段能被选中
        boolean bottom = !binding.episode.canScrollVertically(1) && binding.episode.canScrollVertically(-1);
        int position = bottom ? grid.findLastVisibleItemPosition() : grid.findFirstVisibleItemPosition();
        if (position == RecyclerView.NO_POSITION) return;
        selectGroupByPosition(position);
    }

    private void selectGroupByPosition(int position) {
        int current = groupAdapter.getPosition();
        List<EpisodeGroupAdapter.Group> groups = groupAdapter.getItems();
        for (int i = 0; i < groups.size(); i++) {
            EpisodeGroupAdapter.Group group = groups.get(i);
            if (position < group.start || position >= group.end) continue;
            if (i != current) {
                groupAdapter.setSelected(group);
                binding.group.scrollToPosition(i);
            }
            return;
        }
    }

    @Override
    public void onItemClick(Flag item) {
        if (getActivity() instanceof FlagAdapter.OnClickListener listener) listener.onItemClick(item);
        // 宿主换线路后会通过 refreshEpisodeSheet 回调 refresh()；这里再刷新一次，保证同一线路重复点击时状态也正确
        refresh();
    }

    @Override
    public void onItemClick(EpisodeGroupAdapter.Group item) {
        groupAdapter.setSelected(item);
        binding.group.scrollToPosition(groupAdapter.getPosition());
        RecyclerView.LayoutManager manager = binding.episode.getLayoutManager();
        if (manager instanceof GridLayoutManager grid) grid.scrollToPositionWithOffset(item.start, 0);
        else binding.episode.scrollToPosition(item.start);
    }

    @Override
    public void onItemClick(Episode item) {
        if (getActivity() instanceof EpisodeAdapter.OnClickListener listener) listener.onItemClick(item);
        dismissAllowingStateLoss();
    }
}
