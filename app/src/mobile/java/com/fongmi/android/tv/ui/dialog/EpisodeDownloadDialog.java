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

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.DialogEpisodeDownloadBinding;
import com.fongmi.android.tv.player.PlayerHelper;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.adapter.EpisodeGroupAdapter;
import com.fongmi.android.tv.ui.adapter.EpisodeSizing;
import com.fongmi.android.tv.ui.base.ViewType;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * 详情页“选集下载”抽屉：头部（下载 / 下载列表 >）+ 分段标签 + 可选网格。
 * 供用户选择指定剧集调起 1DM 等外部下载器下载。
 */
public class EpisodeDownloadDialog extends BaseBottomSheetDialog implements EpisodeAdapter.OnClickListener, EpisodeGroupAdapter.OnClickListener {

    private static final float HEIGHT_RATIO = 0.7f;

    public interface Host {

        List<Episode> getEpisodeDownloadItems();

        int getEpisodeDownloadMaxSpan();

        void onEpisodeDownload(Episode item);
    }

    private DialogEpisodeDownloadBinding binding;
    private EpisodeGroupAdapter groupAdapter;
    private EpisodeAdapter episodeAdapter;
    private SpaceItemDecoration episodeDecoration;
    private int spanCount = -1;
    private int measuredWidth;

    public static EpisodeDownloadDialog create() {
        return new EpisodeDownloadDialog();
    }

    public void show(FragmentActivity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || activity.getSupportFragmentManager().isStateSaved()) return;
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof EpisodeDownloadDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogEpisodeDownloadBinding.inflate(inflater, container, false);
    }

    @Override
    protected boolean transparent() {
        return true;
    }

    @Override
    protected void setBehavior(BottomSheetDialog dialog) {
        super.setBehavior(dialog);
        FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet == null) return;
        int height = Math.round(ResUtil.getScreenHeight(requireContext()) * HEIGHT_RATIO);
        ViewGroup.LayoutParams params = sheet.getLayoutParams();
        params.height = height;
        sheet.setLayoutParams(params);
        BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
        behavior.setPeekHeight(height);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        behavior.setSkipCollapsed(true);
    }

    @Override
    protected void initView() {
        binding.group.setNestedScrollingEnabled(false);
        binding.group.setHasFixedSize(true);
        binding.group.setItemAnimator(null);
        binding.group.setAdapter(groupAdapter = new EpisodeGroupAdapter(this, true));
        binding.episode.setNestedScrollingEnabled(true);
        binding.episode.setHasFixedSize(true);
        binding.episode.setItemAnimator(null);
        binding.episode.setAdapter(episodeAdapter = new EpisodeAdapter(this, ViewType.GRID));
        binding.episode.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                syncGroupByScroll();
            }
        });
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
        binding.downloadList.setOnClickListener(view -> PlayerHelper.openDownloadApp(requireActivity()));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    @Nullable
    private Host getDownloadHost() {
        return getActivity() instanceof Host host ? host : null;
    }

    public void refresh() {
        if (binding == null || !isAdded()) return;
        Host host = getDownloadHost();
        if (host == null) return;
        List<Episode> items = host.getEpisodeDownloadItems();
        if (items == null || items.isEmpty()) {
            dismissAllowingStateLoss();
            return;
        }
        episodeAdapter.addAll(items);
        int size = episodeAdapter.size();
        int selected = episodeAdapter.getPosition();
        List<EpisodeGroupAdapter.Group> groups = buildGroups(size, selected);
        groupAdapter.addAll(groups);
        binding.group.setVisibility(groups.isEmpty() ? View.GONE : View.VISIBLE);
        binding.group.scrollToPosition(groupAdapter.getPosition());
        updateSpan(episodeAdapter.getItems());
        binding.episode.post(() -> scrollToSelected(selected));
    }

    private static List<EpisodeGroupAdapter.Group> buildGroups(int size, int selectedIndex) {
        List<EpisodeGroupAdapter.Group> groups = new ArrayList<>();
        if (size <= 0) return groups;
        int groupSize = 20;
        int count = (int) Math.ceil(size / (float) groupSize);
        for (int i = 0; i < count; i++) {
            int start = i * groupSize;
            int end = Math.min(start + groupSize, size);
            int labelStart = start + 1;
            int labelEnd = end;
            String name = (labelStart == labelEnd) ? (labelStart + " - " + labelEnd) : (labelStart + " - " + labelEnd);
            EpisodeGroupAdapter.Group group = new EpisodeGroupAdapter.Group(name, start, end);
            group.selected = selectedIndex >= start && selectedIndex < end;
            groups.add(group);
        }
        if (groups.stream().noneMatch(group -> group.selected)) groups.get(0).selected = true;
        return groups;
    }

    private void updateSpan(List<Episode> items) {
        Host host = getDownloadHost();
        int maxSpan = host == null ? 4 : host.getEpisodeDownloadMaxSpan();
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
        Host host = getDownloadHost();
        if (host != null) host.onEpisodeDownload(item);
        dismissAllowingStateLoss();
    }
}
