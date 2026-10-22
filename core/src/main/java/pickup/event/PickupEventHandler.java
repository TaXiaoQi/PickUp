package pickup.event;

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
import java.util.concurrent.atomic.AtomicInteger;

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
        handleNewItem(item, ItemSpatialIndex.ItemSourceType.NATURAL_DROP, null);
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
        handleNewItem(item, ItemSpatialIndex.ItemSourceType.PLAYER_DROP, player.getUniqueId());

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
            handleNewItem(item, ItemSpatialIndex.ItemSourceType.NATURAL_DROP, null);
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
            String original = event.getDeathMessage();
            if (original == null || original.isEmpty()) {
                // 如果没有死亡信息，创建默认的
                original = "§7" + player.getName() + " 死亡了";
            }

            // 使用 legacy 颜色码拼接坐标（三平台通用，Spigot 无 Adventure API）
            String coordinatePart = "§8[§e" + dimension +
                    "§8 (§6" + x + ", " + y + ", " + z + "§8)]";

            // 设置新的死亡消息
            event.setDeathMessage(original + " " + coordinatePart);
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

        // 仅拦截已由插件接管的物品；
        // 未注册物品（插件启动前已存在、或停用期间生成）保留原版拾取，避免形成无法拾取的黑洞
        if (!itemIndex.isItemRegistered(event.getItem())) {
            return;
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
    private void handleNewItem(Item item, ItemSpatialIndex.ItemSourceType source, UUID droppedBy) {
        // 两种拾取模式都未启用时插件不接管物品，保留原版行为
        // （否则物品会被设置超长拾取延迟却没有任何拾取路径处理，形成"无法拾取"黑洞）
        if (!config.isPlayerDriven() && !config.isItemDrivenEnabled()) {
            return;
        }

        // 1. 立即注册到空间索引
        itemIndex.registerItem(item, source, droppedBy);

        // 2. 通知合并器
        if (plugin.getItemMerger() != null && config.isItemMergeEnabled()) {
            plugin.getItemMerger().notifyItemReady(item);
        }

        // 3. 禁用原版拾取（立即）
        disableVanillaPickup(item);

        // 4. 如果有新物品且配置启用，启动调度器
        if (config.isItemDrivenEnabled() && !itemScheduler.isRunning()) {
            itemScheduler.enable();
        }

        // 5. 延迟注册到调度器
        plugin.getAdapter().runDelayedAtLocation(plugin, item.getLocation(), () -> {
            if (!item.isValid() || item.isDead()) {
                return;
            }

            // 检查是否已正确注册到空间索引
            if (!itemIndex.isItemRegistered(item)) {
                return;
            }

            // 注册到物品驱动调度器（只有调度器已激活时才注册）
            if (config.isItemDrivenEnabled() && itemScheduler.isActive()) {
                itemScheduler.registerItem(item);
            }
        }, 1L);
    }

    /**
     * 玩家驱动的拾取扫描
     */
    public void tryPickup(Player player) {
        if (!active || !config.isPlayerDriven()) return;
        playerHandler.tryPickup(player);
    }

    /**
     * 禁用原版拾取逻辑
     */
    private void disableVanillaPickup(Item item) {
        try {
            item.setPickupDelay(6000);
            // Paper/Folia 走 canPlayerPickup，Spigot 由拾取延迟机制兜底
            // 恢复时必须同步恢复（见 restoreAllItemsPickupState）
            plugin.getAdapter().setCanPlayerPickup(item, false);
        } catch (Exception e) {
            plugin.getLogger().warning("无法禁用原版拾取逻辑: " +
                    item.getItemStack().getType() + " - 插件仍会尝试处理拾取");
        }
    }

    /**
     * 恢复所有已注册物品的原版拾取状态（Folia 线程安全版本）
     * 按区块分发到所属区域线程执行，同时恢复 pickupDelay 与 canPlayerPickup
     */
    public void restoreAllItemsPickupState() {
        plugin.getLogger().info("恢复所有物品的原版拾取状态...");

        AtomicInteger scheduled = new AtomicInteger(0);
        Map<World, Map<ItemSpatialIndex.ChunkCoord, java.util.Set<Item>>> snapshot =
                itemIndex.snapshotByChunk();

        for (Map.Entry<World, Map<ItemSpatialIndex.ChunkCoord, java.util.Set<Item>>> worldEntry : snapshot.entrySet()) {
            World world = worldEntry.getKey();
            for (Map.Entry<ItemSpatialIndex.ChunkCoord, java.util.Set<Item>> chunkEntry : worldEntry.getValue().entrySet()) {
                ItemSpatialIndex.ChunkCoord coord = chunkEntry.getKey();
                java.util.Set<Item> items = chunkEntry.getValue();
                scheduled.addAndGet(items.size());

                Location chunkCenter = new Location(world, (coord.x << 4) + 8, 64, (coord.z << 4) + 8);
                try {
                    plugin.getAdapter().runAtLocation(plugin, chunkCenter, () -> {
                        for (Item item : items) {
                            if (item.isValid() && !item.isDead()) {
                                item.setPickupDelay(0);
                                plugin.getAdapter().setCanPlayerPickup(item, true);
                            }
                        }
                    });
                } catch (Exception ignored) {
                    // 区域调度失败（如世界已卸载），跳过
                }
            }
        }

        plugin.getLogger().info("已为 " + scheduled.get() + " 个物品调度拾取状态恢复");
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
                    restoreVanillaIfNoModeActive();
                }
                break;

            case "mode.item-driven":
                if (Boolean.TRUE.equals(value)) {
                    // 只有在有物品时才启用
                    boolean shouldEnable = false;
                    for (World world : plugin.getServer().getWorlds()) {
                        if (itemIndex.hasItemsInWorld(world)) {
                            shouldEnable = true;
                            break;
                        }
                    }

                    if (shouldEnable) {
                        itemScheduler.enable();
                    } else {
                        plugin.getLogger().info("配置变更：物品驱动已启用，但世界中没有物品，暂不启动调度器");
                    }
                } else {
                    itemScheduler.disable();
                    restoreVanillaIfNoModeActive();
                }
                break;

            case "enabled":
                if (Boolean.TRUE.equals(value)) {
                    enable();
                } else {
                    disable();
                    plugin.restoreVanillaPickup();
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

    /**
     * 若两种拾取模式均已关闭，将已接管物品交还原版拾取
     */
    private void restoreVanillaIfNoModeActive() {
        if (!config.isPlayerDriven() && !config.isItemDrivenEnabled()) {
            plugin.restoreVanillaPickup();
        }
    }

    public void enable() {
        if (active) return;
        active = true;

        if (config.isPlayerDriven()) {
            playerHandler.enable();
        }

        if (config.isItemDrivenEnabled()) {
            // 只有在有物品时才启用物品驱动
            boolean hasItems = false;
            for (World world : plugin.getServer().getWorlds()) {
                if (itemIndex.hasItemsInWorld(world)) {
                    hasItems = true;
                    break;
                }
            }

            if (hasItems) {
                itemScheduler.enable();
            } else {
                plugin.getLogger().info("启用插件时没有物品，跳过物品驱动调度器启动");
            }
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

    /**
     * 玩家离线事件
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        lastCheckTicks.remove(event.getPlayer().getUniqueId());
    }
}