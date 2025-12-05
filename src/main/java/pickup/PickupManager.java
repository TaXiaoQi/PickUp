package pickup;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.EntityEquipment;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 拾取管理器 - 核心逻辑处理类
 * 负责实现双驱动模式（玩家驱动+物品驱动）的物品自动拾取系统
 */
public class PickupManager {

    // 插件主类引用
    private final PickUp plugin;

    // 配置参数（从插件主类加载）
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
    private final Map<World, Set<Item>> activeItemsByWorld = new ConcurrentHashMap<>(); // 按世界分组的活跃物品
    private BukkitRunnable itemDetectionTask = null; // 物品检测定时任务

    /**
     * 构造函数
     * @param plugin 插件主类实例
     */
    public PickupManager(PickUp plugin) {
        this.plugin = plugin;
    }

    /**
     * 获取自定义物品合并器实例
     * @return 物品合并器，可能为null
     */
    private CustomItemMerger getCustomItemMerger() {
        return plugin.getItemMerger();
    }

    /**
     * 从插件主类加载配置参数
     * 应在配置重载时调用
     */
    public void loadConfig() {
        double range = plugin.getPickupRange();
        this.pickupRangeSq = range * range; // 预先计算平方值，避免每次比较都计算
        this.playerDropDelayTicks = plugin.getPlayerDropDelayTicks();
        this.naturalDropDelayTicks = plugin.getNaturalDropDelayTicks();
        this.instantPickupDelayTicks = plugin.getInstantPickupDelayTicks();
        this.selfImmuneTicks = plugin.getSelfImmuneTicks();
        this.activeDetectionTicks = plugin.getActiveDetectionTicks();
    }

    // ====== 公共入口：由 PickupEvent 调用 ======

    /**
     * 处理物品生成事件（所有类型的物品掉落）
     * @param event 物品生成事件
     */
    public void handleItemSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();

        // 从物品堆栈的元数据中继承来源标记
        ItemStack stack = item.getItemStack();
        String source = stack.getItemMeta()
                .getPersistentDataContainer()
                .get(SOURCE_KEY, PersistentDataType.STRING);

        if (source != null) {
            // 将来源标记写入物品实体的持久化数据容器
            item.getPersistentDataContainer().set(SOURCE_KEY, PersistentDataType.STRING, source);
            // 记录生成时间（tick）
            item.getPersistentDataContainer().set(SPAWN_TICK_KEY, PersistentDataType.LONG, item.getWorld().getFullTime());
        } else {
            // 默认视为自然掉落或立即拾取（取决于配置）
            markItemAsNaturalDrop(item);
        }

        // 如果物品驱动模式启用，将物品添加到活跃物品列表
        if (plugin.isItemDrivenEnabled()) {
            addToActiveItems(item);
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

        // 如果物品驱动模式启用，将物品添加到活跃物品列表
        if (plugin.isItemDrivenEnabled()) {
            addToActiveItems(item);
        }

        // 禁用原版拾取逻辑
        disableVanillaPickup(item);

        // 通知物品合并器
        notifyMerger(item);
    }

    /**
     * 处理方块掉落物品事件
     * @param event 方块掉落物品事件
     */
    public void handleBlockDrop(BlockDropItemEvent event) {
        for (Item item : event.getItems()) {
            // 标记为自然掉落物品
            markItemAsNaturalDrop(item);

            // 如果物品驱动模式启用，将物品添加到活跃物品列表
            if (plugin.isItemDrivenEnabled()) {
                addToActiveItems(item);
            }

            // 禁用原版拾取逻辑
            disableVanillaPickup(item);

            // 通知物品合并器
            notifyMerger(item);
        }
    }

