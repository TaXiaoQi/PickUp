package pickup.feature;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.scheduler.BukkitRunnable;
import pickup.Main;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一物品空间索引系统
 * 按区块分区缓存物品，供物品驱动和玩家驱动模式共享使用
 */
public class ItemSpatialIndex { // 移除 implements Listener

    private final Main plugin;

    // 核心数据结构：World -> ChunkCoord -> Set<Item>
    private final Map<World, Map<ChunkCoord, Set<Item>>> chunkIndex = new ConcurrentHashMap<>();

    // 反向索引：Item -> ChunkCoord（用于快速删除）
    private final Map<Item, ChunkCoord> itemToChunk = new ConcurrentHashMap<>();

    // 按世界统计物品数量（优化hasPickupableItems检查）
    private final Map<World, AtomicInteger> worldItemCount = new ConcurrentHashMap<>();

    public ItemSpatialIndex(Main plugin) {
        this.plugin = plugin;
        // 移除事件注册：plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /**
     * 注册新物品到索引
     */
    public void registerItem(Item item) {
        if (item == null || !item.isValid() || item.isDead()) return;

        ChunkCoord coord = getChunkCoord(item.getLocation());

        chunkIndex.computeIfAbsent(item.getWorld(), w -> new ConcurrentHashMap<>())
                .computeIfAbsent(coord, c -> ConcurrentHashMap.newKeySet())
                .add(item);

        itemToChunk.put(item, coord);

        // 更新世界物品计数
        worldItemCount.computeIfAbsent(item.getWorld(), w -> new AtomicInteger(0))
                .incrementAndGet();

        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().fine("注册物品到索引: " + item.getItemStack().getType() +
                    " 在区块 " + coord + ", 世界: " + item.getWorld().getName());
        }
    }

    /**
     * 从索引中移除物品
     */
    public void unregisterItem(Item item) {
        if (item == null) return;

        ChunkCoord coord = itemToChunk.remove(item);
        if (coord == null) return;

        World world = item.getWorld();
        Map<ChunkCoord, Set<Item>> worldChunks = chunkIndex.get(world);
        if (worldChunks != null) {
            Set<Item> itemsInChunk = worldChunks.get(coord);
            if (itemsInChunk != null) {
                itemsInChunk.remove(item);
                if (itemsInChunk.isEmpty()) {
                    worldChunks.remove(coord);
                }
            }

            if (worldChunks.isEmpty()) {
                chunkIndex.remove(world);
            }
        }

        // 更新世界物品计数
        AtomicInteger count = worldItemCount.get(world);
        if (count != null) {
            int remaining = count.decrementAndGet();
            if (remaining <= 0) {
                worldItemCount.remove(world);
            }
        }
    }

    /**
     * 获取指定位置附近的物品（供玩家驱动模式使用）
     * @param center 中心位置
     * @param range 范围（方块）
     * @return 范围内的物品集合
     */
    public Set<Item> getNearbyItems(Location center, double range) {
        if (center == null) return Collections.emptySet();

        World world = center.getWorld();
        Map<ChunkCoord, Set<Item>> worldChunks = chunkIndex.get(world);
        if (worldChunks == null || worldChunks.isEmpty()) {
            return Collections.emptySet();
        }

        Set<Item> result = ConcurrentHashMap.newKeySet();
        double rangeSq = range * range;

        // 计算需要检查的区块范围
        int chunkRange = (int) Math.ceil(range / 16.0);
        ChunkCoord centerChunk = getChunkCoord(center);

        // 只检查相关区块，而不是整个世界
        for (int dx = -chunkRange; dx <= chunkRange; dx++) {
            for (int dz = -chunkRange; dz <= chunkRange; dz++) {
                ChunkCoord coord = new ChunkCoord(centerChunk.x + dx, centerChunk.z + dz);
                Set<Item> itemsInChunk = worldChunks.get(coord);

                if (itemsInChunk != null && !itemsInChunk.isEmpty()) {
                    for (Item item : itemsInChunk) {
                        if (item.isValid() && !item.isDead() &&
                                item.getLocation().distanceSquared(center) <= rangeSq) {
                            result.add(item);
                        }
                    }
                }
            }
        }

        return result;
    }


    /**
     * 检查世界是否有可拾取物品
     */
    public boolean hasItemsInWorld(World world) {
        AtomicInteger count = worldItemCount.get(world);
        return count != null && count.get() > 0;
    }

    /**
     * 获取指定世界中的所有物品（供物品驱动模式使用）
     */
    public Set<Item> getAllItemsInWorld(World world) {
        Map<ChunkCoord, Set<Item>> worldChunks = chunkIndex.get(world);
        if (worldChunks == null || worldChunks.isEmpty()) {
            return Collections.emptySet();
        }

        Set<Item> allItems = ConcurrentHashMap.newKeySet();
        for (Set<Item> chunkItems : worldChunks.values()) {
            allItems.addAll(chunkItems);
        }
        return allItems;
    }

    // 清理队列表格
    public void startCleanupTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                cleanupInvalidItems();
            }
        }.runTaskTimer(plugin, 20 * 60 * 5, 20 * 60 * 5); // 每5分钟清理一次
    }

    /**
     * 清理无效物品（定期调用，防止内存泄漏）
     */
    public void cleanupInvalidItems() {
        int cleaned = 0;

        for (World world : chunkIndex.keySet()) {
            Map<ChunkCoord, Set<Item>> worldChunks = chunkIndex.get(world);
            if (worldChunks == null) continue;

            for (ChunkCoord coord : new ArrayList<>(worldChunks.keySet())) {
                Set<Item> items = worldChunks.get(coord);
                if (items == null) continue;

                Iterator<Item> iter = items.iterator();
                while (iter.hasNext()) {
                    Item item = iter.next();
                    if (!item.isValid() || item.isDead()) {
                        iter.remove();
                        itemToChunk.remove(item);
                        cleaned++;
                    }
                }

                if (items.isEmpty()) {
                    worldChunks.remove(coord);
                }
            }

            if (worldChunks.isEmpty()) {
                chunkIndex.remove(world);
            }
        }

        if (cleaned > 0 && plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("清理了 " + cleaned + " 个无效物品引用");
        }
    }

    // ================== 辅助类 ==================

    /**
     * 区块坐标（用于索引）
     */
    private static class ChunkCoord {
        final int x, z;

        ChunkCoord(int x, int z) {
            this.x = x;
            this.z = z;
        }

        ChunkCoord(Location loc) {
            this.x = loc.getBlockX() >> 4;
            this.z = loc.getBlockZ() >> 4;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ChunkCoord that)) return false;
            return x == that.x && z == that.z;
        }

        @Override
        public int hashCode() {
            return 31 * x + z;
        }

        @Override
        public String toString() {
            return "(" + x + "," + z + ")";
        }
    }

    private ChunkCoord getChunkCoord(Location loc) {
        return new ChunkCoord(loc);
    }
}