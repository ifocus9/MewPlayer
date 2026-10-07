package com.fongmi.android.tv.setting;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.StringRes;

import com.fongmi.android.tv.R;
import com.github.catvod.utils.Prefers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PlayerButtonSetting {

    public static final String PLAYER = "player";
    public static final String DECODE = "decode";
    public static final String PLAY_PARAMS = "play_params";
    public static final String CODEC_CAPABILITY = "codec_capability";
    public static final String SPEED = "speed";
    public static final String SCALE = "scale";
    public static final String LUT = "lut";
    public static final String KARAOKE = "karaoke";
    public static final String RESET = "reset";
    public static final String REPEAT = "repeat";
    public static final String TEXT = "text";
    public static final String AUDIO = "audio";
    public static final String VIDEO = "video";
    public static final String OPENING = "opening";
    public static final String ENDING = "ending";
    public static final String DANMAKU = "danmaku";
    public static final String TITLE = "title";
    public static final String PREV = "prev";
    public static final String NEXT = "next";
    public static final String EPISODES = "episodes";
    public static final String FULLSCREEN = "fullscreen";
    public static final String CHANGE = "change";

    private static final String ORDER = "player_button_order";
    private static final String HIDDEN = "player_button_hidden";
    private static final List<Item> DEFAULT = List.of(
            new Item(PLAYER, R.string.play_exo),
            new Item(DECODE, R.string.play_decode),
            new Item(PLAY_PARAMS, R.string.play_params),
            new Item(CODEC_CAPABILITY, R.string.codec_capability_short),
            new Item(SPEED, R.string.play_speed),
            new Item(SCALE, R.string.play_scale),
            new Item(LUT, R.string.play_lut),
            new Item(RESET, R.string.play_reset),
            new Item(REPEAT, R.string.play_repeat),
            new Item(TEXT, R.string.play_track_text),
            new Item(AUDIO, R.string.play_track_audio),
            new Item(VIDEO, R.string.play_track_video),
            new Item(OPENING, R.string.play_op),
            new Item(ENDING, R.string.play_ed),
            new Item(DANMAKU, R.string.danmaku),
            new Item(TITLE, R.string.play_title),
            new Item(PREV, R.string.play_prev),
            new Item(NEXT, R.string.play_next),
            new Item(EPISODES, R.string.play_episodes),
            new Item(FULLSCREEN, R.string.play_fullscreen),
            new Item(CHANGE, R.string.play_change));
    // 不在“播放器按钮设置”里展示的按钮：仍保留在内部排序中（避免 applyOrder 重排时把它甩到最前面），但不可配置、不会被隐藏
    private static final Set<String> UNCONFIGURABLE = Set.of(DANMAKU);

    public static List<Item> getItems() {
        List<Item> items = new ArrayList<>();
        List<String> order = getOrder();
        Set<String> hidden = getHidden();
        for (String id : order) {
            if (UNCONFIGURABLE.contains(id)) continue;
            Item item = find(id);
            if (item != null) items.add(item.withVisible(!hidden.contains(id)));
        }
        return items;
    }

    public static int getVisibleCount() {
        int count = 0;
        for (Item item : getItems()) if (item.visible()) count++;
        return count;
    }

    public static int getTotalCount() {
        int count = 0;
        for (Item item : DEFAULT) if (!UNCONFIGURABLE.contains(item.id())) count++;
        return count;
    }

    public static boolean isVisible(String id) {
        return !getHidden().contains(id);
    }

    public static void putVisible(String id, boolean visible) {
        if (UNCONFIGURABLE.contains(id)) return;
        Set<String> hidden = getHidden();
        if (visible) hidden.remove(id);
        else hidden.add(id);
        Prefers.put(HIDDEN, join(hidden));
    }

    public static void move(String id, int offset) {
        List<String> order = getOrder();
        int from = order.indexOf(id);
        int to = from + offset;
        if (from < 0 || to < 0 || to >= order.size()) return;
        order.remove(from);
        order.add(to, id);
        Prefers.put(ORDER, join(order));
    }

    public static void putOrder(List<String> ids) {
        List<String> previous = getOrder();
        List<String> order = new ArrayList<>();
        for (String id : ids) if (contains(id) && !UNCONFIGURABLE.contains(id) && !order.contains(id)) order.add(id);
        // 设置页传入的列表不含不可配置按钮：把它们插回到原来紧跟的那个按钮后面，保持在播放栏中的相对位置
        for (int i = 0; i < previous.size(); i++) {
            String id = previous.get(i);
            if (!UNCONFIGURABLE.contains(id) || order.contains(id)) continue;
            int index = 0;
            for (int j = i - 1; j >= 0; j--) {
                int anchor = order.indexOf(previous.get(j));
                if (anchor >= 0) {
                    index = anchor + 1;
                    break;
                }
            }
            order.add(index, id);
        }
        for (Item item : DEFAULT) if (!order.contains(item.id())) order.add(item.id());
        Prefers.put(ORDER, join(order));
    }

    public static void reset() {
        Prefers.remove(ORDER);
        Prefers.remove(HIDDEN);
    }

    public static void applyOrder(ViewGroup container, Map<String, View> views) {
        if (container == null) return;
        if (Prefers.getString(ORDER).isEmpty()) {
            applyVisibility(views);
            return;
        }
        // 只重排本来就在 container 里的按钮；放在其它固定区域（如右侧固定的片头/片尾/选集）的按钮保持原位
        List<View> ordered = new ArrayList<>();
        for (String id : getOrder()) {
            View view = views.get(id);
            if (view != null && view.getParent() == container) ordered.add(view);
        }
        for (View view : ordered) container.removeView(view);
        for (View view : ordered) container.addView(view);
        applyVisibility(views);
    }

    public static void applyVisibility(Map<String, View> views) {
        Set<String> hidden = getHidden();
        for (Map.Entry<String, View> entry : views.entrySet()) {
            if (hidden.contains(entry.getKey())) entry.getValue().setVisibility(View.GONE);
        }
    }

    private static List<String> getOrder() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String id : split(Prefers.getString(ORDER))) if (contains(id)) ids.add(id);
        for (Item item : DEFAULT) ids.add(item.id());
        return new ArrayList<>(ids);
    }

    private static Set<String> getHidden() {
        Set<String> hidden = new HashSet<>();
        // 不可配置的按钮（弹幕）忽略旧的隐藏配置，避免升级后一直被隐藏且无法在设置里恢复
        for (String id : split(Prefers.getString(HIDDEN))) if (contains(id) && !UNCONFIGURABLE.contains(id)) hidden.add(id);
        return hidden;
    }

    private static Item find(String id) {
        for (Item item : DEFAULT) if (item.id().equals(id)) return item;
        return null;
    }

    private static boolean contains(String id) {
        return find(id) != null;
    }

    private static List<String> split(String value) {
        if (value == null || value.isEmpty()) return List.of();
        return Arrays.asList(value.split(","));
    }

    private static String join(Iterable<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append(',');
            builder.append(value);
        }
        return builder.toString();
    }

    public record Item(String id, @StringRes int name, boolean visible) {

        public Item(String id, @StringRes int name) {
            this(id, name, true);
        }

        public Item withVisible(boolean visible) {
            return new Item(id, name, visible);
        }
    }
}
