package pickup.feature.pickupmanager;

import org.bukkit.*;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.*;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import pickup.Main;
import pickup.feature.ItemSpatialIndex;
import pickup.feature.CustomItemMerger;

import java.util.List;
import java.util.Set;

/**
 * 物品生命周期管理器
 * 负责物品的标记、来源追踪、NBT数据管理等
 */
public class ItemLifecycleManager {
    private final Main plugin;
    private final ItemSpatialIndex itemIndex;

    // 持久化数据容器键
    public static final NamespacedKey SPAWN_TICK_KEY = new NamespacedKey("pickup", "spawn_tick");
    public static final NamespacedKey INITIALIZED_KEY = new NamespacedKey("pickup", "initialized");
    public static final NamespacedKey DROPPED_BY_KEY = new NamespacedKey("pickup", "dropped_by");
    public static final NamespacedKey SOURCE_KEY = new NamespacedKey("pickup", "source");

    // 物品来源类型枚举
    public enum ItemSourceType {
        PLAYER_DROP, NATURAL_DROP, INSTANT_PICKUP, UNKNOWN
    }

    // 需要保护nbt的方块物品
    private static final Set<Material> BLOCK_ITEMS_WITH_NBT = Set.of(
            Material.SHULKER_BOX, Material.WHITE_SHULKER_BOX, Material.ORANGE_SHULKER_BOX,
            Material.MAGENTA_SHULKER_BOX, Material.LIGHT_BLUE_SHULKER_BOX, Material.YELLOW_SHULKER_BOX,
            Material.LIME_SHULKER_BOX, Material.PINK_SHULKER_BOX, Material.GRAY_SHULKER_BOX,
            Material.LIGHT_GRAY_SHULKER_BOX, Material.CYAN_SHULKER_BOX, Material.PURPLE_SHULKER_BOX,
            Material.BLUE_SHULKER_BOX, Material.BROWN_SHULKER_BOX, Material.GREEN_SHULKER_BOX,
            Material.RED_SHULKER_BOX, Material.BLACK_SHULKER_BOX,
            Material.BEEHIVE, Material.BEE_NEST, Material.SPAWNER,
            Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK,
            Material.STRUCTURE_BLOCK, Material.BEACON
    );

    public ItemLifecycleManager(Main plugin,  ItemSpatialIndex itemIndex) {
        this.plugin = plugin;
        this.itemIndex = itemIndex;
    }

    // ====== 核心处理方法 ======
    public void handleItemSpawn(Item item) {
        // 延迟任务已经确保了物品有效性，所以这里不需要再检查
        PersistentDataContainer pdc = item.getPersistentDataContainer();

        // 防重复初始化检查
        if (pdc.has(INITIALIZED_KEY, PersistentDataType.BYTE)) {
            return;
        }

        // 设置来源标记（默认为自然掉落）
        pdc.set(SOURCE_KEY, PersistentDataType.STRING, ItemSourceType.NATURAL_DROP.name());

        // 设置生成时间
        pdc.set(SPAWN_TICK_KEY, PersistentDataType.LONG, item.getWorld().getGameTime());

        // 禁用原版拾取逻辑
        disableVanillaPickup(item);

        // 标记为已初始化
        pdc.set(INITIALIZED_KEY, PersistentDataType.BYTE, (byte) 1);

        // 通知物品合并器
        notifyMerger(item);
    }

    public void handlePlayerDrop(Item item, java.util.UUID playerId) {
        // 添加初始化检查
        PersistentDataContainer pdc = item.getPersistentDataContainer();
        if (pdc.has(INITIALIZED_KEY, PersistentDataType.BYTE)) {
            return; // 已经初始化过
        }

        markItemAsPlayerDrop(item, playerId);
        disableVanillaPickup(item);

        // 标记为已初始化
        pdc.set(INITIALIZED_KEY, PersistentDataType.BYTE, (byte) 1);

        itemIndex.registerItem(item);

        // 延迟通知合并器
        plugin.getServer().getRegionScheduler().runDelayed(plugin, item.getLocation(), task -> {
            if (!item.isValid() || item.isDead()) return;
            notifyMerger(item);
        }, 2L);
    }

    public void handleBlockDrop(Item item) {
        // 添加初始化检查
        PersistentDataContainer pdc = item.getPersistentDataContainer();
        if (pdc.has(INITIALIZED_KEY, PersistentDataType.BYTE)) {
            return; // 已经初始化过
        }

        markItemAsNaturalDrop(item);
        disableVanillaPickup(item);

        // 标记为已初始化
        pdc.set(INITIALIZED_KEY, PersistentDataType.BYTE, (byte) 1);

        itemIndex.registerItem(item);

        // 延迟通知合并器
        plugin.getServer().getRegionScheduler().runDelayed(plugin, item.getLocation(), task -> {
            if (!item.isValid() || item.isDead()) return;
            notifyMerger(item);
        }, 2L);
    }

    public void handleEntityDeath(EntityDeathEvent event) {
        for (ItemStack stack : event.getDrops()) {
            if (stack == null || stack.getType().isAir()) continue;
            markItemStackAsNaturalDrop(stack);
        }
    }

    // ====== 标记方法 ======

