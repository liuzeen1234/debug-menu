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
 *
 * <p><b>side（重要，单人环境正确性的关键）：</b>每条 entry 用 {@link Side} 标注它服务于
 * 哪一侧。单人游戏里客户端与内置服务端在<b>同一个 JVM、同一份 {@code valueEntries} 列表</b>，
 * 同一个 key 会有两条 entry（客户端一条、服务端一条）。若不区分 side，则：
 * <ul>
 *   <li>按 key 查找只会取第一条，导致 S2C 回写更新错对象、UI 不刷新；</li>
 *   <li>UI 会把同一个滑条渲染两次。</li>
 * </ul>
 * 因此网络层与 UI 都按 side 过滤：C2S 收包用 {@link Side#SERVER} 侧，S2C 回写与 UI 用
 * {@link Side#CLIENT} 侧（{@link Side#BOTH} 对两侧都匹配）。
 *
 * <p><b>{@link Side#BOTH} 的一致性约定：</b>只有当两侧的 getter/setter 指向<b>同一份状态</b>
 * （例如都读写同一个静态字段或同一个配置类）时才应使用 {@code BOTH}；否则请为两侧各注册一条
 * 独立的 {@code CLIENT}/{@code SERVER} entry，各自指向自己那侧的字段。
 */
public class DebugValueEntry {

    /** 默认权限：操作员等级（permissionLevel >= 2）。 */
    public static final BiPredicate<ServerPlayerEntity, Integer> DEFAULT_PERMISSION =
            (player, value) -> player.hasPermissionLevel(2);

    /**
     * 条目服务的逻辑侧。
     *
     * <ul>
     *   <li>{@link #CLIENT}：仅供 UI（滑条本地显示、发包）。</li>
     *   <li>{@link #SERVER}：仅供服务端收包后执行。</li>
     *   <li>{@link #BOTH}：两侧共用同一份状态时使用，对客户端与服务端查找都匹配。</li>
     * </ul>
     */
    public enum Side {
        CLIENT,
        SERVER,
        BOTH;

        /** 该条目是否应在给定的查询侧可见。BOTH 对任意查询侧都匹配。 */
        public boolean matches(Side query) {
            return this == BOTH || query == BOTH || this == query;
        }
    }

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
    /** 条目服务的逻辑侧，绝不为 null。 */
    private final Side side;

    /**
     * 完整构造（带 side）。
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
     * @param side        条目服务的逻辑侧，为 null 时使用 {@link Side#BOTH}
     */
    public DebugValueEntry(String modId, String key, String displayName,
                           int min, int max, int step,
                           Supplier<Integer> getter, Consumer<Integer> setter,
                           String unitSuffix,
                           BiPredicate<ServerPlayerEntity, Integer> permission,
                           Side side) {
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
        this.side = side != null ? side : Side.BOTH;
    }

    /**
     * 兼容构造：不带 side，默认 {@link Side#BOTH}。
     *
     * <p><b>注意：</b>仅当两侧 getter/setter 指向同一份状态时才适合 {@code BOTH}；否则请用带
     * {@link Side} 的完整构造为两侧各注册一条。
     */
    public DebugValueEntry(String modId, String key, String displayName,
                           int min, int max, int step,
                           Supplier<Integer> getter, Consumer<Integer> setter,
                           String unitSuffix,
                           BiPredicate<ServerPlayerEntity, Integer> permission) {
        this(modId, key, displayName, min, max, step, getter, setter, unitSuffix, permission, Side.BOTH);
    }

    /**
     * 便捷构造：默认步长 1、无单位、默认权限、{@link Side#BOTH}。
     */
    public DebugValueEntry(String modId, String key, String displayName,
                           int min, int max,
                           Supplier<Integer> getter, Consumer<Integer> setter) {
        this(modId, key, displayName, min, max, 1, getter, setter, null, null, Side.BOTH);
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
     * 该条目服务的逻辑侧。
     */
    public Side getSide() {
        return side;
    }

    /**
     * 该条目是否应在给定查询侧可见。{@link Side#BOTH} 对任意查询侧都匹配。
     */
    public boolean matchesSide(Side query) {
        return side.matches(query);
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
