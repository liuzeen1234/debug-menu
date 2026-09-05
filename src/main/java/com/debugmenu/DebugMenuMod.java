package com.debugmenu;

import com.debugmenu.api.DebugMenuApi;
import com.debugmenu.api.DebugValueEntry;
import com.debugmenu.network.DebugValueRequestC2SPacket;
import com.debugmenu.network.DebugValueSyncC2SPacket;
import com.debugmenu.network.EntityNbtRequestC2SPacket;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 调试菜单 Mod 服务端入口。
 * 注册网络包接收器（实体 NBT 查询）。
 */
public class DebugMenuMod implements ModInitializer {

    public static final String MOD_ID = "debug-menu";
    public static final Logger LOGGER = LoggerFactory.getLogger("DebugMenu");

    /** 测试滑条的服务端值（方案 B 演示：服务端权威状态）。 */
    private static int testSliderValue = 10;

    @Override
    public void onInitialize() {
        LOGGER.info("[DebugMenu] Debug Menu Mod initialized!");

        // 注册服务端网络包接收器
        EntityNbtRequestC2SPacket.registerServerReceiver();

        // 注册通用数值同步通道（方案 B）：接收客户端设值请求 + 打开菜单时的取值请求
        DebugValueSyncC2SPacket.registerServerReceiver();
        DebugValueRequestC2SPacket.registerServerReceiver();

        // 注册测试数值条目（服务端侧）：与客户端同 key，setter 服务于收包后执行。
        // 标记 Side.SERVER，与客户端那条区分。
        // 注意（样板局限）：这里的 testSliderValue 是纯内存字段，服务端重启即丢失。
        // 真实接入应在 setter 里把值落盘（如 strike_beacon 写 StrikeConfig），别照抄这一点。
        DebugMenuApi.registerValue(new DebugValueEntry(
                "debug-menu", "debug-menu:test_slider", "测试滑条",
                0, 32, 1,
                () -> testSliderValue,
                (v) -> testSliderValue = v,
                null, null, DebugValueEntry.Side.SERVER
        ));
    }
}
