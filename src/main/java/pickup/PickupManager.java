
package pickup;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.inventory.meta.Damageable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 拾取管理器 - 核心逻辑处理类
 * 负责实现双驱动模式（玩家驱动+物品驱动）的物品自动拾取系统
 */
public class PickupManager implements PickupConfig.ConfigChangeListener {
    // 添加统一索引
    private final ItemSpatialIndex itemIndex;

    // 插件主类引用
    private final PickUp plugin;
    private final PickupConfig config; // 添加 config 字段

    // 配置参数（从 config 直接加载）
    private double pickupRangeSq;                // 拾取范围的平方（用于距离比较优化）
    private int playerDropDelayTicks;           // 玩家丢弃物品的拾取延迟（tick）
    private int naturalDropDelayTicks;          // 自然掉落物品的拾取延迟（tick）
    private int instantPickupDelayTicks;        // 立即拾取物品的延迟（tick）
    private int selfImmuneTicks;                // 自身掉落物免疫时间（tick）
    private int activeDetectionTicks;           // 物品活跃检测时间（tick）

    // 管理器运行状态
    private boolean active = false;

    // 持久化数据容器键（用于在物品NBT中存储元数据）
    private static final NamespacedKey SPAWN_TICK_KEY = new NamespacedKey("pickup", "spawn_tick");
    private static final NamespacedKey DROPPED_BY_KEY = new NamespacedKey("pickup", "dropped_by");
    private static final NamespacedKey SOURCE_KEY = new NamespacedKey("pickup", "source");

    // 玩家驱动模式相关
    private final Set<UUID> activePlayers = ConcurrentHashMap.newKeySet(); // 活跃玩家集合（线程安全）
    private BukkitRunnable activePlayerUpdater = null; // 玩家更新定时任务


    // 物品驱动模式相关
    private BukkitRunnable itemDetectionTask = null; // 物品检测定时任务

    /**
     * 构造函数（带 config 参数）
     * @param plugin 插件主类实例
     * @param config 配置管理器
     */
    public PickupManager(PickUp plugin, PickupConfig config, ItemSpatialIndex spatialIndex) {
        this.plugin = plugin;
        this.config = config;

        // 初始化物品索引
        this.itemIndex = spatialIndex;

        // 注册为配置变更监听器
        this.config.addChangeListener(this);

        // 加载配置
        loadConfig();
    }

    /**
     * 获取自定义物品合并器实例
     * @return 物品合并器，可能为null
     */
    private CustomItemMerger getCustomItemMerger() {
        return plugin.getItemMerger();
    }

    /**
     * 从配置管理器加载配置参数
     */
    public void loadConfig() {
        plugin.getLogger().info("PickupManager配置加载：");
        plugin.getLogger().info("- pickupRange: " + config.getPickupRange());
        plugin.getLogger().info("- playerDropDelayTicks: " + config.getPlayerDropDelayTicks());
        plugin.getLogger().info("- naturalDropDelayTicks: " + config.getNaturalDropDelayTicks());
        plugin.getLogger().info("- instantPickupDelayTicks: " + config.getInstantPickupDelayTicks());
        plugin.getLogger().info("- selfImmuneTicks: " + config.getSelfImmuneTicks());
        plugin.getLogger().info("- activeDetectionTicks: " + config.getActiveDetectionTicks());

        // 计算平方值
        this.pickupRangeSq = config.getPickupRange() * config.getPickupRange();
        this.playerDropDelayTicks = config.getPlayerDropDelayTicks();
        this.naturalDropDelayTicks = config.getNaturalDropDelayTicks();
        this.instantPickupDelayTicks = config.getInstantPickupDelayTicks();
        this.selfImmuneTicks = config.getSelfImmuneTicks();
        this.activeDetectionTicks = config.getActiveDetectionTicks();

        plugin.getLogger().info("- pickupRangeSq: " + pickupRangeSq);
    }

    /**
     * 配置变更监听器实现
     * 当配置通过命令动态修改时，立即更新内存中的值
     */
    @Override
    public void onConfigChanged(String key, Object value) {
        plugin.getLogger().info("配置变更: " + key + " = " + value);

        switch (key) {
            case "pickup.range":
                this.pickupRangeSq = (double) value * (double) value;
                plugin.getLogger().info("更新拾取范围平方值: " + pickupRangeSq);
                break;
            case "pickup.delays.player-drop":
                this.playerDropDelayTicks = (int) value;
                break;
            case "pickup.delays.natural-drop":
                this.naturalDropDelayTicks = (int) value;
                break;
            case "pickup.delays.instant-pickup":
                this.instantPickupDelayTicks = (int) value;
                break;
            case "pickup.self-immune-ticks":
                this.selfImmuneTicks = (int) value;
                break;
            case "mode.item-active-duration":
                this.activeDetectionTicks = (int) value;
                break;
            case "__RELOAD_ALL__":
                // 当配置完全重载时，重新加载所有配置
                loadConfig();
                break;
        }
    }

