package pickup;

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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CustomItemMerger {
    private final JavaPlugin plugin;
    private final double mergeRange;
    private final int activeDurationTicks;
    private final int scanIntervalTicks;
    private boolean running = false;
    private BukkitRunnable mergeTask = null;

    // 只记录哪些物品还在“主动期内”，用于控制扫描行为
    private final Map<Item, ItemEntry> activeEntries = new ConcurrentHashMap<>();

    private static final Set<Material> BLACKLISTED = Set.of(
            Material.BEE_NEST, Material.BEEHIVE,
            Material.SPAWNER, Material.DRAGON_EGG,
            Material.SHULKER_BOX, Material.LECTERN,
            Material.SUSPICIOUS_SAND, Material.SUSPICIOUS_GRAVEL,
            Material.CHISELED_BOOKSHELF
    );

    public CustomItemMerger(JavaPlugin plugin, double mergeRange, int activeDurationTicks, int scanIntervalTicks) {
        this.plugin = Objects.requireNonNull(plugin, "plugin cannot be null");
        this.mergeRange = Math.max(0.1, mergeRange);
        this.activeDurationTicks = Math.max(0, activeDurationTicks);
        this.scanIntervalTicks = Math.max(1, scanIntervalTicks);
    }

    public void start() {
        if (running) return;
        running = true;

        this.mergeTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!running) return;

                // 注意：不能全局取一个 world.getFullTime()，因为物品可能跨世界
                Iterator<Map.Entry<Item, ItemEntry>> iter = activeEntries.entrySet().iterator();
                while (iter.hasNext()) {
                    Map.Entry<Item, ItemEntry> entry = iter.next();
                    Item item = entry.getKey();
                    ItemEntry meta = entry.getValue();

                    if (item == null || !item.isValid() || item.isDead()) {
                        iter.remove();
                        continue;
                    }

                    World world = item.getWorld();

                    long currentTick = world.getFullTime(); // ✅ 使用游戏 tick

                    // 检查是否已过主动期
                    if (currentTick - meta.spawnTick >= activeDurationTicks) {
                        iter.remove();
                        continue;
                    }

                    // 是否到扫描时间？
                    if (currentTick - meta.lastScanTick >= scanIntervalTicks) {
                        meta.lastScanTick = currentTick;
                        tryMergeWithNearby(item);
                    }
                }
            }
        };
        this.mergeTask.runTaskTimer(this.plugin, 0, 1);
    }

    public void stop() {
        if (mergeTask != null) {
            mergeTask.cancel();
            mergeTask = null;
        }
        running = false;
        activeEntries.clear();
    }

    public void notifyItemReady(Item item) {
        if (!running || item == null || !item.isValid() || item.isDead()) return;

        ItemStack stack = item.getItemStack();
        if (stack.getType().isAir()) return; // ← 补上 null 检查
        if (BLACKLISTED.contains(stack.getType())) return;
        if (stack.getAmount() >= stack.getMaxStackSize()) return;

        activeEntries.put(item, new ItemEntry(item.getWorld().getFullTime()));
    }

    private void tryMergeWithNearby(Item source) {
        if (!source.isValid() || source.isDead()) return;

        Location loc = source.getLocation();
        World world = loc.getWorld();
        if (world == null) return;

        Collection<Item> nearby = new ArrayList<>();
        for (org.bukkit.entity.Entity entity : world.getNearbyEntities(loc, mergeRange, mergeRange, mergeRange)) {
            if (entity instanceof Item) {
                nearby.add((Item) entity);
            }
        }

        for (Item target : nearby) {
            if (target == source) continue;
            if (!target.isValid() || target.isDead()) continue;
            if (canMerge(source, target)) {
                performMerge(source, target);

                activeEntries.remove(target); // 移除被合并掉的

                // 让 source 继续活跃（如果未满堆）
                ItemStack stack = source.getItemStack();
                if (stack.getAmount() < stack.getMaxStackSize()) {
                    NamespacedKey spawnTickKey = new NamespacedKey(plugin, "spawn_tick");
                    long newSpawnTick = source.getPersistentDataContainer()
                            .getOrDefault(spawnTickKey, PersistentDataType.LONG, world.getFullTime());
                    activeEntries.put(source, new ItemEntry(newSpawnTick));
                }
                break; // 一次只合并一个，防止连锁过载
            }
        }
    }

    private boolean canMerge(Item item1, Item item2) {
        ItemStack s1 = item1.getItemStack();
        ItemStack s2 = item2.getItemStack();
        if (s1.getType() != s2.getType()) return false;
        if (s1.getAmount() + s2.getAmount() > s1.getMaxStackSize()) return false;
        return Objects.equals(s1.getItemMeta(), s2.getItemMeta());
    }

    private void performMerge(Item keep, Item remove) {
        if (!keep.isValid() || !remove.isValid()) return;

        PersistentDataContainer keepPdc = keep.getPersistentDataContainer();
        PersistentDataContainer removePdc = remove.getPersistentDataContainer();

        NamespacedKey spawnTickKey = new NamespacedKey(plugin, "spawn_tick");
        NamespacedKey sourceKey = new NamespacedKey(plugin, "source");
        NamespacedKey droppedByKey = new NamespacedKey(plugin, "dropped_by");

        long keepSpawnTick = keepPdc.getOrDefault(spawnTickKey, PersistentDataType.LONG, 0L);
        long removeSpawnTick = removePdc.getOrDefault(spawnTickKey, PersistentDataType.LONG, 0L);

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
        try {
            ItemStack keepStack = keep.getItemStack();
            ItemStack removeStack = remove.getItemStack();
            keepStack.setAmount(keepStack.getAmount() + removeStack.getAmount());
        } catch (Exception ignored) {}

        // 移除被合并的物品
        remove.remove();
    }

    private static class ItemEntry {
        final long spawnTick;
        long lastScanTick;

        ItemEntry(long spawnTick) {
            this.spawnTick = spawnTick;
            this.lastScanTick = spawnTick - 1000;
        }
    }
}