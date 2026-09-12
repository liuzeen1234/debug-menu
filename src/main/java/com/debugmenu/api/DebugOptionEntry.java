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
    /** 可见性谓词；为 {@code null} 表示始终可见。 */
    private final Supplier<Boolean> visibleWhen;

    /**
     * 创建多状态开关条目（始终可见）。
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
        this(modId, key, displayName, options, getter, setter, null);
    }

    /**
     * 创建多状态开关条目，并指定可见性谓词（二级/条件显示的多状态开关）。
     *
     * <p>传入 {@code visibleWhen} 后，此条目仅在谓词返回 {@code true} 时才出现在菜单里。
     * 最常见的场景是"某个一级开关开启后才显示该多状态选项"，可配合
     * {@link DebugMenuApi#visibleWhenEnabled(String)} /
     * {@link DebugMenuApi#visibleWhenOption(String, String...)} 直接得到谓词。
     * 可见性在每次菜单重建时实时求值；谓词只影响<b>是否显示</b>，不影响 {@code getter/setter}。
     *
     * @param modId       所属 Mod 的 ID
     * @param key         唯一标识键（建议格式 "modId:feature_name"）
     * @param displayName 菜单中显示的名称
     * @param options     状态名列表（顺序即循环顺序，不能为空、不能含 null）
     * @param getter      获取当前状态名（应返回 options 中的一个）
     * @param setter      设置新状态名
     * @param visibleWhen 可见性谓词；返回 {@code false} 时不显示。传 {@code null} 等同于始终可见
     */
    public DebugOptionEntry(String modId, String key, String displayName,
                            List<String> options,
                            Supplier<String> getter, Consumer<String> setter,
                            Supplier<Boolean> visibleWhen) {
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
        this.visibleWhen = visibleWhen;
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

    /**
     * 是否为二级（条件显示）多状态开关，即注册时提供了可见性谓词。
     *
     * <p>菜单可据此对二级条目做视觉区分（如缩进）。
     */
    public boolean isSecondary() {
        return visibleWhen != null;
    }

    /**
     * 当前是否应在菜单中显示。
     *
     * <p>未提供可见性谓词时恒为 {@code true}；否则实时求值该谓词，
     * 谓词返回 {@code null} 视为不可见。
     */
    public boolean isVisible() {
        return visibleWhen == null || Boolean.TRUE.equals(visibleWhen.get());
    }
}
