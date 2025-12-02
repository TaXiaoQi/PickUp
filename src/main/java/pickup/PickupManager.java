package pickup;

import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.inventory.PlayerInventory;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 拾取管理器：实现三类独立配置的智能拾取系统
 * - PLAYER_DROP: 玩家丢弃（可设自身免疫）
 * - NATURAL_DROP: 怪物死亡 / 挖矿掉落
 * - INSTANT_PICKUP: 其他所有来源（命令、投掷器、漏斗等）
 */
public class PickupManager implements Listener {
    private final PickUp plugin;
    private double pickupRangeSq;
    private int playerDropDelayTicks;
    private int naturalDropDelayTicks;
    private int instantPickupDelayTicks;
    private int selfImmuneTicks;
    private int activeDetectionTicks;
    private boolean active = false;
    private static volatile Field cachedPickupDelayField = null;

    // NBT Keys
    private static final NamespacedKey SPAWN_TICK_KEY = new NamespacedKey("pickup", "spawn_tick");
    private static final NamespacedKey DROPPED_BY_KEY = new NamespacedKey("pickup", "dropped_by");
    private static final NamespacedKey SOURCE_KEY = new NamespacedKey("pickup", "source");

    // 玩家驱动模式
    private final Set<UUID> activePlayers = ConcurrentHashMap.newKeySet();
    private BukkitRunnable activePlayerUpdater = null;

    // 物品驱动模式
    private final Map<World, Set<Item>> activeItemsByWorld = new ConcurrentHashMap<>();
    private BukkitRunnable itemDetectionTask = null;

    public PickupManager(PickUp plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        double range = plugin.getPickupRange();
        this.pickupRangeSq = range * range;
        this.playerDropDelayTicks = plugin.getPlayerDropDelayTicks();
        this.naturalDropDelayTicks = plugin.getNaturalDropDelayTicks();
        this.instantPickupDelayTicks = plugin.getInstantPickupDelayTicks();
        this.selfImmuneTicks = plugin.getSelfImmuneTicks();
        this.activeDetectionTicks = plugin.getActiveDetectionTicks();
    }

    public void enable() {
        if (active) return;
        active = true;
        loadConfig();

        // ========== 玩家驱动模式 ==========
        if (plugin.isPlayerDriven()) {
            int scanInterval = Math.max(1, plugin.getPlayerDrivenScanIntervalTicks());
            activePlayerUpdater = new BukkitRunnable() {
                @Override
                public void run() {
                    if (!plugin.isPickupActive()) {
                        activePlayers.clear();
                        return;
                    }
                    double range = plugin.getPickupRange();
                    activePlayers.clear();
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (player.getGameMode() == GameMode.SPECTATOR) continue;
                        Location loc = player.getLocation();
                        boolean hasNearby = false;
                        for (Entity e : loc.getWorld().getNearbyEntities(loc, range, range, range)) {
                            if (e instanceof Item) {
                                hasNearby = true;
                                break;
                            }
                        }
                        if (hasNearby) {
                            activePlayers.add(player.getUniqueId());
                        }
                    }
                }
            };
            activePlayerUpdater.runTaskTimer(plugin, 0L, scanInterval);
        }

