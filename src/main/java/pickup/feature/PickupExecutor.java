package pickup.feature;

import org.bukkit.*;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import pickup.Main;
import pickup.config.PickupConfig;
import pickup.event.PickupEventHandler;
import pickup.tool.ArmorType;
import pickup.tool.PacketUtils;

import java.util.HashMap;
import java.util.Set;
import java.util.UUID;

/**
 * 拾取执行器
 */
public class PickupExecutor {
    private final Main plugin;
    private final PickupConfig config;
    private final ItemSpatialIndex itemIndex;
    // 移除 ItemLifecycleManager 依赖

    public PickupExecutor(Main plugin, PickupConfig config, ItemSpatialIndex itemIndex) {
        this.plugin = plugin;
        this.config = config;
        this.itemIndex = itemIndex;
        // 移除 lifecycleManager 参数
    }

    // ====== 核心拾取逻辑 ======

    /**
     * 执行玩家拾取物品
     */
    public void performPlayerPickup(Player player, Item item) {
        if (isPickupBlocked(player, item)) {
            return;
        }

        ItemStack originalStack = item.getItemStack();
        if (originalStack.getAmount() <= 0) return;
        int amount = originalStack.getAmount();

        // 创建干净的物品副本
        ItemStack cleanStack = createCleanStack(originalStack);
        int remainingAmount = cleanStack.getAmount();

        PlayerInventory inv = player.getInventory();
        boolean anyPickedUp = false;

        // 第一阶段：合并
        remainingAmount = mergeWithExistingSlots(player, cleanStack, remainingAmount);
        if (remainingAmount < cleanStack.getAmount()) {
            anyPickedUp = true;
        }

        // 第二阶段：放置到空槽
        if (remainingAmount > 0) {
            int placed = placeInEmptySlots(inv, cleanStack, remainingAmount);
            remainingAmount -= placed;
            if (placed > 0) anyPickedUp = true;
        }

        // 第三阶段：放置到副手（如果配置允许）
        if (remainingAmount > 0) {
            int placed = placeInOffhand(inv, cleanStack, remainingAmount);
            remainingAmount -= placed;
            if (placed > 0) anyPickedUp = true;
        }

        // 最终处理
        if (anyPickedUp) {
            handlePickupSuccess(player, item, amount, cleanStack, remainingAmount);
        }
    }

    /**
     * 执行生物拾取物品（支持自动装备）
     */
    public void performLivingEntityPickup(LivingEntity entity, Item item) {
        if (!item.isValid() || item.isDead()) return;
        if (isPickupBlocked(entity, item)) return;

        ItemStack stack = item.getItemStack().clone();
        if (stack.getAmount() <= 0 || stack.getType() == Material.AIR) {
            item.remove();
            itemIndex.unregisterItem(item);
            return;
        }

        EntityEquipment equip = entity.getEquipment();
        if (equip == null) return;

        boolean pickedUp = tryAutoEquip(equip, stack);

        // 如果不能装备，尝试放入背包
        if (!pickedUp && entity instanceof InventoryHolder holder) {
            HashMap<Integer, ItemStack> leftover = holder.getInventory().addItem(stack);
            pickedUp = leftover.isEmpty();
        }

        if (pickedUp) {
            World world = entity.getWorld();
            Location loc = item.getLocation();
            world.spawnParticle(Particle.ITEM, loc, 3, 0.1, 0.1, 0.1, 0.0,
                    new ItemStack(stack.getType(), 1));
            item.remove();
            itemIndex.unregisterItem(item);
        }
    }

    // ====== 条件检查方法 ======

    public boolean isPickupBlocked(LivingEntity entity, Item item) {
        // 返回 true 表示不能拾取，false 表示可以拾取
        return !canPickupNow(entity, item, false);
    }

    public boolean canPickupNow(LivingEntity entity, Item item, boolean skipEntitySpecificChecks) {
        // 使用 ItemSpatialIndex 的元数据
        ItemSpatialIndex.ItemMetadata meta = itemIndex.getItemMetadata(item);

        if (meta == null) {
            // 如果没有元数据，使用默认检查
            return entity != null &&
                    item.getLocation().distanceSquared(entity.getLocation()) <=
                            config.getPickupRange() * config.getPickupRange();
        }

        long currentTick = item.getWorld().getGameTime();
        long spawnTick = meta.spawnTick;
        ItemSpatialIndex.ItemSourceType source = meta.source;
        UUID droppedBy = meta.droppedBy;

        // 检查延迟
        long requiredDelay = getRequiredDelay(source);
        if (currentTick - spawnTick < requiredDelay) {
            return false;
        }

        // 检查自我免疫
        if (!skipEntitySpecificChecks && entity instanceof Player player &&
                source == ItemSpatialIndex.ItemSourceType.PLAYER_DROP) {
            if (droppedBy != null && droppedBy.equals(player.getUniqueId())) {
                if (currentTick - spawnTick < config.getSelfImmuneTicks()) {
                    return false;
                }
            }
        }

        // 检查距离
        if (entity != null) {
            double pickupRangeSq = config.getPickupRange() * config.getPickupRange();
            return item.getLocation().distanceSquared(entity.getLocation()) <= pickupRangeSq;
        }

        return false;
    }

