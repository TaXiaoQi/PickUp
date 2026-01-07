package pickup.feature;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import pickup.Main;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 物品空间索引系统
 */
public class ItemSpatialIndex {

    // 新增：物品源类型（从ItemLifecycleManager移过来）
    public enum ItemSourceType {
        PLAYER_DROP, NATURAL_DROP, INSTANT_PICKUP, UNKNOWN
    }

    // 新增：物品元数据
    public static class ItemMetadata {
        public final long spawnTick;
        public final ItemSourceType source;
        public final UUID droppedBy; // 如果是玩家丢弃

        public ItemMetadata(long spawnTick, ItemSourceType source, UUID droppedBy) {
            this.spawnTick = spawnTick;
            this.source = source;
            this.droppedBy = droppedBy;
        }
    }


    private final Main plugin;

    // 核心数据结构：World -> ChunkCoord -> Set<Item>
    private final Map<World, Map<ChunkCoord, Set<Item>>> chunkIndex = new ConcurrentHashMap<>();

    // 反向索引：Item -> ChunkCoord（用于快速删除）
    private final Map<Item, ChunkCoord> itemToChunk = new ConcurrentHashMap<>();

    // 新增：物品元数据存储
    private final Map<UUID, ItemMetadata> itemMetadata = new ConcurrentHashMap<>();

    // 按世界统计物品数量（优化hasPickupableItems检查）
    private final Map<World, AtomicInteger> worldItemCount = new ConcurrentHashMap<>();

    //
    private ScheduledTask cleanupTask;
    public ItemSpatialIndex(Main plugin) {
        this.plugin = plugin;
    }

    // ====== 元数据接口 ======

    public ItemMetadata getItemMetadata(Item item) {
        if (item == null) return null;
        return itemMetadata.get(item.getUniqueId());
    }

    /**
     * 获取物品生成时间
     */
    public long getSpawnTick(Item item) {
        ItemMetadata meta = getItemMetadata(item);
        return meta != null ? meta.spawnTick : item.getWorld().getGameTime();
    }

    /**
     * 获取物品来源类型
     */
    public ItemSourceType getSourceType(Item item) {
        ItemMetadata meta = getItemMetadata(item);
        return meta != null ? meta.source : ItemSourceType.UNKNOWN;
    }

    /**
     * 获取丢弃者（如果是玩家丢弃）
     */
    public UUID getDroppedBy(Item item) {
        ItemMetadata meta = getItemMetadata(item);
        return meta != null ? meta.droppedBy : null;
    }

    /**
     * 检查物品是否已注册
     */
    public boolean isItemRegistered(Item item) {
        return item != null && itemMetadata.containsKey(item.getUniqueId());
    }

// ====== 原有方法（增强版） ======

    /**
     * 获取附近的玩家（优化性能）
     */
    public Set<Player> getNearbyPlayers(Location location, double radius) {
        Set<Player> players = new HashSet<>();
        World world = location.getWorld();

        if (world == null) return players;

        for (Entity entity : world.getNearbyEntities(location, radius, radius, radius)) {
            if (entity instanceof Player player) {
                players.add(player);
            }
        }

        return players;
    }

    /**
     * 注册新物品到索引（带元数据）
     */
    public void registerItem(Item item, ItemSourceType source, UUID droppedBy) {
        if (item == null) {
            plugin.getLogger().warning("registerItem: item为null");
            return;
        }

        UUID itemId = item.getUniqueId();
        long spawnTick = item.getWorld().getGameTime();

        // 检查是否已注册
        if (itemMetadata.containsKey(itemId)) {
            return;
        }

        ChunkCoord coord = getChunkCoord(item.getLocation());
        try {
            chunkIndex.computeIfAbsent(item.getWorld(), w -> new ConcurrentHashMap<>())
                    .computeIfAbsent(coord, c -> ConcurrentHashMap.newKeySet())
                    .add(item);

            itemToChunk.put(item, coord);

            // 存储元数据
            itemMetadata.put(itemId, new ItemMetadata(spawnTick, source, droppedBy));

            // 更新世界物品计数
            worldItemCount.computeIfAbsent(item.getWorld(), w -> new AtomicInteger(0))
                    .incrementAndGet();
        } catch (Exception e) {
            plugin.getLogger().warning("注册物品时出错: " + e.getMessage());
        }
    }

    /**
     * 注册新物品到索引（简化版，默认自然掉落）
     */
    public void registerItem(Item item) {
        registerItem(item, ItemSourceType.NATURAL_DROP, null);
    }

    /**
     * 从索引中移除物品
     */
    public void unregisterItem(Item item) {
        if (item == null) return;

        UUID itemId = item.getUniqueId();
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

        // 移除元数据
        itemMetadata.remove(itemId);

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

    // 清理队列表格
    public void startCleanupTask() {
        // 确保使用正确的调度器
        plugin.getServer().getGlobalRegionScheduler();
        GlobalRegionScheduler scheduler = plugin.getServer().getGlobalRegionScheduler();
        this.cleanupTask = scheduler.runAtFixedRate(plugin, task -> {
            try {
                cleanupInvalidItems();
            } catch (Exception e) {
                plugin.getLogger().severe("Error in cleanup task: " + e.getMessage());
            }
        }, 20 * 60 * 5, 20 * 60 * 5); // 每5分钟清理一次
    }

    public void stopCleanupTask() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
    }

    /**
     * 清理无效物品（定期调用，防止内存泄漏）
     */
    public void cleanupInvalidItems() {

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

                        // 同时从调度器移除
                        if (plugin.getPickupEventHandler() != null) {
                            ItemDrivenPickupScheduler scheduler = plugin.getPickupEventHandler().getItemScheduler();
                            if (scheduler != null) {
                                scheduler.unregisterItem(item);
                            }
                        }

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