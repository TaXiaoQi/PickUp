package pickup.tool;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 数据包工具类 - 用于发送拾取动画数据包
 * 使用反射处理不同Minecraft版本的API差异
 */
public final class PacketUtils {
    // 获取服务器版本字符串（例如："org.bukkit.craftbukkit.v1_20_R4"）
    private static final String VERSION = Bukkit.getServer().getClass().getPackage().getName();

    // 版本检测标志：是否为1.20.5+的新版CraftBukkit（包路径不同）
    private static final boolean IS_NEW_CRAFTBUKKIT = VERSION.contains("org.bukkit.craftbukkit")
            && !VERSION.contains("v1_16")
            && !VERSION.contains("v1_17")
            && !VERSION.contains("v1_18")
            && !VERSION.contains("v1_19")
            && !VERSION.contains("v1_20");

    // 版本检测标志：是否为1.17+版本（数据包类名和字段名有变化）
    private static final boolean IS_1_17_PLUS = IS_NEW_CRAFTBUKKIT ||
            VERSION.compareTo("org.bukkit.craftbukkit.v1_17_R1") >= 0;

    // 缓存反射对象，避免重复查找提高性能（使用volatile保证多线程可见性）
    private static volatile Method CACHED_GET_HANDLE = null;        // gethandle方法
    private static volatile Method CACHED_GET_ID = null;            // getid方法
    private static volatile Constructor<?> CACHED_PACKET_CONSTRUCTOR = null; // 数据包构造器
    private static volatile Field CACHED_CONNECTION_FIELD = null;   // 玩家连接字段

    // 私有构造函数，防止实例化
    private PacketUtils() {}

    /**
     * 发送拾取动画数据包给指定玩家
     * 显示一个实体被另一个实体拾取的动画效果
     *
     * @param plugin 插件实例（用于日志记录）
     * @param viewer 观看者（接收数据包的玩家）
     * @param collectedItem 被拾取的物品实体
     * @param collectorEntityId 拾取者的实体ID（-1表示未知，0表示由服务器拾取）
     * @param amount 拾取数量（用于显示动画效果）
     */
    public static void sendPickupAnimation(Plugin plugin, Player viewer, Entity collectedItem, int collectorEntityId, int amount) {
        // 参数检查：确保实体有效
        if (collectedItem == null || collectedItem.isDead()) {
            return;
        }

        try {
            // 获取被拾取实体的NMS（Net Minecraft Server）对象
            Object nmsItem = getHandle(collectedItem);
            if (nmsItem == null) return;

            // 获取被拾取实体的id
            int collectedId = getEntityId(nmsItem);

            // 创建拾取动画数据包
            Object packet = createCollectPacket(collectedId, collectorEntityId, amount);

            // 获取观看者的nms对象
            Object nmsViewer = getHandle(viewer);
            if (nmsViewer == null) return;

            // 获取玩家的网络连接对象
            Object connection = getPlayerConnection(nmsViewer);

            // 发送数据包给玩家
            sendPacket(connection, packet);

        } catch (Exception e) {
            // 记录错误日志，但不中断程序执行
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Failed to send pickup animation to " + viewer.getName(), e);
        }
    }

    /**
     * 通过反射获取craftbukkit实体的nms句柄
     *
     * @param entity CraftBukkit实体对象
     * @return NMS实体对象
     * @throws Exception 反射异常
     */
    private static Object getHandle(Entity entity) throws Exception {
        // 双重检查锁确保线程安全且只初始化一次
        if (CACHED_GET_HANDLE == null) {
            synchronized (PacketUtils.class) {
                if (CACHED_GET_HANDLE == null) {
                    Class<?> craftEntityClass;

                    // 根据版本选择正确的类路径
                    if (IS_NEW_CRAFTBUKKIT) {
                        // 1.20.5+版本：使用新的包结构
                        craftEntityClass = Class.forName("org.bukkit.craftbukkit.entity.CraftEntity");
                    } else {
                        // 1.20.4及以下版本：使用版本化包路径
                        craftEntityClass = Class.forName(VERSION + ".entity.CraftEntity");
                    }

                    // 获取gethandle方法
                    CACHED_GET_HANDLE = craftEntityClass.getMethod("getHandle");
                }
            }
        }
        // 调用缓存的gethandle方法
        return CACHED_GET_HANDLE.invoke(entity);
    }

    /**
     * 获取nms实体的id
     *
     * @param nmsEntity NMS实体对象
     * @return oxeID
     * @throws Exception 反射异常
     */
    private static int getEntityId(Object nmsEntity) throws Exception {
        // 双重检查锁确保线程安全且只初始化一次
        if (CACHED_GET_ID == null) {
            synchronized (PacketUtils.class) {
                if (CACHED_GET_ID == null) {
                    // OPENNMSExegetics
                    CACHED_GET_ID = nmsEntity.getClass().getMethod("getId");
                }
            }
        }
        // 调用缓存的getid方法
        return (int) CACHED_GET_ID.invoke(nmsEntity);
    }

