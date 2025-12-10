package pickup;

import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.entity.EntityPickupItemEvent;

/**
 * 拾取事件监听器类
 * 负责监听和处理与物品拾取相关的各种事件
 * 使用@SuppressWarnings("ClassCanBeRecord")抑制警告，因为此类可以设计为record类型（Java 14+）
 * 但由于兼容性考虑，仍使用传统的类定义方式
 */
@SuppressWarnings("ClassCanBeRecord")
public class PickupEvent implements Listener {

    // 插件主类引用，用于访问配置和状态
    private final PickupManager pickupManager; // 拾取管理器，负责实际的处理逻辑
    private final PickUp plugin;              // 插件主类实例
    // 反射缓存
    private static volatile Method cachedGetHandleMethod = null;
    private static volatile Field cachedPickupDelayField = null;

    /**
     * 构造函数
     * @param plugin 插件主类实例，提供配置和状态信息
     * @param pickupManager 拾取管理器，处理具体的拾取逻辑
     */
    public PickupEvent(PickUp plugin, PickupManager pickupManager) {
        this.plugin = plugin;
        this.pickupManager = pickupManager;
    }

    /**
     * 处理物品生成事件
     * 当任何物品实体在世界中生成时触发（包括自然掉落、方块掉落等）
     *
     * @param event 物品生成事件
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        // 检查插件是否启用（防止插件禁用后仍有事件处理）
        if (!plugin.isEnabled() || plugin.isPickupDisabled()) {
            return;
        }

        // 委托给拾取管理器处理具体的生成逻辑
        pickupManager.handleItemSpawn(event);
    }

    /**
     * 处理玩家丢弃物品事件
     * 当玩家主动丢弃物品（按Q键）时触发
     *
     * @param event 玩家丢弃物品事件
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        // 检查插件是否启用
        if (!plugin.isEnabled() || plugin.isPickupDisabled()) {
            return;
        }

        // 委托给拾取管理器处理玩家丢弃逻辑
        pickupManager.handlePlayerDrop(event);
    }

    /**
     * 处理方块掉落物品事件
     * 当玩家挖掘方块掉落物品时触发
     *
     * @param event 方块掉落物品事件
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        // 检查插件是否启用
        if (!plugin.isEnabled() || plugin.isPickupDisabled()) {
            return;
        }

        // 委托给拾取管理器处理方块掉落逻辑
        pickupManager.handleBlockDrop(event);
    }

    /**
     * 处理实体死亡事件
     * 当实体（怪物、动物等）死亡掉落物品时触发
     *
     * @param event 实体死亡事件
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        // 检查插件是否启用
        if (!plugin.isEnabled() || plugin.isPickupDisabled()) {
            return;
        }

        // 委托给拾取管理器处理实体死亡掉落逻辑
        // 注意：此事件中的掉落物需要特殊处理，因为不会立即生成物品实体
        pickupManager.handleEntityDeath(event);
    }

    /**
     * 处理容器（如漏斗）自动拾取物品事件
     * 清理带有拾取标记的 ItemStack，确保其能正常堆叠
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        if (!plugin.isEnabled() || plugin.isPickupDisabled()) {
            return;
        }

        Item item = event.getItem();
        ItemStack stack = item.getItemStack();
        if (stack.getType().isAir()) return;

        // 检查是否是插件物品
        if (pickupManager.hasPickupMark(stack)) {
            // 1. 清理PDC标签
            ItemStack clean = pickupManager.createCleanStack(stack);
            item.setItemStack(clean);

            // 2. 同时清理pickupDelay（设置为0，让容器可以立即拾取）
            try {
                Object nmsItem = getGetHandleMethod().invoke(item);
                Field delayField = getItemPickupDelayField();
                int currentDelay = delayField.getInt(nmsItem);

                // 只有当前延迟>0时才需要清理
                if (currentDelay > 0) {
                    delayField.set(nmsItem, 0);

                    if (plugin.getConfig().getBoolean("debug", false)) {
                        plugin.getLogger().info("容器拾取: 清理PDC标签，设置pickupDelay: " +
                                currentDelay + " -> 0 (" + clean.getType() + ")");
                    }
                }
            } catch (Exception e) {
                if (plugin.getConfig().getBoolean("debug", false)) {
                    plugin.getLogger().info("清理pickupDelay失败: " + e.getMessage());
                }
            }
        }
        // 让容器自己处理正常地拾取逻辑
    }

    /**
     * 获取CraftItem.getHandle()方法（反射）
     */
    private static Method getGetHandleMethod() throws Exception {
        if (cachedGetHandleMethod != null) {
            return cachedGetHandleMethod;
        }

        synchronized (PickupEvent.class) {
            if (cachedGetHandleMethod != null) {
                return cachedGetHandleMethod;
            }

            String version = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
            Class<?> craftItemClass = Class.forName("org.bukkit.craftbukkit." + version + ".entity.CraftItem");
            cachedGetHandleMethod = craftItemClass.getMethod("getHandle");
            cachedGetHandleMethod.setAccessible(true);
            return cachedGetHandleMethod;
        }
    }

