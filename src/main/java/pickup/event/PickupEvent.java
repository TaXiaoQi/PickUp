package pickup.event;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import pickup.Main;
import pickup.config.PickupConfig;
import pickup.feature.pickupmanager.ItemDrivenPickupScheduler;
import pickup.feature.pickupmanager.PickupManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PickupEvent implements Listener {
    // 记录每个玩家上次检测的时间（tick）
    private final Map<UUID, Long> lastCheckTicks = new ConcurrentHashMap<>();

    // 插件主类引用，用于访问配置和状态
    private PickupManager pickupManager; // 改为非final，以便更新
    private final Main plugin;           // 插件主类实例
    private final PickupConfig config;

    /**
     * 构造函数
     * @param plugin 插件主类实例，提供配置和状态信息
     */
    public PickupEvent(Main plugin) {
        this.plugin = plugin;
        this.config = plugin.getPickupConfig();
        this.pickupManager = plugin.getPickupManager(); // 延迟获取
    }

    /**
     * 更新拾取管理器引用
     */
    public void updatePickupManager(PickupManager newManager) {
        this.pickupManager = newManager;
        plugin.getLogger().info("事件监听器已更新");
    }

    /**
     * 安全获取拾取管理器（防止空指针）
     */
    private PickupManager getPickupManager() {
        if (pickupManager == null) {
            // 尝试重新获取
            pickupManager = plugin.getPickupManager();
        }
        return pickupManager;
    }

    /**
     * 处理物品生成事件
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!plugin.isEnabled() || plugin.isPickupDisabled()) {
            return;
        }

        PickupManager manager = getPickupManager();
        if (manager == null) {
            plugin.getLogger().warning("拾取管理器未初始化，跳过物品生成处理");
            return;
        }

        // 1. 立即注册到空间索引（无论物品是否完全初始化）
        Item item = event.getEntity();
        plugin.getItemSpatialIndex().registerItem(item);

        // 2. 完全在延迟任务中处理生命周期逻辑
        plugin.getServer().getRegionScheduler().runDelayed(plugin, item.getLocation(), task -> {
            if (!item.isValid() || item.isDead()) {
                return;
            }

            // 委托给拾取管理器处理拾取逻辑
            manager.handleItemSpawn(event);
        }, 3L);
    }

    /**
     * 处理玩家丢弃物品事件
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        // 检查插件是否启用
        if (!plugin.isEnabled() || plugin.isPickupDisabled()) {
            return;
        }

        PickupManager manager = getPickupManager();
        if (manager == null) return;

        // 委托给拾取管理器处理玩家丢弃逻辑
        manager.handlePlayerDrop(event);
    }

    /**
     * 处理方块掉落物品事件
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        // 检查插件是否启用
        if (!plugin.isEnabled() || plugin.isPickupDisabled()) {
            return;
        }

        PickupManager manager = getPickupManager();
        if (manager == null) return;

        // 委托给拾取管理器处理方块掉落逻辑
        manager.handleBlockDrop(event);
    }

    /**
     * 处理实体死亡事件
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        // 检查插件是否启用
        if (!plugin.isEnabled() || plugin.isPickupDisabled()) {
            return;
        }

        PickupManager manager = getPickupManager();
        if (manager == null) return;

        // 委托给拾取管理器处理实体死亡掉落逻辑
        manager.handleEntityDeath(event);
    }

    /**
     * 当玩家死亡时触发，播报死亡日志
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        // 死亡日志不应该受插件开关控制，只受配置控制
        if (!config.isDeathLogEnabled()) return;

        Player player = event.getEntity();
        Location loc = player.getLocation();
        World world = loc.getWorld();

        if (world == null) {
            plugin.getLogger().warning("玩家死亡时世界为null");
            return;
        }

        String dimension = getDimensionName(world);
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();

        // Log to console
        plugin.getLogger().info("[死亡日志] 玩家 " + player.getName() +
                " 在 " + dimension + " (" + x + ", " + y + ", " + z + ") 死亡");

        if (config.isDeathLogSendPrivateMessage()) {
            Component original = event.deathMessage();
            if (original == null) {
                // 如果没有死亡信息，创建默认的
                original = Component.text(player.getName() + " 死亡了")
                        .color(NamedTextColor.GRAY);
            }

            Component coordinatePart = Component.text("[")
                    .color(NamedTextColor.DARK_GRAY)
                    .append(Component.text(dimension).color(NamedTextColor.YELLOW))
                    .append(Component.text(" (").color(NamedTextColor.DARK_GRAY))
                    .append(Component.text(x + ", " + y + ", " + z).color(NamedTextColor.GOLD))
                    .append(Component.text(")").color(NamedTextColor.DARK_GRAY))
                    .append(Component.text("]"));

            // 设置新的死亡消息
            event.deathMessage(Component.empty()
                    .append(original)
                    .append(Component.space())
                    .append(coordinatePart));
        }
    }

    /**
     * 将世界名称转换为友好维度名称
     */
    private @NotNull String getDimensionName(World world) {
        if (world == null) return "未知维度 (unknown)";
        return switch (world.getEnvironment()) {
            case NORMAL -> "主世界 (world)";
            case NETHER -> "下界 (nether)";
            case THE_END -> "末地 (the_end)";
            default -> world.getName() + " (custom)";
        };
    }

    /**
     * 处理容器（如漏斗）自动拾取物品事件
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        if (!plugin.isEnabled() || plugin.isPickupDisabled()) {
            return;
        }

        PickupManager manager = getPickupManager();
        if (manager == null) return;

        Item item = event.getItem();
        ItemStack original = item.getItemStack();

        if (original.getType().isAir()) return;

        if (manager.hasPickupMark(original)) {
            ItemStack clean = manager.createCleanStack(original);
            item.setItemStack(clean);
        }

        // 从空间索引和调度器移除
        plugin.getItemSpatialIndex().unregisterItem(item);

        // 从调度器移除
        if (manager.isActive() && config.isItemDrivenEnabled()) {
            ItemDrivenPickupScheduler scheduler = manager.getItemScheduler();
            if (scheduler != null) {
                scheduler.unregisterItem(item);
            }
        }
    }

    /**
     * 处理玩家移动事件 - 用于玩家驱动模式
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!plugin.isEnabled() || plugin.isPickupDisabled() || !config.isPlayerDriven()) {
            return;
        }

        Player player = event.getPlayer();
        if (!player.isOnline() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return;
        }

        PickupManager manager = getPickupManager();
        if (manager == null) return;

        // 如果当前世界没有可拾取的物品，直接跳过后续所有逻辑
        if (!manager.hasPickupableItems(player.getWorld())) {
            return;
        }

        // ====== 移动距离检查 ======
        double minMoveDistance = config.getPlayerMinMoveDistance();
        double minMoveDistanceSq = minMoveDistance * minMoveDistance;

        if (event.getFrom().distanceSquared(event.getTo()) > minMoveDistanceSq) {
            manager.tryPickup(player);
        }

        // ====== 新增：时间间隔控制 ======
        UUID playerId = player.getUniqueId();
        long currentTick = player.getWorld().getFullTime();
        long lastCheck = lastCheckTicks.getOrDefault(playerId, 0L);
        int checkInterval = config.getPlayerMoveCheckIntervalTicks();

        // 检查是否达到时间间隔
        if ((currentTick - lastCheck) < checkInterval) {
            return;
        }

        // 记录本次检测时间
        lastCheckTicks.put(playerId, currentTick);

        // 执行拾取检测
        manager.tryPickup(player);
    }

    /**
     * 拦截并取消所有原版物品拾取行为
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        // 双重检查：必须插件启用且拾取功能未禁用
        if (!plugin.isEnabled() || plugin.isPickupDisabled()) {
            return;
        }

        // 记录调试信息（可选）
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("EntityPickupItemEvent 被取消 - " +
                    event.getEntity().getName() + " 拾取 " +
                    event.getItem().getItemStack().getType());
        }

        // 取消原版拾取
        event.setCancelled(true);
    }

    /**
     * 处理物品自然消失事件
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDespawn(org.bukkit.event.entity.ItemDespawnEvent event) {
        if (!plugin.isEnabled() || plugin.isPickupDisabled()) {
            return;
        }

        Item item = event.getEntity();

        // 1. 从空间索引中移除
        plugin.getItemSpatialIndex().unregisterItem(item);

        // 2. 从调度器队列中移除（如果物品驱动模式启用）
        PickupManager manager = getPickupManager();
        if (manager != null && manager.isActive() && config.isItemDrivenEnabled()) {
            ItemDrivenPickupScheduler scheduler = manager.getItemScheduler();
            if (scheduler != null) {
                scheduler.unregisterItem(item);
            }
        }
    }

    // 玩家离线事件
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        lastCheckTicks.remove(event.getPlayer().getUniqueId());
    }
}