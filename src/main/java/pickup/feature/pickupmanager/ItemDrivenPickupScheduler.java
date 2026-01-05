package pickup.feature.pickupmanager;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import pickup.Main;
import pickup.config.PickupConfig;
import pickup.feature.ItemSpatialIndex;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 物品驱动拾取调度器（Folia优化版）
 * 负责定期按区域扫描活跃物品并尝试可被拾取生物拾取
 */
public class ItemDrivenPickupScheduler {
    private final Main plugin;
    private final PickupConfig config;
    private final PickupExecutor pickupExecutor;
    private final ItemSpatialIndex spatialIndex;

    private final GlobalRegionScheduler globalScheduler;
    private final RegionScheduler regionScheduler;

    private ScheduledTask globalScanTask = null;
    private boolean active = false;
    private boolean scanning = false;

    // 按世界的活跃物品队列（用于轮询）
    private final Map<World, Queue<Item>> worldItemQueues = new ConcurrentHashMap<>();

    private static final NamespacedKey SPAWN_TICK_KEY = ItemLifecycleManager.SPAWN_TICK_KEY;

    public ItemDrivenPickupScheduler(Main plugin, PickupConfig config,
                                     PickupExecutor pickupExecutor) {
        this.plugin = plugin;
        this.config = config;
        this.pickupExecutor = pickupExecutor;
        this.spatialIndex = plugin.getItemSpatialIndex();
        this.globalScheduler = plugin.getServer().getGlobalRegionScheduler();
        this.regionScheduler = plugin.getServer().getRegionScheduler();
    }

    // ====== 物品注册/注销 ======

    /**
     * 注册物品到调度器队列
     */
    public void registerItem(Item item) {
        if (!item.isValid() || item.isDead()) {
            plugin.getLogger().fine("尝试注册无效物品，跳过");
            return;
        }

        // 在物品所在区域执行注册
        Location loc = item.getLocation();
        regionScheduler.execute(plugin, loc, () -> {
            if (!item.isValid() || item.isDead()) {
                return;
            }

            // 检查物品是否已初始化
            PersistentDataContainer pdc = item.getPersistentDataContainer();
            Byte initialized = pdc.get(ItemLifecycleManager.INITIALIZED_KEY, PersistentDataType.BYTE);
            if (initialized == null || initialized == 0) {
                plugin.getLogger().fine("物品未初始化，跳过注册到调度器");
                return;
            }

            World world = item.getWorld();
            Queue<Item> queue = worldItemQueues.computeIfAbsent(world, w -> new ConcurrentLinkedQueue<>());

            // 检查是否已存在（防重复）
            if (!queue.contains(item)) {
                queue.add(item);

                if (plugin.getConfig().getBoolean("debug", false)) {
                    plugin.getLogger().info("物品注册到调度器: " + item.getItemStack().getType() +
                            " at " + item.getLocation() + " 队列大小: " + queue.size());
                }

                // 如果之前没有在扫描，启动扫描
                if (active && !scanning && hasItemsToProcess()) {
                    startScanning();
                }
            }
        });
    }

    /**
     * 从调度器队列移除物品
     */
    public void unregisterItem(Item item) {
        if (item == null) return;

        Location loc = item.getLocation();

        // 在物品所在区域执行移除操作
        regionScheduler.execute(plugin, loc, () -> {
            World world = item.getWorld();
            Queue<Item> queue = worldItemQueues.get(world);
            if (queue != null) {
                boolean removed = queue.remove(item);
                if (removed && plugin.getConfig().getBoolean("debug", false)) {
                    plugin.getLogger().info("物品从调度器移除: " + item.getItemStack().getType() +
                            " 剩余队列大小: " + queue.size());
                }

                if (queue.isEmpty()) {
                    worldItemQueues.remove(world);
                    plugin.getLogger().info("世界 " + world.getName() + " 的物品队列已清空");
                }

                // 检查是否还有物品需要处理
                if (!hasItemsToProcess() && scanning) {
                    stopScanning();
                }
            }
        });
    }

    // ====== 启用/禁用控制 ======

    public void enable() {
        if (active) return;
        active = true;
        plugin.getLogger().info("物品驱动调度器已启用");

        // 检查是否有物品需要处理
        if (hasItemsToProcess()) {
            startScanning();
        } else {
            plugin.getLogger().info("当前没有需要处理的物品，扫描任务未启动");
        }
    }

