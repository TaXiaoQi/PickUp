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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
    public static final NamespacedKey DROPPED_BY_KEY = new NamespacedKey("pickup", "dropped_by");
    public static final NamespacedKey SOURCE_KEY = new NamespacedKey("pickup", "source");

    // 物品来源类型枚举
    public enum ItemSourceType {
        PLAYER_DROP, NATURAL_DROP, INSTANT_PICKUP, UNKNOWN
    }

    // 需要保护NBT的方块物品
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
        PersistentDataContainer pdc = item.getPersistentDataContainer();

        // 设置来源标记
        String existingSource = pdc.get(SOURCE_KEY, PersistentDataType.STRING);
        if (existingSource == null) {
            pdc.set(SOURCE_KEY, PersistentDataType.STRING, ItemSourceType.NATURAL_DROP.name());
        }

        // 设置生成时间
        if (!pdc.has(SPAWN_TICK_KEY, PersistentDataType.LONG)) {
            pdc.set(SPAWN_TICK_KEY, PersistentDataType.LONG, item.getWorld().getGameTime());
        }

        // 禁用原版拾取逻辑
        disableVanillaPickup(item);

        // 通知物品合并器
        notifyMerger(item);

        // 注册到索引
        itemIndex.registerItem(item);
    }

    public void handlePlayerDrop(Item item, java.util.UUID playerId) {
        markItemAsPlayerDrop(item, playerId);
        disableVanillaPickup(item);
        notifyMerger(item);
        itemIndex.registerItem(item);
    }

    public void handleBlockDrop(Item item) {
        markItemAsNaturalDrop(item);
        disableVanillaPickup(item);
        notifyMerger(item);
        itemIndex.registerItem(item);
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

    public boolean hasPickupMark(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.has(SOURCE_KEY, PersistentDataType.STRING) ||
                pdc.has(SPAWN_TICK_KEY, PersistentDataType.LONG) ||
                pdc.has(DROPPED_BY_KEY, PersistentDataType.STRING);
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
            Object nmsItem = getGetHandleMethod().invoke(item);
            Field delayField = getItemPickupDelayField();
            delayField.set(nmsItem, 6000);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to disable vanilla pickup for item: " +
                    item.getItemStack().getType());
        }
    }

    private void notifyMerger(Item item) {
        CustomItemMerger merger = plugin.getItemMerger();
        if (merger != null) {
            merger.notifyItemReady(item);
        }
    }

    // ====== 反射工具 ======

    private static volatile Field cachedPickupDelayField = null;
    private static volatile Method cachedGetHandleMethod = null;

    public static Field getItemPickupDelayField() {
        if (cachedPickupDelayField != null) {
            return cachedPickupDelayField;
        }

        Class<?> nmsItemClass;
        try {
            nmsItemClass = Class.forName("net.minecraft.world.entity.item.ItemEntity");
        } catch (ClassNotFoundException e1) {
            try {
                String version = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
                nmsItemClass = Class.forName("net.minecraft.server." + version + ".EntityItem");
            } catch (Exception e2) {
                throw new RuntimeException("Unsupported server version for Item pickupDelay reflection.", e2);
            }
        }

        String[] candidates = {"pickupDelay", "bK", "c", "d", "e"};
        for (String fieldName : candidates) {
            try {
                Field field = nmsItemClass.getDeclaredField(fieldName);
                if (field.getType() == int.class) {
                    field.setAccessible(true);
                    cachedPickupDelayField = field;
                    return field;
                }
            } catch (NoSuchFieldException ignored) {}
        }

        throw new RuntimeException("Could not find pickupDelay field in " + nmsItemClass.getName());
    }

    public static Method getGetHandleMethod() throws Exception {
        if (cachedGetHandleMethod != null) {
            return cachedGetHandleMethod;
        }

        String version = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
        Class<?> craftEntityClass = Class.forName("org.bukkit.craftbukkit." + version + ".entity.CraftEntity");

        cachedGetHandleMethod = craftEntityClass.getMethod("getHandle");
        cachedGetHandleMethod.setAccessible(true);
        return cachedGetHandleMethod;
    }
}