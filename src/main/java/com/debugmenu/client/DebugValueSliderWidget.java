package com.debugmenu.client;

import com.debugmenu.api.DebugValueEntry;
import com.debugmenu.network.DebugValueSyncC2SPacket;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

/**
 * 绑定到 {@link DebugValueEntry} 的滑条控件。
 *
 * <p>底层 {@link SliderWidget} 的 {@code value} 是 [0,1] 的归一化 double，
 * 这里把它映射到条目的 [min, max] 整数区间，并按 step 对齐。
 *
 * <p>拖动过程中实时更新条目本地值（UI 显示）；拖动/点击结束（松开鼠标）后，
 * 通过 {@link DebugValueSyncC2SPacket} 把最终值发给服务端。
 */
public class DebugValueSliderWidget extends SliderWidget {

    private final DebugValueEntry entry;
    /** 最近一次已同步给服务端的值，避免重复发包。 */
    private int lastSentValue;

    public DebugValueSliderWidget(int x, int y, int width, int height, DebugValueEntry entry) {
        super(x, y, width, height, Text.empty(), normalize(entry, entry.getValue()));
        this.entry = entry;
        this.lastSentValue = entry.getValue();
        updateMessage();
    }

    private static double normalize(DebugValueEntry entry, int value) {
        int range = entry.getMax() - entry.getMin();
        if (range <= 0) return 0.0;
        return (double) (value - entry.getMin()) / (double) range;
    }

    /** 把当前归一化 value 换算成对齐 step 后的整数值。 */
    private int currentIntValue() {
        int range = entry.getMax() - entry.getMin();
        double raw = entry.getMin() + this.value * range;
        int step = entry.getStep();
        // 按 step 对齐（相对 min）
        long steps = Math.round((raw - entry.getMin()) / step);
        int aligned = entry.getMin() + (int) (steps * step);
        return entry.clamp(aligned);
    }

    @Override
    protected void updateMessage() {
        int v = currentIntValue();
        String suffix = entry.getUnitSuffix();
        String valueText = suffix != null ? (v + " " + suffix) : String.valueOf(v);
        this.setMessage(Text.literal(entry.getDisplayName() + ": §e" + valueText));
    }

    @Override
    protected void applyValue() {
        // 拖动中：更新本地条目值，使其他读取处（如 HUD）即时反映
        int v = currentIntValue();
        entry.setValue(v);
    }

    /**
     * 松开鼠标：拖动结束，把最终值同步给服务端（仅在变化时发包）。
     */
    @Override
    public void onRelease(double mouseX, double mouseY) {
        super.onRelease(mouseX, mouseY);
        syncIfChanged();
    }

    private void syncIfChanged() {
        int v = currentIntValue();
        if (v != lastSentValue) {
            lastSentValue = v;
            DebugValueSyncC2SPacket.send(entry.getKey(), v);
        }
    }

    /**
     * 外部（收到服务端回写时）刷新滑条到条目当前值。
     */
    public void refreshFromEntry() {
        this.value = normalize(entry, entry.getValue());
        this.lastSentValue = entry.getValue();
        updateMessage();
    }
}
