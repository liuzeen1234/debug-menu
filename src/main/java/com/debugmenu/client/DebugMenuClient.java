package com.debugmenu.client;

import com.debugmenu.api.DebugMenuApi;
import com.debugmenu.api.DebugOptionEntry;
import com.debugmenu.api.DebugToggleEntry;
import com.debugmenu.api.DebugValueEntry;
import com.debugmenu.config.DebugMenuConfig;
import com.debugmenu.log.InGameLogAppender;
import com.debugmenu.network.DebugValueSyncS2CPacket;
import com.debugmenu.network.EntityNbtResponseS2CPacket;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.apache.logging.log4j.Level;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 调试菜单 Mod 客户端入口。
 * 注册按键绑定、HUD 渲染回调、网络包接收器。
 *
 * <p>同时注册调试 Mod 自带的"玩家行为日志"开关到 DebugMenuApi，
 * 使行为日志 Mixin 可以通过 API 查询开关状态。
 */
public class DebugMenuClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("DebugMenu");

    /** 打开调试菜单的按键，默认无绑定 */
    private static KeyBinding openDebugMenuKey;

    /** 行为日志开关状态（调试 Mod 自带功能） */
    private static boolean behaviorLogEnabled = false;

    /** 测试滑条的本地值（客户端 UI 演示用，验证方案 B 数值通道） */
    private static int testSliderValue = 10;

    /** 测试二级滑条的本地值（演示滑条作为二级/条件显示条目，仅当一级开关开启时显示） */
    private static int testChildSliderValue = 5;

    /** 测试多状态开关的本地状态（演示"语言选择"式的多状态开关） */
    private static String testLanguage = "English";

    /** 测试一级开关状态（普通开关，始终可见） */
    private static boolean testParentEnabled = false;

    /** 测试二级开关状态（普通开关，仅当一级开关开启时在菜单显示） */
    private static boolean testChildEnabled = false;

    /** “聊天框日志显示”开关在菜单中的唯一标识。 */
    private static final String LOG_TOGGLE_KEY = "debug-menu:log_display";

    /** “日志最低级别”选项在菜单中的唯一标识。 */
    private static final String LOG_LEVEL_KEY = "debug-menu:log_level";

    // 日志级别在菜单中的显示名（也是循环顺序：由粗到细）。
    private static final String LEVEL_ERROR = "ERROR";
    private static final String LEVEL_WARN = "WARN";
    private static final String LEVEL_INFO = "INFO";
    private static final String LEVEL_DEBUG = "DEBUG";

    /** 供菜单渲染的日志级别选项顺序。 */
    private static final java.util.List<String> LEVEL_OPTIONS =
            java.util.List.of(LEVEL_ERROR, LEVEL_WARN, LEVEL_INFO, LEVEL_DEBUG);

    @Override
    public void onInitializeClient() {
        // 加载配置
        DebugMenuConfig.load();

        // 从配置恢复行为日志状态
        behaviorLogEnabled = DebugMenuConfig.getToggleState("behavior_log", false);

        // 注册调试 Mod 自带的"玩家行为日志"开关
        DebugMenuApi.register(new DebugToggleEntry(
                "debug-menu", "behavior_log", "玩家行为日志",
                () -> behaviorLogEnabled,
                (enabled) -> {
                    behaviorLogEnabled = enabled;
                    DebugMenuConfig.setToggleState("behavior_log", enabled);
                }
        ));

        // 安装“日志转发到聊天框”的 Log4j2 Appender（挂在 Root Logger 上，捕获所有模组的日志）。
        // 默认开启，玩家可通过下面的“聊天框日志显示”开关随时开关。
        InGameLogAppender.install();

        // 注册“聊天框日志显示”开关（对应原 /ailog on|off，现改为菜单开关）。
        DebugMenuApi.register(new DebugToggleEntry(
                "debug-menu", LOG_TOGGLE_KEY, "聊天框日志显示",
                InGameLogAppender::isEnabled,
                InGameLogAppender::setEnabled
        ));

        // 注册“日志最低级别”二级选项（对应原 /ailog level <error|warn|info|debug>）。
        // 仅当“聊天框日志显示”开关开启时才在菜单里显示。
        DebugMenuApi.registerOption(new DebugOptionEntry(
                "debug-menu", LOG_LEVEL_KEY, "日志最低级别",
                LEVEL_OPTIONS,
                () -> levelToOption(InGameLogAppender.getMinLevel()),
                (name) -> InGameLogAppender.setMinLevel(optionToLevel(name)),
                DebugMenuApi.visibleWhenEnabled(LOG_TOGGLE_KEY)
        ));

        // 注册一个测试数值条目（客户端 UI 侧），验证方案 B 的滑条 + 同步。
        // 标记 Side.CLIENT：单人环境下与 DebugMenuMod 注册的服务端条目区分开，避免值分裂/UI 重复。
        testSliderValue = DebugMenuConfig.getValueState("test_slider", 10);
        DebugMenuApi.registerValue(new DebugValueEntry(
                "debug-menu", "debug-menu:test_slider", "测试滑条",
                0, 32, 1,
                () -> testSliderValue,
                (v) -> {
                    testSliderValue = v;
                    DebugMenuConfig.setValueState("test_slider", v);
                },
                null, null, DebugValueEntry.Side.CLIENT
        ));

        // 注册一个测试多状态开关（客户端 UI 侧），演示"语言选择"式的自定义多状态开关。
        // 状态名完全由注册方自定义，点击按钮在各状态之间循环切换。
        testLanguage = DebugMenuConfig.getOptionState("test_language", "English");
        DebugMenuApi.registerOption(new DebugOptionEntry(
                "debug-menu", "debug-menu:test_language", "语言",
                java.util.List.of("English", "简体中文", "日本語"),
                () -> testLanguage,
                (v) -> {
                    testLanguage = v;
                    DebugMenuConfig.setOptionState("test_language", v);
                }
        ));

        // 注册测试一级开关（普通开关，始终可见）
        testParentEnabled = DebugMenuConfig.getToggleState("test_parent", false);
        DebugMenuApi.register(new DebugToggleEntry(
                "debug-menu", "debug-menu:test_parent", "测试一级开关",
                () -> testParentEnabled,
                (enabled) -> {
                    testParentEnabled = enabled;
                    DebugMenuConfig.setToggleState("test_parent", enabled);
                }
        ));

        // 注册测试二级开关（普通开关，仅当一级开关开启时显示）
        testChildEnabled = DebugMenuConfig.getToggleState("test_child", false);
        DebugMenuApi.register(new DebugToggleEntry(
                "debug-menu", "debug-menu:test_child", "测试二级开关",
                () -> testChildEnabled,
                (enabled) -> {
                    testChildEnabled = enabled;
                    DebugMenuConfig.setToggleState("test_child", enabled);
                },
                DebugMenuApi.visibleWhenEnabled("debug-menu:test_parent")
        ));

        // 注册测试二级滑条（客户端 UI 侧）：演示滑条也能作为二级条目，仅当一级开关开启时显示。
        // 与二级开关一致，靠 visibleWhen 谓词条件显示，并在菜单中左缩进以区分层级。
        testChildSliderValue = DebugMenuConfig.getValueState("test_child_slider", 5);
        DebugMenuApi.registerValue(new DebugValueEntry(
                "debug-menu", "debug-menu:test_child_slider", "测试二级滑条",
                0, 20, 1,
                () -> testChildSliderValue,
                (v) -> {
                    testChildSliderValue = v;
                    DebugMenuConfig.setValueState("test_child_slider", v);
                },
                null, null, DebugValueEntry.Side.CLIENT,
                DebugMenuApi.visibleWhenEnabled("debug-menu:test_parent")
        ));

        // 注册实体 NBT 响应包的客户端接收器
        EntityNbtResponseS2CPacket.registerClientReceiver();

        // 注册数值同步回写包的客户端接收器（方案 B）
        DebugValueSyncS2CPacket.registerClientReceiver();

        // 注册按键绑定: 打开调试功能菜单（默认按键 M）
        openDebugMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.debug-menu.open_debug_menu",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                "category.debug-menu.general"
        ));

        // 监听按键事件 & 把捕获到的日志刷新到聊天框
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openDebugMenuKey.wasPressed()) {
                client.setScreen(new DebugMenuScreen());
            }

            // 将捕获的日志消息发送到聊天框（在主线程执行）
            InGameLogAppender.flushToChat();
        });

        // 注册 HUD 渲染回调
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            ItemCountHud.render(drawContext);
            EntityHealthHud.render(drawContext, tickDelta);
        });

        LOGGER.info("[DebugMenu] Client initialized.");
    }

    /**
     * Log4j2 级别 -> 菜单显示名。
     *
     * <p>只区分 ERROR / WARN / INFO / DEBUG 四档。FATAL 归入 ERROR，
     * TRACE 及其余更细/未知级别归入 DEBUG（与该选项的最细档对齐）。
     *
     * @param level Log4j2 级别，可为 null（按最粗的 ERROR 处理）
     */
    private static String levelToOption(Level level) {
        if (level == null || level == Level.ERROR || level == Level.FATAL) {
            return LEVEL_ERROR;
        }
        if (level == Level.WARN) {
            return LEVEL_WARN;
        }
        if (level == Level.INFO) {
            return LEVEL_INFO;
        }
        return LEVEL_DEBUG;
    }

    /**
     * 菜单显示名 -> Log4j2 级别。
     *
     * @param optionName WARN / INFO / DEBUG；其余（含 null / 未知）一律按 ERROR 处理
     */
    private static Level optionToLevel(String optionName) {
        if (LEVEL_WARN.equals(optionName)) {
            return Level.WARN;
        }
        if (LEVEL_INFO.equals(optionName)) {
            return Level.INFO;
        }
        if (LEVEL_DEBUG.equals(optionName)) {
            return Level.DEBUG;
        }
        return Level.ERROR;
    }
}
