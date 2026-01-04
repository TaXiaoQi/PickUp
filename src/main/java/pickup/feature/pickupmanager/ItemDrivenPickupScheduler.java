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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 物品驱动拾取调度器
 * 负责定期扫描活跃物品并尝试可被拾取生物拾取
 */
public class ItemDrivenPickupScheduler {
    private final Main plugin;
    private final PickupConfig config;
    private final ItemSpatialIndex itemIndex;
    private final PickupExecutor pickupExecutor;

    private final GlobalRegionScheduler globalScheduler;
    private final RegionScheduler regionScheduler;

    private ScheduledTask itemDetectionTask = null;
    private boolean active = false;

    private static final NamespacedKey SPAWN_TICK_KEY = ItemLifecycleManager.SPAWN_TICK_KEY;

    public ItemDrivenPickupScheduler(Main plugin, PickupConfig config,
                                     ItemSpatialIndex itemIndex, PickupExecutor pickupExecutor) {
        this.plugin = plugin;
        this.config = config;
        this.itemIndex = itemIndex;
        this.pickupExecutor = pickupExecutor;

        this.globalScheduler = plugin.getServer().getGlobalRegionScheduler();
        this.regionScheduler = plugin.getServer().getRegionScheduler();
    }

    /**
     * 启动单个物品的驱动任务
     */
    public void startItemDrivenPickupTask(Item item) {
        if (!item.isValid()) return;

        long activeDuration = config.getActiveDetectionTicks();
        long checkInterval = 2L; // 固定检查间隔
        double pickupRange = config.getPickupRange();

        World world = item.getWorld();
        Location loc = item.getLocation();
        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;

        // 在物品所属 Region 启动周期性任务
        plugin.getServer().getRegionScheduler().runAtFixedRate(
                plugin,
                world,
                chunkX,
                chunkZ,
                task -> {
                    if (!item.isValid()) {
                        task.cancel();
                        return;
                    }

                    // 获取附近玩家
                    Collection<Player> nearbyPlayers = world.getNearbyEntitiesByType(
                            Player.class,
                            loc,
                            pickupRange
                    );

                    boolean pickedUp = false;
                    for (Player player : nearbyPlayers) {
                        if (pickupExecutor.performPlayerPickup(player, item)) {
                            pickedUp = true;
                            break;
                        }
                    }

                    // 检查是否超时
                    long currentTick = world.getGameTime();
                    Long spawnTick = item.getPersistentDataContainer().get(SPAWN_TICK_KEY, PersistentDataType.LONG);
                    if (pickedUp || spawnTick == null || (currentTick - spawnTick) > activeDuration) {
                        task.cancel();
                    }
                },
                1L,
                checkInterval
        );
    }

    // ====== 启用/禁用控制 ======

    public void enable() {
        if (active) return;
        active = true;
        startItemDriven();
    }

    public void disable() {
        if (!active) return;
        active = false;

        if (itemDetectionTask != null) {
            itemDetectionTask.cancel();
            itemDetectionTask = null;
        }
    }

    public boolean isActive() {
        return active;
    }

    // ====== 私有方法 ======

    private void startItemDriven() {
        int checkInterval = config.getPickupAttemptIntervalTicks();

        itemDetectionTask = globalScheduler.runAtFixedRate(plugin, task -> {
            if (!active) {
                task.cancel();
                return;
            }

            try {
                // 获取所有世界
                List<World> worlds = new ArrayList<>(Bukkit.getWorlds());
                if (worlds.isEmpty()) return;

                // 轮询机制
                for (World world : worlds) {
                    if (!world.isChunkLoaded(0, 0)) continue;

                    Set<Item> allItems = itemIndex.getAllItemsInWorld(world);
                    if (allItems == null || allItems.isEmpty()) continue;

                    List<Item> itemList = new ArrayList<>(allItems);
                    int maxItemsPerScan = Math.min(20, itemList.size() / 4 + 1);

                    for (int i = 0; i < maxItemsPerScan && i < itemList.size(); i++) {
                        final Item item = itemList.get(i);

                        // 在地块中执行拾取逻辑
                        Location loc = item.getLocation();
                        regionScheduler.execute(plugin, loc, () -> {
                            try {
                                processItemForPickup(item);
                            } catch (Exception e) {
                                plugin.getLogger().warning("Error processing item: " + e.getMessage());
                            }
                        });
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Error in item detection task: " + e.getMessage());
            }
        }, 1, checkInterval);
    }

    private void processItemForPickup(Item item) {
        if (item.isDead() || !item.isValid()) {
            itemIndex.unregisterItem(item);
            return;
        }

        if (!isItemActive(item)) {
            itemIndex.unregisterItem(item);
            return;
        }

        LivingEntity nearestPicker = findNearestPicker(item);
        if (nearestPicker != null) {
            if (nearestPicker instanceof Player player) {
                pickupExecutor.performPlayerPickup(player, item);
            } else {
                pickupExecutor.performLivingEntityPickup(nearestPicker, item);
            }
        }
    }

    private boolean isItemActive(Item item) {
        PersistentDataContainer pdc = item.getPersistentDataContainer();
        Long spawnTick = pdc.get(SPAWN_TICK_KEY, PersistentDataType.LONG);

        if (spawnTick == null) return true;

        long currentTick = item.getWorld().getGameTime();
        return currentTick - spawnTick <= config.getActiveDetectionTicks();
    }

    private LivingEntity findNearestPicker(Item item) {
        Location loc = item.getLocation();
        double range = config.getPickupRange();
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