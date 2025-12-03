package pickup;

import org.bukkit.*;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class CustomItemMerger {

    private final PickUp plugin;
    private final NamespacedKey SPAWN_TICK_KEY;
    private final double mergeRangeSq;
    private final int intervalTicks;
    private final Map<World, Set<Item>> activeItemsByWorld;
    private BukkitRunnable mergeTask = null;

    private final ThreadLocal<Map<ChunkKey, Map<Material, List<Item>>>> chunkGroupCache =
            ThreadLocal.withInitial(HashMap::new);

    public CustomItemMerger(
            PickUp plugin,
            Map<World, Set<Item>> activeItemsByWorld,
            double mergeRange,
            int intervalTicks
    ) {
        this.plugin = plugin;
        this.activeItemsByWorld = activeItemsByWorld;
        this.SPAWN_TICK_KEY = new NamespacedKey(plugin, "spawn_tick");
        this.mergeRangeSq = Math.max(0.1, mergeRange) * Math.max(0.1, mergeRange);
        this.intervalTicks = Math.max(1, intervalTicks);
    }

    public void start() {
        if (mergeTask != null) return;
        mergeTask = new BukkitRunnable() {
            @Override
            public void run() {
                performMerge();
            }
        };
        mergeTask.runTaskTimer(plugin, 20L, intervalTicks);
    }

    public void stop() {
        if (mergeTask != null) {
            mergeTask.cancel();
            mergeTask = null;
        }
    }

    private void performMerge() {
        if (activeItemsByWorld.isEmpty()) return;

        Map<ChunkKey, Map<Material, List<Item>>> chunkGroups = chunkGroupCache.get();
        chunkGroups.clear();

        for (Map.Entry<World, Set<Item>> entry : activeItemsByWorld.entrySet()) {
            World world = entry.getKey();
            for (Item item : entry.getValue()) {
                if (item.isDead()) continue;
                ItemStack stack = item.getItemStack();
                if (stack.getAmount() >= stack.getMaxStackSize()) continue;

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
                    for (int j = i + 1; j < items.size(); j++) {
                        Item b = items.get(j);
                        if (b.isDead()) continue;
                        if (a.getItemStack().isSimilar(b.getItemStack())) {
                            if (a.getLocation().distanceSquared(b.getLocation()) <= mergeRangeSq) {
                                performMerge(a, b);
                                break;
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

        stackA.setAmount(total);
        target.setItemStack(stackA);

        PersistentDataContainer pdcTarget = target.getPersistentDataContainer();
        PersistentDataContainer pdcMerge = toMerge.getPersistentDataContainer();

        Long tickA = pdcTarget.get(SPAWN_TICK_KEY, PersistentDataType.LONG);
        Long tickB = pdcMerge.get(SPAWN_TICK_KEY, PersistentDataType.LONG);
        if (tickB != null && (tickA == null || tickB < tickA)) {
            pdcTarget.set(SPAWN_TICK_KEY, PersistentDataType.LONG, tickB);
        }

        toMerge.remove();
    }

    private static final class ChunkKey {
        final int x, z;
        final String worldName;

        ChunkKey(int x, int z, String worldName) {
            this.x = x;
            this.z = z;
            this.worldName = worldName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ChunkKey that = (ChunkKey) o;
            return x == that.x && z == that.z && Objects.equals(worldName, that.worldName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, z, worldName);
        }
    }
}