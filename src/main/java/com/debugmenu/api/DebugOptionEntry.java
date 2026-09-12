package com.debugmenu.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 调试多状态开关条目：其他 Mod 注册"有多个状态、状态名可自定义"的开关时使用此格式。
 *
 * <p>与 {@link DebugToggleEntry}（只有开/关两态）不同，本条目可以有任意数量的状态，
 * 且每个状态的显示名称完全由注册方自定义。典型用途是"语言选择"这类多选项开关：
 * <pre>{@code
 * DebugMenuApi.registerOption(new DebugOptionEntry(
 *     "my-mod", "my-mod:language", "语言",
 *     List.of("English", "简体中文", "日本語"),
 *     () -> currentLanguage,               // getter：返回当前状态名
 *     (v) -> { currentLanguage = v; save(); } // setter：写入新状态名
 * ));
 * }</pre>
 *
 * <p>菜单会把它渲染成一个按钮，点击时在各状态之间循环切换（最后一个之后回到第一个）。
 *
 * <p><b>状态以名称（String）为准。</b>getter 返回的名称必须是 {@link #getOptions()} 中的一个；
 * 若返回了不在列表内的名称（或 null），菜单会退回到第一个状态。这样即使配置里存了旧的
 * 无效状态名，也不会导致崩溃。
 *
 * <p>本条目是<b>纯客户端</b>概念（与 {@link DebugToggleEntry} 一致）：状态只在客户端切换，
 * 不涉及服务端同步。如需服务端权限/同步语义，请使用 {@link DebugValueEntry}。
 */
public class DebugOptionEntry {

    private final String modId;
    private final String key;
    private final String displayName;
    /** 不可变的状态名列表，顺序即循环顺序，至少含一个元素。 */
    private final List<String> options;
    private final Supplier<String> getter;
    private final Consumer<String> setter;

    /**
     * 创建多状态开关条目。
     *
     * @param modId       所属 Mod 的 ID
     * @param key         唯一标识键（建议格式 "modId:feature_name"）
     * @param displayName 菜单中显示的名称
     * @param options     状态名列表（顺序即循环顺序，不能为空、不能含 null）
     * @param getter      获取当前状态名（应返回 options 中的一个）
     * @param setter      设置新状态名
     */
    public DebugOptionEntry(String modId, String key, String displayName,
                            List<String> options,
                            Supplier<String> getter, Consumer<String> setter) {
        Objects.requireNonNull(options, "options cannot be null");
        Objects.requireNonNull(getter, "getter cannot be null");
        Objects.requireNonNull(setter, "setter cannot be null");
        if (options.isEmpty()) {
            throw new IllegalArgumentException("options must contain at least one state");
        }
        List<String> copy = new ArrayList<>(options.size());
        for (String opt : options) {
            if (opt == null) {
                throw new IllegalArgumentException("option state name cannot be null");
            }
            copy.add(opt);
        }
        this.modId = modId;
        this.key = key;
        this.displayName = displayName;
        this.options = Collections.unmodifiableList(copy);
        this.getter = getter;
        this.setter = setter;
    }

    public String getModId() {
        return modId;
    }

    public String getKey() {
        return key;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 获取所有状态名（只读，顺序即循环顺序）。
     */
    public List<String> getOptions() {
        return options;
    }

    /**
     * 状态数量。
     */
    public int size() {
        return options.size();
    }

    /**
     * 获取当前状态名。
     *
     * <p>若 getter 返回的名称不在 {@link #getOptions()} 里（或为 null），
     * 则退回到第一个状态的名称，保证始终返回一个有效状态。
     */
    public String getSelected() {
        String current = getter.get();
        if (current != null && options.contains(current)) {
            return current;
        }
        return options.get(0);
    }

    /**
     * 获取当前状态在列表中的下标（0-based）。无效时返回 0。
     */
    public int getSelectedIndex() {
        int idx = options.indexOf(getSelectedRaw());
        return idx >= 0 ? idx : 0;
    }

    /** getter 的原始返回值（可能无效），仅内部下标计算使用。 */
    private String getSelectedRaw() {
        return getter.get();
    }

    /**
     * 直接设置为某个状态名。若名称不在列表内则忽略。
     *
     * @param optionName 目标状态名
     */
    public void setSelected(String optionName) {
        if (optionName != null && options.contains(optionName)) {
            setter.accept(optionName);
        }
    }

    /**
     * 切换到下一个状态（到末尾后回到第一个）。
     */
    public void cycle() {
        int next = (getSelectedIndex() + 1) % options.size();
        setter.accept(options.get(next));
    }

    /**
     * 切换到上一个状态（到开头后回到最后一个）。
     */
    public void cycleBack() {
        int size = options.size();
        int prev = (getSelectedIndex() - 1 + size) % size;
        setter.accept(options.get(prev));
    }
}
