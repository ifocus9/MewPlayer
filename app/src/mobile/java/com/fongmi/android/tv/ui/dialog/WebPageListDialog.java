package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterWebPageBinding;
import com.fongmi.android.tv.databinding.DialogWebPageListBinding;
import com.fongmi.android.tv.impl.WebPageListener;
import com.fongmi.android.tv.web.page.WebPage;
import com.fongmi.android.tv.web.page.WebPageManager;
import com.google.android.material.textview.MaterialTextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 网页列表：点击切换；更多菜单可设为默认 / 编辑 / 上移 / 下移 / 删除。
 * 必须从宿主 Fragment（实现 {@link WebPageListener}）的 childFragmentManager 弹出。
 */
public class WebPageListDialog extends BaseBottomSheetDialog {

    private static final String ARG_CURRENT = "current";
    private static final int MENU_DEFAULT = 1;
    private static final int MENU_EDIT = 2;
    private static final int MENU_UP = 3;
    private static final int MENU_DOWN = 4;
    private static final int MENU_DELETE = 5;

    private DialogWebPageListBinding binding;
    private Adapter adapter;

    public static void show(Fragment fragment, String currentId) {
        WebPageListDialog dialog = new WebPageListDialog();
        Bundle args = new Bundle();
        args.putString(ARG_CURRENT, currentId == null ? "" : currentId);
        dialog.setArguments(args);
        dialog.show(fragment.getChildFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogWebPageListBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        binding.recycler.setItemAnimator(null);
        binding.recycler.setAdapter(adapter = new Adapter());
        refresh();
    }

    @Override
    protected void initEvent() {
        binding.add.setOnClickListener(view -> openEditor(null));
    }

    private String currentId() {
        return getArguments() == null ? "" : getArguments().getString(ARG_CURRENT, "");
    }

    private void refresh() {
        List<WebPage> items = WebPageManager.list();
        adapter.setItems(items, WebPageManager.getDefaultId(), currentId());
        binding.empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private WebPageListener listener() {
        if (getParentFragment() instanceof WebPageListener listener) return listener;
        if (getActivity() instanceof WebPageListener listener) return listener;
        return null;
    }

    private void onSelect(WebPage page) {
        WebPageListener listener = listener();
        if (listener != null) listener.onWebPageSelected(page);
        dismiss();
    }

    /**
     * 编辑弹窗挂到宿主 Fragment 上，保证保存回调能到达 WebTabFragment。
     */
    private void openEditor(@Nullable WebPage page) {
        Fragment parent = getParentFragment();
        if (parent != null) WebPageEditDialog.show(parent, page);
        dismiss();
    }

    private void onMore(View anchor, WebPage page) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        Menu items = menu.getMenu();
        if (!page.getId().equals(WebPageManager.getDefaultId())) items.add(Menu.NONE, MENU_DEFAULT, Menu.NONE, R.string.web_page_set_default);
        items.add(Menu.NONE, MENU_EDIT, Menu.NONE, R.string.dialog_edit);
        items.add(Menu.NONE, MENU_UP, Menu.NONE, R.string.web_page_move_up);
        items.add(Menu.NONE, MENU_DOWN, Menu.NONE, R.string.web_page_move_down);
        items.add(Menu.NONE, MENU_DELETE, Menu.NONE, R.string.menu_delete);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_DEFAULT -> changed(() -> WebPageManager.setDefault(page.getId()));
                case MENU_EDIT -> openEditor(page);
                case MENU_UP -> changed(() -> WebPageManager.move(page.getId(), -1));
                case MENU_DOWN -> changed(() -> WebPageManager.move(page.getId(), 1));
                case MENU_DELETE -> confirmDelete(page);
                default -> {
                    return false;
                }
            }
            return true;
        });
        menu.show();
    }

    private void confirmDelete(WebPage page) {
        MaterialTextView message = new MaterialTextView(requireContext());
        message.setText(getString(R.string.web_page_delete_confirm, page.getDisplayName()));
        message.setTextSize(14);
        Dialog[] holder = new Dialog[1];
        holder[0] = LightDialog.create(requireContext(), getString(R.string.menu_delete), message, getString(R.string.dialog_positive), view -> {
            holder[0].dismiss();
            changed(() -> WebPageManager.remove(page.getId()));
        }, getString(R.string.dialog_negative), view -> holder[0].dismiss());
        holder[0].show();
    }

    private void changed(Runnable action) {
        action.run();
        if (!isAdded() || binding == null) return;
        refresh();
        WebPageListener listener = listener();
        if (listener != null) listener.onWebPagesChanged();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.Holder> {

        private final List<WebPage> items = new ArrayList<>();
        private String defaultId = "";
        private String currentId = "";

        void setItems(List<WebPage> items, String defaultId, String currentId) {
            this.items.clear();
            this.items.addAll(items);
            this.defaultId = defaultId == null ? "" : defaultId;
            this.currentId = currentId == null ? "" : currentId;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(AdapterWebPageBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            WebPage page = items.get(position);
            boolean current = page.getId().equals(currentId);
            holder.binding.name.setText(page.getDisplayName());
            holder.binding.name.setTypeface(null, current ? Typeface.BOLD : Typeface.NORMAL);
            holder.binding.url.setText(page.getUrl());
            holder.binding.badge.setVisibility(page.getId().equals(defaultId) ? View.VISIBLE : View.GONE);
            holder.binding.getRoot().setSelected(current);
            holder.binding.getRoot().setOnClickListener(view -> onSelect(page));
            holder.binding.getRoot().setOnLongClickListener(view -> {
                onMore(holder.binding.more, page);
                return true;
            });
            holder.binding.more.setOnClickListener(view -> onMore(view, page));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {

            private final AdapterWebPageBinding binding;

            Holder(AdapterWebPageBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
