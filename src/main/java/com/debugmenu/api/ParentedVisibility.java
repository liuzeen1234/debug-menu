package com.debugmenu.api;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 携带父条目 key 的可见性谓词。
 *
 * <p>{@link DebugMenuApi#visibleWhenEnabled(String)} /
 * {@link DebugMenuApi#visibleWhenOption(String, String...)} 生成的二级条目谓词都是本类型，
 * 除了实时求值"是否可见"外，还把它依赖的<b>父条目 key</b> 显式记录下来，供菜单在渲染时
 * 把子条目自动排到父条目下方。
 *
 * <p>普通的 {@code Supplier<Boolean>} 谓词（用户手写的 lambda）不携带父信息，
 * 此时对应条目的 {@code getParentKey()} 返回 {@code null}，其排序位置保持注册顺序。
 */
public final class ParentedVisibility implements Supplier<Boolean> {

    private final String parentKey;
    private final Supplier<Boolean> delegate;

    /**
     * @param parentKey 依赖的父条目 key（不能为 null）
     * @param delegate  实际求值可见性的谓词（不能为 null）
     */
    public ParentedVisibility(String parentKey, Supplier<Boolean> delegate) {
        this.parentKey = Objects.requireNonNull(parentKey, "parentKey cannot be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
    }

    /** 依赖的父条目 key。 */
    public String getParentKey() {
        return parentKey;
    }

    @Override
    public Boolean get() {
        return delegate.get();
    }

    /**
     * 从任意可见性谓词中提取父条目 key。
     *
     * @param visibleWhen 条目的可见性谓词，可为 null
     * @return 若谓词是 {@link ParentedVisibility} 则返回其 parentKey，否则返回 {@code null}
     */
    public static String parentKeyOf(Supplier<Boolean> visibleWhen) {
        return visibleWhen instanceof ParentedVisibility
                ? ((ParentedVisibility) visibleWhen).getParentKey()
                : null;
    }
}
