package pickup.tool;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 数据包工具类 - 用于发送拾取动画数据包
 * 使用反射处理不同Minecraft版本的API差异
 * Folia线程安全版本
 */
public final class PacketUtils {

    // 获取服务器版本字符串
    private static final String VERSION = Bukkit.getServer().getClass().getPackage().getName();

    // 版本检测标志
    private static final boolean IS_1_17_PLUS;
    private static final boolean IS_FOLIA;

    static {
        boolean is17Plus;
        boolean isFolia;

        try {
            // 检查是否为1.17+
            Class.forName("net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket");
            is17Plus = true;
        } catch (ClassNotFoundException e) {
            is17Plus = false;
        }

        try {
            // 检查是否为Folia
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            isFolia = true;
        } catch (ClassNotFoundException e) {
            isFolia = false;
        }

        IS_1_17_PLUS = is17Plus;
        IS_FOLIA = isFolia;
    }

    // 反射缓存（使用线程安全的ConcurrentMap）
    private static final ConcurrentMap<String, Object> REFLECTION_CACHE = new ConcurrentHashMap<>();

    private PacketUtils() {}

    /**
     * 发送拾取动画数据包（线程安全版本）
     */
    public static void sendPickupAnimation(Plugin plugin, Player viewer, Entity collectedItem, int amount) {
        if (viewer == null || collectedItem == null || collectedItem.isDead()) {
            return;
        }

        try {
            // Folia：确保在地块线程中执行
            if (IS_FOLIA && !Bukkit.isOwnedByCurrentRegion(collectedItem.getLocation())) {
                Bukkit.getRegionScheduler().execute(plugin, collectedItem.getLocation(), () -> sendPickupAnimationInternal(plugin, viewer, collectedItem, amount));
                return;
            }

            // 非Folia或已在正确线程
            sendPickupAnimationInternal(plugin, viewer, collectedItem, amount);

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to send pickup animation to " + viewer.getName() + ": " + e.getMessage());
        }
    }

    /**
     * 内部方法：实际发送拾取动画
     */
    private static void sendPickupAnimationInternal(Plugin plugin, Player viewer, Entity collectedItem, int amount) {
        try {
            // 获取被拾取实体的NMS对象
            Object nmsItem = getHandle(collectedItem);
            if (nmsItem == null) return;

            // 获取实体ID
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
                    // 动态获取CraftEntity类
                    Class<?> craftEntityClass = getCraftEntityClass();
                    method = craftEntityClass.getMethod("getHandle");
                    method.setAccessible(true);
                    REFLECTION_CACHE.put(cacheKey, method);
                }
            }
        }

        return method.invoke(entity);
    }

    /**
     * 获取CraftEntity类
     */
    private static Class<?> getCraftEntityClass() throws ClassNotFoundException {
        String cacheKey = "craftEntityClass";
        Class<?> clazz = (Class<?>) REFLECTION_CACHE.get(cacheKey);

        if (clazz == null) {
            synchronized (REFLECTION_CACHE) {
                clazz = (Class<?>) REFLECTION_CACHE.get(cacheKey);
                if (clazz == null) {
                    if (VERSION.contains("org.bukkit.craftbukkit")) {
                        // 1.17+ 新包结构
                        clazz = Class.forName("org.bukkit.craftbukkit.entity.CraftEntity");
                    } else {
                        // 旧版本
                        String version = VERSION.split("\\.")[3];
                        clazz = Class.forName("org.bukkit.craftbukkit." + version + ".entity.CraftEntity");
                    }
                    REFLECTION_CACHE.put(cacheKey, clazz);
                }
            }
        }

        return clazz;
    }

    /**
     * 获取NMS实体的ID
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
                    Class<?> packetClass;

                    if (IS_1_17_PLUS) {
                        // 1.17+ 使用Mojang映射
                        packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket");
                    } else {
                        // 1.16.5及以下
                        packetClass = Class.forName("net.minecraft.server." +
                                VERSION.split("\\.")[3] + ".PacketPlayOutCollect");
                    }

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

                    // 查找连接字段
                    for (Field f : playerClass.getDeclaredFields()) {
                        if (IS_1_17_PLUS) {
                            // 1.17+ 查找ServerGamePacketListenerImpl类型
                            if (f.getType().getName().contains("ServerGamePacketListenerImpl")) {
                                field = f;
                                break;
                            }
                        } else {
                            // 1.16.5及以下 查找playerConnection字段
                            if (f.getName().equals("playerConnection")) {
                                field = f;
                                break;
                            }
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
                    Class<?> connectionClass = connection.getClass();

                    if (IS_1_17_PLUS) {
                        method = connectionClass.getMethod("send",
                                Class.forName("net.minecraft.network.protocol.Packet"));
                    } else {
                        method = connectionClass.getMethod("sendPacket",
                                Class.forName("net.minecraft.server." +
                                        VERSION.split("\\.")[3] + ".Packet"));
                    }

                    REFLECTION_CACHE.put(cacheKey, method);
                }
            }
        }

        method.invoke(connection, packet);
    }

    /**
     * 简化版本：发送拾取动画
     * 保持向后兼容
     */
    @Deprecated
    @SuppressWarnings("unused")
    public static void sendPickupAnimation(Plugin plugin, Player viewer, Entity collectedItem,
                                           int collectorEntityId, int amount) {
        // 转发到新方法，忽略collectorEntityId参数
        sendPickupAnimation(plugin, viewer, collectedItem, amount);
    }
}