    // ====== 私有辅助方法 ======

    private int mergeWithExistingSlots(Player player, ItemStack cleanStack, int remainingAmount) {
        PlayerInventory inv = player.getInventory();

        // 1. 副手合并
        if (remainingAmount > 0) {
            ItemStack offhand = inv.getItemInOffHand();
            if (offhand.isSimilar(cleanStack) && offhand.getAmount() < offhand.getMaxStackSize()) {
                int space = offhand.getMaxStackSize() - offhand.getAmount();
                if (space > 0) {
                    int toAdd = Math.min(space, remainingAmount);
                    ItemStack newOffhand = cleanAndPreserveMeta(offhand, offhand.getAmount() + toAdd);
                    inv.setItemInOffHand(newOffhand);
                    remainingAmount -= toAdd;
                }
            }
        }

        // 2. 光标合并
        if (remainingAmount > 0) {
            ItemStack cursor = player.getItemOnCursor();
            if (!cursor.getType().isAir() && cursor.isSimilar(cleanStack)) {
                int space = cursor.getMaxStackSize() - cursor.getAmount();
                if (space > 0) {
                    int toAdd = Math.min(space, remainingAmount);
                    ItemStack newCursor = cleanAndPreserveMeta(cursor, cursor.getAmount() + toAdd);
                    player.setItemOnCursor(newCursor);
                    remainingAmount -= toAdd;
                }
            }
        }

        // 3. 背包合并
        if (remainingAmount > 0) {
            for (int slot = 0; slot < 36; slot++) {
                if (remainingAmount == 0) break;
                ItemStack existing = inv.getItem(slot);
                if (existing != null && existing.isSimilar(cleanStack)) {
                    int space = existing.getMaxStackSize() - existing.getAmount();
                    if (space > 0) {
                        int toAdd = Math.min(space, remainingAmount);
                        ItemStack newExisting = cleanAndPreserveMeta(existing, existing.getAmount() + toAdd);
                        inv.setItem(slot, newExisting);
                        remainingAmount -= toAdd;
                    }
                }
            }
        }

        return remainingAmount;
    }

    private int placeInEmptySlots(PlayerInventory inv, ItemStack cleanStack, int remainingAmount) {
        int placed = 0;

        for (int slot = 0; slot < 36; slot++) {
            if (remainingAmount <= 0) break;

            if (inv.getItem(slot) == null) {
                int toPlace = Math.min(remainingAmount, cleanStack.getType().getMaxStackSize());
                ItemStack newStack = cleanStack.clone();
                newStack.setAmount(toPlace);
                inv.setItem(slot, newStack);
                remainingAmount -= toPlace;
                placed += toPlace;
            }
        }

        return placed;
    }

    private int placeInOffhand(PlayerInventory inv, ItemStack cleanStack, int remainingAmount) {
        ItemStack offhand = inv.getItemInOffHand();

        if (offhand.getType() == Material.AIR && config.isOffhandPickupEnabled()) {
            int toPlace = Math.min(remainingAmount, cleanStack.getType().getMaxStackSize());
            ItemStack newStack = cleanStack.clone();
            newStack.setAmount(toPlace);
            inv.setItemInOffHand(newStack);
            return toPlace;
        }

        return 0;
    }

    private boolean tryAutoEquip(EntityEquipment equip, ItemStack stack) {
        Material type = stack.getType();

        if (ArmorType.isHelmet(type)) {
            if (isBetterEquipment(stack, equip.getHelmet())) {
                equip.setHelmet(stack);
                return true;
            }
        } else if (ArmorType.isChestplate(type)) {
            if (isBetterEquipment(stack, equip.getChestplate())) {
                equip.setChestplate(stack);
                return true;
            }
        } else if (ArmorType.isLeggings(type)) {
            if (isBetterEquipment(stack, equip.getLeggings())) {
                equip.setLeggings(stack);
                return true;
            }
        } else if (ArmorType.isBoots(type)) {
            if (isBetterEquipment(stack, equip.getBoots())) {
                equip.setBoots(stack);
                return true;
            }
        } else if (isWeaponOrTool(type)) {
            if (isBetterEquipment(stack, equip.getItemInMainHand())) {
                equip.setItemInMainHand(stack);
                return true;
            }
        }

        return false;
    }

