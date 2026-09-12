package com.debugmenu.client;

import com.debugmenu.api.DebugMenuApi;
import com.debugmenu.api.DebugOptionEntry;
import com.debugmenu.api.DebugToggleEntry;
import com.debugmenu.api.DebugValueEntry;
import com.debugmenu.network.DebugValueRequestC2SPacket;
import com.debugmenu.network.DebugValueSyncS2CPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 调试功能菜单主屏幕。
 *
 * <p>动态读取所有通过 {@link DebugMenuApi} 注册的调试开关，
 * 按 modId 分组显示。如果没有任何注册，显示"无可控制的开关"。
 *
 * <p>支持滚动：当开关数量超过一屏时可上下滚动。
 */
public class DebugMenuScreen extends Screen {

    /** 每个按钮的高度 */
    private static final int BUTTON_HEIGHT = 20;
    /** 按钮间距 */
    private static final int GAP = 22;
    /** 按钮宽度 */
    private static final int BUTTON_WIDTH = 260;
    /** 二级（条件显示）开关的左缩进像素，用于视觉区分层级 */
    private static final int SECONDARY_INDENT = 16;
    /** Mod 分组标题高度 */
    private static final int GROUP_HEADER_HEIGHT = 16;
    /** 顶部边距 */
    private static final int TOP_MARGIN = 40;
    /** 底部边距（留给关闭按钮） */
    private static final int BOTTOM_MARGIN = 40;

    /** 滚动偏移量 */
    private int scrollOffset = 0;
    /** 内容总高度 */
    private int totalContentHeight = 0;

    /** 当前可见的滑条控件，按 key 索引，供收到服务端回写时刷新。 */
    private final Map<String, DebugValueSliderWidget> visibleSliders = new java.util.HashMap<>();

    public DebugMenuScreen() {
        super(Text.literal("调试功能菜单"));
    }

    @Override
    protected void init() {
        this.scrollOffset = 0;

        // 注册服务端回写监听：收到真实值后刷新对应滑条
        DebugValueSyncS2CPacket.setListener((key, value) -> {
            if (this.client != null) {
                this.client.execute(() -> {
                    DebugValueSliderWidget slider = visibleSliders.get(key);
                    if (slider != null) {
                        slider.refreshFromEntry();
                    }
                });
            }
        });

        // 打开菜单时向服务端请求所有数值条目的真实当前值（仅在已连接时）
        if (DebugMenuApi.hasValueEntries() && ClientPlayNetworking.canSend(DebugValueRequestC2SPacket.CHANNEL)) {
            DebugValueRequestC2SPacket.sendAll();
        }

        rebuildWidgets();
    }

    @Override
    public void removed() {
        // 屏幕关闭时清除监听，避免悬挂引用
        DebugValueSyncS2CPacket.setListener(null);
        super.removed();
    }

    /**
     * 合并布尔与数值条目的 modId，保持插入顺序（布尔在前）。
     */
    private Set<String> allModIds(Map<String, List<DebugToggleEntry>> toggles,
                                  Map<String, List<DebugValueEntry>> values,
                                  Map<String, List<DebugOptionEntry>> options) {
        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(toggles.keySet());
        ids.addAll(values.keySet());
        ids.addAll(options.keySet());
        return ids;
    }

