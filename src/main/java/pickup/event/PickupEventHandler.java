package pickup.event;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import pickup.Main;
import pickup.config.PickupConfig;
import pickup.feature.ItemSpatialIndex;
import pickup.feature.*;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 拾取事件处理器（合并PickupEvent和PickupManager）
 */
public class PickupEventHandler implements Listener, PickupConfig.ConfigChangeListener {

    // 记录每个玩家上次检测的时间（tick）
    private final Map<UUID, Long> lastCheckTicks = new ConcurrentHashMap<>();

    private final Main plugin;
    private final PickupConfig config;
    private final ItemSpatialIndex itemIndex;
    private final PickupExecutor pickupExecutor;
    private final PlayerDrivenPickupHandler playerHandler;
    private final ItemDrivenPickupScheduler itemScheduler;

    // 管理器运行状态
    private boolean active = false;

    public PickupEventHandler(Main plugin, PickupConfig config, ItemSpatialIndex spatialIndex) {
        this.plugin = plugin;
        this.config = config;
        this.itemIndex = spatialIndex;

        // 初始化组件
        this.pickupExecutor = new PickupExecutor(plugin, config, itemIndex);
        this.playerHandler = new PlayerDrivenPickupHandler(plugin, config, itemIndex, pickupExecutor);
        this.itemScheduler = new ItemDrivenPickupScheduler(plugin, config, pickupExecutor);

        this.config.addChangeListener(this);
    }

    // ====== 事件处理方法 ======

    /**
     * 处理物品生成事件
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!plugin.isEnabled() || plugin.isPickupDisabled()) {
            return;
        }

        Item item = event.getEntity();

        // 1. 立即注册到空间索引
        itemIndex.registerItem(item, ItemSpatialIndex.ItemSourceType.NATURAL_DROP, null);

        // 2. 完全在延迟任务中处理其他逻辑
        plugin.getServer().getRegionScheduler().runDelayed(plugin, item.getLocation(), task -> {
            if (!item.isValid() || item.isDead()) {
                return;
            }

            // 禁用原版拾取逻辑
            disableVanillaPickup(item);

            // 延迟注册到调度器（确保物品完全初始化）
            scheduleItemRegistration(item);
        }, 3L);
    }

    /**
     * 处理玩家丢弃物品事件
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!plugin.isEnabled() || plugin.isPickupDisabled()) {
            return;
        }

        Player player = event.getPlayer();
        Item item = event.getItemDrop();

        // 注册到空间索引
        itemIndex.registerItem(item, ItemSpatialIndex.ItemSourceType.PLAYER_DROP, player.getUniqueId());
        disableVanillaPickup(item);

        // 注册到物品驱动调度器
        if (config.isItemDrivenEnabled()) {
            itemScheduler.registerItem(item);
        }
    }

    /**
     * 处理方块掉落物品事件
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        if (!plugin.isEnabled() || plugin.isPickupDisabled()) {
            return;
        }

        for (Item item : event.getItems()) {
            // 注册到空间索引
            itemIndex.registerItem(item, ItemSpatialIndex.ItemSourceType.NATURAL_DROP, null);
            disableVanillaPickup(item);

            // 注册到物品驱动调度器
            if (config.isItemDrivenEnabled()) {
                itemScheduler.registerItem(item);
            }
        }
    }

    /**
     * 处理实体死亡事件
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        // 检查插件是否启用
        if (plugin.isEnabled()) {
            plugin.isPickupDisabled();
        }

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

        Item item = event.getItem();
        ItemStack original = item.getItemStack();

        if (original.getType().isAir()) return;

        // 创建干净堆栈（如果需要）
        ItemStack clean = pickupExecutor.createCleanStack(original);
        if (clean != original) {
            item.setItemStack(clean);
        }

        // 从空间索引移除
        itemIndex.unregisterItem(item);

        // 从调度器移除
        if (config.isItemDrivenEnabled() && itemScheduler != null) {
            itemScheduler.unregisterItem(item);
        }
    }

    /**
     * 处理玩家移动事件 - 用于玩家驱动模式
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // 第一层：基础条件检查
        if (!plugin.isEnabled() || plugin.isPickupDisabled() || !config.isPlayerDriven()) {
            return;
        }

        Player player = event.getPlayer();
        if (!player.isOnline() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return;
        }

        // 第二层：移动距离检查（最轻量的检查）
        double minMoveDistance = config.getPlayerMinMoveDistance();
        double minMoveDistanceSq = minMoveDistance * minMoveDistance;
        if (event.getFrom().distanceSquared(event.getTo()) <= minMoveDistanceSq) {
            return;
        }

        // 第三层：时间间隔检查
        UUID playerId = player.getUniqueId();
        long currentTick = player.getWorld().getFullTime();
        long lastCheck = lastCheckTicks.getOrDefault(playerId, 0L);
        int checkInterval = config.getPlayerMoveCheckIntervalTicks();
        if ((currentTick - lastCheck) < checkInterval) {
            return;
        }

        // 第四层：世界物品检查（在时间和距离都满足后才检查）
        if (!itemIndex.hasItemsInWorld(player.getWorld())) {
            lastCheckTicks.remove(playerId); // 清空记录，避免重复计算
            return;
        }

        // 记录本次检测时间
        lastCheckTicks.put(playerId, currentTick);

        // 执行拾取检测
        tryPickup(player);
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

        // 直接从空间索引和调度器移除
        itemIndex.unregisterItem(item);

        if (itemScheduler != null) {
            itemScheduler.unregisterItem(item);
        }
    }

    /**
     * 延迟注册到调度器
     */
    private void scheduleItemRegistration(Item item) {
        if (!config.isItemDrivenEnabled()) {
            return;
        }

        plugin.getServer().getRegionScheduler().runDelayed(plugin, item.getLocation(), task -> {
            if (!item.isValid() || item.isDead()) {
                return;
            }

            // 检查物品是否已注册到空间索引
            if (!itemIndex.isItemRegistered(item)) {
                return;
            }

            // 注册到调度器
            itemScheduler.registerItem(item);
        }, 5L);
    }

