package pickup.tool;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import pickup.Main;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 数据包工具类 - 用于发送拾取动画数据包
 * 使用反射处理不同 Minecraft 版本的 API 差异（1.17+ Mojang 映射包名，无版本段）
 */
public final class PacketUtils {

    // 是否为 Folia（决定是否需要区域线程分发）
    private static final boolean IS_FOLIA;

    static {
        boolean isFolia;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            isFolia = true;
        } catch (ClassNotFoundException e) {
            isFolia = false;
        }
        IS_FOLIA = isFolia;
    }

    // 反射缓存（使用线程安全的ConcurrentMap）
    private static final ConcurrentMap<String, Object> REFLECTION_CACHE = new ConcurrentHashMap<>();

    private PacketUtils() {}

    /**
     * 反射调用 Bukkit.isOwnedByCurrentRegion（Folia 专有 API，避免编译期依赖）
     */
    private static boolean isOwnedByCurrentRegion(Entity entity) {
        try {
            Method method = (Method) REFLECTION_CACHE.get("isOwnedByCurrentRegion");
            if (method == null) {
                synchronized (REFLECTION_CACHE) {
                    method = (Method) REFLECTION_CACHE.get("isOwnedByCurrentRegion");
                    if (method == null) {
                        method = Bukkit.class.getMethod("isOwnedByCurrentRegion",
                                org.bukkit.Location.class);
                        REFLECTION_CACHE.put("isOwnedByCurrentRegion", method);
                    }
                }
            }
            return (boolean) method.invoke(null, entity.getLocation());
        } catch (Exception e) {
            // 非 Folia（方法不存在），视为已在正确线程
            return true;
        }
    }

    /**
     * 发送拾取动画数据包（线程安全版本）
     */
    public static void sendPickupAnimation(Main plugin, Player viewer, Entity collectedItem, int amount) {
        if (viewer == null || collectedItem == null || collectedItem.isDead()) {
            return;
        }

        try {
            // Folia：确保在实体所属区域线程中执行；Bukkit 单线程模型直接发送
            if (IS_FOLIA && !isOwnedByCurrentRegion(collectedItem)) {
                plugin.getAdapter().runAtLocation(plugin, collectedItem.getLocation(),
                        () -> sendPickupAnimationInternal(plugin, viewer, collectedItem, amount));
                return;
            }

            sendPickupAnimationInternal(plugin, viewer, collectedItem, amount);

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to send pickup animation to " + viewer.getName() + ": " + e.getMessage());
        }
    }

    /**
     * 内部方法：实际发送拾取动画
     */
    private static void sendPickupAnimationInternal(Main plugin, Player viewer, Entity collectedItem, int amount) {
        try {
            // 获取被拾取实体的nms对象
            Object nmsItem = getHandle(collectedItem);
            if (nmsItem == null) return;

            // 获取实体id
            int collectedId = getEntityId(nmsItem);

            // 获取拾取者（玩家）的NMS对象和ID
            Object nmsViewer = getHandle(viewer);
            if (nmsViewer == null) return;
            int viewerId = getEntityId(nmsViewer);

            // 创建拾取动画数据包
            Object packet = createCollectPacket(collectedId, viewerId, amount);

            // 获取玩家的网络连接对象
            Object connection = getPlayerConnection(nmsViewer);

            // 发送数据包给玩家
            sendPacket(connection, packet);

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to send pickup animation internally: " + e.getMessage());
        }
    }

    /**
     * 通过反射获取craftbukkit实体的nms句柄
     */
    private static Object getHandle(Entity entity) throws Exception {
        String cacheKey = "getHandleMethod";
        Method method = (Method) REFLECTION_CACHE.get(cacheKey);

        if (method == null) {
            synchronized (REFLECTION_CACHE) {
                method = (Method) REFLECTION_CACHE.get(cacheKey);
                if (method == null) {
                    // 1.17+ 无版本段包名
                    Class<?> craftEntityClass = Class.forName("org.bukkit.craftbukkit.entity.CraftEntity");
                    method = craftEntityClass.getMethod("getHandle");
                    method.setAccessible(true);
                    REFLECTION_CACHE.put(cacheKey, method);
                }
            }
        }

        return method.invoke(entity);
    }

    /**
     * 获取nms实体的id
     */
    private static int getEntityId(Object nmsEntity) throws Exception {
        String cacheKey = "getIdMethod";
        Method method = (Method) REFLECTION_CACHE.get(cacheKey);

        if (method == null) {
            synchronized (REFLECTION_CACHE) {
                method = (Method) REFLECTION_CACHE.get(cacheKey);
                if (method == null) {
                    method = nmsEntity.getClass().getMethod("getId");
                    method.setAccessible(true);
                    REFLECTION_CACHE.put(cacheKey, method);
                }
            }
        }

        return (int) method.invoke(nmsEntity);
    }

    /**
     * 创建拾取动画数据包
     */
    private static Object createCollectPacket(int collectedId, int collectorId, int count) throws Exception {
        String cacheKey = "collectPacketConstructor";
        Constructor<?> constructor = (Constructor<?>) REFLECTION_CACHE.get(cacheKey);

        if (constructor == null) {
            synchronized (REFLECTION_CACHE) {
                constructor = (Constructor<?>) REFLECTION_CACHE.get(cacheKey);
                if (constructor == null) {
                    // 1.17+ 使用 Mojang 映射类名
                    Class<?> packetClass = Class.forName(
                            "net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket");
                    constructor = packetClass.getConstructor(int.class, int.class, int.class);
                    constructor.setAccessible(true);
                    REFLECTION_CACHE.put(cacheKey, constructor);
                }
            }
        }

        return constructor.newInstance(collectedId, collectorId, count);
    }

    /**
     * 获取玩家的网络连接对象
     */
    private static Object getPlayerConnection(Object nmsPlayer) throws Exception {
        String cacheKey = "playerConnectionField";
        Field field = (Field) REFLECTION_CACHE.get(cacheKey);

        if (field == null) {
            synchronized (REFLECTION_CACHE) {
                field = (Field) REFLECTION_CACHE.get(cacheKey);
                if (field == null) {
                    Class<?> playerClass = nmsPlayer.getClass();

                    // 查找 ServerGamePacketListenerImpl 类型的连接字段
                    for (Field f : playerClass.getDeclaredFields()) {
                        if (f.getType().getName().contains("ServerGamePacketListenerImpl")) {
                            field = f;
                            break;
                        }
                    }

                    if (field == null) {
                        // 备用方案：通过父类查找
                        for (Field f : playerClass.getFields()) {
                            if (f.getType().getName().contains("PlayerConnection") ||
                                    f.getType().getName().contains("ServerGamePacketListenerImpl")) {
                                field = f;
                                break;
                            }
                        }
                    }

                    if (field != null) {
                        field.setAccessible(true);
                        REFLECTION_CACHE.put(cacheKey, field);
                    }
                }
            }
        }

        if (field == null) {
            throw new RuntimeException("Failed to find PlayerConnection field");
        }

        return field.get(nmsPlayer);
    }

    /**
     * 发送数据包给玩家
     */
    private static void sendPacket(Object connection, Object packet) throws Exception {
        String cacheKey = "sendPacketMethod";
        Method method = (Method) REFLECTION_CACHE.get(cacheKey);

        if (method == null) {
            synchronized (REFLECTION_CACHE) {
                method = (Method) REFLECTION_CACHE.get(cacheKey);
                if (method == null) {
                    method = connection.getClass().getMethod("send",
                            Class.forName("net.minecraft.network.protocol.Packet"));
                    REFLECTION_CACHE.put(cacheKey, method);
                }
            }
        }

        method.invoke(connection, packet);
    }
}
