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
import pickup.feature.pickupmanager.ItemLifecycleManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 掉落物合并器
 */
public class ItemMerger {
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
    public ItemMerger(JavaPlugin plugin, double mergeRange,
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

        // 从物品的 PDC 中获取实际的生成时间
        PersistentDataContainer pdc = item.getPersistentDataContainer();
        Long spawnTick = pdc.get(ItemLifecycleManager.SPAWN_TICK_KEY, PersistentDataType.LONG);

        // 如果没有生成时间，使用当前游戏时间
        if (spawnTick == null) {
            spawnTick = item.getWorld().getGameTime();
        }

        // 记录物品信息
        ItemData data = new ItemData(
                item.getUniqueId(),
                item.getLocation().clone(),
                spawnTick  // 使用从 PDC 获取的时间
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

                long currentTick = item.getWorld().getGameTime();  // 修复：使用getGameTime()
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

        if (nearby.isEmpty()) return;

        // 确定哪个物品是最早生成的（包括source自身）
        Item oldestItem = source;
        PersistentDataContainer sourcePdc = source.getPersistentDataContainer();
        NamespacedKey spawnTickKey = ItemLifecycleManager.SPAWN_TICK_KEY;
        long oldestSpawnTick = sourcePdc.getOrDefault(spawnTickKey, PersistentDataType.LONG, 0L);

        // 先找出最早生成的物品
        for (Item target : nearby) {
            if (target == source) continue;

            PersistentDataContainer targetPdc = target.getPersistentDataContainer();
            long targetSpawnTick = targetPdc.getOrDefault(spawnTickKey, PersistentDataType.LONG, 0L);

            if (targetSpawnTick < oldestSpawnTick) {
                oldestItem = target;
                oldestSpawnTick = targetSpawnTick;
            }
        }

        // 将所有可合并的物品合并到最早物品中
        for (Item target : nearby) {
            if (target == oldestItem) continue;

            if (canMerge(oldestItem, target)) {
                // 执行合并（老物品合并到更老的物品）
                if (oldestItem == source) {
                    // source就是最老的，其他合并到source
                    performMerge(source, target);
                    // 从数据中移除被合并的物品
                    String worldName = world.getName();
                    WorldMergeData worldMergeData = worldData.get(worldName);
                    if (worldMergeData != null) {
                        worldMergeData.removeItem(target.getUniqueId());
                    }
                } else if (target == source) {
                    // source不是最老的，source合并到最老的
                    performMerge(oldestItem, source);
                    // 从数据中移除被合并的物品（source）
                    String worldName = world.getName();
                    WorldMergeData worldMergeData = worldData.get(worldName);
                    if (worldMergeData != null) {
                        worldMergeData.removeItem(source.getUniqueId());
                    }
                    // 因为source被移除了，可以提前结束
                    break;
                } else {
                    // 其他物品合并到最老的
                    performMerge(oldestItem, target);
                    // 从数据中移除被合并的物品
                    String worldName = world.getName();
                    WorldMergeData worldMergeData = worldData.get(worldName);
                    if (worldMergeData != null) {
                        worldMergeData.removeItem(target.getUniqueId());
                    }
                }
            }
        }
    }

    /**
     * 检查两个物品是否可以合并
     */
    private boolean canMerge(Item item1, Item item2) {
        ItemStack s1 = item1.getItemStack();
        ItemStack s2 = item2.getItemStack();

        // 基础类型检查
        if (s1.getType() != s2.getType()) return false;

        // 堆叠上限检查
        if (s1.getAmount() >= s1.getMaxStackSize() ||
                s2.getAmount() >= s2.getMaxStackSize()) return false;
        if (s1.getAmount() + s2.getAmount() > s1.getMaxStackSize()) return false;

        // 原版物品合并逻辑：检查是否可以堆叠
        return s1.isSimilar(s2);
    }

    /**
     * 执行合并操作
     */
    private void performMerge(Item keep, Item remove) {
        if (!keep.isValid() || !remove.isValid()) return;

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

            // 修复：同时从调度器队列中移除
            pickupPlugin.getPickupManager().getItemScheduler().unregisterItem(remove);
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