    private void handlePickupSuccess(Player player, Item item, int originalAmount,
                                     ItemStack cleanStack, int remainingAmount) {
        World world = player.getWorld();
        Location loc = item.getLocation();

        // 发送拾取动画
        PacketUtils.sendPickupAnimation(plugin, player, item, originalAmount);
        world.playSound(loc, Sound.ENTITY_ITEM_PICKUP, 0.2f, (float) (0.8 + Math.random() * 0.4));

        if (remainingAmount > 0) {
            // 还有剩余，更新物品实体
            ItemStack remainingStack = cleanStack.clone();
            remainingStack.setAmount(remainingAmount);
            item.setItemStack(remainingStack);
        } else {
            // 全部拾取完成，移除物品实体
            item.remove();
            itemIndex.unregisterItem(item);

            // 同时从调度器移除（如果物品驱动模式启用）
            if (config.isItemDrivenEnabled()) {
                // 通过 PickupEventHandler 获取调度器
                PickupEventHandler pickupHandler = plugin.getPickupEventHandler();
                if (pickupHandler != null) {
                    ItemDrivenPickupScheduler scheduler = pickupHandler.getItemScheduler();
                    if (scheduler != null) {
                        scheduler.unregisterItem(item);
                    }
                }
            }
        }
    }

    // ====== 工具方法 ======

    /**
     * 创建干净的物品堆栈（移除插件添加的元数据）
     */
    public ItemStack createCleanStack(ItemStack original) {
        if (original == null || original.getType().isAir()) {
            return new ItemStack(Material.AIR);
        }

        // 检查是否需要保护 NBT
        Material type = original.getType();
        if (BLOCK_ITEMS_WITH_NBT.contains(type)) {
            // 对于需要保护 NBT 的方块，返回原样副本
            return original.clone();
        }

        ItemStack clean = original.clone();

        // 移除插件添加的持久化数据
        if (clean.hasItemMeta()) {
            org.bukkit.inventory.meta.ItemMeta meta = clean.getItemMeta().clone();
            PersistentDataContainer pdc = meta.getPersistentDataContainer();

            // 移除插件添加的键
            for (NamespacedKey key : pdc.getKeys()) {
                if (key.getNamespace().equals("pickup")) {
                    pdc.remove(key);
                }
            }

            clean.setItemMeta(meta);
        }

        return clean;
    }

    private ItemStack cleanAndPreserveMeta(ItemStack original, int newAmount) {
        if (original == null || original.getType().isAir()) {
            return new ItemStack(Material.AIR);
        }
        ItemStack clean = original.clone();
        clean.setAmount(newAmount);
        if (clean.hasItemMeta()) {
            org.bukkit.inventory.meta.ItemMeta meta = clean.getItemMeta().clone();
            PersistentDataContainer pdc = meta.getPersistentDataContainer();

            // 移除插件添加的持久化数据
            for (NamespacedKey key : pdc.getKeys()) {
                if (key.getNamespace().equals("pickup")) {
                    pdc.remove(key);
                }
            }

            clean.setItemMeta(meta);
        }
        return clean;
    }

    private boolean isBetterEquipment(ItemStack newItem, ItemStack current) {
        if (current == null || current.getType() == Material.AIR) return true;
        if (newItem == null || newItem.getType() == Material.AIR) return false;

        int newScore = getEquipmentScore(newItem);
        int currentScore = getEquipmentScore(current);

        if (newScore != currentScore) return newScore > currentScore;

        boolean newHasEnchants = !newItem.getEnchantments().isEmpty();
        boolean currentHasEnchants = !current.getEnchantments().isEmpty();
        return newHasEnchants && !currentHasEnchants;
    }

    private int getEquipmentScore(ItemStack stack) {
        Material mat = stack.getType();
        String name = mat.name();

        if (name.contains("NETHERITE")) return 5;
        if (name.contains("DIAMOND")) return 4;
        if (name.contains("GOLD")) return 3;
        if (name.contains("IRON")) return 3;
        if (name.contains("STONE")) return 2;
        if (name.contains("WOOD") || name.contains("LEATHER")) return 1;

        if (name.equals("CHAINMAIL_CHESTPLATE") ||
                name.equals("CHAINMAIL_HELMET") ||
                name.equals("CHAINMAIL_LEGGINGS") ||
                name.equals("CHAINMAIL_BOOTS")) return 2;

        return 0;
    }

    private boolean isWeaponOrTool(Material mat) {
        String name = mat.name();
        return name.endsWith("_SWORD") ||
                name.endsWith("_AXE") ||
                name.endsWith("_PICKAXE") ||
                name.endsWith("_SHOVEL") ||
                name.endsWith("_HOE") ||
                name.equals("BOW") ||
                name.equals("CROSSBOW") ||
                name.equals("TRIDENT") ||
                name.equals("FISHING_ROD") ||
                name.equals("SHEARS") ||
                name.equals("FLINT_AND_STEEL") ||
                name.equals("CARROT_ON_A_STICK") ||
                name.equals("WARPED_FUNGUS_ON_A_STICK");
    }

    private long getRequiredDelay(ItemSpatialIndex.ItemSourceType source) {
        return switch (source) {
            case PLAYER_DROP -> config.getPlayerDropDelayTicks();
            case NATURAL_DROP -> config.getNaturalDropDelayTicks();
            default -> config.getInstantPickupDelayTicks();
        };
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
}