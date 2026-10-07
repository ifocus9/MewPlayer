package com.fongmi.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.DialogEpisodeSheetBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.adapter.EpisodeGroupAdapter;
import com.fongmi.android.tv.ui.adapter.EpisodeSizing;
import com.fongmi.android.tv.ui.base.ViewType;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.List;

/**
 * 详情页“全部剧集”抽屉：分段标签与网格滚动联动，倒序 / 精简开关也在这里。
 * 剧集列表直接取宿主 Activity 当前线路的列表（不传副本），宿主数据变化时调用 refresh() 同步。
 */
public class EpisodeSheetDialog extends BaseBottomSheetDialog implements EpisodeAdapter.OnClickListener, EpisodeGroupAdapter.OnClickListener {

    private static final float HEIGHT_RATIO = 0.7f;

    public interface Host {

        List<Episode> getEpisodeSheetItems();

        boolean isEpisodeSheetReverse();

        int getEpisodeSheetMaxSpan();

        void onEpisodeSheetReverse();

        void onEpisodeSheetCompact();
    }

    private DialogEpisodeSheetBinding binding;
    private EpisodeGroupAdapter groupAdapter;
    private EpisodeAdapter episodeAdapter;
    private SpaceItemDecoration episodeDecoration;
    private int spanCount = -1;
    private int measuredWidth;

    public static EpisodeSheetDialog create() {
        return new EpisodeSheetDialog();
    }

    public void show(FragmentActivity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || activity.getSupportFragmentManager().isStateSaved()) return;
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof EpisodeSheetDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogEpisodeSheetBinding.inflate(inflater, container, false);
    }

    @Override
    protected boolean transparent() {
        // 背景由布局自带的圆角 detail_bg 提供，与详情页配色一致
        return true;
    }

    @Override
    protected void setBehavior(BottomSheetDialog dialog) {
        super.setBehavior(dialog);
        FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet == null) return;
        ViewGroup.LayoutParams params = sheet.getLayoutParams();
        params.height = Math.round(ResUtil.getScreenHeight(requireContext()) * HEIGHT_RATIO);
        sheet.setLayoutParams(params);
    }

    @Override
    protected void initView() {
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
        // 首次刷新时还拿不到真实宽度（平板上抽屉有最大宽度），布局完成后按实际宽度重算列数
        binding.episode.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = right - left;
            if (width <= 0 || width == measuredWidth) return;
            measuredWidth = width;
            view.post(() -> {
                if (binding != null) updateSpan(episodeAdapter.getItems());
            });
        });
        refresh();
    }

    @Override
    protected void initEvent() {
        binding.compact.setOnClickListener(view -> {
            Host host = getSheetHost();
            if (host != null) host.onEpisodeSheetCompact();
        });
        binding.reverse.setOnClickListener(view -> {
            Host host = getSheetHost();
            if (host != null) host.onEpisodeSheetReverse();
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    @Nullable
    private Host getSheetHost() {
        return getActivity() instanceof Host host ? host : null;
    }

    /**
     * 按宿主当前数据重建：剧集、分段、开关状态、列数，并滚到当前集。
     */
    public void refresh() {
        if (binding == null || !isAdded()) return;
        Host host = getSheetHost();
        if (host == null) return;
        List<Episode> items = host.getEpisodeSheetItems();
        if (items == null || items.isEmpty()) {
            dismissAllowingStateLoss();
            return;
        }
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
        Host host = getSheetHost();
        int maxSpan = host == null ? 4 : host.getEpisodeSheetMaxSpan();
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
        return Math.max(1, ResUtil.getScreenWidth(requireContext()) - ResUtil.dp2px(32));
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