    // ====== 公共入口：由 PickupEvent 调用 ======

    /**
     * 处理物品生成事件（所有类型的物品掉落）
     * @param event 物品生成事件
     */
    public void handleItemSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        PersistentDataContainer pdc = item.getPersistentDataContainer();

        // ✅ 关键修复：直接从 Item Entity 的 PDC 读取已有的来源标记
        String existingSource = pdc.get(SOURCE_KEY, PersistentDataType.STRING);

        // 如果没有来源标记（例如自然掉落、方块破坏等），默认设为 NATURAL_DROP
        if (existingSource == null) {
            pdc.set(SOURCE_KEY, PersistentDataType.STRING, ItemSourceType.NATURAL_DROP.name());
        }

        // 使用原版 tick 计数
        if (!pdc.has(SPAWN_TICK_KEY, PersistentDataType.LONG)) {
            pdc.set(SPAWN_TICK_KEY, PersistentDataType.LONG, item.getWorld().getGameTime());
        }

        // 禁用原版拾取逻辑（通过反射设置pickupDelay）
        disableVanillaPickup(item);

        // 通知物品合并器有新物品可合并
        notifyMerger(item);

    }

    /**
     * 处理玩家丢弃物品事件
     * @param event 玩家丢弃物品事件
     */
    public void handlePlayerDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        Item item = event.getItemDrop();

        // 标记为玩家丢弃物品
        markItemAsPlayerDrop(item, player.getUniqueId());

        // 禁用原版拾取逻辑
        disableVanillaPickup(item);

        // 通知物品合并器
        notifyMerger(item);

        // 将物品注册到索引中
        itemIndex.registerItem(item);
    }

    /**
     * 处理方块掉落物品事件
     * @param event 方块掉落物品事件
     */
    public void handleBlockDrop(BlockDropItemEvent event) {
        for (Item item : event.getItems()) {
            // 标记为自然掉落物品
            markItemAsNaturalDrop(item);

            // 禁用原版拾取逻辑
            disableVanillaPickup(item);

            // 通知物品合并器
            notifyMerger(item);

            // 将物品注册到索引中
            itemIndex.registerItem(item);
        }
    }

    /**
     * 处理实体死亡事件（怪物/动物死亡掉落）
     * @param event 实体死亡事件
     */
    public void handleEntityDeath(EntityDeathEvent event) {
        for (ItemStack stack : event.getDrops()) {
            if (stack == null || stack.getType().isAir()) continue;
            // 直接标记为 NATURAL_DROP（与方块掉落一致）
            markItemStackAsNaturalDrop(stack);
        }
    }

    /**
     * 标记物品堆栈为自然掉落（在物品实体生成之前）
     * @param stack 物品堆栈
     */
    private void markItemStackAsNaturalDrop(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return;

        // 编辑物品元数据，添加来源标记
        stack.editMeta(meta -> meta.getPersistentDataContainer().set(
                SOURCE_KEY,
                PersistentDataType.STRING,
                ItemSourceType.NATURAL_DROP.name()
        ));
    }

    /**
     * 玩家驱动的拾取扫描（由玩家移动事件触发）
     * @param player 尝试拾取物品的玩家
     */
    public void tryPickup(Player player) {
        // 旁观者模式不拾取物品
        if (player.getGameMode() == GameMode.SPECTATOR) return;

        // 使用索引获取附近物品，而不是world.getNearbyEntities()
        Set<Item> nearbyItems = itemIndex.getNearbyItems(
                player.getLocation(), Math.sqrt(pickupRangeSq));

        // 对每个物品尝试拾取
        for (Item item : nearbyItems) {
            if (canPickupNow(player, item)) {
                performPickup(player, item);
            }
        }
    }

    // ====== 内部逻辑 ======

    /**
     * 标记物品为玩家丢弃
     * @param item 物品实体
     * @param playerId 丢弃玩家的UUID
     */
    private void markItemAsPlayerDrop(Item item, UUID playerId) {
        PersistentDataContainer pdc = item.getPersistentDataContainer();
        pdc.set(SPAWN_TICK_KEY, PersistentDataType.LONG, item.getWorld().getGameTime());
        pdc.set(DROPPED_BY_KEY, PersistentDataType.STRING, playerId.toString());
        pdc.set(SOURCE_KEY, PersistentDataType.STRING, ItemSourceType.PLAYER_DROP.name());
    }

    /**
     * 标记物品为自然掉落
     * @param item 物品实体
     */
    private void markItemAsNaturalDrop(Item item) {
        PersistentDataContainer pdc = item.getPersistentDataContainer();
        pdc.set(SPAWN_TICK_KEY, PersistentDataType.LONG, item.getWorld().getGameTime());
        pdc.set(SOURCE_KEY, PersistentDataType.STRING, ItemSourceType.NATURAL_DROP.name());
    }


    /**
     * 禁用原版物品拾取逻辑（通过反射设置pickupDelay为最大值）
     * @param item 物品实体
     */
    private void disableVanillaPickup(Item item) {
        try {
            // 使用反射获取NMS ItemEntity对象
            Object nmsItem = getGetHandleMethod().invoke(item);
            // 获取pickupDelay字段并设置为最大值（禁止原版拾取）
            Field delayField = getItemPickupDelayField();
            delayField.set(nmsItem, 6000);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to disable vanilla pickup for item: " +
                    item.getItemStack().getType());
        }
    }

    /**
     * 通知物品合并器有新物品可合并
     * @param item 新生成的物品
     */
    private void notifyMerger(Item item) {
        CustomItemMerger merger = getCustomItemMerger();
        if (merger != null) {
            merger.notifyItemReady(item);
        }
    }


    /**
     * 检查指定世界中是否存在可拾取的物品。
     * @param world 要检查的世界
     * @return 如果存在则返回true，否则false
     */
    public boolean hasPickupableItems(World world) {
        return itemIndex.hasItemsInWorld(world);
    }

    /**
     * 检查 LivingEntity（玩家或生物）是否可以拾取指定物品
     * @param entity 尝试拾取的实体
     * @param item 要拾取的物品
     * @return 是否可以拾取
     */
    private boolean canPickupNow(LivingEntity entity, Item item) {
        long currentTime = item.getWorld().getGameTime();// 当前游戏时间（tick）
        PersistentDataContainer pdc = item.getPersistentDataContainer();

        // 获取物品的生成时间和来源类型
        Long spawnTick = pdc.get(SPAWN_TICK_KEY, PersistentDataType.LONG);
        String sourceStr = pdc.get(SOURCE_KEY, PersistentDataType.STRING);
        ItemSourceType source = parseSource(sourceStr); // 解析来源类型

        // 如果未记录生成时间，使用当前时间作为默认值
        if (spawnTick == null) {
            spawnTick = currentTime;
        }

        // 根据物品来源类型获取要求的延迟时间
        long requiredDelay = getRequiredDelay(source);

        // 检查是否满足延迟要求（冷却时间）
        if (currentTime - spawnTick < requiredDelay) {
            return false;
        }

        // 特殊处理：玩家自己丢弃的物品有自身免疫时间（仅对玩家有效）
        if (entity instanceof Player player && source == ItemSourceType.PLAYER_DROP) {
            String droppedByStr = pdc.get(DROPPED_BY_KEY, PersistentDataType.STRING);
            if (droppedByStr != null) {
                try {
                    UUID droppedBy = UUID.fromString(droppedByStr);
                    if (droppedBy.equals(player.getUniqueId())) {
                        // 如果拾取者就是丢弃者，检查是否还在自身免疫期内
                        if (currentTime - spawnTick < selfImmuneTicks) {
                            return false;
                        }
                    }
                } catch (IllegalArgumentException ignored) {
                    // UUID格式无效，忽略
                }
            }
        }

        // 检查距离是否在拾取范围内（使用预先计算的平方值优化性能）
        return item.getLocation().distanceSquared(entity.getLocation()) <= pickupRangeSq;
    }

    // 容器拾取时检测是否是我们的物品
    public boolean hasPickupMark(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.has(SOURCE_KEY, PersistentDataType.STRING) ||
                pdc.has(SPAWN_TICK_KEY, PersistentDataType.LONG) ||
                pdc.has(DROPPED_BY_KEY, PersistentDataType.STRING);
    }

    @SuppressWarnings("deprecation") // 兼容 1.20.4 及以下；1.20.5+ 虽弃用但可用
    private boolean hasMeaningfulData(ItemMeta meta) {
        if (meta == null) return false;

        if (meta.hasDisplayName()) {
            return true;
        }

        List<String> lore = meta.getLore();
        if (lore != null && !lore.isEmpty()) {
            return true;
        }

        if (!meta.getEnchants().isEmpty()) {
            return true;
        }

        if (meta.hasCustomModelData()) {
            return true;
        }

        if (meta instanceof Damageable damageable) {
            if (damageable.getDamage() > 0) {
                return true;
            }
        }

        if (meta instanceof PotionMeta potionMeta) {
            if (potionMeta.getBasePotionType() != null) {
                return true;
            }
        }

        if (meta instanceof EnchantmentStorageMeta esm) {
            if (!esm.getStoredEnchants().isEmpty()) {
                return true;
            }
        }

        return !meta.getPersistentDataContainer().isEmpty();
    }

    ItemStack createCleanStack(ItemStack original) {
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

    private static final Set<Material> BLOCK_ITEMS_WITH_NBT = Set.of(
            // 潜影盒（所有颜色）
            Material.SHULKER_BOX,
            Material.WHITE_SHULKER_BOX,
            Material.ORANGE_SHULKER_BOX,
            Material.MAGENTA_SHULKER_BOX,
            Material.LIGHT_BLUE_SHULKER_BOX,
            Material.YELLOW_SHULKER_BOX,
            Material.LIME_SHULKER_BOX,
            Material.PINK_SHULKER_BOX,
            Material.GRAY_SHULKER_BOX,
            Material.LIGHT_GRAY_SHULKER_BOX,
            Material.CYAN_SHULKER_BOX,
            Material.PURPLE_SHULKER_BOX,
            Material.BLUE_SHULKER_BOX,
            Material.BROWN_SHULKER_BOX,
            Material.GREEN_SHULKER_BOX,
            Material.RED_SHULKER_BOX,
            Material.BLACK_SHULKER_BOX,

            // 蜂箱 & 蜂巢
            Material.BEEHIVE,
            Material.BEE_NEST,

            // 刷怪笼
            Material.SPAWNER,

            // 命令方块系列
            Material.COMMAND_BLOCK,
            Material.CHAIN_COMMAND_BLOCK,
            Material.REPEATING_COMMAND_BLOCK,

            // 结构方块
            Material.STRUCTURE_BLOCK,

            // 信标
            Material.BEACON
            // 注意：花盆在 1.20.5+ 使用 BlockStateTag，但通常可堆叠（空花盆），暂不列入
    );


    /**
     * 执行非玩家 LivingEntity 拾取物品（支持自动装备）
     */
    private void performLivingEntityPickup(LivingEntity entity, Item item) {
        if (!item.isValid() || item.isDead()) return;

        ItemStack stack = item.getItemStack().clone();
        if (stack.getAmount() <= 0 || stack.getType() == Material.AIR) {
            item.remove();
            return;
        }

        EntityEquipment equip = entity.getEquipment();
        if (equip == null) return;

        World world = entity.getWorld();
        Location loc = item.getLocation();
        boolean pickedUp = false;

        Material type = stack.getType();

        // === 尝试自动装备到正确槽位 ===
        if (ArmorType.isHelmet(type)) {
            if (isBetterEquipment(stack, equip.getHelmet())) {
                equip.setHelmet(stack);
                pickedUp = true;
            }
        } else if (ArmorType.isChestplate(type)) {
            if (isBetterEquipment(stack, equip.getChestplate())) {
                equip.setChestplate(stack);
                pickedUp = true;
            }
        } else if (ArmorType.isLeggings(type)) {
            if (isBetterEquipment(stack, equip.getLeggings())) {
                equip.setLeggings(stack);
                pickedUp = true;
            }
        } else if (ArmorType.isBoots(type)) {
            if (isBetterEquipment(stack, equip.getBoots())) {
                equip.setBoots(stack);
                pickedUp = true;
            }
        } else if (isWeaponOrTool(type)) {
            // 主手：优先装备武器/工具
            if (isBetterEquipment(stack, equip.getItemInMainHand())) {
                equip.setItemInMainHand(stack);
                pickedUp = true;
            }
        }

        // === 如果不能装备，尝试放入背包（仅限 InventoryHolder）===
        if (!pickedUp && entity instanceof InventoryHolder holder) {
            Inventory inv = holder.getInventory();
            HashMap<Integer, ItemStack> leftover = inv.addItem(stack);
            pickedUp = leftover.isEmpty();
        }

        // === 反馈 ===
        if (pickedUp) {
            world.spawnParticle(Particle.ITEM, loc, 3, 0.1, 0.1, 0.1, 0.0,
                    new ItemStack(stack.getType(), 1));
            item.remove();
            // 从索引中移除
            itemIndex.unregisterItem(item);
        }
    }

    /**
     * 判断新物品是否比当前装备更优（用于生物自动替换）
     * 评分规则：材质等级 > 是否有附魔 > 耐久（可选，此处暂不考虑）
     */
    private boolean isBetterEquipment(ItemStack newItem, ItemStack current) {
        if (current == null || current.getType() == Material.AIR) {
            return true; // 空槽位总是可以装备
        }
        if (newItem == null || newItem.getType() == Material.AIR) {
            return false;
        }

        int newScore = getEquipmentScore(newItem);
        int currentScore = getEquipmentScore(current);

        if (newScore != currentScore) {
            return newScore > currentScore;
        }

        // 分数相同：优先选择有附魔的
        boolean newHasEnchants = !newItem.getEnchantments().isEmpty();
        boolean currentHasEnchants = !current.getEnchantments().isEmpty();
        return newHasEnchants && !currentHasEnchants;
    }

    /**
     * 获取装备的材质评分（越高越好）
     */
    private int getEquipmentScore(ItemStack stack) {
        Material mat = stack.getType();
        String name = mat.name();

        // 盔甲 & 工具通用材质顺序（1.13+ 命名）
        if (name.contains("NETHERITE")) return 5;
        if (name.contains("DIAMOND")) return 4;
        if (name.contains("GOLD")) return 3;      // 注意：金质工具耐久低但挖掘快，这里按常规视为中等
        if (name.contains("IRON")) return 3;
        if (name.contains("STONE")) return 2;
        if (name.contains("WOOD") || name.contains("LEATHER")) return 1;
        if (name.equals("CHAINMAIL_CHESTPLATE") ||
                name.equals("CHAINMAIL_HELMET") ||
                name.equals("CHAINMAIL_LEGGINGS") ||
                name.equals("CHAINMAIL_BOOTS")) return 2; // 链甲 ≈ 石头

        return 0; // 其他（如南瓜头、鞘翅等）不参与比较
    }

    /**
     * 判断是否为武器或工具（可放入主手）
     * 兼容 1.13+ 的材质命名（如 WOODEN_SWORD → STONE_AXE 等）
     */
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

    /**
     * 执行物品拾取逻辑
     * @param player 拾取物品的玩家
     * @param item 要拾取的物品
     */
    private void performPickup(Player player, Item item) {
        ItemStack originalStack = item.getItemStack();
        if (originalStack.getAmount() <= 0) return;
        int amount = originalStack.getAmount(); // 动画用原始数量

        // 创建干净的、可堆叠的物品副本（已清理PDC标签）
        ItemStack cleanStack = createCleanStack(originalStack);
        int remainingAmount = cleanStack.getAmount();

        PlayerInventory inv = player.getInventory();
        boolean anyPickedUp = false;

        // ====== 第一阶段：合并（按优先级：副手 → 光标 → 背包）======

        // 1. 首先检查副手（最高优先级）- 无论配置如何，合并阶段都检查
        if (remainingAmount > 0) {
            ItemStack offhand = inv.getItemInOffHand();
            // 副手有相同物品且未满（合并，不关心配置）
            if (offhand.isSimilar(cleanStack) && offhand.getAmount() < offhand.getMaxStackSize()) {
                int space = offhand.getMaxStackSize() - offhand.getAmount();
                if (space > 0) {
                    int toAdd = Math.min(space, remainingAmount);
                    // ✅ 清理PDC标签
                    ItemStack newOffhand = createCleanStack(new ItemStack(offhand.getType(), offhand.getAmount() + toAdd));
                    // 保留原物品的其他元数据（名称、附魔等）
                    if (offhand.hasItemMeta()) {
                        ItemMeta meta = offhand.getItemMeta().clone();
                        // 手动清理pdc标签
                        meta.getPersistentDataContainer().remove(SOURCE_KEY);
                        meta.getPersistentDataContainer().remove(SPAWN_TICK_KEY);
                        meta.getPersistentDataContainer().remove(DROPPED_BY_KEY);
                        newOffhand.setItemMeta(meta);
                    }
                    inv.setItemInOffHand(newOffhand);
                    remainingAmount -= toAdd;
                    anyPickedUp = true;
                }
            }
        }

        // 2. 检查光标（手持物品）
        if (remainingAmount > 0) {
            ItemStack cursor = player.getItemOnCursor();
            if (!cursor.getType().isAir() && cursor.isSimilar(cleanStack)) {
                int space = cursor.getMaxStackSize() - cursor.getAmount();
                if (space > 0) {
                    int toAdd = Math.min(space, remainingAmount);
                    // ✅ 清理PDC标签
                    ItemStack newCursor = createCleanStack(new ItemStack(cursor.getType(), cursor.getAmount() + toAdd));
                    if (cursor.hasItemMeta()) {
                        ItemMeta meta = cursor.getItemMeta().clone();
                        meta.getPersistentDataContainer().remove(SOURCE_KEY);
                        meta.getPersistentDataContainer().remove(SPAWN_TICK_KEY);
                        meta.getPersistentDataContainer().remove(DROPPED_BY_KEY);
                        newCursor.setItemMeta(meta);
                    }
                    player.setItemOnCursor(newCursor);
                    remainingAmount -= toAdd;
                    anyPickedUp = true;
                }
            }
        }

        // 3. 检查背包（0-35槽）
        if (remainingAmount > 0) {
            for (int slot = 0; slot < 36; slot++) {
                if (remainingAmount == 0) break;

                ItemStack existing = inv.getItem(slot);
                if (existing != null && existing.isSimilar(cleanStack)) {
                    int space = existing.getMaxStackSize() - existing.getAmount();
                    if (space > 0) {
                        int toAdd = Math.min(space, remainingAmount);
                        // ✅ 清理PDC标签
                        ItemStack newExisting = createCleanStack(new ItemStack(existing.getType(), existing.getAmount() + toAdd));
                        if (existing.hasItemMeta()) {
                            ItemMeta meta = existing.getItemMeta().clone();
                            meta.getPersistentDataContainer().remove(SOURCE_KEY);
                            meta.getPersistentDataContainer().remove(SPAWN_TICK_KEY);
                            meta.getPersistentDataContainer().remove(DROPPED_BY_KEY);
                            newExisting.setItemMeta(meta);
                        }
                        inv.setItem(slot, newExisting);
                        remainingAmount -= toAdd;
                        anyPickedUp = true;
                    }
                }
            }
        }

        // ====== 第二阶段：放置到空槽（按原版顺序：0-35槽）======
        if (remainingAmount > 0) {
            // 按原版顺序从0到35寻找空槽位
            for (int slot = 0; slot < 36; slot++) {
                if (remainingAmount <= 0) break;

                if (inv.getItem(slot) == null) {
                    int toPlace = Math.min(remainingAmount, cleanStack.getType().getMaxStackSize());
                    ItemStack newStack = cleanStack.clone(); // ✅ 使用干净的副本
                    newStack.setAmount(toPlace);
                    inv.setItem(slot, newStack);
                    remainingAmount -= toPlace;
                    anyPickedUp = true;
                }
            }
        }

        // ====== 第三阶段：根据配置决定是否放置到副手空槽 ======
        if (remainingAmount > 0) {
            ItemStack offhand = inv.getItemInOffHand();

            // 只有当配置允许时，才放置到空的副手
            if (offhand.getType() == Material.AIR && config.isOffhandPickupEnabled()) {
                int toPlace = Math.min(remainingAmount, cleanStack.getType().getMaxStackSize());
                ItemStack newStack = cleanStack.clone(); // ✅ 使用干净的副本
                newStack.setAmount(toPlace);
                inv.setItemInOffHand(newStack);
                remainingAmount -= toPlace;
                anyPickedUp = true;
            }
        }

        // ====== 最终处理 ======
        if (anyPickedUp) {
            World world = player.getWorld();
            Location loc = item.getLocation();

            PacketUtils.sendPickupAnimation(plugin, player, item, amount);
            world.playSound(loc, Sound.ENTITY_ITEM_PICKUP, 0.2f, (float) (0.8 + Math.random() * 0.4));

            if (remainingAmount > 0) {
                // 还有剩余，更新物品实体
                ItemStack remainingStack = cleanStack.clone();
                remainingStack.setAmount(remainingAmount);
                item.setItemStack(remainingStack);
            } else {
                // 全部拾取完成，移除物品实体
                item.remove();
                // 从索引中移除
                itemIndex.unregisterItem(item);
            }
        }
    }

    // ====== 启用/禁用控制 ======

    /**
     * 启用拾取管理器
     * 根据配置启动相应的驱动模式
     */
    public void enable() {
        if (active) return; // 防止重复启用
        active = true;

        // 根据配置启动相应的驱动模式
        if (config.isPlayerDriven()) {
            startPlayerDriven(); // 启动玩家驱动模式
        }
        if (config.isItemDrivenEnabled()) {
            startItemDriven(); // 启动物品驱动模式
        }
    }

    /**
     * 禁用拾取管理器
     * 停止所有定时任务，清理数据，恢复原版逻辑
     */
    public void disable() {
        if (!active) return; // 防止重复禁用
        active = false;

        // 停止玩家驱动模式相关任务
        if (activePlayerUpdater != null) {
            activePlayerUpdater.cancel();
            activePlayerUpdater = null;
        }
        activePlayers.clear(); // 清空活跃玩家列表

        // 停止物品驱动模式相关任务
        if (itemDetectionTask != null) {
            itemDetectionTask.cancel();
            itemDetectionTask = null;
        }

        // 恢复原版物品拾取延迟为0（立即可拾取）
        restoreOriginalPickupDelayToZero();

    }

    /**
     * 恢复所有物品的原版拾取延迟为0（禁用插件时调用）
     * 使物品可以立即被原版机制拾取
     */
    public void restoreOriginalPickupDelayToZero() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Item item) {
                    try {
                        // 使用反射恢复pickupDelay为0（立即可拾取）
                        Object nmsItem = getGetHandleMethod().invoke(item);
                        Field field = getItemPickupDelayField();
                        field.set(nmsItem, 0); // 设置为0，立即可拾取
                    } catch (Exception ignored) {
                        // 忽略反射异常
                    }
                }
            }
        }
    }

    /**
     * 启动玩家驱动模式
     * 定期更新活跃玩家列表（用于移动事件触发）
     */
    private void startPlayerDriven() {
        // 不需要定时任务了，现在由 PlayerMoveEvent 按频率触发
        plugin.getLogger().info("玩家驱动模式已启用，移动检测间隔: " +
                config.getPlayerMoveCheckIntervalTicks() + " ticks");
    }

    /**
     * 启动物品驱动模式
     * 定期扫描活跃物品并尝试可被拾取生物拾取
     */
    private void startItemDriven() {
        int checkInterval = config.getPickupAttemptIntervalTicks();
        itemDetectionTask = new BukkitRunnable() {
            private int scanIndex = 0; // 用于轮询玩家

            @Override
            public void run() {
                // 获取所有在线玩家
                Collection<? extends Player> players = Bukkit.getOnlinePlayers();
                if (players.isEmpty()) return;

                // 轮询机制：每次扫描只处理一部分玩家
                List<Player> playerList = new ArrayList<>(players);
                int playersPerScan = Math.max(1, playerList.size() / 4); // 每次处理25%的玩家

                int startIdx = scanIndex % playerList.size();
                scanIndex = (scanIndex + 1) % playerList.size();

                // 处理本轮玩家
                for (int i = 0; i < playersPerScan; i++) {
                    int idx = (startIdx + i) % playerList.size();
                    Player player = playerList.get(idx);
                    if (player.isOnline() && !player.isDead()) {
                        scanItemsNearPlayer(player);
                    }
                }
            }
        };
        itemDetectionTask.runTaskTimer(plugin, 0, checkInterval);
    }

    /**
     * 扫描玩家附近区块的物品，同时查找附近的生物
     */
    private void scanItemsNearPlayer(Player player) {
        if (player.getGameMode() == GameMode.SPECTATOR) return;

        World world = player.getWorld();
        Location loc = player.getLocation();

        // 计算需要扫描的区块范围
        double scanRange = config.getPickupRange();
        int chunkRange = (int) Math.ceil(scanRange / 16.0) + 1; // +1确保覆盖

        int centerChunkX = loc.getBlockX() >> 4;
        int centerChunkZ = loc.getBlockZ() >> 4;

        // 扫描附近区块
        for (int dx = -chunkRange; dx <= chunkRange; dx++) {
            for (int dz = -chunkRange; dz <= chunkRange; dz++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;

                // 使用索引获取该区块的物品
                Set<Item> chunkItems = itemIndex.getItemsInChunk(world, chunkX, chunkZ);
                if (chunkItems.isEmpty()) continue;

                // 处理该区块的物品
                processChunkItemsForPlayer(player, chunkItems);
            }
        }
    }

    /**
     * 处理一个区块中的物品，寻找最近的拾取者
     */
    private void processChunkItemsForPlayer(Player player, Set<Item> chunkItems) {
        Location playerLoc = player.getLocation();
        double rangeSq = config.getPickupRange() * config.getPickupRange();

        for (Item item : chunkItems) {
            if (!item.isValid() || item.isDead()) continue;

            // 快速距离检查（使用平方距离）
            if (item.getLocation().distanceSquared(playerLoc) > rangeSq) continue;

            // 检查物品是否在活跃期内
            if (!isItemActive(item)) continue;

            // 检查玩家是否可以拾取
            if (canPickupNow(player, item)) {
                performPickup(player, item);
            }
        }
    }

    /**
     * 检查物品是否在活跃期内
     */
    private boolean isItemActive(Item item) {
        PersistentDataContainer pdc = item.getPersistentDataContainer();
        Long spawnTick = pdc.get(SPAWN_TICK_KEY, PersistentDataType.LONG);

        if (spawnTick == null) {
            return true; // 没有记录时间，默认活跃
        }

        long currentTick = item.getWorld().getFullTime();
        return currentTick - spawnTick <= activeDetectionTicks;
    }

    /**
     * 判断一个 LivingEntity 是否在原版中具备拾取物品的能力
     */
    private boolean isEligiblePicker(LivingEntity entity) {
        if (entity instanceof Player player) {
            return player.getGameMode() != GameMode.SPECTATOR;
        }
        if (entity instanceof Mob mob) {
            return mob.getCanPickupItems();
        }
        return false;
    }

    // ====== 反射工具（增强版）======
    private static volatile Field cachedPickupDelayField = null;

    /**
     * 获取ItemEntity类的pickupDelay字段（反射）
     * 支持多个Minecraft版本
     * @return pickupdelay字段对象
     */
    private static Field getItemPickupDelayField() {
        // 如果已缓存，直接返回
        if (cachedPickupDelayField != null) {
            return cachedPickupDelayField;
        }

        Class<?> nmsItemClass;
        try {
            // 尝试1.17+的新映射类名（Mojang映射）
            nmsItemClass = Class.forName("net.minecraft.world.entity.item.ItemEntity");
        } catch (ClassNotFoundException e1) {
            try {
                // 尝试1.16及以下的旧NMS路径（CraftBukkit映射）
                String version = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
                nmsItemClass = Class.forName("net.minecraft.server." + version + ".EntityItem");
            } catch (Exception e2) {
                // 两个版本都失败，抛出运行时异常
                throw new RuntimeException("Unsupported server version for Item pickupDelay reflection.", e2);
            }
        }

        // 常见字段名候选（按版本排序，支持多个混淆名称）
        String[] candidates = {
                "pickupDelay",   // 未混淆（如开发环境、部分Paper服务器）
                "bK",            // 1.17 ~ 1.19.4 (yarn/mojang 混淆名称)
                "c",             // 1.20.0 ~ 1.20.4
                "d",             // 1.20.5+ （观察到的部分版本）
                "e"              // 预防未来变化的候选
        };

        // 尝试所有候选字段名
        for (String fieldName : candidates) {
            try {
                Field field = nmsItemClass.getDeclaredField(fieldName);
                // 验证字段类型为int（确保找到正确的字段）
                if (field.getType() == int.class) {
                    field.setAccessible(true); // 设置可访问
                    cachedPickupDelayField = field; // 缓存找到的字段
                    return field;
                }
            } catch (NoSuchFieldException ignored) {
                // 尝试下一个候选字段名
            }
        }

        // 所有候选都失败 → 抛出异常
        throw new RuntimeException("Could not find pickupDelay field in " + nmsItemClass.getName());
    }

    // ====== 辅助枚举与解析 ======

    /**
     * 物品来源类型枚举
     */
    private enum ItemSourceType {
        PLAYER_DROP,    // 玩家丢弃（按Q键）
        NATURAL_DROP,   // 自然掉落（方块挖掘、实体死亡等）
        INSTANT_PICKUP, // 立即拾取（特殊来源）
        UNKNOWN         // 未知来源
    }

    /**
     * 解析字符串为物品来源类型
     * @param str 来源字符串
     * @return 对应的itemsourcetype枚举值
     */
    private ItemSourceType parseSource(String str) {
        if (str == null) return ItemSourceType.UNKNOWN;
        try {
            return ItemSourceType.valueOf(str);
        } catch (IllegalArgumentException e) {
            // 字符串无法解析为已知枚举值
            return ItemSourceType.UNKNOWN;
        }
    }

    /**
     * 根据物品来源类型获取要求的拾取延迟
     * @param source 物品来源类型
     * @return 要求的延迟时间（tick）
     */
    private long getRequiredDelay(ItemSourceType source) {
        // 使用switch表达式（Java 14+）返回对应的延迟
        return switch (source) {
            case PLAYER_DROP -> playerDropDelayTicks;      // 玩家丢弃延迟
            case NATURAL_DROP -> naturalDropDelayTicks;    // 自然掉落延迟
            case INSTANT_PICKUP -> instantPickupDelayTicks;// 立即拾取延迟
            default -> instantPickupDelayTicks;                          // 未知来源无延迟
        };
    }

    private static volatile Method cachedGetHandleMethod = null;

    /**
     * 获取CraftItem.getHandle()方法（反射）
     * @return gethandle方法对象
     * @throws Exception 反射相关异常
     */
    private static Method getGetHandleMethod() throws Exception {
        // 如果已缓存，直接返回
        if (cachedGetHandleMethod != null) {
            return cachedGetHandleMethod;
        }

        String version = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
        Class<?> craftEntityClass = Class.forName("org.bukkit.craftbukkit." + version + ".entity.CraftEntity");

        cachedGetHandleMethod = craftEntityClass.getMethod("getHandle");
        cachedGetHandleMethod.setAccessible(true);
        return cachedGetHandleMethod;
    }

    /**
     * 获取管理器当前是否活跃
     * @return true如果管理器正在运行
     */
    public boolean isActive() {
        return active;
    }

    /**
     * 从索引中手动移除物品（供合并器等外部调用）
     */
    public void removeItemFromIndex(Item item) {
        if (itemIndex != null && item != null) {
            itemIndex.unregisterItem(item);
        }
    }
}