        // ========== 物品驱动模式 ==========
        if (plugin.isItemDrivenEnabled()) {
            for (World world : Bukkit.getWorlds()) {
                activeItemsByWorld.put(world, ConcurrentHashMap.newKeySet());
            }

            int attemptInterval = Math.max(1, plugin.getPickupAttemptIntervalTicks());
            itemDetectionTask = new BukkitRunnable() {
                @Override
                public void run() {
                    if (!plugin.isPickupActive()) return;

                    for (Map.Entry<World, Set<Item>> entry : activeItemsByWorld.entrySet()) {
                        World world = entry.getKey();
                        long currentTick = world.getFullTime();
                        Set<Item> activeItems = entry.getValue();
                        Set<Item> toRemove = new HashSet<>();

                        for (Item item : activeItems) {
                            if (item.isDead()) {
                                toRemove.add(item);
                                continue;
                            }

                            PersistentDataContainer pdc = item.getPersistentDataContainer();
                            Long spawnTick = pdc.get(SPAWN_TICK_KEY, PersistentDataType.LONG);
                            if (spawnTick == null) {
                                toRemove.add(item);
                                continue;
                            }

                            long age = currentTick - spawnTick;
                            if (age > activeDetectionTicks) {
                                toRemove.add(item);
                                continue;
                            }

                            String sourceStr = pdc.get(SOURCE_KEY, PersistentDataType.STRING);
                            ItemSourceType source = parseSource(sourceStr);

                            // UNKNOWN 不允许拾取（应已被转为 INSTANT_PICKUP）
                            if (source == ItemSourceType.UNKNOWN) {
                                continue;
                            }

                            long requiredDelay = getRequiredDelay(source);
                            if (age < requiredDelay) {
                                continue;
                            }

                            Player nearest = null;
                            double minDistSq = Double.MAX_VALUE;
                            Location itemLoc = item.getLocation();

                            for (Player player : world.getPlayers()) {
                                if (player.getGameMode() == GameMode.SPECTATOR) continue;
                                double distSq = player.getLocation().distanceSquared(itemLoc);
                                if (distSq <= pickupRangeSq && distSq < minDistSq) {
                                    nearest = player;
                                    minDistSq = distSq;
                                }
                            }

                            if (nearest != null) {
                                performPickup(nearest, item);
                            }

                            if (item.isDead()) {
                                toRemove.add(item);
                            }
                        }

                        activeItems.removeAll(toRemove);
                    }
                }
            };
            itemDetectionTask.runTaskTimer(plugin, 0L, attemptInterval);
        }

