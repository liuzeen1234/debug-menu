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
 * 客户端 -&gt; 服务端：请求所有数值条目的当前值。
 *
 * <p>打开 {@link com.debugmenu.client.DebugMenuScreen} 时发送一次，服务端针对每个
 * 已注册的数值条目用 {@link DebugValueSyncS2CPacket} 回写真实值，使滑条显示服务端
 * 权威值而非客户端本地猜测。
 *
 * <p>包体为空（请求"全部"）。
 */
public class DebugValueRequestC2SPacket {

    public static final Identifier CHANNEL = new Identifier(DebugMenuMod.MOD_ID, "value_request");

    /**
     * 客户端发送：请求全部数值条目的当前值。
     */
    public static void sendAll() {
        PacketByteBuf buf = PacketByteBufs.create();
        ClientPlayNetworking.send(CHANNEL, buf);
    }

    /**
     * 在服务端注册接收器。
     */
    public static void registerServerReceiver() {
        ServerPlayNetworking.registerGlobalReceiver(CHANNEL, (server, player, handler, buf, responseSender) -> {
            server.execute(() -> handle(player));
        });
    }

    private static void handle(ServerPlayerEntity player) {
        // 只回写服务端权威值：遍历 SERVER（含 BOTH）侧条目，避免把客户端那条的本地值当真实值下发。
        for (DebugValueEntry entry : DebugMenuApi.getValueEntries()) {
            if (entry.matchesSide(DebugValueEntry.Side.SERVER)) {
                DebugValueSyncS2CPacket.send(player, entry.getKey(), entry.getValue());
            }
        }
    }
}
