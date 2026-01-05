package pickup.feature.pickupmanager;

import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import pickup.Main;
import pickup.config.PickupConfig;
import pickup.feature.ItemSpatialIndex;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 物品驱动拾取调度器（事件驱动版本）
 * 在物品生成时安排检测，物品被拾取后自动停止
 * 超时后转为玩家移动模式拾取
 */
public class ItemDrivenPickupScheduler {
    private final Main plugin;
    private final PickupConfig config;
    private final PickupExecutor pickupExecutor;
    private final ItemSpatialIndex spatialIndex;
    private final RegionScheduler regionScheduler;

    // 按世界分组的活跃物品队列
    private final Map<World, Queue<Item>> worldItemQueues = new ConcurrentHashMap<>();

    private static final NamespacedKey SPAWN_TICK_KEY = ItemLifecycleManager.SPAWN_TICK_KEY;

    public ItemDrivenPickupScheduler(Main plugin, PickupConfig config,
                                     PickupExecutor pickupExecutor) {
        this.plugin = plugin;
        this.config = config;
        this.pickupExecutor = pickupExecutor;
        this.spatialIndex = plugin.getItemSpatialIndex();
        this.regionScheduler = plugin.getServer().getRegionScheduler();
    }

    // ====== 物品注册/注销 ======

    /**
     * 注册物品到调度器队列，并安排第一次检测
     */
    public void registerItem(Item item) {
        if (!item.isValid() || item.isDead()) {
            return;
        }

        Location loc = item.getLocation();
        regionScheduler.execute(plugin, loc, () -> {
            if (!item.isValid() || item.isDead()) {
                return;
            }

            // 检查物品是否已初始化
            PersistentDataContainer pdc = item.getPersistentDataContainer();
            Byte initialized = pdc.get(ItemLifecycleManager.INITIALIZED_KEY, PersistentDataType.BYTE);
            if (initialized == null || initialized == 0) {
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

                // 安排第一次检测（延迟5tick让物品稳定）
                scheduleItemDetection(item, 5L);
            }
        });
    }

    /**
     * 从调度器队列移除物品
     */
    public void unregisterItem(Item item) {
        if (item == null) return;

        Location loc = item.getLocation();
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
                    if (plugin.getConfig().getBoolean("debug", false)) {
                        plugin.getLogger().info("世界 " + world.getName() + " 的物品队列已清空");
                    }
                }
            }
        });
    }

    // ====== 启用/禁用控制 ======

    /**
     * 启用物品驱动调度器（不需要实际启动全局任务）
     */
    public void enable() {
        plugin.getLogger().info("物品驱动调度器已启用（事件驱动模式）");
        // 不需要启动全局任务，由物品注册时自动安排检测
    }

    /**
     * 禁用物品驱动调度器
     */
    public void disable() {
        // 清空所有队列，取消所有待检测物品
        worldItemQueues.clear();
        plugin.getLogger().info("物品驱动调度器已禁用");
    }

    /**
     * 检查调度器是否活跃
     */
    public boolean isActive() {
        // 只要配置启用就认为激活，实际检测由事件驱动
        return config.isItemDrivenEnabled();
    }

    // ====== 私有方法 ======

    /**
     * 为单个物品安排检测任务
     */
    private void scheduleItemDetection(Item item, long delayTicks) {
        if (!item.isValid() || item.isDead()) {
            unregisterItem(item);
            return;
        }

        Location loc = item.getLocation();
        regionScheduler.runDelayed(plugin, loc, task -> {
            try {
                if (!item.isValid() || item.isDead()) {
                    unregisterItem(item);
                    return;
                }

                // 检查物品是否还在活跃期内（使用配置文件时长）
                if (!isItemActive(item)) {
                    if (plugin.getConfig().getBoolean("debug", false)) {
                        plugin.getLogger().info("物品 " + item.getItemStack().getType() +
                                " 超出物品驱动活跃期，转为玩家移动模式拾取");
                    }
                    unregisterItem(item);
                    return;
                }

                // 执行单次检测
                performSingleDetection(item);

                // 如果物品仍然存在且未满堆，安排下次检测
                if (item.isValid() && !item.isDead()) {
                    ItemStack stack = item.getItemStack();
                    if (stack.getAmount() < stack.getMaxStackSize()) {
                        // 再次检查是否还在活跃期内
                        if (isItemActive(item)) {
                            // 安排下次检测（使用配置的扫描间隔）
                            scheduleItemDetection(item, config.getPickupAttemptIntervalTicks());
                        } else {
                            // 超出活跃期，停止检测
                            unregisterItem(item);
                        }
                    } else {
                        // 已满堆，移除
                        unregisterItem(item);
                    }
                } else {
                    unregisterItem(item);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("物品检测失败: " + e.getMessage());
                unregisterItem(item);
            }
        }, delayTicks);
    }

    /**
     * 执行单次检测
     */
    private void performSingleDetection(Item item) {
        if (!item.isValid() || item.isDead()) {
            return;
        }

        // 使用空间索引查找最近的玩家
        Player nearestPlayer = findNearestPlayerUsingIndex(item);
        if (nearestPlayer != null) {
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
    }

    /**
     * 检查物品是否还在活跃期内 - 超时后仅停止物品驱动检测
     */
    private boolean isItemActive(Item item) {
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
            return false;
        }

        long currentTick = item.getWorld().getGameTime();
        long activeTicks = config.getActiveDetectionTicks();

        // 如果 activeTicks 为0，表示永久活跃（一直检测到被拾取）
        if (activeTicks <= 0) {
            return true;
        }

        // 检查是否在活跃期内
        return (currentTick - spawnTick) <= activeTicks;
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

    /**
     * 检查实体是否有资格拾取物品
     */
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

    // ====== 统计方法 ======

    /**
     * 获取总队列物品数
     */
    public int getTotalQueuedItems() {
        int total = 0;
        for (Queue<Item> queue : worldItemQueues.values()) {
            total += queue.size();
        }
        return total;
    }

    /**
     * 获取世界队列数量
     */
    public int getWorldQueueCount() {
        return worldItemQueues.size();
    }
}