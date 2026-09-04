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
 * 客户端 -&gt; 服务端 通用数值同步包（方案 B 的核心）。
 *
 * <p>客户端在滑条拖动结束后发送 {@code {key, value}}，服务端收到后：
 * <ol>
 *   <li>回到主线程；</li>
 *   <li>按 key 查找服务端注册的 {@link DebugValueEntry}；</li>
 *   <li>做权限校验（默认等级 2，或由条目自定义）；</li>
 *   <li>通过则 clamp 后调用 setter；不通过或值被 clamp 时，通过
 *       {@link DebugValueSyncS2CPacket} 把服务端真实值回写给客户端，避免 UI 错位。</li>
 * </ol>
 */
public class DebugValueSyncC2SPacket {

    public static final Identifier CHANNEL = new Identifier(DebugMenuMod.MOD_ID, "value_sync");

    /**
     * 客户端发送：请求把某个数值条目设置为 value。
     */
    public static void send(String key, int value) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(key);
        buf.writeInt(value);
        ClientPlayNetworking.send(CHANNEL, buf);
    }

    /**
     * 在服务端注册接收器。
     */
    public static void registerServerReceiver() {
        ServerPlayNetworking.registerGlobalReceiver(CHANNEL, (server, player, handler, buf, responseSender) -> {
            String key = buf.readString();
            int requested = buf.readInt();
            server.execute(() -> handle(player, key, requested));
        });
    }

    private static void handle(ServerPlayerEntity player, String key, int requested) {
        DebugValueEntry entry = DebugMenuApi.getValueEntry(key);
        if (entry == null) {
            // 未在服务端注册该 key —— 忽略（陌生/拼错的 key）
            DebugMenuMod.LOGGER.warn("[DebugMenu] Ignoring value_sync for unknown key '{}' from {}",
                    key, player.getName().getString());
            return;
        }

        int clamped = entry.clamp(requested);

        // 权限校验（针对 clamp 后的目标值）
        if (!entry.canModify(player, clamped)) {
            DebugMenuMod.LOGGER.warn("[DebugMenu] Player {} lacks permission to modify '{}' (requested {})",
                    player.getName().getString(), key, requested);
            // 把服务端真实值推回客户端，纠正 UI
            DebugValueSyncS2CPacket.send(player, key, entry.getValue());
            return;
        }

        entry.setValue(clamped);

        // 若客户端请求值与实际落地值不一致（被 clamp），回写纠正
        if (clamped != requested) {
            DebugValueSyncS2CPacket.send(player, key, entry.getValue());
        }
    }
}