    public void markItemAsPlayerDrop(Item item, java.util.UUID playerId) {
        PersistentDataContainer pdc = item.getPersistentDataContainer();
        pdc.set(SPAWN_TICK_KEY, PersistentDataType.LONG, item.getWorld().getGameTime());
        pdc.set(DROPPED_BY_KEY, PersistentDataType.STRING, playerId.toString());
        pdc.set(SOURCE_KEY, PersistentDataType.STRING, ItemSourceType.PLAYER_DROP.name());
    }

    public void markItemAsNaturalDrop(Item item) {
        PersistentDataContainer pdc = item.getPersistentDataContainer();
        pdc.set(SPAWN_TICK_KEY, PersistentDataType.LONG, item.getWorld().getGameTime());
        pdc.set(SOURCE_KEY, PersistentDataType.STRING, ItemSourceType.NATURAL_DROP.name());
    }

    public void markItemStackAsNaturalDrop(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return;
        stack.editMeta(meta -> meta.getPersistentDataContainer().set(
                SOURCE_KEY,
                PersistentDataType.STRING,
                ItemSourceType.NATURAL_DROP.name()
        ));
    }

    // ====== 工具方法 ======

    /**
     * 清理单个物品
     */
    private void cleanupSingleItem(Item item) {
        if (item == null || !item.isValid() || item.isDead()) {
            return;
        }
        try {
            item.setPickupDelay(0);

            PersistentDataContainer pdc = item.getPersistentDataContainer();
            pdc.remove(SPAWN_TICK_KEY);
            pdc.remove(DROPPED_BY_KEY);
            pdc.remove(SOURCE_KEY);
            pdc.remove(INITIALIZED_KEY);

            ItemStack stack = item.getItemStack();
            if (!stack.getType().isAir()) {
                ItemStack cleanStack = createCleanStack(stack);
                item.setItemStack(cleanStack);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("清理物品失败: " + e.getMessage());
        }
    }

    public boolean hasPickupMark(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.has(SOURCE_KEY, PersistentDataType.STRING) ||
                pdc.has(SPAWN_TICK_KEY, PersistentDataType.LONG) ||
                pdc.has(DROPPED_BY_KEY, PersistentDataType.STRING);
    }

    /**
     * 强制清理指定世界中的所有物品
     */
    public void forceCleanupWorld(World world) {
        if (world == null) return;

        for (Item item : world.getEntitiesByClass(Item.class)) {
            cleanupSingleItem(item);
        }

    }

    public ItemStack createCleanStack(ItemStack original) {
        if (original == null || original.getType().isAir()) {
            return original;
        }

        Material type = original.getType();

        // 保护带状态的方块物品
        if (BLOCK_ITEMS_WITH_NBT.contains(type)) {
            ItemStack clean = original.clone();
            ItemMeta meta = clean.getItemMeta();
            if (meta != null) {
                PersistentDataContainer pdc = meta.getPersistentDataContainer();
                pdc.remove(SOURCE_KEY);
                pdc.remove(SPAWN_TICK_KEY);
                pdc.remove(DROPPED_BY_KEY);
                clean.setItemMeta(meta);
            }
            return clean;
        }

        // 普通物品处理
        ItemStack clean = original.clone();
        ItemMeta meta = clean.getItemMeta();

        if (meta == null) {
            return new ItemStack(type, original.getAmount());
        }

        // 清除插件标记
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.remove(SOURCE_KEY);
        pdc.remove(SPAWN_TICK_KEY);
        pdc.remove(DROPPED_BY_KEY);

        if (hasMeaningfulData(meta)) {
            clean.setItemMeta(meta);
            return clean;
        } else {
            return new ItemStack(type, original.getAmount());
        }
    }

    @SuppressWarnings("deprecation")
    public boolean hasMeaningfulData(ItemMeta meta) {
        if (meta == null) return false;

        if (meta.hasDisplayName()) return true;

        List<String> lore = meta.getLore();
        if (lore != null && !lore.isEmpty()) return true;

        if (!meta.getEnchants().isEmpty()) return true;

        if (meta.hasCustomModelData()) return true;

        return switch (meta) {
            case Damageable damageable when damageable.getDamage() > 0 -> true;
            case PotionMeta potionMeta when potionMeta.getBasePotionType() != null -> true;
            case EnchantmentStorageMeta esm when !esm.getStoredEnchants().isEmpty() -> true;
            default -> !meta.getPersistentDataContainer().isEmpty();
        };

    }

    // ====== 私有方法 ======

    private void disableVanillaPickup(Item item) {
        try {
            item.setPickupDelay(6000);

            if (plugin.getServer().getClass().getName().contains("folia")) {
                item.setCanPlayerPickup(false);
            }

        } catch (Exception e) {
            try {
                Object handle = item.getClass().getMethod("getHandle").invoke(item);
                java.lang.reflect.Field field = handle.getClass().getDeclaredField("pickupDelay");
                field.setAccessible(true);
                field.set(handle, 6000);
            } catch (Exception ex) {
                plugin.getLogger().warning("无法完全禁用原版拾取逻辑: " +
                        item.getItemStack().getType() + " - 插件仍会尝试处理拾取");
            }
        }
    }

    private void notifyMerger(Item item) {
        CustomItemMerger merger = plugin.getItemMerger();
        if (merger != null) {
            merger.notifyItemReady(item);
        }
    }
}