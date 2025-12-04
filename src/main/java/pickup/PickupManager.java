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

// 拾取类
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

    private static final NamespacedKey SPAWN_TICK_KEY = new NamespacedKey("pickup", "spawn_tick");
    private static final NamespacedKey DROPPED_BY_KEY = new NamespacedKey("pickup", "dropped_by");
    private static final NamespacedKey SOURCE_KEY = new NamespacedKey("pickup", "source");

    private final Set<UUID> activePlayers = ConcurrentHashMap.newKeySet();
    private BukkitRunnable activePlayerUpdater = null;

    private final Map<World, Set<Item>> activeItemsByWorld = new ConcurrentHashMap<>();
    private BukkitRunnable itemDetectionTask = null;

    public PickupManager(PickUp plugin) {
        this.plugin = plugin;
    }
    private CustomItemMerger getCustomItemMerger() {
        return plugin.getItemMerger();
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

        if (plugin.isPlayerDriven()) {
            int scanInterval = Math.max(1, plugin.getPlayerDrivenScanIntervalTicks());
            activePlayerUpdater = new BukkitRunnable() {
                @Override
                public void run() {
                    if (plugin.isPickupDisabled()) {
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

        if (plugin.isItemDrivenEnabled()) {
            for (World world : Bukkit.getWorlds()) {
                activeItemsByWorld.put(world, ConcurrentHashMap.newKeySet());
            }

            int attemptInterval = Math.max(1, plugin.getPickupAttemptIntervalTicks());
            itemDetectionTask = new BukkitRunnable() {
                @Override
                public void run() {
                    if (plugin.isPickupDisabled()) return;

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
        if (plugin.isPickupDisabled()) {
            return;
        }

        Item item = event.getEntity();
        if (item.isDead()) return;

        PersistentDataContainer pdc = item.getPersistentDataContainer();

        if (!pdc.has(SOURCE_KEY, PersistentDataType.STRING)) {
            long currentTick = item.getWorld().getFullTime();
            pdc.set(SPAWN_TICK_KEY, PersistentDataType.LONG, currentTick);
            pdc.set(SOURCE_KEY, PersistentDataType.STRING, ItemSourceType.UNKNOWN.name());
        }

        setPickupDelayToMax(item);

        if (plugin.isItemDrivenEnabled()) {
            activeItemsByWorld.computeIfAbsent(item.getWorld(), w -> ConcurrentHashMap.newKeySet()).add(item);
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!item.isDead()) {
                PersistentDataContainer pdc2 = item.getPersistentDataContainer();
                String current = pdc2.get(SOURCE_KEY, PersistentDataType.STRING);
                if (ItemSourceType.UNKNOWN.name().equals(current)) {
                    pdc2.set(SOURCE_KEY, PersistentDataType.STRING, ItemSourceType.INSTANT_PICKUP.name());
                }

                CustomItemMerger merger = getCustomItemMerger();
                if (merger != null) {
                    merger.notifyItemReady(item);
                }
            }
        }, 2L);
    }

    public void restoreOriginalPickupDelay() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Item item && !item.isDead()) {
                    PersistentDataContainer pdc = item.getPersistentDataContainer();
                    if (pdc.has(SPAWN_TICK_KEY, PersistentDataType.LONG) || pdc.has(SOURCE_KEY, PersistentDataType.STRING)) {
                        try {
                            item.setPickupDelay(10);
                        } catch (Exception e) {
                            //
                        }
                    }
                }
            }
        }
        plugin.getLogger().info("已恢复所有被管理物品的拾取延迟");
    }


    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (plugin.isPickupDisabled()) return;
        Item item = event.getItemDrop();
        if (item.isDead()) return;

        PersistentDataContainer pdc = item.getPersistentDataContainer();
        long currentTick = item.getWorld().getFullTime();

        pdc.set(SPAWN_TICK_KEY, PersistentDataType.LONG, currentTick);
        pdc.set(SOURCE_KEY, PersistentDataType.STRING, ItemSourceType.PLAYER_DROP.name());
        pdc.set(DROPPED_BY_KEY, PersistentDataType.STRING, event.getPlayer().getUniqueId().toString());

        CustomItemMerger merger = getCustomItemMerger();
        if (merger != null) {
            merger.notifyItemReady(item);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (plugin.isPickupDisabled()) return;
        if (event.getDrops().isEmpty()) return;

        Location loc = event.getEntity().getLocation();
        World world = loc.getWorld();
        @SuppressWarnings("unused")
        long currentTick = world.getFullTime();

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Entity entity : world.getNearbyEntities(loc, 2.0, 2.0, 2.0)) {
                if (entity instanceof Item item && !item.isDead()) {
                    PersistentDataContainer pdc = item.getPersistentDataContainer();
                    String source = pdc.get(SOURCE_KEY, PersistentDataType.STRING);
                    if ("UNKNOWN".equals(source)) {
                        pdc.set(SOURCE_KEY, PersistentDataType.STRING, ItemSourceType.NATURAL_DROP.name());
                        CustomItemMerger merger = getCustomItemMerger();
                        if (merger != null) {
                            merger.notifyItemReady(item);
                        }
                    }
                }
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDropItem(BlockDropItemEvent event) {
        if (plugin.isPickupDisabled()) return;
        long currentTick = event.getBlock().getWorld().getFullTime();

        for (Item item : event.getItems()) {
            if (item.isDead()) continue;
            PersistentDataContainer pdc = item.getPersistentDataContainer();

            pdc.set(SPAWN_TICK_KEY, PersistentDataType.LONG, currentTick);
            pdc.set(SOURCE_KEY, PersistentDataType.STRING, ItemSourceType.NATURAL_DROP.name());

            CustomItemMerger merger = getCustomItemMerger();
            if (merger != null) {
                merger.notifyItemReady(item);
            }
        }
    }


    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (plugin.isPickupDisabled() || !plugin.isPlayerDriven()) return;

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

        attemptPickup(player, item);
    }

    private void attemptPickup(Player player, Item item) {
        if (item == null || item.isDead()) return;

        ItemStack originalStack = item.getItemStack();
        if (originalStack.getType() == Material.AIR || originalStack.getAmount() <= 0) {
            return;
        }

        PlayerInventory inv = player.getInventory();
        ItemStack stack = originalStack.clone(); // 完整克隆，保留所有 NBT（潜影盒内容、附魔等）
        int taken = 0;

        // === Step 1: 尝试合并到副手（仅当副手已有相同物品）===
        ItemStack offHand = inv.getItemInOffHand();
        if (!offHand.getType().isAir() && offHand.isSimilar(stack)) {
            int space = Math.min(stack.getMaxStackSize() - offHand.getAmount(), stack.getAmount());
            if (space > 0) {
                offHand.setAmount(offHand.getAmount() + space);
                inv.setItemInOffHand(offHand);
                stack.setAmount(stack.getAmount() - space);
                taken += space;
            }
        }

        // === Step 2: 如果还有剩余，尝试放入主背包（36格：0~35）===
        if (stack.getAmount() > 0) {
            int remaining = stack.getAmount();
            ItemStack toPlace = stack.clone(); // toPlace 用于创建新堆，必须保留 NBT

            // 2.1 先尝试合并到已有同类堆叠（遍历全部36格）
            for (int slot = 0; slot < 36; slot++) {
                if (remaining <= 0) break;
                ItemStack existing = inv.getItem(slot);
                if (existing != null && existing.isSimilar(toPlace) && existing.getAmount() < existing.getMaxStackSize()) {
                    int space = Math.min(existing.getMaxStackSize() - existing.getAmount(), remaining);
                    existing.setAmount(existing.getAmount() + space);
                    inv.setItem(slot, existing);
                    remaining -= space;
                    taken += space;
                }
            }

            // 2.2 如果还有剩余，尝试放入主手快捷栏（0~8）的空位
            if (remaining > 0) {
                int heldSlot = player.getInventory().getHeldItemSlot(); // 通常是 0~8
                ItemStack heldItem = inv.getItem(heldSlot);
                if (heldItem == null || heldItem.getType() == Material.AIR) {
                    int placeAmount = Math.min(toPlace.getMaxStackSize(), remaining);
                    ItemStack placed = toPlace.clone(); // ✅ 安全：保留 NBT
                    placed.setAmount(placeAmount);
                    inv.setItem(heldSlot, placed);
                    remaining -= placeAmount;
                    taken += placeAmount;
                }
            }

            // 2.3 再尝试热键栏（0~8）其他空位
            if (remaining > 0) {
                for (int slot = 0; slot < 9; slot++) {
                    if (remaining <= 0) break;
                    ItemStack existing = inv.getItem(slot);
                    if (existing == null || existing.getType() == Material.AIR) {
                        int placeAmount = Math.min(toPlace.getMaxStackSize(), remaining);
                        ItemStack placed = toPlace.clone(); // ✅ 安全：保留 NBT
                        placed.setAmount(placeAmount);
                        inv.setItem(slot, placed);
                        remaining -= placeAmount;
                        taken += placeAmount;
                    }
                }
            }

            // 2.4 最后尝试主背包（9~35）空位
            if (remaining > 0) {
                for (int slot = 9; slot < 36; slot++) {
                    if (remaining <= 0) break;
                    ItemStack existing = inv.getItem(slot);
                    if (existing == null || existing.getType() == Material.AIR) {
                        int placeAmount = Math.min(toPlace.getMaxStackSize(), remaining);
                        ItemStack placed = toPlace.clone(); // ✅ 安全：保留 NBT
                        placed.setAmount(placeAmount);
                        inv.setItem(slot, placed);
                        remaining -= placeAmount;
                        taken += placeAmount;
                    }
                }
            }

            // 更新剩余数量
            stack.setAmount(Math.max(remaining, 0));
        }

        // === Step 3: 应用拾取结果 ===
        if (taken > 0) {
            if (stack.getAmount() <= 0) {
                item.remove(); // 全部拾取
            } else {
                item.setItemStack(stack); // 部分拾取，更新掉落物
            }

            // 发送拾取动画和音效
            PacketUtils.sendPickupAnimation(plugin, player, item, taken);
            float pitch = (float) (0.8 + Math.random() * 0.4);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.1f, pitch);
            broadcastPickupAnimation(item, player.getEntityId(), taken);
        }
    }

    private void broadcastPickupAnimation(Item item, int collectorEntityId, int amount) {
        Location loc = item.getLocation();
        World world = loc.getWorld();
        if (world == null) return;

        Collection<Player> viewers = world.getNearbyEntitiesByType(Player.class, loc, 64.0);
        for (Player viewer : viewers) {
            PacketUtils.sendPickupAnimation(plugin, viewer, item, collectorEntityId, amount);
        }
    }


    private void setPickupDelayToMax(Item item) {
        try {
            item.setPickupDelay(Integer.MAX_VALUE);
            return;
        } catch (NoSuchMethodError | AbstractMethodError e) {
            //
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to set pickup delay via API: " + e.getMessage());
            return;
        }

        try {
            Object nmsItem = item.getClass().getMethod("getHandle").invoke(item);
            Field pickupDelayField = getCachedPickupDelayField(nmsItem.getClass());
            if (pickupDelayField != null) {
                pickupDelayField.setAccessible(true);
                pickupDelayField.set(nmsItem, Integer.MAX_VALUE);
            } else {
                plugin.getLogger().warning("Could not find pickupDelay field in NMS Item class.");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to disable vanilla pickup delay via reflection: " + e.getMessage());
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

    public enum ItemSourceType {
        PLAYER_DROP,      // 玩家丢弃
        NATURAL_DROP,     // 怪物死亡、方块破坏
        INSTANT_PICKUP,   // 其他所有（命令、投掷器、漏斗等）
        UNKNOWN           // 初始状态
    }
}