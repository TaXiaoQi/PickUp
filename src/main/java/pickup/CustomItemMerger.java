package pickup; // 替换为你的实际包名

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CustomItemMerger {

    private final Map<World, Set<Item>> readyItemsByWorld = new ConcurrentHashMap<>();
    private final double mergeRangeSq; // 合并检测半径的平方（避免频繁开方）

    public CustomItemMerger(double mergeRange) {
        this.mergeRangeSq = mergeRange * mergeRange;
    }


    public void notifyItemReady(Item item) {
        if (item == null || item.isDead()) return;

        ItemStack stack = item.getItemStack();

        Material type = stack.getType();
        if (type == Material.BEEHIVE || type == Material.BEE_NEST ||
                type == Material.SPAWNER || type == Material.DECORATED_POT ||
                type == Material.SUSPICIOUS_SAND || type == Material.SUSPICIOUS_GRAVEL) {
            return;
        }

        if (stack.getAmount() >= stack.getMaxStackSize()) {
            return;
        }

        World world = item.getWorld();
        readyItemsByWorld.computeIfAbsent(world, w -> ConcurrentHashMap.newKeySet()).add(item);

        triggerMergeNear(item);
    }

    private void triggerMergeNear(Item newItem) {
        if (newItem.isDead()) return;

        ItemStack newStack = newItem.getItemStack();
        if (newStack.getAmount() >= newStack.getMaxStackSize()) return;

        World world = newItem.getWorld();
        Location loc = newItem.getLocation();
        Set<Item> itemsInWorld = readyItemsByWorld.get(world);
        if (itemsInWorld == null || itemsInWorld.isEmpty()) return;

        Material type = newStack.getType();
        List<Item> candidates = new ArrayList<>();

        for (Item other : itemsInWorld) {
            if (other == newItem || other.isDead()) continue;

            ItemStack otherStack = other.getItemStack();
            if (!otherStack.getType().equals(type)) continue;
            if (!canSafelyMerge(newStack, otherStack)) continue;
            if (loc.distanceSquared(other.getLocation()) <= mergeRangeSq) {
                candidates.add(other);
            }
        }

        if (candidates.isEmpty()) return;

        for (Item candidate : candidates) {
            if (newItem.isDead() || candidate.isDead()) continue;
            performMerge(newItem, candidate);
            break;
        }

        if (!newItem.isDead() && newItem.getItemStack().getAmount() >= newItem.getItemStack().getMaxStackSize()) {
            readyItemsByWorld.get(world).remove(newItem);
        }
    }

    private void performMerge(Item target, Item source) {
        if (target.isDead() || source.isDead()) return;

        ItemStack targetStack = target.getItemStack();
        ItemStack sourceStack = source.getItemStack();

        int total = targetStack.getAmount() + sourceStack.getAmount();
        int max = targetStack.getMaxStackSize();

        if (total <= max) {
            targetStack.setAmount(total);
            source.remove();
            removeFromReady(source);
        } else {
            int overflow = total - max;
            targetStack.setAmount(max);
            sourceStack.setAmount(overflow);
            if (overflow <= 0) {
                source.remove();
                removeFromReady(source);
            }
        }
    }


    private boolean canSafelyMerge(ItemStack a, ItemStack b) {
        if (!a.getType().equals(b.getType())) return false;
        if (a.getAmount() >= a.getMaxStackSize()) return false;
        if (b.getAmount() <= 0) return false;

        if (a.hasItemMeta() != b.hasItemMeta()) return false;

        if (a.hasItemMeta()) {
            return Bukkit.getItemFactory().equals(a.getItemMeta(), b.getItemMeta());
        }

        return true;
    }

    private void removeFromReady(Item item) {
        if (item == null) return;
        World world = item.getWorld();
        Set<Item> set = readyItemsByWorld.get(world);
        if (set != null) {
            set.remove(item);
        }
    }

    /**
     * 插件禁用时清理
     */
    public void stop() {
        readyItemsByWorld.clear();
    }

    // start() 方法保留但为空（兼容性）
    public void start() {
        // 不再启动定时任务
    }
}