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

    private final GlobalRegionScheduler globalScheduler;
    private final RegionScheduler regionScheduler;

    private ScheduledTask globalScanTask = null;
    private boolean active = false;

    // 按世界的活跃物品队列（用于轮询）
    private final Map<World, Queue<Item>> worldItemQueues = new ConcurrentHashMap<>();

    private static final NamespacedKey SPAWN_TICK_KEY = ItemLifecycleManager.SPAWN_TICK_KEY;

    public ItemDrivenPickupScheduler(Main plugin, PickupConfig config,
                                     PickupExecutor pickupExecutor) {
        this.plugin = plugin;
        this.config = config;
        this.pickupExecutor = pickupExecutor;

        this.globalScheduler = plugin.getServer().getGlobalRegionScheduler();
        this.regionScheduler = plugin.getServer().getRegionScheduler();
    }

    // ====== 物品注册/注销 ======

    /**
     * 注册物品到调度器队列
     */
    public void registerItem(Item item) {
        if (!item.isValid() || item.isDead()) return;

        World world = item.getWorld();
        worldItemQueues.computeIfAbsent(world, w -> new ConcurrentLinkedQueue<>()).add(item);
    }

    /**
     * 从调度器队列移除物品
     */
    public void unregisterItem(Item item) {
        if (item == null) return;

        World world = item.getWorld();
        Queue<Item> queue = worldItemQueues.get(world);
        if (queue != null) {
            queue.remove(item);
            if (queue.isEmpty()) {
                worldItemQueues.remove(world);
            }
        }
    }

    // ====== 启用/禁用控制 ======

    public void enable() {
        if (active) return;
        active = true;
        startGlobalScanTask();
    }

    public void disable() {
        if (!active) return;
        active = false;

        if (globalScanTask != null) {
            globalScanTask.cancel();
            globalScanTask = null;
        }

        worldItemQueues.clear();
    }

    public boolean isActive() {
        return active;
    }

    // ====== 私有方法 ======

    /**
     * 启动全局扫描任务
     */
    private void startGlobalScanTask() {
        // 使用配置文件中的 item-check-interval
        int checkInterval = config.getPickupAttemptIntervalTicks(); // 这是 item-check-interval

        globalScanTask = globalScheduler.runAtFixedRate(plugin, task -> {
            if (!active) {
                task.cancel();
                return;
            }

            try {
                // 遍历所有有物品的世界
                for (Map.Entry<World, Queue<Item>> entry : worldItemQueues.entrySet()) {
                    World world = entry.getKey();
                    Queue<Item> queue = entry.getValue();

                    if (queue.isEmpty() || !world.isChunkLoaded(0, 0)) {
                        continue;
                    }

                    // 每轮扫描处理的物品数量 - 使用固定值，因为配置文件中没有这个选项
                    int itemsPerScan = 20; // 固定值，因为配置中没有 items-per-scan
                    int itemsToProcess = Math.min(itemsPerScan, queue.size());

                    for (int i = 0; i < itemsToProcess; i++) {
                        Item item = queue.poll();
                        if (item == null) break;

                        if (!item.isValid() || item.isDead()) {
                            unregisterItem(item);
                            continue;
                        }

                        // 重新加入队列末尾（实现轮询）
                        queue.offer(item);

                        // 在物品所在的区域执行处理
                        processItemInRegion(item);
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Error in item scan task: " + e.getMessage());
            }
        }, 1, checkInterval);
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

                // 查找最近的拾取者
                LivingEntity nearestPicker = findNearestPicker(item);
                if (nearestPicker instanceof Player player) {
                    pickupExecutor.performPlayerPickup(player, item);// 拾取成功，不需要继续处理
                } else {
                    pickupExecutor.performLivingEntityPickup(nearestPicker, item);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error processing item in region: " + e.getMessage());
            }
        });
    }

    private boolean isItemActive(Item item) {
        PersistentDataContainer pdc = item.getPersistentDataContainer();

        // 首先检查是否已初始化
        Byte initialized = pdc.get(ItemLifecycleManager.INITIALIZED_KEY, PersistentDataType.BYTE);
        if (initialized == null || initialized == 0) {
            return false; // 未初始化的物品不应被处理
        }

        Long spawnTick = pdc.get(SPAWN_TICK_KEY, PersistentDataType.LONG);
        if (spawnTick == null) return true;

        long currentTick = item.getWorld().getGameTime();
        return currentTick - spawnTick <= config.getActiveDetectionTicks();
    }

    private LivingEntity findNearestPicker(Item item) {
        Location loc = item.getLocation();
        double range = config.getPickupRange(); // 这是 pickup.range
        double rangeSq = range * range;

        LivingEntity nearestPicker = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (Entity entity : loc.getWorld().getNearbyEntities(loc, range, range, range)) {
            if (!(entity instanceof LivingEntity livingEntity)) continue;

            if (!isEligiblePicker(livingEntity)) continue;

            double distSq = livingEntity.getLocation().distanceSquared(loc);
            if (distSq > rangeSq || distSq >= nearestDistSq) continue;

            nearestPicker = livingEntity;
            nearestDistSq = distSq;
        }

        return nearestPicker;
    }

    private boolean isEligiblePicker(LivingEntity entity) {
        if (entity instanceof Player player) {
            return player.getGameMode() != GameMode.SPECTATOR;
        }
        if (entity instanceof Mob mob) {
            return mob.getCanPickupItems();
        }
        return false;
    }
}