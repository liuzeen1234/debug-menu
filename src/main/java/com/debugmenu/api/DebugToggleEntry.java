package com.debugmenu.api;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 调试开关条目：其他 Mod 注册调试开关时使用此格式。
 *
 * <p>每个条目描述一个可开关的调试功能：
 * <ul>
 *   <li>modId: 所属 Mod 的 ID</li>
 *   <li>key: 唯一标识（建议格式 "modId:feature_name"）</li>
 *   <li>displayName: 菜单中显示的名称</li>
 *   <li>getter: 获取当前开关状态</li>
 *   <li>setter: 设置开关状态</li>
 *   <li>visibleWhen（可选）: 可见性谓词，返回 false 时该开关不在菜单中显示</li>
 * </ul>
 *
 * <p><b>二级（条件显示）开关：</b>传入 {@code visibleWhen} 谓词即可让此开关仅在满足条件时
 * 才出现在菜单里。最常见的场景是"某个一级开关开启后才显示二级开关"，可配合
 * {@link DebugMenuApi#visibleWhenEnabled(String)} /
 * {@link DebugMenuApi#visibleWhenOption(String, String...)} 一行搞定：
 * <pre>{@code
 * // 一级开关
 * DebugMenuApi.register(new DebugToggleEntry(
 *     "my-mod", "my-mod:feature", "总功能",
 *     () -> featureOn, (v) -> { featureOn = v; save(); }));
 *
 * // 二级开关：仅当一级开启时显示
 * DebugMenuApi.register(new DebugToggleEntry(
 *     "my-mod", "my-mod:feature_detail", "细节子选项",
 *     () -> detailOn, (v) -> { detailOn = v; save(); },
 *     DebugMenuApi.visibleWhenEnabled("my-mod:feature")));
 * }</pre>
 *
 * <p>可见性在每次菜单重建时实时求值，因此切换一级开关后二级开关会立即出现/隐藏。
 * 谓词只影响<b>是否显示</b>，不影响 {@code getter/setter}——隐藏时开关值保持不变。
 */
public class DebugToggleEntry {

    private final String modId;
    private final String key;
    private final String displayName;
    private final Supplier<Boolean> getter;
    private final Consumer<Boolean> setter;
    /** 可见性谓词；为 {@code null} 表示始终可见。 */
    private final Supplier<Boolean> visibleWhen;

    /**
     * 创建调试开关条目（始终可见）。
     *
     * @param modId       所属 Mod 的 ID
     * @param key         唯一标识键
     * @param displayName 菜单中显示的名称
     * @param getter      获取当前开关状态
     * @param setter      设置开关状态
     */
    public DebugToggleEntry(String modId, String key, String displayName,
                            Supplier<Boolean> getter, Consumer<Boolean> setter) {
        this(modId, key, displayName, getter, setter, null);
    }

    /**
     * 创建调试开关条目，并指定可见性谓词（二级/条件显示开关）。
     *
     * @param modId       所属 Mod 的 ID
     * @param key         唯一标识键
     * @param displayName 菜单中显示的名称
     * @param getter      获取当前开关状态
     * @param setter      设置开关状态
     * @param visibleWhen 可见性谓词；返回 {@code false} 时不显示。传 {@code null} 等同于始终可见
     */
    public DebugToggleEntry(String modId, String key, String displayName,
                            Supplier<Boolean> getter, Consumer<Boolean> setter,
                            Supplier<Boolean> visibleWhen) {
        this.modId = modId;
        this.key = key;
        this.displayName = displayName;
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

    public boolean isEnabled() {
        return getter.get();
    }

    public void setEnabled(boolean enabled) {
        setter.accept(enabled);
    }

    public void toggle() {
        setter.accept(!getter.get());
    }

    /**
     * 是否为二级（条件显示）开关，即注册时提供了可见性谓词。
     *
     * <p>菜单可据此对二级开关做视觉区分（如缩进）。
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