    public void disable() {
        if (!active) return;
        active = false;

        // 停止扫描
        stopScanning();

        worldItemQueues.clear();
        plugin.getLogger().info("物品驱动调度器已禁用");
    }

    public boolean isActive() {
        return active;
    }

    // 新增：获取总队列物品数
    public int getTotalQueuedItems() {
        int total = 0;
        for (Queue<Item> queue : worldItemQueues.values()) {
            total += queue.size();
        }
        return total;
    }

    // 新增：获取世界队列数量
    public int getWorldQueueCount() {
        return worldItemQueues.size();
    }

    // ====== 私有方法 ======

    /**
     * 检查是否有物品需要处理
     */
    private boolean hasItemsToProcess() {
        // 方法1：检查队列
        if (!worldItemQueues.isEmpty()) {
            for (Queue<Item> queue : worldItemQueues.values()) {
                if (!queue.isEmpty()) {
                    return true;
                }
            }
        }

        // 方法2：检查空间索引（更准确）
        return spatialIndex != null && spatialIndex.hasAnyItems();
    }


    /**
     * 启动扫描任务
     */
    private void startScanning() {
        if (scanning) return;

        scanning = true;
        int checkInterval = config.getPickupAttemptIntervalTicks();

        plugin.getLogger().info("启动物品驱动扫描任务，间隔: " + checkInterval + " ticks");

        globalScanTask = globalScheduler.runAtFixedRate(plugin, task -> {
            if (!active || !scanning) {
                task.cancel();
                scanning = false;
                return;
            }

            try {
                // 再次检查是否有物品需要处理
                if (!hasItemsToProcess()) {
                    plugin.getLogger().info("没有物品需要处理，暂停扫描");
                    stopScanning();
                    task.cancel();
                    return;
                }

                plugin.getLogger().info("[调度器任务] 开始扫描周期，总物品数: " + getTotalQueuedItems());

                // 遍历所有有物品的世界
                for (Map.Entry<World, Queue<Item>> entry : worldItemQueues.entrySet()) {
                    World world = entry.getKey();
                    Queue<Item> queue = entry.getValue();

                    if (queue.isEmpty()) {
                        continue;
                    }

                    plugin.getLogger().info("[调度器任务] 处理世界: " + world.getName() +
                            " 队列大小: " + queue.size());

                    int itemsPerScan = 20;
                    int itemsToProcess = Math.min(itemsPerScan, queue.size());

                    plugin.getLogger().info("[调度器任务] 计划处理 " + itemsToProcess + " 个物品");

                    for (int i = 0; i < itemsToProcess; i++) {
                        Item item = queue.poll();
                        if (item == null) break;

                        // 重新加入队列末尾（实现轮询）
                        queue.offer(item);

                        // 在物品所在的区域执行处理
                        processItemInRegion(item);
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Error in item scan task: " + e.getMessage());
                e.printStackTrace();
            }
        }, 1, checkInterval);
    }

    /**
     * 停止扫描任务
     */
    private void stopScanning() {
        scanning = false;

        if (globalScanTask != null) {
            globalScanTask.cancel();
            globalScanTask = null;
            plugin.getLogger().info("物品驱动扫描任务已停止");
        }
    }


    /**
     * 在物品所在的区域处理物品
     */
    private void processItemInRegion(Item item) {
        Location loc = item.getLocation();

        // 使用区域调度器在物品所在区域执行
        regionScheduler.execute(plugin, loc, () -> {
            try {
                if (!item.isValid() || item.isDead()) {
                    unregisterItem(item);
                    return;
                }

                // 检查物品是否还在活跃期
                if (!isItemActive(item)) {
                    unregisterItem(item);
                    return;
                }

                // 使用空间索引查找附近的拾取者（更高效）
                Player nearestPlayer = findNearestPlayerUsingIndex(item);
                if (nearestPlayer != null) {
                    // 检查玩家是否可以拾取
                    if (pickupExecutor.canPickupNow(nearestPlayer, item, false)) {
                        pickupExecutor.performPlayerPickup(nearestPlayer, item);
                        return;
                    }
                }

                // 如果没有找到玩家，查找其他生物
                LivingEntity nearestMob = findNearestMob(item);
                if (nearestMob != null) {
                    pickupExecutor.performLivingEntityPickup(nearestMob, item);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error processing item in region: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * 使用空间索引查找最近的玩家（更高效）
     */
    private Player findNearestPlayerUsingIndex(Item item) {
        if (!item.isValid() || item.isDead()) {
            return null;
        }

        Location loc = item.getLocation();
        double range = config.getPickupRange();

        // 使用空间索引查找附近的玩家
        Set<Player> nearbyPlayers = spatialIndex.getNearbyPlayers(loc, range);

        Player nearestPlayer = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (Player player : nearbyPlayers) {
            if (!isEligiblePicker(player)) {
                continue;
            }

            double distSq = player.getLocation().distanceSquared(loc);
            if (distSq > range * range || distSq >= nearestDistSq) {
                continue;
            }

            if (pickupExecutor.canPickupNow(player, item, false)) {
                nearestPlayer = player;
                nearestDistSq = distSq;

                if (plugin.getConfig().getBoolean("debug", false)) {
                    plugin.getLogger().info("找到可拾取玩家: " + player.getName() +
                            " 距离: " + Math.sqrt(distSq));
                }
            }
        }

        return nearestPlayer;
    }

    /**
     * 查找最近的生物
     */
    private LivingEntity findNearestMob(Item item) {
        if (!item.isValid() || item.isDead()) {
            return null;
        }

        Location loc = item.getLocation();
        double range = config.getPickupRange();
        double rangeSq = range * range;

        LivingEntity nearestMob = null;
        double nearestDistSq = Double.MAX_VALUE;

        // 获取范围内的所有实体
        for (Entity entity : loc.getWorld().getNearbyEntities(loc, range, range, range)) {
            if (!(entity instanceof Mob mob)) {
                continue;
            }

            // 检查是否是合格的拾取者
            if (!isEligiblePicker(mob)) {
                continue;
            }

            // 计算距离
            double distSq = mob.getLocation().distanceSquared(loc);
            if (distSq > rangeSq || distSq >= nearestDistSq) {
                continue;
            }

            // 检查是否可以拾取
            if (pickupExecutor.canPickupNow(mob, item, false)) {
                nearestMob = mob;
                nearestDistSq = distSq;

                if (plugin.getConfig().getBoolean("debug", false)) {
                    plugin.getLogger().info("找到可拾取生物: " + mob.getName() +
                            " 距离: " + Math.sqrt(distSq));
                }
            }
        }

        return nearestMob;
    }

    private boolean isItemActive(Item item) {
        // 这个方法现在在区域调度器中调用，所以可以安全访问物品

        if (!item.isValid() || item.isDead()) {
            return false;
        }

        PersistentDataContainer pdc = item.getPersistentDataContainer();

        // 检查是否已初始化
        Byte initialized = pdc.get(ItemLifecycleManager.INITIALIZED_KEY, PersistentDataType.BYTE);
        if (initialized == null || initialized == 0) {
            return false;
        }

        // 检查生成时间
        Long spawnTick = pdc.get(SPAWN_TICK_KEY, PersistentDataType.LONG);
        if (spawnTick == null) {
            return false; // 如果没有生成时间，认为不活跃
        }

        long currentTick = item.getWorld().getGameTime();
        long activeTicks = config.getActiveDetectionTicks();

        // 如果 activeTicks 为0，表示永久活跃
        if (activeTicks <= 0) {
            return true;
        }

        // 检查是否在活跃期内
        boolean isActive = (currentTick - spawnTick) <= activeTicks;

        return isActive;
    }

    private boolean isEligiblePicker(LivingEntity entity) {
        if (entity == null || !entity.isValid()) {
            return false;
        }

        // 1. 检查玩家
        if (entity instanceof Player player) {
            return player.getGameMode() != GameMode.SPECTATOR &&
                    !player.isDead();
        }

        // 2. 检查生物（Mob）
        if (entity instanceof Mob mob) {
            return mob.getCanPickupItems() &&
                    !mob.isDead() &&
                    mob.getHealth() > 0;
        }

        // 默认不允许拾取
        return false;
    }
}