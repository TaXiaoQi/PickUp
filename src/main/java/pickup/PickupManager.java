package pickup;

import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PickupManager implements Listener {
    private final PickUp plugin;
    private double pickupRangeSq;
    private int playerDropDelayTicks;     // 玩家丢弃冷却
    private int naturalDropDelayTicks;    // 怪物/方块掉落冷却
    private int instantPickupDelayTicks;  // 红石/命令等即时物品冷却（可设为0）
    private int selfImmuneTicks;
    private int activeDetectionTicks;
    private boolean active = false;
    private static volatile Field cachedPickupDelayField = null;

    // NBT Keys
    private static final NamespacedKey SPAWN_TICK_KEY = new NamespacedKey("pickup", "spawn_tick");
    private static final NamespacedKey DROPPED_BY_KEY = new NamespacedKey("pickup", "dropped_by");
    private static final NamespacedKey SOURCE_KEY = new NamespacedKey("pickup", "source");

    // Player-Driven: 活跃玩家集合
    private final Set<UUID> activePlayers = ConcurrentHashMap.newKeySet();
    private BukkitRunnable activePlayerUpdater = null;

    // Item-Driven: 按世界追踪活跃物品
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
        this.instantPickupDelayTicks = plugin.getInstantPickupDelayTicks(); // 通常为0，但可配置
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

                            // 获取来源类型
                            String sourceStr = pdc.get(SOURCE_KEY, PersistentDataType.STRING);
                            ItemSourceType source = parseSource(sourceStr);

                            long requiredDelay = getRequiredDelay(source);
                            if (age < requiredDelay) {
                                continue;
                            }

                            // 查找最近玩家
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

        plugin.getLogger().info("PickupManager 已启用（双引擎：玩家驱动 + 物品驱动）");
    }

    private long getRequiredDelay(ItemSourceType source) {
        return switch (source) {
            case PLAYER_DROP -> playerDropDelayTicks;
            case NATURAL_DROP -> naturalDropDelayTicks;
            case INSTANT_PICKUP -> instantPickupDelayTicks;
        };
    }

    private ItemSourceType parseSource(String sourceStr) {
        if (sourceStr == null) return ItemSourceType.NATURAL_DROP;
        try {
            return ItemSourceType.valueOf(sourceStr);
        } catch (IllegalArgumentException e) {
            return ItemSourceType.NATURAL_DROP;
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

    // ========== 事件监听 ==========

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!plugin.isPickupActive()) return;
        Item item = event.getEntity();
        if (item.isDead()) return;

        PersistentDataContainer pdc = item.getPersistentDataContainer();
        if (pdc.has(SPAWN_TICK_KEY, PersistentDataType.LONG)) return;

        long currentTick = item.getWorld().getFullTime();
        pdc.set(SPAWN_TICK_KEY, PersistentDataType.LONG, currentTick);

        // === 关键：记录原始 pickupDelay 以判断来源 ===
        int originalDelay = item.getPickupDelay();
        setPickupDelayToMax(item); // 所有物品 delay 拉满，交由插件控制

        // 根据原始 delay 判断来源
        if (originalDelay == 0) {
            pdc.set(SOURCE_KEY, PersistentDataType.STRING, ItemSourceType.INSTANT_PICKUP.name());
        } else {
            pdc.set(SOURCE_KEY, PersistentDataType.STRING, ItemSourceType.NATURAL_DROP.name());
        }

        if (plugin.isItemDrivenEnabled()) {
            activeItemsByWorld.computeIfAbsent(item.getWorld(), w -> ConcurrentHashMap.newKeySet()).add(item);
        }
    }

    private void setPickupDelayToMax(Item item) {
        try {
            item.getClass().getMethod("setPickupDelay", int.class);
            item.setPickupDelay(Integer.MAX_VALUE);
            return;
        } catch (NoSuchMethodException ignored) {
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!plugin.isPickupActive()) return;
        Item item = event.getItemDrop();
        if (item.isDead()) return;

        PersistentDataContainer pdc = item.getPersistentDataContainer();
        pdc.set(SOURCE_KEY, PersistentDataType.STRING, ItemSourceType.PLAYER_DROP.name());
        pdc.set(DROPPED_BY_KEY, PersistentDataType.STRING, event.getPlayer().getUniqueId().toString());
    }

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
        ItemStack stack = item.getItemStack();
        if (stack.getType() == Material.AIR) return;

        PersistentDataContainer pdc = item.getPersistentDataContainer();
        Long spawnTickObj = pdc.get(SPAWN_TICK_KEY, PersistentDataType.LONG);
        long currentTick = player.getWorld().getFullTime();
        long ticksSinceSpawn = (spawnTickObj != null) ? currentTick - spawnTickObj : Long.MAX_VALUE;

        String sourceStr = pdc.get(SOURCE_KEY, PersistentDataType.STRING);
        ItemSourceType source = parseSource(sourceStr);

        long requiredDelay = getRequiredDelay(source);
        if (ticksSinceSpawn < requiredDelay) {
            return;
        }

        // 自免疫：仅对 PLAYER_DROP 且同一玩家
        if (source == ItemSourceType.PLAYER_DROP) {
            String droppedBy = pdc.get(DROPPED_BY_KEY, PersistentDataType.STRING);
            if (droppedBy != null && droppedBy.equals(player.getUniqueId().toString())) {
                if (ticksSinceSpawn < selfImmuneTicks) {
                    return;
                }
            }
        }

        if (player.getLocation().distanceSquared(item.getLocation()) > pickupRangeSq) {
            return;
        }

        attemptPickup(player, item);
    }

    private void attemptPickup(Player player, Item item) {
        ItemStack stack = item.getItemStack().clone();
        if (stack.getType() == Material.AIR) return;

        int originalAmount = stack.getAmount();
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        int totalLeftover = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
        int taken = originalAmount - totalLeftover;

        if (taken > 0) {
            if (totalLeftover == 0) {
                item.remove();
            } else {
                ItemStack remaining = stack.clone();
                remaining.setAmount(totalLeftover);
                item.setItemStack(remaining);
            }

            float pitch = (float) (0.8 + Math.random() * 0.4);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.1f, pitch);
        }
    }

    // 枚举定义（建议放在单独文件，但这里内联方便）
    public enum ItemSourceType {
        PLAYER_DROP,
        NATURAL_DROP,
        INSTANT_PICKUP
    }
}