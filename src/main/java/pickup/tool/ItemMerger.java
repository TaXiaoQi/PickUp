package pickup.tool;

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
import pickup.event.PickupEventHandler;
import pickup.feature.ItemDrivenPickupScheduler;
import pickup.feature.ItemSpatialIndex;

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

    private static final NamespacedKey SPAWN_TICK_KEY =
            new NamespacedKey("pickup", "spawn_tick");

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
        Long spawnTick = pdc.get(SPAWN_TICK_KEY, PersistentDataType.LONG);

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

                long currentTick = item.getWorld().getGameTime();

                // 检查活跃期
                if (activeDurationTicks > 0 &&
                        currentTick - data.spawnTick >= activeDurationTicks) {
                    worldMergeData.removeItem(item.getUniqueId());
                    return;
                }

                // 检查是否满堆
                ItemStack stack = item.getItemStack();
                if (stack.getAmount() >= stack.getMaxStackSize()) {
                    worldMergeData.removeItem(item.getUniqueId());
                    return;
                }

                // 检查是否需要扫描
                if (currentTick - data.lastScanTick >= scanIntervalTicks) {
                    data.lastScanTick = currentTick;
                    tryMergeWithNearby(item);

                    // 安排下次检查（如果物品仍然有效且未满堆）
                    if (item.isValid() && !item.isDead()) {
                        ItemStack newStack = item.getItemStack();
                        if (newStack.getAmount() < newStack.getMaxStackSize()) {
                            scheduleMergeCheck(item, scanIntervalTicks);
                        } else {
                            worldMergeData.removeItem(item.getUniqueId());
                        }
                    } else {
                        worldMergeData.removeItem(item.getUniqueId());
                    }
                } else {
                    // 计算下次检查的延迟
                    long nextCheck = scanIntervalTicks - (currentTick - data.lastScanTick);
                    if (nextCheck > 0) {
                        scheduleMergeCheck(item, nextCheck);
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "合并检查失败: " + e.getMessage(), e);
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

        // 确定哪个物品是最早生成的
        Item oldestItem = source;
        long oldestSpawnTick = getSpawnTick(source);

        // 先找出最早生成的物品
        for (Item target : nearby) {
            if (target == source) continue;

            long targetSpawnTick = getSpawnTick(target);
            if (targetSpawnTick < oldestSpawnTick) {
                oldestItem = target;
                oldestSpawnTick = targetSpawnTick;
            }
        }

        // 创建需要合并的物品列表（避免在循环中修改）
        List<Item> toMerge = new ArrayList<>();
        for (Item target : nearby) {
            if (target != oldestItem && canMerge(oldestItem, target)) {
                toMerge.add(target);
            }
        }

        // 执行合并
        for (Item target : toMerge) {
            // 检查物品是否仍然有效
            if (!oldestItem.isValid() || oldestItem.isDead() ||
                    !target.isValid() || target.isDead()) {
                continue;
            }

            if (canMerge(oldestItem, target)) {
                performMerge(oldestItem, target);

                // 如果oldestItem变成source，并且source被完全合并了，可以提前结束
                if (oldestItem == source && source.isDead()) {
                    break;
                }
            }
        }
    }

    /**
     * 获取物品的生成时间
     */
    private long getSpawnTick(Item item) {
        PersistentDataContainer pdc = item.getPersistentDataContainer();
        return pdc.getOrDefault(
                SPAWN_TICK_KEY,
                PersistentDataType.LONG,
                item.getWorld().getGameTime()
        );
    }

    /**
     * 检查两个物品是否可以合并
     */
    private boolean canMerge(Item item1, Item item2) {
        ItemStack s1 = item1.getItemStack();
        ItemStack s2 = item2.getItemStack();

        // 基础类型检查
        if (s1.getType() != s2.getType()) return false;

        // 原版物品合并逻辑：检查是否可以堆叠
        if (!s1.isSimilar(s2)) return false;

        // 堆叠上限检查
        int totalAmount = s1.getAmount() + s2.getAmount();
        return totalAmount <= s1.getMaxStackSize();
    }

    /**
     * 执行合并操作
     */
    private void performMerge(Item keep, Item remove) {
        if (!keep.isValid() || !remove.isValid()) return;

        try {
            ItemStack keepStack = keep.getItemStack();
            ItemStack removeStack = remove.getItemStack();

            // 记录原始数量
            int originalKeepAmount = keepStack.getAmount();
            int originalRemoveAmount = removeStack.getAmount();
            int totalAmount = originalKeepAmount + originalRemoveAmount;
            int maxStackSize = keepStack.getMaxStackSize();

            ItemStack newKeepStack = keepStack.clone();
            if (totalAmount <= maxStackSize) {
                // 可以完全合并
                newKeepStack.setAmount(totalAmount);
                keep.setItemStack(newKeepStack);

                // 删除被合并的物品实体
                remove.remove();

                // 清理被合并物品的数据
                cleanupMergedItem(remove);

            } else {
                // 超过最大堆叠，只能合并部分
                // 设置保留物品为满堆
                newKeepStack.setAmount(maxStackSize);
                keep.setItemStack(newKeepStack);

                // 设置被合并物品为剩余数量
                int remaining = totalAmount - maxStackSize;
                ItemStack newRemoveStack = removeStack.clone();
                newRemoveStack.setAmount(remaining);
                remove.setItemStack(newRemoveStack);

                // 被合并物品还有剩余，不删除
            }

            // 更新主物品的索引
            updateItemIndex(keep);

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "合并操作失败: " + e.getMessage(), e);
        }
    }

    /**
     * 清理被合并物品的关联数据
     */
    private void cleanupMergedItem(Item item) {
        if (item.isDead() || !item.isValid()) return;

        // 从世界数据中移除
        String worldName = item.getWorld().getName();
        WorldMergeData worldMergeData = worldData.get(worldName);
        if (worldMergeData != null) {
            worldMergeData.removeItem(item.getUniqueId());
        }

        // 从索引和调度器中移除
        if (plugin instanceof Main pickupPlugin) {
            PickupEventHandler handler = pickupPlugin.getPickupEventHandler();
            if (handler != null) {
                ItemDrivenPickupScheduler scheduler = handler.getItemScheduler();
                if (scheduler != null) {
                    scheduler.unregisterItem(item);
                }
            }
        }
    }

    /**
     * 更新物品在索引中的状态
     */
    private void updateItemIndex(Item item) {
        if (!item.isValid()) return;

        if (plugin instanceof Main pickupPlugin) {
            ItemSpatialIndex index = pickupPlugin.getItemSpatialIndex();
            if (index != null) {
                // 重新注册以更新状态
                index.unregisterItem(item);
                index.registerItem(item);
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