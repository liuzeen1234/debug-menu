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
import java.util.HashMap;
import java.util.HashSet;
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

    /**
     * 已折叠的 mod 分组（存 modId）。折叠后该组只显示标题、隐藏所有按钮/滑条。
     * 仅在菜单打开期间有效，关闭后不保留。
     */
    private final Set<String> collapsedMods = new HashSet<>();

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
     * {@link #forEachGroup} 的回调：接收 modId、标题所在的 y、该组是否折叠。
     */
    @FunctionalInterface
    private interface GroupVisitor {
        void visit(String modId, int headerY, boolean collapsed);
    }

    /**
     * 按与布局完全一致的算法遍历所有<b>可见</b>分组，回调标题的 y 坐标。
     *
     * <p>{@link #rebuildWidgets()}、{@link #render(DrawContext, int, int, float)} 和
     * {@link #mouseClicked(double, double, int)} 三处共用此方法，确保标题位置、按钮位置、
     * 点击命中三者永远对齐，避免各自累加 y 时算法漂移。
     *
     * <p>回调发生在"标题占位之前"，即 {@code headerY} 是标题绘制的顶端。回调返回后本方法
     * 会自动累加标题高度；若该组未折叠，再累加其内容行与组间距。
     *
     * @return 遍历结束后的 y（用于计算内容总高度）
     */
    private int forEachGroup(Map<String, List<DebugToggleEntry>> grouped,
                             Map<String, List<DebugValueEntry>> valueGrouped,
                             Map<String, List<DebugOptionEntry>> optionGrouped,
                             GroupVisitor visitor) {
        int y = TOP_MARGIN - scrollOffset;
        for (String modId : allModIds(grouped, valueGrouped, optionGrouped)) {
            if (!groupHasVisibleContent(grouped, valueGrouped, optionGrouped, modId)) {
                continue;
            }
            boolean collapsed = collapsedMods.contains(modId);
            visitor.visit(modId, y, collapsed);
            y += GROUP_HEADER_HEIGHT;

            if (!collapsed) {
                int rows = visibleToggles(grouped, modId).size()
                        + visibleValues(valueGrouped, modId).size()
                        + visibleOptions(optionGrouped, modId).size();
                y += rows * GAP;
                y += 6; // 分组间距
            }
        }
        return y;
    }

    /**
     * 取某个 modId 下当前<b>可见</b>的布尔开关（过滤掉可见性谓词为 false 的二级开关）。
     * {@link #forEachGroup} 遍历时用它统计行数，与实际创建的按钮保持一致。
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
     * 取某个 modId 下当前<b>可见</b>的数值条目（过滤掉可见性谓词为 false 的二级滑条）。
     * 与 {@link #visibleToggles} 同理，供 {@link #forEachGroup} 统计行数与 {@link #rebuildWidgets}
     * 创建滑条时共用，保证行数与实际滑条一致。
     *
     * <p>注意：传入的列表应已按 side 过滤/去重（见
     * {@link DebugMenuApi#getValueEntriesByMod(DebugValueEntry.Side)}）。
     */
    private List<DebugValueEntry> visibleValues(Map<String, List<DebugValueEntry>> grouped, String modId) {
        List<DebugValueEntry> all = grouped.getOrDefault(modId, List.of());
        List<DebugValueEntry> result = new ArrayList<>(all.size());
        for (DebugValueEntry entry : all) {
            if (entry.isVisible()) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * 取某个 modId 下当前<b>可见</b>的多状态开关（过滤掉可见性谓词为 false 的二级选项）。
     * 与 {@link #visibleToggles} 同理，供 {@link #forEachGroup} 统计行数与 {@link #rebuildWidgets}
     * 创建按钮时共用，保证行数与实际按钮一致。
     */
    private List<DebugOptionEntry> visibleOptions(Map<String, List<DebugOptionEntry>> grouped, String modId) {
        List<DebugOptionEntry> all = grouped.getOrDefault(modId, List.of());
        List<DebugOptionEntry> result = new ArrayList<>(all.size());
        for (DebugOptionEntry entry : all) {
            if (entry.isVisible()) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * 分组内一行内容的统一表示，用于跨三种条目类型做"子紧跟父"的排序。
     *
     * <p>三种条目（布尔开关 / 数值滑条 / 多状态开关）恰有一个非 null，其余为 null。
     * {@code key} 是该条目的唯一标识，{@code parentKey} 是它依赖的父条目 key（无则为 null）。
     */
    private static final class Row {
        final DebugToggleEntry toggle;
        final DebugValueEntry value;
        final DebugOptionEntry option;
        final String key;
        final String parentKey;

        private Row(DebugToggleEntry toggle, DebugValueEntry value, DebugOptionEntry option,
                    String key, String parentKey) {
            this.toggle = toggle;
            this.value = value;
            this.option = option;
            this.key = key;
            this.parentKey = parentKey;
        }

        static Row of(DebugToggleEntry e) {
            return new Row(e, null, null, e.getKey(), e.getParentKey());
        }

        static Row of(DebugValueEntry e) {
            return new Row(null, e, null, e.getKey(), e.getParentKey());
        }

        static Row of(DebugOptionEntry e) {
            return new Row(null, null, e, e.getKey(), e.getParentKey());
        }
    }

    /**
     * 组装某个 modId 分组内所有<b>可见</b>行，并把子条目稳定重排到父条目正下方（仅一层）。
     *
     * <p>初始顺序沿用历史布局：布尔开关 → 数值滑条 → 多状态开关，各段内按注册顺序。
     * 随后做一次稳定归位：凡带 {@code parentKey} 且父条目也在本组可见集合内的行，插入到父行
     * （及其已归位的兄弟）之后；父不在可见集合内、或本就没有父的行保持原相对顺序。
     *
     * <p>父子可以是不同类型（如父是布尔、子是滑条），因此必须在合并后的统一序列上排序。
     * 只处理一层：子的子不做递归上提，仍按其"直接父"归位。
     */
    private List<Row> orderedRows(Map<String, List<DebugToggleEntry>> grouped,
                                  Map<String, List<DebugValueEntry>> valueGrouped,
                                  Map<String, List<DebugOptionEntry>> optionGrouped,
                                  String modId) {
        List<Row> initial = new ArrayList<>();
        for (DebugToggleEntry e : visibleToggles(grouped, modId)) {
            initial.add(Row.of(e));
        }
        for (DebugValueEntry e : visibleValues(valueGrouped, modId)) {
            initial.add(Row.of(e));
        }
        for (DebugOptionEntry e : visibleOptions(optionGrouped, modId)) {
            initial.add(Row.of(e));
        }

        // 本组可见 key 集合：只有父也可见时才归位，避免把子挂到一个不显示的父下面。
        Set<String> presentKeys = new HashSet<>();
        for (Row r : initial) {
            presentKeys.add(r.key);
        }

        // 第一遍：把所有"父在本组可见"的子按初始顺序登记到父名下。子相对彼此保持稳定顺序，
        // 且不论子在初始序列中位于父的前面还是后面，都能正确归位。
        Map<String, List<Row>> childrenByParent = new HashMap<>();
        for (Row r : initial) {
            boolean hasResolvableParent = r.parentKey != null
                    && presentKeys.contains(r.parentKey)
                    && !r.parentKey.equals(r.key);
            if (hasResolvableParent) {
                childrenByParent.computeIfAbsent(r.parentKey, k -> new ArrayList<>()).add(r);
            }
        }

        // 第二遍：按初始顺序输出所有"非子"行（顶层行），每落一个就把它名下的子递归紧跟其后。
        // 子行在第二遍会被跳过（它们由 appendChildren 负责插入）。
        List<Row> result = new ArrayList<>(initial.size());
        for (Row r : initial) {
            boolean isChild = r.parentKey != null
                    && presentKeys.contains(r.parentKey)
                    && !r.parentKey.equals(r.key);
            if (isChild) {
                continue;
            }
            result.add(r);
            appendChildren(result, childrenByParent, r.key);
        }
        // 兜底：理论上不会发生（顶层行必已落位并带出其子）。若仍有挂起的子
        // （例如父子互相引用形成环），按原顺序补到末尾，保证不丢行、不死循环。
        for (List<Row> orphans : childrenByParent.values()) {
            result.addAll(orphans);
        }
        return result;
    }

    /**
     * 把某个父 key 下已登记的子行紧跟着追加到父后面。
     *
     * <p>子行落位后再递归处理"子的子"，使得链式依赖（父→子→孙）也能让每个条目
     * 紧跟其<b>直接父</b>之下。{@code childrenByParent} 用 remove 消费，天然避免环导致的死循环。
     */
    private void appendChildren(List<Row> result, Map<String, List<Row>> childrenByParent, String parentKey) {
        List<Row> children = childrenByParent.remove(parentKey);
        if (children == null) {
            return;
        }
        for (Row child : children) {
            result.add(child);
            appendChildren(result, childrenByParent, child.key);
        }
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
                || !visibleValues(valueGrouped, modId).isEmpty()
                || !visibleOptions(optionGrouped, modId).isEmpty();
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

        int endY = forEachGroup(grouped, valueGrouped, optionGrouped, (modId, headerY, collapsed) -> {
            // 折叠的分组只保留标题，不创建任何按钮/滑条
            if (collapsed) {
                return;
            }

            // 标题下方第一行按钮的起点
            int y = headerY + GROUP_HEADER_HEIGHT;

            // 统一有序序列：子条目已被稳定重排到父条目下方（不论注册顺序、不论条目类型）。
            // 三种条目共用同一 y 累加，二级条目左缩进以区分层级。
            for (Row row : orderedRows(grouped, valueGrouped, optionGrouped, modId)) {
                boolean visibleArea = y + BUTTON_HEIGHT > TOP_MARGIN - 5 && y < this.height - BOTTOM_MARGIN;
                if (visibleArea) {
                    if (row.toggle != null) {
                        final DebugToggleEntry finalEntry = row.toggle;
                        int indent = finalEntry.isSecondary() ? SECONDARY_INDENT : 0;
                        ButtonWidget btn = ButtonWidget.builder(
                                getToggleText(finalEntry),
                                button -> {
                                    finalEntry.toggle();
                                    button.setMessage(getToggleText(finalEntry));
                                    // 二级开关值变化可能影响更深层条目的可见性，重建以即时反映
                                    rebuildWidgets();
                                }
                        ).dimensions(centerX + indent, y, BUTTON_WIDTH - indent, BUTTON_HEIGHT).build();
                        this.addDrawableChild(btn);
                    } else if (row.value != null) {
                        DebugValueEntry entry = row.value;
                        int indent = entry.isSecondary() ? SECONDARY_INDENT : 0;
                        DebugValueSliderWidget slider = new DebugValueSliderWidget(
                                centerX + indent, y, BUTTON_WIDTH - indent, BUTTON_HEIGHT, entry);
                        this.addDrawableChild(slider);
                        visibleSliders.put(entry.getKey(), slider);
                    } else if (row.option != null) {
                        final DebugOptionEntry finalEntry = row.option;
                        int indent = finalEntry.isSecondary() ? SECONDARY_INDENT : 0;
                        ButtonWidget btn = ButtonWidget.builder(
                                getOptionText(finalEntry),
                                button -> {
                                    finalEntry.cycle();
                                    button.setMessage(getOptionText(finalEntry));
                                    // 与二级 toggle 一致：切换后重建，联动更深层条目的可见性
                                    rebuildWidgets();
                                }
                        ).dimensions(centerX + indent, y, BUTTON_WIDTH - indent, BUTTON_HEIGHT).build();
                        this.addDrawableChild(btn);
                    }
                }
                y += GAP;
            }
        });

        totalContentHeight = endY + scrollOffset - TOP_MARGIN;

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

            forEachGroup(grouped, valueGrouped, optionGrouped, (modId, headerY, collapsed) -> {
                // 绘制 mod 分组标题（折叠 ▶ / 展开 ▼），内容行的跳过由 forEachGroup 统一处理
                if (headerY > TOP_MARGIN - 15 && headerY < this.height - BOTTOM_MARGIN) {
                    String arrow = collapsed ? "\u25B6" : "\u25BC";
                    String header = arrow + " " + DebugMenuApi.getModDisplayName(modId);
                    context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(header),
                            this.width / 2, headerY + 3, 0xFFFF55);
                }
            });
        }

        super.render(context, mouseX, mouseY, delta);
    }

    /**
     * 鼠标点击：检测是否单击了某个 mod 分组标题，是则折叠/展开该组。
     *
     * <p>标题不是 widget，父类不会派发点击，故在此自行命中测试：命中范围为标题<b>文字本身</b>
     * （居中文本的矩形），而非整行。未命中标题时交回父类处理，保证按钮/滑条点击不受影响。
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && DebugMenuApi.hasEntries()) {
            String hit = headerAt(mouseX, mouseY);
            if (hit != null) {
                if (!collapsedMods.add(hit)) {
                    collapsedMods.remove(hit); // 已折叠 → 展开
                }
                // 折叠状态变化后重新夹紧滚动偏移，避免内容变短后停在空白处
                rebuildWidgets();
                clampScroll();
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * 命中测试：返回鼠标位置落在哪个 mod 标题<b>文字矩形</b>上，无则返回 null。
     * 使用与布局一致的 {@link #forEachGroup} 遍历，保证与绘制位置对齐。
     */
    private String headerAt(double mouseX, double mouseY) {
        Map<String, List<DebugToggleEntry>> grouped = DebugMenuApi.getEntriesByMod();
        Map<String, List<DebugValueEntry>> valueGrouped =
                DebugMenuApi.getValueEntriesByMod(DebugValueEntry.Side.CLIENT);
        Map<String, List<DebugOptionEntry>> optionGrouped = DebugMenuApi.getOptionEntriesByMod();

        String[] found = new String[1];
        forEachGroup(grouped, valueGrouped, optionGrouped, (modId, headerY, collapsed) -> {
            if (found[0] != null) {
                return;
            }
            // 文本与 render 保持一致：箭头 + 空格 + 分组显示名（未登记则回退 modId），居中绘制
            String arrow = collapsed ? "\u25B6" : "\u25BC";
            String header = arrow + " " + DebugMenuApi.getModDisplayName(modId);
            int textWidth = this.textRenderer.getWidth(header);
            int left = this.width / 2 - textWidth / 2;
            int right = this.width / 2 + textWidth / 2;
            int top = headerY + 3;
            int bottom = top + this.textRenderer.fontHeight;
            if (mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom) {
                found[0] = modId;
            }
        });
        return found[0];
    }

    /** 将 scrollOffset 夹紧到合法区间（内容高度变化后调用）。 */
    private void clampScroll() {
        int maxScroll = Math.max(0, totalContentHeight - (this.height - TOP_MARGIN - BOTTOM_MARGIN));
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
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
