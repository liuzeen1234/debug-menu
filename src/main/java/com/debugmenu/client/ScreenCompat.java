package com.debugmenu.client;

import net.minecraft.client.gui.DrawContext;

/**
 * 跨版本兼容工具：屏幕背景绘制。
 *
 * <p>{@code Screen#renderBackground} 在 1.20.1 与 1.20.2+ 之间签名不同
 * （1.20.1 为单参数，1.20.2+ 为四参数），直接调用会导致其中一个版本编译失败。
 * 这里改为自行绘制与原版一致的半透明渐变背景，避免依赖会变动的父类方法。
 */
public final class ScreenCompat {

    /** 原版游戏内菜单背景渐变色（上） */
    private static final int BACKGROUND_TOP = 0xC0101010;
    /** 原版游戏内菜单背景渐变色（下） */
    private static final int BACKGROUND_BOTTOM = 0xD0101010;

    private ScreenCompat() {}

    /**
     * 绘制游戏内菜单的半透明背景。
     *
     * @param context 绘制上下文
     * @param width   屏幕宽度
     * @param height  屏幕高度
     */
    public static void renderMenuBackground(DrawContext context, int width, int height) {
        context.fillGradient(0, 0, width, height, BACKGROUND_TOP, BACKGROUND_BOTTOM);
    }
}
