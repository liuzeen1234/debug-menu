package com.debugmenu.network;

import com.debugmenu.DebugMenuMod;
import com.debugmenu.api.DebugMenuApi;
import com.debugmenu.api.DebugValueEntry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * 服务端 -&gt; 客户端 数值回写/纠正包。
 *
 * <p>用于两种场景：
 * <ul>
 *   <li>玩家打开菜单请求当前值后，服务端下发真实值供滑条显示；</li>
 *   <li>客户端设置被拒或被 clamp 时，把真实值推回，避免 UI 与服务端不一致。</li>
 * </ul>
 *
 * <p>客户端收到后会更新对应 {@link DebugValueEntry} 的本地值（调用其 setter），
 * 并通知已注册的监听器（供打开中的菜单刷新滑条位置）。
 */
public class DebugValueSyncS2CPacket {

    public static final Identifier CHANNEL = new Identifier(DebugMenuMod.MOD_ID, "value_sync_s2c");

    /** 客户端监听器：收到服务端回写后触发（key, value）。菜单可注册以刷新滑条。 */
    public interface Listener {
        void onValueUpdated(String key, int value);
    }

    private static volatile Listener listener;

    public static void setListener(Listener l) {
        listener = l;
    }

    /**
     * 服务端发送回写给指定玩家。
     */
    public static void send(ServerPlayerEntity player, String key, int value) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(key);
        buf.writeInt(value);
        ServerPlayNetworking.send(player, CHANNEL, buf);
    }

    /**
     * 在客户端注册接收器。
     */
    public static void registerClientReceiver() {
        ClientPlayNetworking.registerGlobalReceiver(CHANNEL, (client, handler, buf, responseSender) -> {
            String key = buf.readString();
            int value = buf.readInt();
            client.execute(() -> {
                // 更新客户端本地条目值（供 UI 显示）。只认 CLIENT（含 BOTH）侧，
                // 避免单人环境下误更新到服务端那条字段而 UI 不刷新。
                DebugValueEntry entry = DebugMenuApi.getValueEntry(key, DebugValueEntry.Side.CLIENT);
                if (entry != null) {
                    entry.setValue(value);
                }
                Listener l = listener;
                if (l != null) {
                    l.onValueUpdated(key, value);
                }
            });
        });
    }
}