    /**
     * 创建拾取动画数据包
     *
     * @param collectedId 被拾取实体的id
     * @param collectorId 拾取者实体的id
     * @param count 拾取数量
     * @return 数据包对象
     * @throws Exception 反射异常
     */
    private static Object createCollectPacket(int collectedId, int collectorId, int count) throws Exception {
        // 双重检查锁确保线程安全且只初始化一次
        if (CACHED_PACKET_CONSTRUCTOR == null) {
            synchronized (PacketUtils.class) {
                if (CACHED_PACKET_CONSTRUCTOR == null) {
                    Class<?> packetClass;

                    // 根据版本选择正确的数据包类
                    if (IS_1_17_PLUS) {
                        // 1.17+版本：使用Mojang映射的类名
                        packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket");
                    } else {
                        // 1.16.5及以下版本：使用CraftBukkit的类名
                        packetClass = Class.forName(VERSION + ".PacketPlayOutCollect");
                    }

                    // 获取数据包的构造函数 (int collectedId, int collectorId, int count)
                    CACHED_PACKET_CONSTRUCTOR = packetClass.getConstructor(int.class, int.class, int.class);
                }
            }
        }
        // 使用缓存的构造函数创建数据包实例
        return CACHED_PACKET_CONSTRUCTOR.newInstance(collectedId, collectorId, count);
    }

    /**
     * 获取玩家的网络连接对象（PlayerConnection）
     *
     * @param nmsPlayer NOMSox
     * @return 网络连接对象
     * @throws Exception 反射异常
     */
    private static Object getPlayerConnection(Object nmsPlayer) throws Exception {
        // 双重检查锁确保线程安全且只初始化一次
        if (CACHED_CONNECTION_FIELD == null) {
            synchronized (PacketUtils.class) {
                if (CACHED_CONNECTION_FIELD == null) {
                    Class<?> playerClass = nmsPlayer.getClass();
                    Field connectionField = null;

                    if (IS_1_17_PLUS) {
                        // 1.17+版本：通过类型名称查找字段
                        for (Field f : playerClass.getDeclaredFields()) {
                            // ServerGamePacketListenerImpl是1.17+的玩家连接类
                            if (f.getType().getSimpleName().equals("ServerGamePacketListenerImpl")) {
                                f.setAccessible(true); // 设置可访问（因为是私有字段）
                                connectionField = f;
                                break;
                            }
                        }
                    } else {
                        // 1.16.5及以下版本：直接通过字段名获取
                        connectionField = playerClass.getDeclaredField("playerConnection");
                        connectionField.setAccessible(true); // 设置可访问
                    }

                    // 如果找不到连接字段，抛出异常
                    if (connectionField == null) {
                        throw new RuntimeException("Failed to find PlayerConnection field in " + playerClass.getName());
                    }

                    // 缓存找到的字段
                    CACHED_CONNECTION_FIELD = connectionField;
                }
            }
        }
        // 使用缓存的字段获取连接对象
        return CACHED_CONNECTION_FIELD.get(nmsPlayer);
    }

    /**
     * 发送数据包给玩家
     *
     * @param connection 玩家连接对象
     * @param packet 要发送的数据包
     * @throws Exception 反射异常
     */
    private static void sendPacket(Object connection, Object packet) throws Exception {
        Method sendMethod;

        // 根据版本选择正确的发送方法
        if (IS_1_17_PLUS) {
            // 1.17+版本：方法名为"send"，参数为Packet类
            sendMethod = connection.getClass().getMethod("send",
                    Class.forName("net.minecraft.network.protocol.Packet"));
        } else {
            // 1.16.5及以下版本：方法名为"sendPacket"，参数为Packet类
            sendMethod = connection.getClass().getMethod("sendPacket",
                    Class.forName(VERSION + ".Packet"));
        }

        // 调用发送方法
        sendMethod.invoke(connection, packet);
    }

    /**
     * 发送拾取动画数据包（简化版）
     * 自动获取拾取者的实体ID
     *
     * @param plugin 插件实例
     * @param collector 拾取者玩家
     * @param collectedItem 被拾取的物品
     * @param amount 拾取数量
     */
    public static void sendPickupAnimation(Plugin plugin, Player collector, Entity collectedItem, int amount) {
        // 参数检查
        if (collector == null || collectedItem == null || collectedItem.isDead()) {
            return;
        }

        try {
            // 获取拾取者的nms对象和实体id
            Object nmsCollector = getHandle(collector);
            int collectorId = getEntityId(nmsCollector);

            // 调用完整版方法发送数据包
            sendPickupAnimation(plugin, collector, collectedItem, collectorId, amount);
        } catch (Exception e) {
            // 记录错误日志
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Failed to send pickup animation (auto ID)", e);
        }
    }
}