    /**
     * 取某个 modId 下当前<b>可见</b>的布尔开关（过滤掉可见性谓词为 false 的二级开关）。
     *
     * <p>{@link #rebuildWidgets()} 和 {@link #render(DrawContext, int, int, float)} 都要用同一份
     * 过滤结果，否则分组标题位置与滚动高度会与实际渲染错位。
     */
    private List<DebugToggleEntry> visibleToggles(Map<String, List<DebugToggleEntry>> grouped, String modId) {
        List<DebugToggleEntry> all = grouped.getOrDefault(modId, List.of());
        List<DebugToggleEntry> result = new ArrayList<>(all.size());
        for (DebugToggleEntry entry : all) {
            if (entry.isVisible()) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * 该 modId 分组当前是否有任何可见内容（可见布尔开关 / 数值条目 / 多状态开关）。
     *
     * <p>若某分组下的开关全是被隐藏的二级开关且无其他条目，则整个分组（含标题）都不显示，
     * 避免出现空标题。
     */
    private boolean groupHasVisibleContent(Map<String, List<DebugToggleEntry>> grouped,
                                           Map<String, List<DebugValueEntry>> valueGrouped,
                                           Map<String, List<DebugOptionEntry>> optionGrouped,
                                           String modId) {
        return !visibleToggles(grouped, modId).isEmpty()
                || !valueGrouped.getOrDefault(modId, List.of()).isEmpty()
                || !optionGrouped.getOrDefault(modId, List.of()).isEmpty();
    }

    private void rebuildWidgets() {
        this.clearChildren();

        if (!DebugMenuApi.hasEntries()) {
            // 无可控制的开关 — 不添加任何按钮，render 中绘制提示文字
            // 添加 HUD 设置按钮（调试 Mod 自带功能，始终可用）
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("HUD 设置"),
                    button -> this.client.setScreen(new HudSettingsScreen(this))
            ).dimensions(this.width / 2 - 100, this.height / 2 + 20, 200, BUTTON_HEIGHT).build());

            // 添加关闭按钮
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("关闭"),
                    button -> this.close()
            ).dimensions(this.width / 2 - 50, this.height - 30, 100, BUTTON_HEIGHT).build());
            return;
        }

        visibleSliders.clear();

        Map<String, List<DebugToggleEntry>> grouped = DebugMenuApi.getEntriesByMod();
        // 只取客户端（含 BOTH）侧条目并按 key 去重，避免单人环境下同 key 两侧重复渲染。
        Map<String, List<DebugValueEntry>> valueGrouped =
                DebugMenuApi.getValueEntriesByMod(DebugValueEntry.Side.CLIENT);
        Map<String, List<DebugOptionEntry>> optionGrouped = DebugMenuApi.getOptionEntriesByMod();
        int centerX = this.width / 2 - BUTTON_WIDTH / 2;
        int y = TOP_MARGIN - scrollOffset;

        for (String modId : allModIds(grouped, valueGrouped, optionGrouped)) {
            // 分组内全部条目都被隐藏时，连标题一起跳过
            if (!groupHasVisibleContent(grouped, valueGrouped, optionGrouped, modId)) {
                continue;
            }

            // Mod 分组标题占用空间
            y += GROUP_HEADER_HEIGHT;

            // 每个开关一个按钮（仅渲染当前可见的；二级开关左缩进以区分层级）
            List<DebugToggleEntry> toggles = visibleToggles(grouped, modId);
            for (DebugToggleEntry entry : toggles) {
                if (y + BUTTON_HEIGHT > TOP_MARGIN - 5 && y < this.height - BOTTOM_MARGIN) {
                    // 按钮在可视区域内
                    final DebugToggleEntry finalEntry = entry;
                    int indent = entry.isSecondary() ? SECONDARY_INDENT : 0;
                    ButtonWidget btn = ButtonWidget.builder(
                            getToggleText(entry),
                            button -> {
                                finalEntry.toggle();
                                button.setMessage(getToggleText(finalEntry));
                                // 二级开关值变化可能影响更深层条目的可见性，重建以即时反映
                                rebuildWidgets();
                            }
                    ).dimensions(centerX + indent, y, BUTTON_WIDTH - indent, BUTTON_HEIGHT).build();
                    this.addDrawableChild(btn);
                }
                y += GAP;
            }

            // 每个数值条目一个滑条
            List<DebugValueEntry> values = valueGrouped.getOrDefault(modId, List.of());
            for (DebugValueEntry entry : values) {
                if (y + BUTTON_HEIGHT > TOP_MARGIN - 5 && y < this.height - BOTTOM_MARGIN) {
                    DebugValueSliderWidget slider =
                            new DebugValueSliderWidget(centerX, y, BUTTON_WIDTH, BUTTON_HEIGHT, entry);
                    this.addDrawableChild(slider);
                    visibleSliders.put(entry.getKey(), slider);
                }
                y += GAP;
            }

            // 每个多状态开关一个循环按钮
            List<DebugOptionEntry> options = optionGrouped.getOrDefault(modId, List.of());
            for (DebugOptionEntry entry : options) {
                if (y + BUTTON_HEIGHT > TOP_MARGIN - 5 && y < this.height - BOTTOM_MARGIN) {
                    final DebugOptionEntry finalEntry = entry;
                    ButtonWidget btn = ButtonWidget.builder(
                            getOptionText(entry),
                            button -> {
                                finalEntry.cycle();
                                button.setMessage(getOptionText(finalEntry));
                            }
                    ).dimensions(centerX, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
                    this.addDrawableChild(btn);
                }
                y += GAP;
            }

            // 分组间距
            y += 6;
        }

        totalContentHeight = y + scrollOffset - TOP_MARGIN;

        // HUD 设置按钮（调试 Mod 自带功能）
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("HUD 设置"),
                button -> this.client.setScreen(new HudSettingsScreen(this))
        ).dimensions(this.width / 2 - 130, this.height - 30, 120, BUTTON_HEIGHT).build());

        // 关闭按钮
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("关闭"),
                button -> this.close()
        ).dimensions(this.width / 2 + 10, this.height - 30, 100, BUTTON_HEIGHT).build());
    }

    private Text getToggleText(DebugToggleEntry entry) {
        String status = entry.isEnabled() ? "§a开启" : "§c关闭";
        return Text.literal(entry.getDisplayName() + ": " + status);
    }

    private Text getOptionText(DebugOptionEntry entry) {
        // 显示 "名称: §e当前状态 (下标+1/总数)"，黄色高亮当前状态名
        int idx = entry.getSelectedIndex() + 1;
        int total = entry.size();
        return Text.literal(entry.getDisplayName() + ": §e" + entry.getSelected()
                + " §7(" + idx + "/" + total + ")");
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        ScreenCompat.renderMenuBackground(context, this.width, this.height);

        // 标题
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 15, 0xFFFFFF);

        if (!DebugMenuApi.hasEntries()) {
            // 画面中央显示"无可控制的开关"
            String noEntryText = "无可控制的开关";
            int textWidth = this.textRenderer.getWidth(noEntryText);
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(noEntryText),
                    this.width / 2, this.height / 2, 0xAAAAAA);
        } else {
            // 绘制分组标题
            Map<String, List<DebugToggleEntry>> grouped = DebugMenuApi.getEntriesByMod();
            Map<String, List<DebugValueEntry>> valueGrouped =
                    DebugMenuApi.getValueEntriesByMod(DebugValueEntry.Side.CLIENT);
            Map<String, List<DebugOptionEntry>> optionGrouped = DebugMenuApi.getOptionEntriesByMod();
            int y = TOP_MARGIN - scrollOffset;

            for (String modId : allModIds(grouped, valueGrouped, optionGrouped)) {
                // 与 rebuildWidgets 一致：整组不可见则跳过
                if (!groupHasVisibleContent(grouped, valueGrouped, optionGrouped, modId)) {
                    continue;
                }

                // 绘制 mod 分组标题
                if (y > TOP_MARGIN - 15 && y < this.height - BOTTOM_MARGIN) {
                    String header = "── " + modId + " ──";
                    context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(header),
                            this.width / 2, y + 3, 0xFFFF55);
                }
                y += GROUP_HEADER_HEIGHT;

                // 跳过按钮/滑条区域（布尔 + 数值）；布尔只计可见条目，与 rebuildWidgets 保持一致
                int rows = visibleToggles(grouped, modId).size()
                        + valueGrouped.getOrDefault(modId, List.of()).size()
                        + optionGrouped.getOrDefault(modId, List.of()).size();
                y += rows * GAP;

                // 分组间距
                y += 6;
            }
        }

        super.render(context, mouseX, mouseY, delta);
    }

    /**
     * 滚轮处理（1.20.2+ 签名，含横向滚动量）。
     * 不加 {@code @Override}：该签名在 1.20.1 上不存在，仅作为普通方法保留以便跨版本编译。
     */
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return debugMenu$scroll(verticalAmount);
    }

    /**
     * 滚轮处理（1.20.1 签名）。
     * 不加 {@code @Override}：该签名在 1.20.2+ 上已被移除，仅作为普通方法保留以便跨版本编译。
     */
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        return debugMenu$scroll(amount);
    }

    private boolean debugMenu$scroll(double verticalAmount) {
        int maxScroll = Math.max(0, totalContentHeight - (this.height - TOP_MARGIN - BOTTOM_MARGIN));
        scrollOffset -= (int) (verticalAmount * 10);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        rebuildWidgets();
        return true;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