    /**
     * 获取ItemEntity类的pickupDelay字段（反射）
     */
    private static Field getItemPickupDelayField() throws Exception {
        if (cachedPickupDelayField != null) {
            return cachedPickupDelayField;
        }

        synchronized (PickupEvent.class) {
            if (cachedPickupDelayField != null) {
                return cachedPickupDelayField;
            }

            Class<?> nmsItemClass;
            try {
                // 尝试1.17+的新映射类名
                nmsItemClass = Class.forName("net.minecraft.world.entity.item.ItemEntity");
            } catch (ClassNotFoundException e1) {
                // 尝试1.16及以下的旧NMS路径
                String version = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
                nmsItemClass = Class.forName("net.minecraft.server." + version + ".EntityItem");
            }

            // 尝试不同的字段名
            String[] candidates = {
                    "pickupDelay",   // 未混淆
                    "bK",            // 1.17 ~ 1.19.4
                    "c",             // 1.20.0 ~ 1.20.4
                    "d",             // 1.20.5+
                    "e"              // 预防未来变化
            };

            for (String fieldName : candidates) {
                try {
                    Field field = nmsItemClass.getDeclaredField(fieldName);
                    if (field.getType() == int.class) {
                        field.setAccessible(true);
                        cachedPickupDelayField = field;
                        return field;
                    }
                } catch (NoSuchFieldException ignored) {
                    // 尝试下一个字段名
                }
            }

            throw new RuntimeException("Could not find pickupDelay field");
        }
    }

    /**
     * 处理玩家移动事件 - 用于玩家驱动模式
     * 当玩家移动时触发，用于检测附近的物品并尝试拾取
     *
     * @param event 玩家移动事件
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!plugin.isEnabled() || plugin.isPickupDisabled() || !plugin.isPlayerDriven()) {
            return;
        }

        Player player = event.getPlayer();
        if (!player.isOnline() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return;
        }

        if (event.getFrom().getBlockX() != event.getTo().getBlockX() ||
                event.getFrom().getBlockY() != event.getTo().getBlockY() ||
                event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            pickupManager.tryPickup(player);
        }
    }

    /**
     * 拦截并取消所有原版物品拾取行为
     * 插件启用时，所有玩家都无法通过原版机制拾取任何物品
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        // 双重检查：必须插件启用且拾取功能未禁用
        if (!plugin.isEnabled() || plugin.isPickupDisabled()) {
            return;
        }

        // 额外的安全检查
        if (!plugin.isStoppedByCommand() && plugin.getConfig().getBoolean("enabled", true)) {
            // 记录调试信息
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().info("EntityPickupItemEvent 被取消 - " +
                        event.getEntity().getName() + " 拾取 " +
                        event.getItem().getItemStack().getType());
            }
            // 🔒 取消原版拾取
            event.setCancelled(true);
        }
    }

    /// 事件优先级说明：
    /// - LOWEST: 最早执行，用于处理基础的物品生成和掉落事件
    /// - MONITOR: 最后执行，用于玩家移动后的拾取检测，避免干扰其他插件
    /// ignoreCancelled = true: 如果事件被其他插件取消，则跳过处理
    /// 这样可以避免在事件已被取消的情况下仍然执行不必要的逻辑
}