    /**
     * 玩家驱动的拾取扫描
     */
    public void tryPickup(Player player) {
        if (!active || !config.isPlayerDriven()) return;
        playerHandler.tryPickup(player);
    }

    /**
     * 检查世界是否有可拾取物品
     */
    public boolean hasPickupableItems(World world) {
        return itemIndex.hasItemsInWorld(world);
    }

    /**
     * 禁用原版拾取逻辑
     */
    private void disableVanillaPickup(Item item) {
        try {
            item.setPickupDelay(6000);
            if (plugin.getServer().getClass().getName().contains("folia")) {
                item.setCanPlayerPickup(false);
            }
        } catch (Exception e) {
            try {
                Object handle = item.getClass().getMethod("getHandle").invoke(item);
                java.lang.reflect.Field field = handle.getClass().getDeclaredField("pickupDelay");
                field.setAccessible(true);
                field.set(handle, 6000);
            } catch (Exception ex) {
                plugin.getLogger().warning("无法完全禁用原版拾取逻辑: " +
                        item.getItemStack().getType() + " - 插件仍会尝试处理拾取");
            }
        }
    }

    // ====== 配置变更监听 ======

    @Override
    public void onConfigChanged(String key, Object value) {
        plugin.getLogger().info("配置变更: " + key + " = " + value);

        switch (key) {
            case "mode.player-driven":
                if (Boolean.TRUE.equals(value)) {
                    playerHandler.enable();
                } else {
                    playerHandler.disable();
                }
                break;

            case "mode.item-driven":
                if (Boolean.TRUE.equals(value)) {
                    itemScheduler.enable();
                } else {
                    itemScheduler.disable();
                }
                break;

            case "enabled":
                if (Boolean.TRUE.equals(value)) {
                    enable();
                } else {
                    disable();
                }
                break;

            case "__RELOAD_ALL__":
                // 重新加载所有配置后重新启用组件
                if (!plugin.isPickupDisabled()) {
                    if (config.isPlayerDriven()) {
                        playerHandler.enable();
                    } else {
                        playerHandler.disable();
                    }

                    if (config.isItemDrivenEnabled()) {
                        itemScheduler.enable();
                    } else {
                        itemScheduler.disable();
                    }
                }
                break;
        }
    }

    // ====== 启用/禁用控制 ======

    public void enable() {
        if (active) return;
        active = true;

        if (config.isPlayerDriven()) {
            playerHandler.enable();
        }
        if (config.isItemDrivenEnabled()) {
            itemScheduler.enable();
        }
    }

    public void disable() {
        if (!active) return;
        active = false;

        playerHandler.disable();
        itemScheduler.disable();
        lastCheckTicks.clear();
    }

    public boolean isActive() {
        return active && (
                (config.isPlayerDriven() && playerHandler.isActive()) ||
                        (config.isItemDrivenEnabled() && itemScheduler.isActive())
        );
    }

    // ====== 获取组件实例 ======

    public ItemDrivenPickupScheduler getItemScheduler() {
        return itemScheduler;
    }

    public PickupExecutor getPickupExecutor() {
        return pickupExecutor;
    }

    /**
     * 玩家离线事件
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        lastCheckTicks.remove(event.getPlayer().getUniqueId());
    }
}