package com.fongmi.android.tv.ui.base;

import com.fongmi.android.tv.web.WebHomeViewport;

/**
 * HomeActivity 中需要感知网页 chrome 状态（normal / edge / immersive）与系统栏安全区的 Fragment。
 * WebHomeChromeController 的模式与 viewport 变化会分发给所有已创建且实现该接口的 Fragment。
 */
public interface WebChromeHost {

    /**
     * chrome 模式变化。注意这里传入的是控制器的原始模式；
     * 只有网页 Tab 可见时模式才会真正生效，其它 Tab 可忽略。
     */
    default void applyWebHomeChrome(String mode) {
    }

    /**
     * 系统栏 / 刘海 / 手势区等安全区变化。
     */
    default void applyWebHomeViewport(WebHomeViewport viewport) {
    }
}
