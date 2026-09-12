package com.debugmenu.api;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 调试菜单 API：其他 Mod 通过此类注册调试开关。
 *
 * <p>使用方法（在其他 Mod 的初始化阶段调用）：
 * <pre>{@code
 * DebugMenuApi.register(new DebugToggleEntry(
 *     "my-mod", "my-mod:feature_debug", "功能调试",
 *     () -> myDebugEnabled, (v) -> { myDebugEnabled = v; saveConfig(); }
 * ));
 * }</pre>
 *
 * <p>除布尔开关外，还支持：
 * <ul>
 *   <li>{@link DebugValueEntry} 数值条目（滑条，支持服务端同步）—— {@link #registerValue}</li>
 *   <li>{@link DebugOptionEntry} 多状态开关（状态名可自定义，如语言选择）—— {@link #registerOption}</li>
 * </ul>
 * <pre>{@code
 * DebugMenuApi.registerOption(new DebugOptionEntry(
 *     "my-mod", "my-mod:language", "语言",
 *     java.util.List.of("English", "简体中文", "日本語"),
 *     () -> currentLanguage, (v) -> { currentLanguage = v; saveConfig(); }
 * ));
 * }</pre>
 *
 * <p>调试菜单 Mod 会自动读取所有注册的条目并生成 UI。
 * 如果没有任何注册，菜单将显示"无可控制的开关"。
 */
public final class DebugMenuApi {

    private static final List<DebugToggleEntry> entries = new CopyOnWriteArrayList<>();

    /**
     * 数值条目注册表。
     *
     * <p><b>注意：</b>与布尔条目不同，这份注册表在方案 B 下<b>客户端和服务端都会填充</b>
     * （服务端注册 setter 用于收包后执行，客户端注册 getter/setter 用于 UI）。
     * 因此不能再假设"只有客户端碰"。这里沿用 {@link CopyOnWriteArrayList} 保证线程安全。
     */
    private static final List<DebugValueEntry> valueEntries = new CopyOnWriteArrayList<>();

    /**
     * 多状态开关条目注册表。
     *
     * <p>与布尔开关一样是纯客户端概念（状态只在客户端切换）。沿用
     * {@link CopyOnWriteArrayList} 保证线程安全。
     */
    private static final List<DebugOptionEntry> optionEntries = new CopyOnWriteArrayList<>();

    private DebugMenuApi() {}

    /**
     * 注册一个调试开关。
     *
     * @param entry 调试开关条目
     */
    public static void register(DebugToggleEntry entry) {
        Objects.requireNonNull(entry, "DebugToggleEntry cannot be null");
        entries.add(entry);
    }

    /**
     * 批量注册调试开关。
     *
     * @param newEntries 调试开关条目集合
     */
    public static void registerAll(Collection<DebugToggleEntry> newEntries) {
        for (DebugToggleEntry entry : newEntries) {
            register(entry);
        }
    }

    /**
     * 获取所有已注册的调试开关（只读视图）。
     */
    public static List<DebugToggleEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    /**
     * 是否有任何已注册的条目（布尔开关或数值条目任一非空）。
     *
     * <p>菜单据此判断是否显示"无可控制的开关"。只要有数值条目也应视为有内容，
     * 否则纯数值场景会误报为空。
     */
    public static boolean hasEntries() {
        return !entries.isEmpty() || !valueEntries.isEmpty() || !optionEntries.isEmpty();
    }

    /**
     * 按 modId 分组获取所有条目。
     */
    public static Map<String, List<DebugToggleEntry>> getEntriesByMod() {
        Map<String, List<DebugToggleEntry>> grouped = new LinkedHashMap<>();
        for (DebugToggleEntry entry : entries) {
            grouped.computeIfAbsent(entry.getModId(), k -> new ArrayList<>()).add(entry);
        }
        return grouped;
    }

    /**
     * 根据 key 查找条目。
     *
     * @param key 开关的唯一标识
     * @return 对应条目，未找到返回 null
     */
    public static DebugToggleEntry getEntry(String key) {
        for (DebugToggleEntry entry : entries) {
            if (entry.getKey().equals(key)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 快速查询某个开关是否启用（便捷方法）。
     * 如果 key 不存在，返回 false。
     *
     * @param key 开关的唯一标识
     * @return 开关是否启用
     */
    public static boolean isEnabled(String key) {
        DebugToggleEntry entry = getEntry(key);
        return entry != null && entry.isEnabled();
    }

    // ==================== 数值条目 ====================

    /**
     * 注册一个调试数值条目（滑条）。
     *
     * <p>同一个 key 需要在客户端与服务端各注册一次，见 {@link DebugValueEntry} 的说明。
     *
     * @param entry 数值条目
     */
    public static void registerValue(DebugValueEntry entry) {
        Objects.requireNonNull(entry, "DebugValueEntry cannot be null");
        valueEntries.add(entry);
    }

    /**
     * 批量注册数值条目。
     */
    public static void registerAllValues(Collection<DebugValueEntry> newEntries) {
        for (DebugValueEntry entry : newEntries) {
            registerValue(entry);
        }
    }

    /**
     * 获取所有已注册的数值条目（只读视图）。
     */
    public static List<DebugValueEntry> getValueEntries() {
        return Collections.unmodifiableList(valueEntries);
    }

    /**
     * 是否有已注册的数值条目。
     */
    public static boolean hasValueEntries() {
        return !valueEntries.isEmpty();
    }

    /**
     * 按 modId 分组获取所有数值条目（不过滤 side，可能含同 key 的两侧重复）。
     *
     * <p>一般应使用 {@link #getValueEntriesByMod(DebugValueEntry.Side)} 以按 side 过滤并去重。
     */
    public static Map<String, List<DebugValueEntry>> getValueEntriesByMod() {
        Map<String, List<DebugValueEntry>> grouped = new LinkedHashMap<>();
        for (DebugValueEntry entry : valueEntries) {
            grouped.computeIfAbsent(entry.getModId(), k -> new ArrayList<>()).add(entry);
        }
        return grouped;
    }

    /**
     * 按 modId 分组获取匹配指定 side 的数值条目，并<b>按 key 去重</b>。
     *
     * <p>单人环境下同一个 key 会有客户端和服务端两条 entry；此方法只保留匹配 {@code side}
     * （或 {@link DebugValueEntry.Side#BOTH}）的条目，并对同 key 只保留先注册的一条，避免
     * UI 重复渲染。
     *
     * @param side 查询侧（UI 用 {@link DebugValueEntry.Side#CLIENT}）
     */
    public static Map<String, List<DebugValueEntry>> getValueEntriesByMod(DebugValueEntry.Side side) {
        Map<String, List<DebugValueEntry>> grouped = new LinkedHashMap<>();
        Set<String> seenKeys = new HashSet<>();
        for (DebugValueEntry entry : valueEntries) {
            if (!entry.matchesSide(side)) {
                continue;
            }
            if (!seenKeys.add(entry.getKey())) {
                continue; // 同 key 已收录，去重
            }
            grouped.computeIfAbsent(entry.getModId(), k -> new ArrayList<>()).add(entry);
        }
        return grouped;
    }

    /**
     * 根据 key 查找数值条目（不过滤 side，返回先注册的一条）。
     *
     * <p>在同 key 两端注册的场景下这可能返回错误的一侧，一般应使用
     * {@link #getValueEntry(String, DebugValueEntry.Side)}。
     *
     * @param key 条目的唯一标识
     * @return 对应条目，未找到返回 null
     */
    public static DebugValueEntry getValueEntry(String key) {
        for (DebugValueEntry entry : valueEntries) {
            if (entry.getKey().equals(key)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 根据 key 和 side 查找数值条目。
     *
     * <p>只返回匹配该 side（或 {@link DebugValueEntry.Side#BOTH}）的条目。网络层据此确保：
     * C2S 收包用 {@link DebugValueEntry.Side#SERVER} 拿到服务端那条，S2C 回写用
     * {@link DebugValueEntry.Side#CLIENT} 拿到客户端那条。
     *
     * @param key  条目的唯一标识
     * @param side 查询侧
     * @return 对应条目，未找到返回 null
     */
    public static DebugValueEntry getValueEntry(String key, DebugValueEntry.Side side) {
        for (DebugValueEntry entry : valueEntries) {
            if (entry.getKey().equals(key) && entry.matchesSide(side)) {
                return entry;
            }
        }
        return null;
    }

    // ==================== 多状态开关条目 ====================

    /**
     * 注册一个多状态开关条目（状态名由注册方自定义，如语言选择）。
     *
     * @param entry 多状态开关条目
     */
    public static void registerOption(DebugOptionEntry entry) {
        Objects.requireNonNull(entry, "DebugOptionEntry cannot be null");
        optionEntries.add(entry);
    }

    /**
     * 批量注册多状态开关条目。
     */
    public static void registerAllOptions(Collection<DebugOptionEntry> newEntries) {
        for (DebugOptionEntry entry : newEntries) {
            registerOption(entry);
        }
    }

    /**
     * 获取所有已注册的多状态开关条目（只读视图）。
     */
    public static List<DebugOptionEntry> getOptionEntries() {
        return Collections.unmodifiableList(optionEntries);
    }

    /**
     * 是否有已注册的多状态开关条目。
     */
    public static boolean hasOptionEntries() {
        return !optionEntries.isEmpty();
    }

    /**
     * 按 modId 分组获取所有多状态开关条目。
     */
    public static Map<String, List<DebugOptionEntry>> getOptionEntriesByMod() {
        Map<String, List<DebugOptionEntry>> grouped = new LinkedHashMap<>();
        for (DebugOptionEntry entry : optionEntries) {
            grouped.computeIfAbsent(entry.getModId(), k -> new ArrayList<>()).add(entry);
        }
        return grouped;
    }

    /**
     * 根据 key 查找多状态开关条目。
     *
     * @param key 条目的唯一标识
     * @return 对应条目，未找到返回 null
     */
    public static DebugOptionEntry getOptionEntry(String key) {
        for (DebugOptionEntry entry : optionEntries) {
            if (entry.getKey().equals(key)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 快速查询某个多状态开关的当前状态名（便捷方法）。
     * 如果 key 不存在，返回 null。
     *
     * @param key 条目的唯一标识
     * @return 当前状态名，未找到返回 null
     */
    public static String getSelectedOption(String key) {
        DebugOptionEntry entry = getOptionEntry(key);
        return entry != null ? entry.getSelected() : null;
    }
}
