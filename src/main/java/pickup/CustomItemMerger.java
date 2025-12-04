package pickup;

import org.bukkit.*;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// 掉落物合成类
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
        Material type = item.getItemStack().getType();
        if (type == Material.BEEHIVE ||
                type == Material.BEE_NEST ||
                type == Material.SPAWNER ||
                type == Material.DECORATED_POT ||
                type == Material.SUSPICIOUS_SAND ||
                type == Material.SUSPICIOUS_GRAVEL) {
            return;
        }
        if (item.isDead()) return;
        World world = item.getWorld();
        readyItemsByWorld.computeIfAbsent(world, w -> ConcurrentHashMap.newKeySet()).add(item);
    }

    private void performMerge() {
        if (readyItemsByWorld.isEmpty()) return;

        Map<ChunkKey, Map<Material, List<Item>>> chunkGroups = chunkGroupCache.get();
        chunkGroups.clear();

        for (Map.Entry<World, Set<Item>> entry : readyItemsByWorld.entrySet()) {
            World world = entry.getKey();
            Set<Item> itemsInWorld = entry.getValue();
            if (itemsInWorld.isEmpty()) continue;

            Iterator<Item> iter = itemsInWorld.iterator();
            while (iter.hasNext()) {
                Item item = iter.next();
                if (item.isDead()) {
                    iter.remove();
                    continue;
                }

                ItemStack stack = item.getItemStack();
                if (stack.getAmount() >= stack.getMaxStackSize()) {
                    iter.remove();
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

        for (Map<Material, List<Item>> materialGroups : chunkGroups.values()) {
            for (List<Item> items : materialGroups.values()) {
                if (items.size() < 2) continue;

                for (int i = 0; i < items.size(); i++) {
                    Item a = items.get(i);
                    if (a.isDead()) continue;
                    Location locA = a.getLocation();
                    for (int j = i + 1; j < items.size(); j++) {
                        Item b = items.get(j);
                        if (b.isDead()) continue;
                        if (canSafelyMerge(a.getItemStack(), b.getItemStack())){
                            if (locA.distanceSquared(b.getLocation()) <= mergeRangeSq) {
                                performMerge(a, b);
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean isMergeableContainer(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) {
            return false;
        }
        String typeName = stack.getType().name();
        return typeName.endsWith("_SHULKER_BOX") && stack.getMaxStackSize() == 64;
    }

    private boolean canSafelyMerge(ItemStack a, ItemStack b) {
        if (!a.getType().equals(b.getType())) {
            return false;
        }

        Material type = a.getType();
        if (!type.name().endsWith("_SHULKER_BOX")) {
            return a.isSimilar(b);
        }

        return isMergeableContainer(a) && isMergeableContainer(b);
    }

    private void performMerge(Item target, Item toMerge) {
        ItemStack stackA = target.getItemStack();
        ItemStack stackB = toMerge.getItemStack();
        int total = stackA.getAmount() + stackB.getAmount();
        if (total > stackA.getMaxStackSize()) return;

        stackA.setAmount(total);
        target.setItemStack(stackA);

        PersistentDataContainer pdcTarget = target.getPersistentDataContainer();
        PersistentDataContainer pdcMerge = toMerge.getPersistentDataContainer();

        Long newSpawnTick = pdcMerge.get(SPAWN_TICK_KEY, PersistentDataType.LONG);

        if (newSpawnTick == null) {
            newSpawnTick = toMerge.getWorld().getFullTime();
        }

        pdcTarget.set(SPAWN_TICK_KEY, PersistentDataType.LONG, newSpawnTick);

        toMerge.remove();

        World world = toMerge.getWorld();
        Set<Item> items = readyItemsByWorld.get(world);
        if (items != null) {
            items.remove(toMerge);
        }
    }

    public void removeItemFromReady(Item item) {
        if (item == null || item.isDead()) return;
        World world = item.getWorld();
        Set<Item> items = readyItemsByWorld.get(world);
        if (items != null) {
            items.remove(item);
        }
    }

    private record ChunkKey(int x, int z, String worldName) {

    }
}