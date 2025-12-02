package pickup;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class PacketUtils {

    private static volatile Method CACHED_GET_HANDLE = null;
    private static volatile Method CACHED_GET_ID = null;
    private static volatile Constructor<?> CACHED_COLLECT_PACKET_CONSTRUCTOR = null;
    private static volatile Field CACHED_CONNECTION_FIELD = null;

    private PacketUtils() {}

    public static void sendPickupAnimation(Player player, Entity item, int amount) {
        try {
            Object nmsPlayer = getHandle(player);
            Object nmsItem = getHandle(item);

            if (nmsPlayer == null || nmsItem == null) return;

            int itemId = getEntityId(nmsItem);
            int playerId = getEntityId(nmsPlayer);

            Object packet = createCollectPacket(itemId, playerId, amount);
            Object connection = getPlayerConnection(nmsPlayer);
            sendPacket(connection, packet);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ✅ 修复点：从 CraftEntity 获取 getHandle 方法
    private static Object getHandle(Entity entity) throws Exception {
        if (CACHED_GET_HANDLE == null) {
            synchronized (PacketUtils.class) {
                if (CACHED_GET_HANDLE == null) {
                    CACHED_GET_HANDLE = Class.forName("org.bukkit.craftbukkit.entity.CraftEntity")
                            .getMethod("getHandle");
                }
            }
        }
        return CACHED_GET_HANDLE.invoke(entity);
    }

    private static int getEntityId(Object nmsEntity) throws Exception {
        if (CACHED_GET_ID == null) {
            synchronized (PacketUtils.class) {
                if (CACHED_GET_ID == null) {
                    CACHED_GET_ID = nmsEntity.getClass().getMethod("getId");
                }
            }
        }
        return (int) CACHED_GET_ID.invoke(nmsEntity);
    }

    private static Object createCollectPacket(int collectedId, int collectorId, int count) throws Exception {
        if (CACHED_COLLECT_PACKET_CONSTRUCTOR == null) {
            synchronized (PacketUtils.class) {
                if (CACHED_COLLECT_PACKET_CONSTRUCTOR == null) {
                    Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket");
                    CACHED_COLLECT_PACKET_CONSTRUCTOR = packetClass.getConstructor(int.class, int.class, int.class);
                }
            }
        }
        return CACHED_COLLECT_PACKET_CONSTRUCTOR.newInstance(collectedId, collectorId, count);
    }

    private static Object getPlayerConnection(Object nmsPlayer) throws Exception {
        if (CACHED_CONNECTION_FIELD == null) {
            synchronized (PacketUtils.class) {
                if (CACHED_CONNECTION_FIELD == null) {
                    Class<?> playerClass = nmsPlayer.getClass();
                    for (Field f : playerClass.getDeclaredFields()) {
                        if ("ServerGamePacketListenerImpl".equals(f.getType().getSimpleName())) {
                            f.setAccessible(true);
                            CACHED_CONNECTION_FIELD = f;
                            break;
                        }
                    }
                    if (CACHED_CONNECTION_FIELD == null) {
                        throw new RuntimeException("无法找到 PlayerConnection 字段");
                    }
                }
            }
        }
        return CACHED_CONNECTION_FIELD.get(nmsPlayer);
    }

    private static void sendPacket(Object connection, Object packet) throws Exception {
        Method sendMethod = connection.getClass().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet"));
        sendMethod.invoke(connection, packet);
    }
}