    /**
     * 处理实体死亡事件（怪物/动物死亡掉落）
     * @param event 实体死亡事件
     */
    public void handleEntityDeath(EntityDeathEvent event) {
        // 仅标记物品堆栈的来源为"natural"（实体死亡属于自然掉落）
        // 注意：此时物品实体尚未生成，需要先标记ItemStack
        for (ItemStack stack : event.getDrops()) {
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

        Location loc = player.getLocation();
        World world = loc.getWorld();
        double range = plugin.getPickupRange();
        List<Item> nearbyItems = new ArrayList<>();

        // 获取范围内的所有实体，筛选出物品实体
        for (Entity entity : world.getNearbyEntities(loc, range, range, range)) {
            if (entity instanceof Item) {
                nearbyItems.add((Item) entity);
            }
        }

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
        pdc.set(SPAWN_TICK_KEY, PersistentDataType.LONG, item.getWorld().getFullTime()); // 记录生成时间
        pdc.set(DROPPED_BY_KEY, PersistentDataType.STRING, playerId.toString()); // 记录丢弃者
        pdc.set(SOURCE_KEY, PersistentDataType.STRING, ItemSourceType.PLAYER_DROP.name()); // 标记来源类型
    }

    /**
     * 标记物品为自然掉落
     * @param item 物品实体
     */
    private void markItemAsNaturalDrop(Item item) {
        PersistentDataContainer pdc = item.getPersistentDataContainer();
        pdc.set(SPAWN_TICK_KEY, PersistentDataType.LONG, item.getWorld().getFullTime()); // 记录生成时间
        pdc.set(SOURCE_KEY, PersistentDataType.STRING, ItemSourceType.NATURAL_DROP.name()); // 标记来源类型
    }

    /**
     * 将物品添加到活跃物品列表（物品驱动模式使用）
     * @param item 要添加的物品
     */
    private void addToActiveItems(Item item) {
        // 按世界分组管理活跃物品，提高性能
        activeItemsByWorld.computeIfAbsent(item.getWorld(), w -> ConcurrentHashMap.newKeySet()).add(item);
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
            delayField.set(nmsItem, Integer.MAX_VALUE);
        } catch (Exception e) {
            // 建议降级为debug日志，避免刷屏WARN
            // 反射失败通常是因为版本不兼容或服务器修改
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().info("Reflection failed for pickup delay: " + e.getMessage());
            }
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
     * 执行 LivingEntity 拾取物品（通用版本）
     */
    private boolean canPickupNow(LivingEntity entity, Item item) {
        if (entity instanceof Player player) {
            return canPickupNow(player, item); // 复用玩家逻辑
        }
        // 对于非玩家生物：只要在范围内、物品有效，就允许拾取
        if (!item.isValid() || item.isDead()) {
            return false;
        }

        // 距离检查（复用已有字段）
        double distSq = entity.getLocation().distanceSquared(item.getLocation());
        return !(distSq > pickupRangeSq);

        // 生物不需要冷却时间（原版行为），所以直接返回 true
    }

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
     * 检查玩家是否可以立即拾取指定物品
     * @param player 尝试拾取的玩家
     * @param item 要拾取的物品
     * @return 是否可以拾取
     */
    private boolean canPickupNow(Player player, Item item) {
        long currentTime = item.getWorld().getFullTime(); // 当前游戏时间（tick）
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

        // 特殊处理：玩家自己丢弃的物品有自身免疫时间
        if (source == ItemSourceType.PLAYER_DROP) {
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
        return item.getLocation().distanceSquared(player.getLocation()) <= pickupRangeSq;
    }

    /**
     * 执行物品拾取逻辑
     * @param player 拾取物品的玩家
     * @param item 要拾取的物品
     */
    private void performPickup(Player player, Item item) {
        ItemStack stack = item.getItemStack();
        if (stack.getAmount() <= 0) return;
        int amount = stack.getAmount();

        PlayerInventory inv = player.getInventory();
        boolean merged = false;

        // 第一阶段：尝试合并到现有堆栈（优先主背包9-35槽位）
        for (int i = 9; i < 36; i++) {
            ItemStack existing = inv.getItem(i);
            if (existing != null && existing.isSimilar(stack)) {
                int space = existing.getMaxStackSize() - existing.getAmount();
                if (space >= stack.getAmount()) {
                    // 现有堆栈有足够空间，完全合并
                    existing.setAmount(existing.getAmount() + stack.getAmount());
                    merged = true;
                    break;
                } else if (space > 0) {
                    // 现有堆栈有部分空间，部分合并
                    existing.setAmount(existing.getMaxStackSize());
                    stack.setAmount(stack.getAmount() - space);
                }
            }
        }

        // 第二阶段：如果未完全合并，尝试放入空位（优先热键栏0-8，然后9-35）
        if (!merged && stack.getAmount() > 0) {
            for (int i = 0; i < 36; i++) {
                if (inv.getItem(i) == null) {
                    inv.setItem(i, stack.clone());
                    merged = true;
                    break;
                }
            }
        }

        // 第三阶段：如果仍未处理完，尝试放入副手
        if (!merged && stack.getAmount() > 0) {
            ItemStack offhand = inv.getItemInOffHand();
            if (offhand.getType() == Material.AIR || (offhand.isSimilar(stack) && offhand.getAmount() < offhand.getMaxStackSize())) {
                if (offhand.getType() == Material.AIR) {
                    // 副手为空，直接放入
                    inv.setItemInOffHand(stack.clone());
                } else {
                    // 副手有同类物品，尝试合并
                    int space = offhand.getMaxStackSize() - offhand.getAmount();
                    if (space >= stack.getAmount()) {
                        // 副手有足够空间
                        offhand.setAmount(offhand.getAmount() + stack.getAmount());
                    } else {
                        // 副手空间不足，部分合并
                        offhand.setAmount(offhand.getMaxStackSize());
                        stack.setAmount(stack.getAmount() - space);
                        // 剩余物品无法处理 → 理论上不会发生，作为安全兜底
                    }
                }
                merged = true;
            }
        }

        // 如果成功处理了物品，发送拾取动画并移除物品实体
        if (merged) {
            World world = player.getWorld();
            Location loc = item.getLocation();

            // 发送拾取动画数据包给所有观看者
            PacketUtils.sendPickupAnimation(plugin, player, item, amount);
            // 播放拾取音效（仅对玩家）
            world.playSound(loc, Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.0f);
            // 移除物品实体
            item.remove();
        }
        // 注意：如果merged为false，物品会留在地上（通常是背包已满的情况）
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
        if (plugin.isPlayerDriven()) {
            startPlayerDriven(); // 启动玩家驱动模式
        }
        if (plugin.isItemDrivenEnabled()) {
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
        activeItemsByWorld.clear(); // 清空活跃物品列表

        // 恢复原版物品拾取延迟
        restoreOriginalPickupDelay();
        // === 新增代码：修复已存在的物品 ===
        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                try {
                    Object nmsItem = getGetHandleMethod().invoke(item);
                    Field delayField = getItemPickupDelayField();
                    // 将 pickupDelay 重置为 0，使其可以立即被拾取
                    delayField.set(nmsItem, 0);
                } catch (Exception e) {
                    if (plugin.getConfig().getBoolean("debug", false)) {
                        plugin.getLogger().info("Failed to reset pickup delay for an item: " + e.getMessage());
                    }
                }
            }
        }
        plugin.unregisterPickupEvent();
    }

    /**
     * 启动玩家驱动模式
     * 定期更新活跃玩家列表（用于移动事件触发）
     */
    private void startPlayerDriven() {
        int interval = plugin.getPlayerDrivenScanIntervalTicks();
        activePlayerUpdater = new BukkitRunnable() {
            @Override
            public void run() {
                // 将所有非旁观者玩家添加到活跃列表
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getGameMode() != GameMode.SPECTATOR) {
                        activePlayers.add(player.getUniqueId());
                    }
                }
            }
        };
        // 立即执行，然后按配置间隔定期执行
        activePlayerUpdater.runTaskTimer(plugin, 0, interval);
    }

    /**
     * 启动物品驱动模式
     * 定期扫描活跃物品并尝试可被拾取生物拾取
     */
    private void startItemDriven() {
        int checkInterval = plugin.getPickupAttemptIntervalTicks();
        itemDetectionTask = new BukkitRunnable() {
            @Override
            public void run() {
                long currentTick = -1;

                Iterator<Map.Entry<World, Set<Item>>> worldIter = activeItemsByWorld.entrySet().iterator();
                while (worldIter.hasNext()) {
                    Map.Entry<World, Set<Item>> entry = worldIter.next();
                    Set<Item> items = entry.getValue();
                    Iterator<Item> itemIter = items.iterator();

                    while (itemIter.hasNext()) {
                        Item item = itemIter.next();

                        if (item.isDead() || !item.isValid()) {
                            itemIter.remove();
                            continue;
                        }

                        PersistentDataContainer pdc = item.getPersistentDataContainer();
                        Long spawnTick = pdc.get(SPAWN_TICK_KEY, PersistentDataType.LONG);

                        if (spawnTick == null) {
                            if (currentTick == -1) currentTick = item.getWorld().getFullTime();
                            spawnTick = currentTick;
                            pdc.set(SPAWN_TICK_KEY, PersistentDataType.LONG, spawnTick);
                        }

                        if (currentTick == -1) currentTick = item.getWorld().getFullTime();
                        if (currentTick - spawnTick > activeDetectionTicks) {
                            itemIter.remove();
                            continue;
                        }

                        // === 新增：支持玩家和可拾取生物 ===
                        World world = item.getWorld();
                        Location loc = item.getLocation();
                        double range = plugin.getPickupRange();
                        double rangeSq = range * range;

                        LivingEntity nearestPicker = null;
                        double nearestDistSq = Double.MAX_VALUE;

                        // 查找范围内的所有 LivingEntity（包括玩家和生物）
                        for (Entity entity : world.getNearbyEntities(loc, range, range, range,
                                e -> e instanceof LivingEntity le && isEligiblePicker(le))) {

                            LivingEntity picker = (LivingEntity) entity;
                            double distSq = picker.getLocation().distanceSquared(loc);

                            if (distSq > rangeSq || distSq >= nearestDistSq) {
                                continue;
                            }

                            // 检查该实体是否可以拾取此物品（需重载 canPickupNow）
                            if (canPickupNow(picker, item)) {
                                nearestPicker = picker;
                                nearestDistSq = distSq;
                            }
                        }

                        if (nearestPicker != null) {
                            if (nearestPicker instanceof Player player) {
                                performPickup(player, item); // 调用玩家专用版本
                            } else {
                                performLivingEntityPickup(nearestPicker, item); // 调用生物通用版本
                            } // 需重载
                            itemIter.remove();
                        }
                        // ==================================
                    }

                    if (items.isEmpty()) {
                        worldIter.remove();
                    }

                    currentTick = -1;
                }
            }
        };
        itemDetectionTask.runTaskTimer(plugin, 0, checkInterval);
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

    /**
     * 恢复所有物品的原版拾取延迟（禁用插件时调用）
     */
    public void restoreOriginalPickupDelay() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Item item) {
                    PersistentDataContainer pdc = item.getPersistentDataContainer();

                    // 只处理被本插件标记过的物品（有来源标记的物品）
                    if (pdc.has(SOURCE_KEY, PersistentDataType.STRING)) {
                        try {
                            // 使用反射恢复pickupDelay为默认值10
                            Object nmsItem = getGetHandleMethod().invoke(item);
                            Field field = getItemPickupDelayField();
                            field.set(nmsItem, 10); // 原版默认值
                        } catch (Exception ignored) {
                            // 忽略反射异常（通常发生在插件卸载时）
                        }
                    }
                }
            }
        }
    }

    // ====== 反射工具（增强版）======
    private static volatile Field cachedPickupDelayField = null;

    /**
     * 获取ItemEntity类的pickupDelay字段（反射）
     * 支持多个Minecraft版本
     * @return pickupDelay字段对象
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
     * @return 对应的ItemSourceType枚举值
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
            default -> 0;                                  // 未知来源无延迟
        };
    }

    private static volatile Method cachedGetHandleMethod = null;

    /**
     * 获取CraftItem.getHandle()方法（反射）
     * @return getHandle方法对象
     * @throws Exception 反射相关异常
     */
    private static Method getGetHandleMethod() throws Exception {
        // 如果已缓存，直接返回
        if (cachedGetHandleMethod != null) {
            return cachedGetHandleMethod;
        }

        // 获取服务器版本字符串（如"v1_20_R4"）
        String version = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];

        // 加载对应版本的CraftItem类
        Class<?> craftItemClass = Class.forName("org.bukkit.craftbukkit." + version + ".entity.CraftItem");

        // 获取getHandle方法
        cachedGetHandleMethod = craftItemClass.getMethod("getHandle");
        cachedGetHandleMethod.setAccessible(true); // 设置可访问
        return cachedGetHandleMethod;
    }

    /**
     * 获取管理器当前是否活跃
     * @return true如果管理器正在运行
     */
    public boolean isActive() {
        return active;
    }
}