package pickup;

import org.bukkit.*;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CustomItemMerger {

    private final PickUp plugin;
    private final NamespacedKey SPAWN_TICK_KEY;
    private final double mergeRangeSq;
    private final int intervalTicks;
    private final Map<World, Set<Item>> readyItemsByWorld = new ConcurrentHashMap<>();

    private BukkitRunnable mergeTask = null;

    private final ThreadLocal<Map<ChunkKey, Map<Material, List<Item>>>> chunkGroupCache =
            ThreadLocal.withInitial(HashMap::new);

    public CustomItemMerger(PickUp plugin, double mergeRange, int intervalTicks) {
        this.plugin = plugin;
        this.SPAWN_TICK_KEY = new NamespacedKey(plugin, "spawn_tick");
        this.mergeRangeSq = Math.max(0.1, mergeRange) * Math.max(0.1, mergeRange);
        this.intervalTicks = Math.max(1, intervalTicks);

        for (World world : Bukkit.getWorlds()) {
            readyItemsByWorld.put(world, ConcurrentHashMap.newKeySet());
        }
    }

    public void start() {
        if (mergeTask != null) return;
        mergeTask = new BukkitRunnable() {
            @Override
            public void run() {
                performMerge();
            }
        };
        mergeTask.runTaskTimer(plugin, 4L, intervalTicks);
    }

    public void stop() {
        if (mergeTask != null) {
            mergeTask.cancel();
            mergeTask = null;
        }
        readyItemsByWorld.clear();
    }

    public void notifyItemReady(Item item) {
        if (item == null || item.isDead()) return;
        World world = item.getWorld();
        readyItemsByWorld.computeIfAbsent(world, w -> ConcurrentHashMap.newKeySet()).add(item);
    }

    private void performMerge() {
        if (readyItemsByWorld.isEmpty()) return;

        Map<ChunkKey, Map<Material, List<Item>>> chunkGroups = chunkGroupCache.get();
        chunkGroups.clear();

        // 收集所有待合并物品（按世界 → Chunk → Material 分组）
        for (Map.Entry<World, Set<Item>> entry : readyItemsByWorld.entrySet()) {
            World world = entry.getKey();
            Set<Item> itemsInWorld = entry.getValue();
            if (itemsInWorld.isEmpty()) continue;

            Iterator<Item> iter = itemsInWorld.iterator();
            while (iter.hasNext()) {
                Item item = iter.next();
                if (item.isDead()) {
                    iter.remove(); // 清理已消失的物品
                    continue;
                }

                ItemStack stack = item.getItemStack();
                if (stack.getAmount() >= stack.getMaxStackSize()) {
                    iter.remove(); // 满堆物品无需合并
                    continue;
                }

                Location loc = item.getLocation();
                Chunk chunk = loc.getChunk();
                ChunkKey key = new ChunkKey(chunk.getX(), chunk.getZ(), world.getName());

                chunkGroups.computeIfAbsent(key, k -> new EnumMap<>(Material.class))
                        .computeIfAbsent(stack.getType(), k -> new ArrayList<>())
                        .add(item);
            }
        }

        // 执行合并
        for (Map<Material, List<Item>> materialGroups : chunkGroups.values()) {
            for (List<Item> items : materialGroups.values()) {
                if (items.size() < 2) continue;

                for (int i = 0; i < items.size(); i++) {
                    Item a = items.get(i);
                    if (a.isDead()) continue;

                    for (int j = i + 1; j < items.size(); j++) {
                        Item b = items.get(j);
                        if (b.isDead()) continue;

                        if (a.getItemStack().isSimilar(b.getItemStack())) {
                            if (a.getLocation().distanceSquared(b.getLocation()) <= mergeRangeSq) {
                                performMerge(a, b);
                                break; // 合并后跳出内层循环（a 已更新，b 已删除）
                            }
                        }
                    }
                }
            }
        }
    }

    private void performMerge(Item target, Item toMerge) {
        ItemStack stackA = target.getItemStack();
        ItemStack stackB = toMerge.getItemStack();
        int total = stackA.getAmount() + stackB.getAmount();
        if (total > stackA.getMaxStackSize()) return;

        // 1. 合并数量（不影响 NBT）
        stackA.setAmount(total);
        target.setItemStack(stackA);

        // 2. 获取 PDC
        PersistentDataContainer pdcTarget = target.getPersistentDataContainer();
        PersistentDataContainer pdcMerge = toMerge.getPersistentDataContainer();

        // 3. ✅ 关键逻辑：
        //    - source / dropped_by 保持不变（即保留 target 的，也就是“第一个”）
        //    - spawn_tick 以 toMerge（“最后一个”）为准

        Long newSpawnTick = pdcMerge.get(SPAWN_TICK_KEY, PersistentDataType.LONG);

        // 如果 toMerge 没有 spawn_tick（理论上不该发生），用当前世界时间
        if (newSpawnTick == null) {
            newSpawnTick = toMerge.getWorld().getFullTime();
        }

        // 设置新的 spawn_tick（覆盖原来的）
        pdcTarget.set(SPAWN_TICK_KEY, PersistentDataType.LONG, newSpawnTick);

        // 4. 删除被合并项
        toMerge.remove();

        // 5. 从 readyItemsByWorld 清理
        World world = toMerge.getWorld();
        Set<Item> items = readyItemsByWorld.get(world);
        if (items != null) {
            items.remove(toMerge);
        }
    }

    private record ChunkKey(int x, int z, String worldName) {

    }
}