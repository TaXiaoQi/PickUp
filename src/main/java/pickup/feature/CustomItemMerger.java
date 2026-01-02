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
import org.bukkit.scheduler.BukkitRunnable;
import pickup.Main;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自定义物品合并器
 * 用于管理掉落物在一定范围内的自动合并
 */
public class CustomItemMerger {
    // 插件主类引用
    private final JavaPlugin plugin;
    // 合并检测范围（半径）
    private final double mergeRange;
    // 物品活跃持续时间（tick）
    private final int activeDurationTicks;
    // 扫描间隔时间（tick）
    private final int scanIntervalTicks;
    // 运行状态标志
    private boolean running = false;
    // 定时任务对象
    private BukkitRunnable mergeTask = null;

    // 记录处于"主动期"内的物品及其元数据（线程安全的Map）
    private final Map<Item, ItemEntry> activeEntries = new ConcurrentHashMap<>();

    // 黑名单：禁止合并的物品类型
    private static final Set<Material> BLACKLISTED = Set.of(
            Material.BEE_NEST, Material.BEEHIVE,        // 蜂巢/蜂箱（有状态）
            Material.SPAWNER, Material.DRAGON_EGG,      // 刷怪笼、龙蛋（特殊物品）
            Material.SHULKER_BOX, Material.LECTERN,     // 潜影盒、讲台（有NBT数据）
            Material.SUSPICIOUS_SAND, Material.SUSPICIOUS_GRAVEL, // 可疑的沙子和沙砾（考古物品）
            Material.CHISELED_BOOKSHELF                 // 雕纹书架（有状态）
    );

