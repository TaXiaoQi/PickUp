package pickup;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class PacketUtils {

    private static final String VERSION = Bukkit.getServer().getClass().getPackage().getName();
    private static final boolean IS_NEW_CRAFTBUKKIT = !VERSION.contains("v1_"); // 1.20.5+
    private static final boolean IS_1_17_PLUS = IS_NEW_CRAFTBUKKIT ||
            VERSION.compareTo("org.bukkit.craftbukkit.v1_17_R1") >= 0;

    // 反射缓存
    private static volatile Method CACHED_GET_HANDLE = null;
    private static volatile Method CACHED_GET_ID = null;
    private static volatile Constructor<?> CACHED_PACKET_CONSTRUCTOR = null;
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

    private static Object getHandle(Entity entity) throws Exception {
        if (CACHED_GET_HANDLE == null) {
            synchronized (PacketUtils.class) {
                if (CACHED_GET_HANDLE == null) {
                    Class<?> craftEntityClass;
                    if (IS_NEW_CRAFTBUKKIT) {
                        craftEntityClass = Class.forName("org.bukkit.craftbukkit.entity.CraftEntity");
                    } else {
                        craftEntityClass = Class.forName(VERSION + ".entity.CraftEntity");
                    }
                    CACHED_GET_HANDLE = craftEntityClass.getMethod("getHandle");
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
        if (CACHED_PACKET_CONSTRUCTOR == null) {
            synchronized (PacketUtils.class) {
                if (CACHED_PACKET_CONSTRUCTOR == null) {
                    Class<?> packetClass;
                    if (IS_1_17_PLUS) {
                        packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket");
                    } else {
                        packetClass = Class.forName(VERSION + ".PacketPlayOutCollect");
                    }
                    CACHED_PACKET_CONSTRUCTOR = packetClass.getConstructor(int.class, int.class, int.class);
                }
            }
        }
        return CACHED_PACKET_CONSTRUCTOR.newInstance(collectedId, collectorId, count);
    }

    private static Object getPlayerConnection(Object nmsPlayer) throws Exception {
        if (CACHED_CONNECTION_FIELD == null) {
            synchronized (PacketUtils.class) {
                if (CACHED_CONNECTION_FIELD == null) {
                    Class<?> playerClass = nmsPlayer.getClass();
                    Field connectionField = null;

                    if (IS_1_17_PLUS) {
                        for (Field f : playerClass.getDeclaredFields()) {
                            if (f.getType().getSimpleName().equals("ServerGamePacketListenerImpl")) {
                                f.setAccessible(true);
                                connectionField = f;
                                break;
                            }
                        }
                    } else {
                        connectionField = playerClass.getDeclaredField("playerConnection");
                        connectionField.setAccessible(true);
                    }

                    if (connectionField == null) {
                        throw new RuntimeException("Failed to find PlayerConnection field in " + playerClass.getName());
                    }
                    CACHED_CONNECTION_FIELD = connectionField;
                }
            }
        }
        return CACHED_CONNECTION_FIELD.get(nmsPlayer);
    }

    private static void sendPacket(Object connection, Object packet) throws Exception {
        Method sendMethod;
        if (IS_1_17_PLUS) {
            sendMethod = connection.getClass().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet"));
        } else {
            sendMethod = connection.getClass().getMethod("sendPacket", Class.forName(VERSION + ".Packet"));
        }
        sendMethod.invoke(connection, packet);
    }
}