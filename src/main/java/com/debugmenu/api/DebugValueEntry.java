package com.debugmenu.api;

import net.minecraft.server.network.ServerPlayerEntity;

import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 调试数值条目：其他 Mod 注册可调整的整数值时使用此格式。
 *
 * <p>对标 {@link DebugToggleEntry}，但表示一个受 min/max/step 约束的整数值，
 * 在菜单中渲染为滑条。
 *
 * <p><b>重要约定（方案 B）：</b>同一个 {@code key} 需要在客户端和服务端两侧各注册一次：
 * <ul>
 *   <li>客户端注册的 getter/setter 服务于 UI（滑条本地显示）；</li>
 *   <li>服务端注册的 setter 服务于收到同步包后在服务端主线程执行。</li>
 * </ul>
 * 两侧靠相同的 {@code key} 对应。
 *
 * <p><b>权限：</b>服务端应用来自客户端的数值前会做权限校验。默认要求玩家的
 * 权限等级 {@code >= 2}（{@link #DEFAULT_PERMISSION}）。各 Mod 可在构造时传入
 * 自定义 {@link BiPredicate} 覆盖默认策略。
 */
public class DebugValueEntry {

    /** 默认权限：操作员等级（permissionLevel >= 2）。 */
    public static final BiPredicate<ServerPlayerEntity, Integer> DEFAULT_PERMISSION =
            (player, value) -> player.hasPermissionLevel(2);

    private final String modId;
    private final String key;
    private final String displayName;
    private final int min;
    private final int max;
    private final int step;
    private final Supplier<Integer> getter;
    private final Consumer<Integer> setter;
    /** 可选显示单位后缀（如 "格"、"%"），可为 null。 */
    private final String unitSuffix;
    /** 服务端应用数值时的权限判据，绝不为 null。 */
    private final BiPredicate<ServerPlayerEntity, Integer> permission;

    /**
     * 完整构造。
     *
     * @param modId       所属 Mod 的 ID
     * @param key         唯一标识键（建议格式 "modId:feature_name"）
     * @param displayName 菜单中显示的名称
     * @param min         最小值（含）
     * @param max         最大值（含）
     * @param step        步长（必须 &gt; 0）
     * @param getter      获取当前值
     * @param setter      设置当前值（内部已 clamp）
     * @param unitSuffix  可选单位后缀，可为 null
     * @param permission  服务端权限判据，为 null 时使用 {@link #DEFAULT_PERMISSION}
     */
    public DebugValueEntry(String modId, String key, String displayName,
                           int min, int max, int step,
                           Supplier<Integer> getter, Consumer<Integer> setter,
                           String unitSuffix,
                           BiPredicate<ServerPlayerEntity, Integer> permission) {
        if (max < min) {
            throw new IllegalArgumentException("max (" + max + ") must be >= min (" + min + ")");
        }
        if (step <= 0) {
            throw new IllegalArgumentException("step must be > 0, got " + step);
        }
        this.modId = modId;
        this.key = key;
        this.displayName = displayName;
        this.min = min;
        this.max = max;
        this.step = step;
        this.getter = getter;
        this.setter = setter;
        this.unitSuffix = unitSuffix;
        this.permission = permission != null ? permission : DEFAULT_PERMISSION;
    }

    /**
     * 便捷构造：默认步长 1、无单位、默认权限。
     */
    public DebugValueEntry(String modId, String key, String displayName,
                           int min, int max,
                           Supplier<Integer> getter, Consumer<Integer> setter) {
        this(modId, key, displayName, min, max, 1, getter, setter, null, null);
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

    public int getMin() {
        return min;
    }

    public int getMax() {
        return max;
    }

    public int getStep() {
        return step;
    }

    public String getUnitSuffix() {
        return unitSuffix;
    }

    /**
     * 获取当前值（已 clamp 到 [min, max]）。
     */
    public int getValue() {
        return clamp(getter.get());
    }

    /**
     * 设置当前值。传入值会先 clamp 到 [min, max] 再交给 setter。
     */
    public void setValue(int value) {
        setter.accept(clamp(value));
    }

    /**
     * 将值 clamp 到 [min, max]。
     */
    public int clamp(int value) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    /**
     * 服务端权限校验：判断该玩家是否允许把此条目设置为给定值。
     *
     * @param player 发起请求的服务端玩家
     * @param value  期望设置的值（已 clamp）
     * @return true 表示允许
     */
    public boolean canModify(ServerPlayerEntity player, int value) {
        return permission.test(player, value);
    }
}
