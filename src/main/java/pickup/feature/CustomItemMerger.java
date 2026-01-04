package pickup.feature;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import pickup.Main;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 自定义物品合并器 - Folia兼容版本
 * 保留事件驱动架构，优化线程安全问题
 */
public class CustomItemMerger {
    private final JavaPlugin plugin;
    private final double mergeRange;
    private final int activeDurationTicks;
    private final int scanIntervalTicks;
    private boolean running = false;

    // 存储物品的合并数据（按世界分组）
    private final Map<String, WorldMergeData> worldData = new ConcurrentHashMap<>();

    // 黑名单：禁止合并的物品类型
    private static final Set<Material> BLACKLISTED = Set.of(
            Material.BEE_NEST, Material.BEEHIVE,
            Material.SPAWNER, Material.DRAGON_EGG,
            Material.SHULKER_BOX, Material.LECTERN,
            Material.SUSPICIOUS_SAND, Material.SUSPICIOUS_GRAVEL,
            Material.CHISELED_BOOKSHELF
    );

    /**
     * 构造函数
     */
    public CustomItemMerger(JavaPlugin plugin, double mergeRange,
                            int activeDurationTicks, int scanIntervalTicks) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.mergeRange = Math.max(0.1, mergeRange);
        this.activeDurationTicks = Math.max(0, activeDurationTicks);
        this.scanIntervalTicks = Math.max(1, scanIntervalTicks); // 最小1tick
    }

    /**
     * 启动合并器
     */
    public void start() {
        if (running) return;
        running = true;

        // Folia下不需要全局定时任务，完全事件驱动
        plugin.getLogger().info("自定义物品合并器已启动（事件驱动模式）");
        plugin.getLogger().info("扫描间隔: " + scanIntervalTicks + " ticks, 活跃期: " + activeDurationTicks + " ticks");
    }

    /**
     * 停止合并器
     */
    public void stop() {
        running = false;
        worldData.clear();
    }

    /**
     * 通知有新的物品可以合并
     */
    public void notifyItemReady(Item item) {
        if (!running || item == null || !item.isValid() || item.isDead()) return;

        ItemStack stack = item.getItemStack();
        if (stack.getType().isAir()) return;
        if (BLACKLISTED.contains(stack.getType())) return;
        if (stack.getAmount() >= stack.getMaxStackSize()) return;

        // 记录物品信息
        ItemData data = new ItemData(
                item.getUniqueId(),
                item.getLocation().clone(),
                item.getWorld().getFullTime()
        );

        // 添加到对应世界的数据中
        String worldName = item.getWorld().getName();
        WorldMergeData worldMergeData = worldData.computeIfAbsent(worldName,
                k -> new WorldMergeData());
        worldMergeData.addItem(data);

        // 安排第一次合并检查（延迟5tick让物品稳定）
        scheduleMergeCheck(item, 5L);
    }

    /**
     * 安排合并检查
     */
    private void scheduleMergeCheck(Item item, long delayTicks) {
        if (!item.isValid() || item.isDead()) return;

        Location loc = item.getLocation();
        plugin.getServer().getRegionScheduler().runDelayed(plugin, loc, task -> {
            if (!running || !item.isValid() || item.isDead()) return;

            try {
                // 检查是否仍在活跃期内
                String worldName = item.getWorld().getName();
                WorldMergeData worldMergeData = worldData.get(worldName);
                if (worldMergeData == null) return;

                ItemData data = worldMergeData.getItemData(item.getUniqueId());
                if (data == null) return;

                long currentTick = item.getWorld().getFullTime();
                if (currentTick - data.spawnTick >= activeDurationTicks) {
                    // 超过活跃期，移除
                    worldMergeData.removeItem(item.getUniqueId());
                    return;
                }

                // 检查是否需要扫描（基于scanIntervalTicks）
                if (currentTick - data.lastScanTick >= scanIntervalTicks) {
                    data.lastScanTick = currentTick;

                    // 执行合并检查
                    tryMergeWithNearby(item);

                    // 如果物品仍然存在且未满堆，安排下次检查
                    if (item.isValid() && !item.isDead()) {
                        ItemStack stack = item.getItemStack();
                        if (stack.getAmount() < stack.getMaxStackSize()) {
                            // 安排下次检查（使用配置的扫描间隔）
                            scheduleMergeCheck(item, scanIntervalTicks);
                        } else {
                            // 已满堆，移除
                            worldMergeData.removeItem(item.getUniqueId());
                        }
                    } else {
                        worldMergeData.removeItem(item.getUniqueId());
                    }
                } else {
                    // 未到扫描时间，继续等待
                    scheduleMergeCheck(item, scanIntervalTicks - (currentTick - data.lastScanTick));
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "合并检查失败", e);
            }
        }, delayTicks);
    }

    /**
     * 尝试与附近的物品合并
     */
    private void tryMergeWithNearby(Item source) {
        if (!source.isValid() || source.isDead()) return;

        Location loc = source.getLocation();
        World world = loc.getWorld();
        if (world == null) return;

        // 获取范围内的所有物品
        List<Item> nearby = new ArrayList<>();
        double rangeSq = mergeRange * mergeRange;

        for (org.bukkit.entity.Entity entity : world.getNearbyEntities(loc, mergeRange, mergeRange, mergeRange)) {
            if (entity instanceof Item item &&
                    item.isValid() && !item.isDead() &&
                    entity.getLocation().distanceSquared(loc) <= rangeSq) {
                nearby.add(item);
            }
        }

        // 寻找可合并的目标
        for (Item target : nearby) {
            if (target == source) continue;

            if (canMerge(source, target)) {
                performMerge(source, target);

                // 从数据中移除被合并的物品
                String worldName = world.getName();
                WorldMergeData worldMergeData = worldData.get(worldName);
                if (worldMergeData != null) {
                    worldMergeData.removeItem(target.getUniqueId());
                }

                break; // 一次只合并一个
            }
        }
    }

    /**
     * 检查两个物品是否可以合并
     */
    private boolean canMerge(Item item1, Item item2) {
        ItemStack s1 = item1.getItemStack();
        ItemStack s2 = item2.getItemStack();

        if (s1.getType() != s2.getType()) return false;
        if (s1.getAmount() + s2.getAmount() > s1.getMaxStackSize()) return false;
        return Objects.equals(s1.getItemMeta(), s2.getItemMeta());
    }

    /**
     * 执行合并操作
     */
    private void performMerge(Item keep, Item remove) {
        if (!keep.isValid() || !remove.isValid()) return;

        // 获取持久化数据容器
        PersistentDataContainer keepPdc = keep.getPersistentDataContainer();
        PersistentDataContainer removePdc = remove.getPersistentDataContainer();

        // 定义持久化数据的键
        NamespacedKey spawnTickKey = new NamespacedKey(plugin, "spawn_tick");
        NamespacedKey sourceKey = new NamespacedKey(plugin, "source");
        NamespacedKey droppedByKey = new NamespacedKey(plugin, "dropped_by");

        // 获取生成时间
        long keepSpawnTick = keepPdc.getOrDefault(spawnTickKey, PersistentDataType.LONG, 0L);
        long removeSpawnTick = removePdc.getOrDefault(spawnTickKey, PersistentDataType.LONG, 0L);

        // 如果被移除的物品更新，则将其数据转移到保留的物品上
        if (removeSpawnTick > keepSpawnTick) {
            keepPdc.set(spawnTickKey, PersistentDataType.LONG, removeSpawnTick);

            String source = removePdc.get(sourceKey, PersistentDataType.STRING);
            if (source != null) {
                keepPdc.set(sourceKey, PersistentDataType.STRING, source);
            } else {
                keepPdc.remove(sourceKey);
            }

            String droppedByStr = removePdc.get(droppedByKey, PersistentDataType.STRING);
            if (droppedByStr != null) {
                keepPdc.set(droppedByKey, PersistentDataType.STRING, droppedByStr);
            } else {
                keepPdc.remove(droppedByKey);
            }
        }

        // 合并物品堆叠数量
        try {
            ItemStack keepStack = keep.getItemStack();
            ItemStack removeStack = remove.getItemStack();
            keepStack.setAmount(keepStack.getAmount() + removeStack.getAmount());
        } catch (Exception ignored) {
            // 忽略合并过程中的异常
        }

        // 删除被合并的物品实体
        remove.remove();

        // 从空间索引中移除被合并的物品
        if (plugin instanceof Main pickupPlugin) {
            ItemSpatialIndex index = pickupPlugin.getItemSpatialIndex();
            if (index != null) {
                index.unregisterItem(remove);
            }
        }
    }

    /**
     * 物品数据类
     */
    private static class ItemData {
        final UUID itemId;
        final Location location;
        final long spawnTick;
        long lastScanTick;

        ItemData(UUID itemId, Location location, long spawnTick) {
            this.itemId = itemId;
            this.location = location;
            this.spawnTick = spawnTick;
            this.lastScanTick = spawnTick - 1000; // 初始化为较早时间，确保立即扫描
        }
    }

    /**
     * 世界合并数据类
     */
    private static class WorldMergeData {
        private final Map<UUID, ItemData> items = new ConcurrentHashMap<>();

        void addItem(ItemData data) {
            items.put(data.itemId, data);
        }

        ItemData getItemData(UUID itemId) {
            return items.get(itemId);
        }

        void removeItem(UUID itemId) {
            items.remove(itemId);
        }
    }
}