        plugin.getLogger().info("PickupManager 已启用（三类独立：PLAYER_DROP / NATURAL_DROP / INSTANT_PICKUP）");
    }

    private long getRequiredDelay(ItemSourceType source) {
        return switch (source) {
            case PLAYER_DROP -> playerDropDelayTicks;
            case NATURAL_DROP -> naturalDropDelayTicks;
            case INSTANT_PICKUP -> instantPickupDelayTicks;
            case UNKNOWN -> Long.MAX_VALUE; // 不应发生
        };
    }

    private ItemSourceType parseSource(String sourceStr) {
        if (sourceStr == null) return ItemSourceType.UNKNOWN;
        try {
            return ItemSourceType.valueOf(sourceStr);
        } catch (IllegalArgumentException e) {
            return ItemSourceType.UNKNOWN;
        }
    }

    public void disable() {
        if (!active) return;
        active = false;

        if (activePlayerUpdater != null) {
            activePlayerUpdater.cancel();
            activePlayerUpdater = null;
        }
        activePlayers.clear();

        if (itemDetectionTask != null) {
            itemDetectionTask.cancel();
            itemDetectionTask = null;
        }
        activeItemsByWorld.clear();

        plugin.getLogger().info("PickupManager 已禁用");
    }

    public boolean isActive() {
        return active;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!plugin.isPickupActive()) return;
        Item item = event.getEntity();
        if (item.isDead()) return;

        PersistentDataContainer pdc = item.getPersistentDataContainer();

        // ✅ 关键：如果已经有标记（来自 HIGHEST 事件），则不设置 UNKNOWN
        if (!pdc.has(SOURCE_KEY, PersistentDataType.STRING)) {
            long currentTick = item.getWorld().getFullTime();
            pdc.set(SPAWN_TICK_KEY, PersistentDataType.LONG, currentTick);
            pdc.set(SOURCE_KEY, PersistentDataType.STRING, ItemSourceType.UNKNOWN.name());
        }

        setPickupDelayToMax(item);

        if (plugin.isItemDrivenEnabled()) {
            activeItemsByWorld.computeIfAbsent(item.getWorld(), w -> ConcurrentHashMap.newKeySet()).add(item);
        }

        // ✅ 延迟 2L，但只转换真正的 UNKNOWN
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!item.isDead()) {
                PersistentDataContainer pdc2 = item.getPersistentDataContainer();
                String current = pdc2.get(SOURCE_KEY, PersistentDataType.STRING);
                // ✅ 只转换 UNKNOWN，不转换已有的 PLAYER_DROP 或 NATURAL_DROP
                if (ItemSourceType.UNKNOWN.name().equals(current)) {
                    pdc2.set(SOURCE_KEY, PersistentDataType.STRING, ItemSourceType.INSTANT_PICKUP.name());
                }
            }
        }, 2L);
    }


    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!plugin.isPickupActive()) return;
        Item item = event.getItemDrop();
        if (item.isDead()) return;

        PersistentDataContainer pdc = item.getPersistentDataContainer();
        long currentTick = item.getWorld().getFullTime();

        // ✅ 强制设置完整标记
        pdc.set(SPAWN_TICK_KEY, PersistentDataType.LONG, currentTick);
        pdc.set(SOURCE_KEY, PersistentDataType.STRING, ItemSourceType.PLAYER_DROP.name());
        pdc.set(DROPPED_BY_KEY, PersistentDataType.STRING, event.getPlayer().getUniqueId().toString());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!plugin.isPickupActive()) return;
        if (event.getDrops().isEmpty()) return;

        Location loc = event.getEntity().getLocation();
        World world = loc.getWorld();
        long currentTick = world.getFullTime();

        // 延迟1 tick，等物品生成后再打标
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Entity entity : world.getNearbyEntities(loc, 2.0, 2.0, 2.0)) {
                if (entity instanceof Item item && !item.isDead()) {
                    PersistentDataContainer pdc = item.getPersistentDataContainer();
                    String source = pdc.get(SOURCE_KEY, PersistentDataType.STRING);
                    if ("UNKNOWN".equals(source)) {
                        pdc.set(SOURCE_KEY, PersistentDataType.STRING, ItemSourceType.NATURAL_DROP.name());
                        // ✅ 不再设置 SPAWN_TICK_KEY！
                    }
                }
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDropItem(BlockDropItemEvent event) {
        if (!plugin.isPickupActive()) return;
        long currentTick = event.getBlock().getWorld().getFullTime();

        for (Item item : event.getItems()) {
            if (item.isDead()) continue;
            PersistentDataContainer pdc = item.getPersistentDataContainer();

            // ✅ 强制设置完整标记
            pdc.set(SPAWN_TICK_KEY, PersistentDataType.LONG, currentTick);
            pdc.set(SOURCE_KEY, PersistentDataType.STRING, ItemSourceType.NATURAL_DROP.name());
        }
    }

    // ========================
    // 拾取逻辑（玩家驱动 + 通用）
    // ========================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!plugin.isPickupActive() || !plugin.isPlayerDriven()) return;

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.SPECTATOR) return;
        if (!activePlayers.contains(player.getUniqueId())) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX() &&
                from.getBlockY() == to.getBlockY() &&
                from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        tryPickup(player);
    }

    private void tryPickup(Player player) {
        double range = plugin.getPickupRange();
        Location loc = player.getLocation();
        World world = player.getWorld();

        for (Entity entity : world.getNearbyEntities(loc, range, range, range)) {
            if (entity instanceof Item item && !item.isDead()) {
                performPickup(player, item);
            }
        }
    }

    private void performPickup(Player player, Item item) {
        if (item.isDead()) return;

        // === 你的全部自定义逻辑 ===
        PersistentDataContainer pdc = item.getPersistentDataContainer();
        Long spawnTickObj = pdc.get(SPAWN_TICK_KEY, PersistentDataType.LONG);
        long currentTick = player.getWorld().getFullTime();
        long ticksSinceSpawn = (spawnTickObj != null) ? currentTick - spawnTickObj : -1;

        String sourceStr = pdc.get(SOURCE_KEY, PersistentDataType.STRING);
        ItemSourceType source = parseSource(sourceStr);

        long requiredDelay = getRequiredDelay(source);
        if (ticksSinceSpawn < requiredDelay) return;

        if (player.getLocation().distanceSquared(item.getLocation()) > pickupRangeSq) return;

        if (source == ItemSourceType.PLAYER_DROP) {
            String droppedBy = pdc.get(DROPPED_BY_KEY, PersistentDataType.STRING);
            if (droppedBy != null && droppedBy.equals(player.getUniqueId().toString())) {
                if (ticksSinceSpawn < selfImmuneTicks) return;
            }
        }
        int amount = item.getItemStack().getAmount();
        PacketUtils.sendPickupAnimation(player, item, amount);
        attemptPickup(player, item);
    }

    private void attemptPickup(Player player, Item item) {
        if (item.isDead()) return;
        ItemStack stack = item.getItemStack().clone();
        if (stack.getType() == Material.AIR) return;

        PlayerInventory inv = player.getInventory();

        // 1. 先尝试加入主背包（含热键栏）
        Map<Integer, ItemStack> leftover = inv.addItem(stack);
        int totalLeftover = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
        int taken = stack.getAmount() - totalLeftover;

        // 2. 如果还有剩余，尝试放入副手（仅当副手为空 或 可堆叠）
        if (totalLeftover > 0) {
            ItemStack offHand = inv.getItemInOffHand();
            ItemStack remaining = leftover.values().iterator().next(); // 只处理第一个（通常只有一个）

            if (offHand.getType() == Material.AIR) {
                // 副手为空：直接放入（最多一组）
                int amountToMove = Math.min(remaining.getMaxStackSize(), remaining.getAmount());
                inv.setItemInOffHand(new ItemStack(remaining.getType(), amountToMove));
                remaining.setAmount(remaining.getAmount() - amountToMove);
                taken += amountToMove;
            } else if (offHand.isSimilar(remaining)) {
                // 副手有同类物品：尝试堆叠
                int space = remaining.getMaxStackSize() - offHand.getAmount();
                if (space > 0) {
                    int amountToMove = Math.min(space, remaining.getAmount());
                    offHand.setAmount(offHand.getAmount() + amountToMove);
                    inv.setItemInOffHand(offHand);
                    remaining.setAmount(remaining.getAmount() - amountToMove);
                    taken += amountToMove;
                }
            }

            // 更新 leftover 状态
            totalLeftover = remaining.getAmount();
        }

        // 3. 根据最终结果更新物品实体
        if (taken > 0) {
            if (totalLeftover <= 0) {
                item.remove();
            } else {
                // 构造新的剩余堆栈
                ItemStack finalRemaining = stack.clone();
                finalRemaining.setAmount(totalLeftover);
                item.setItemStack(finalRemaining);
            }

            float pitch = (float) (0.8 + Math.random() * 0.4);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.1f, pitch);
        }
    }

    // ========================
    // 反射 & 工具方法
    // ========================

    private void setPickupDelayToMax(Item item) {
        try {
            item.setPickupDelay(Integer.MAX_VALUE);
            return;
        } catch (NoSuchMethodError ignored) {
        }

        try {
            Object nmsItem = item.getClass().getMethod("getHandle").invoke(item);
            Field pickupDelayField = getCachedPickupDelayField(nmsItem.getClass());
            if (pickupDelayField != null) {
                pickupDelayField.setAccessible(true);
                pickupDelayField.set(nmsItem, Integer.MAX_VALUE);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to disable vanilla pickup delay: " + e.getMessage());
        }
    }

    private static Field getCachedPickupDelayField(Class<?> nmsItemClass) {
        Field field = cachedPickupDelayField;
        if (field != null) return field;

        synchronized (PickupManager.class) {
            if (cachedPickupDelayField != null) return cachedPickupDelayField;

            Field pickupDelayField = null;
            for (Field f : nmsItemClass.getDeclaredFields()) {
                if (f.getType() == int.class) {
                    String name = f.getName().toLowerCase();
                    if (name.contains("delay") || name.equals("e") || name.equals("bv")) {
                        pickupDelayField = f;
                        break;
                    }
                }
            }

            if (pickupDelayField == null) {
                int intFieldIndex = 0;
                for (Field f : nmsItemClass.getDeclaredFields()) {
                    if (f.getType() == int.class) {
                        if (intFieldIndex == 2) {
                            pickupDelayField = f;
                            break;
                        }
                        intFieldIndex++;
                    }
                }
            }

            cachedPickupDelayField = pickupDelayField;
            return pickupDelayField;
        }
    }

    // ========================
    // 枚举
    // ========================

    public enum ItemSourceType {
        PLAYER_DROP,      // 玩家丢弃
        NATURAL_DROP,     // 怪物死亡、方块破坏
        INSTANT_PICKUP,   // 其他所有（命令、投掷器、漏斗等）
        UNKNOWN           // 临时状态，1 tick 后转为 INSTANT_PICKUP
    }
}