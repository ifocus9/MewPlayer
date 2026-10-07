package com.fongmi.android.tv.impl;

import com.fongmi.android.tv.web.page.WebPage;

/**
 * 网页管理弹窗（WebPageEditDialog / WebPageListDialog）回调给宿主 Fragment。
 * 弹窗内部已完成持久化（WebPageManager），这里只负责刷新界面。
 */
public interface WebPageListener {

    /**
     * 新增或编辑后已保存。
     */
    void onWebPageSaved(WebPage page);

    /**
     * 用户在列表中点选了某个网页。
     */
    void onWebPageSelected(WebPage page);

    /**
     * 删除 / 排序 / 设为默认等变更。
     */
    void onWebPagesChanged();
}