    /**
     * 构造函数
     * @param plugin 插件主类
     * @param mergeRange 合并检测范围
     * @param activeDurationTicks 物品活跃持续时间（tick）
     * @param scanIntervalTicks 扫描间隔时间（tick）
     */
    public CustomItemMerger(JavaPlugin plugin, double mergeRange, int activeDurationTicks, int scanIntervalTicks) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        // 参数有效性检查，确保最小值
        this.mergeRange = Math.max(0.1, mergeRange);
        this.activeDurationTicks = Math.max(0, activeDurationTicks);
        this.scanIntervalTicks = Math.max(1, scanIntervalTicks);
    }

    /**
     * 启动合并器
     */
    public void start() {
        if (running) return; // 防止重复启动
        running = true;

        // 创建定时任务，每tick执行一次
        this.mergeTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!running) return; // 检查运行状态

                // 遍历所有活跃物品
                Iterator<Map.Entry<Item, ItemEntry>> iter = activeEntries.entrySet().iterator();
                while (iter.hasNext()) {
                    Map.Entry<Item, ItemEntry> entry = iter.next();
                    Item item = entry.getKey();
                    ItemEntry meta = entry.getValue();

                    // 检查物品是否有效
                    if (item == null || !item.isValid() || item.isDead()) {
                        iter.remove();
                        continue;
                    }

                    World world = item.getWorld();
                    long currentTick = world.getFullTime(); // 使用游戏世界的时间（tick）

                    // 检查是否已过活跃期
                    if (currentTick - meta.spawnTick >= activeDurationTicks) {
                        iter.remove(); // 从活跃列表中移除
                        continue;
                    }

                    // 检查是否到达扫描时间
                    if (currentTick - meta.lastScanTick >= scanIntervalTicks) {
                        meta.lastScanTick = currentTick; // 更新上次扫描时间
                        tryMergeWithNearby(item); // 尝试合并
                    }
                }
            }
        };
        // 启动定时任务：延迟0tick，周期1tick（每tick执行）
        this.mergeTask.runTaskTimer(this.plugin, 0, 1);
    }

    /**
     * 停止合并器
     */
    public void stop() {
        if (mergeTask != null) {
            mergeTask.cancel(); // 取消定时任务
            mergeTask = null;
        }
        running = false; // 更新运行状态
        activeEntries.clear(); // 清空活跃物品列表
    }

    /**
     * 通知有新的物品可以合并
     * 通常在物品生成或掉落时调用
     * @param item 新生成的物品实体
     */
    public void notifyItemReady(Item item) {
        if (!running || item == null || !item.isValid() || item.isDead()) return;

        ItemStack stack = item.getItemStack();
        // 检查物品是否有效
        if (stack.getType().isAir()) return; // 空物品跳过
        if (BLACKLISTED.contains(stack.getType())) return; // 黑名单物品跳过
        if (stack.getAmount() >= stack.getMaxStackSize()) return; // 已满堆跳过

        // 将物品添加到活跃列表，记录生成时间
        activeEntries.put(item, new ItemEntry(item.getWorld().getFullTime()));
    }

    /**
     * 尝试与附近的物品合并
     * @param source 源物品（主动合并的物品）
     */
    private void tryMergeWithNearby(Item source) {
        if (!source.isValid() || source.isDead()) return;

        Location loc = source.getLocation();
        World world = loc.getWorld();
        if (world == null) return;

        // 获取范围内的所有实体
        Collection<Item> nearby = new ArrayList<>();
        for (org.bukkit.entity.Entity entity : world.getNearbyEntities(loc, mergeRange, mergeRange, mergeRange)) {
            if (entity instanceof Item) {
                nearby.add((Item) entity);
            }
        }

        // 遍历附近物品，寻找可合并的目标
        for (Item target : nearby) {
            if (target == source) continue; // 跳过自身
            if (!target.isValid() || target.isDead()) continue; // 检查有效性

            if (canMerge(source, target)) {
                performMerge(source, target); // 执行合并

                activeEntries.remove(target); // 从活跃列表中移除被合并的物品

                // 如果源物品仍未满堆，重新添加到活跃列表
                ItemStack stack = source.getItemStack();
                if (stack.getAmount() < stack.getMaxStackSize()) {
                    // 从持久化数据中读取生成时间
                    NamespacedKey spawnTickKey = new NamespacedKey(plugin, "spawn_tick");
                    long newSpawnTick = source.getPersistentDataContainer()
                            .getOrDefault(spawnTickKey, PersistentDataType.LONG, world.getFullTime());
                    activeEntries.put(source, new ItemEntry(newSpawnTick));
                }
                break; // 一次只合并一个，防止连锁反应和性能问题
            }
        }
    }

    /**
     * 检查两个物品是否可以合并
     * @param item1 第一个物品
     * @param item2 第二个物品
     * @return 是否可以合并
     */
    private boolean canMerge(Item item1, Item item2) {
        ItemStack s1 = item1.getItemStack();
        ItemStack s2 = item2.getItemStack();

        // 检查物品类型是否相同
        if (s1.getType() != s2.getType()) return false;
        // 检查合并后是否会超过最大堆叠数
        if (s1.getAmount() + s2.getAmount() > s1.getMaxStackSize()) return false;
        // 检查物品元数据是否相同（NBT数据等）
        return Objects.equals(s1.getItemMeta(), s2.getItemMeta());
    }

    /**
     * 执行合并操作
     * @param keep 保留的物品（合并后存在）
     * @param remove 移除的物品（合并后被删除）
     */
    private void performMerge(Item keep, Item remove) {
        if (!keep.isValid() || !remove.isValid()) return;

        // 获取两个物品的持久化数据容器
        PersistentDataContainer keepPdc = keep.getPersistentDataContainer();
        PersistentDataContainer removePdc = remove.getPersistentDataContainer();

        // 定义持久化数据的键
        NamespacedKey spawnTickKey = new NamespacedKey(plugin, "spawn_tick");
        NamespacedKey sourceKey = new NamespacedKey(plugin, "source");
        NamespacedKey droppedByKey = new NamespacedKey(plugin, "dropped_by");

        // 获取两个物品的生成时间
        long keepSpawnTick = keepPdc.getOrDefault(spawnTickKey, PersistentDataType.LONG, 0L);
        long removeSpawnTick = removePdc.getOrDefault(spawnTickKey, PersistentDataType.LONG, 0L);

        // 如果被移除的物品更新（生成时间更晚），则将其数据转移到保留的物品上
        if (removeSpawnTick > keepSpawnTick) {
            // 更新生成时间
            keepPdc.set(spawnTickKey, PersistentDataType.LONG, removeSpawnTick);

            // 转移来源信息
            String source = removePdc.get(sourceKey, PersistentDataType.STRING);
            if (source != null) {
                keepPdc.set(sourceKey, PersistentDataType.STRING, source);
            } else {
                keepPdc.remove(sourceKey);
            }

            // 转移掉落者信息
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

        // 注意：这里需要能访问到 PickupManager。一种方式是将 plugin 强转为 PickUp 类型
        if (plugin instanceof Main pickUpPlugin) {
            PickupManager manager = pickUpPlugin.getPickupManager();
            if (manager != null) {
                manager.decrementPickupableItemCount(remove.getWorld());
            }
        }
    }

    /**
     * 物品条目元数据内部类
     * 用于记录物品的活跃状态信息
     */
    private static class ItemEntry {
        final long spawnTick;      // 物品生成时间（tick）
        long lastScanTick;         // 上次扫描时间（tick）

        /**
         * 构造函数
         * @param spawnTick 生成时间
         */
        ItemEntry(long spawnTick) {
            this.spawnTick = spawnTick;
            this.lastScanTick = spawnTick - 1000; // 初始化为较早时间，确保立即扫描
        }
    }
}