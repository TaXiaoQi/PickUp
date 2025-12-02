package pickup;

import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PickupManager implements Listener {
    private final PickUp plugin;
    private double pickupRangeSq;
    private int throwCooldownTicks;
    private int selfImmuneTicks;
    private int activeDetectionTicks;
    private boolean active = false;

    // NBT Keys
    private static final NamespacedKey SPAWN_TICK_KEY = new NamespacedKey("pickup", "spawn_tick");
    private static final NamespacedKey DROPPED_BY_KEY = new NamespacedKey("pickup", "dropped_by");
    private static final NamespacedKey IS_DROPPED_KEY = new NamespacedKey("pickup", "is_dropped");

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
        this.throwCooldownTicks = plugin.getThrowCooldownTicks();
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
                    if (!shouldRun()) {
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
            // 初始化每个世界的活跃物品集
            for (World world : Bukkit.getWorlds()) {
                activeItemsByWorld.put(world, ConcurrentHashMap.newKeySet());
            }

            int attemptInterval = Math.max(1, plugin.getPickupAttemptIntervalTicks());
            itemDetectionTask = new BukkitRunnable() {
                @Override
                public void run() {
                    if (!shouldRun()) return;

                    long currentTick = Bukkit.getWorlds().getFirst().getFullTime(); // 全局 tick

                    for (Map.Entry<World, Set<Item>> entry : activeItemsByWorld.entrySet()) {
                        World world = entry.getKey();
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

                            // 超过活跃上限 → 停止追踪
                            if (age > activeDetectionTicks) {
                                toRemove.add(item);
                                continue;
                            }

                            // 全局投掷冷却
                            if (age < throwCooldownTicks) {
                                continue;
                            }

                            // 查找最近有效玩家
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

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean shouldRun() {
        return plugin.isPickupEnabled() && !plugin.isStoppedByCommand();
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
        if (!shouldRun()) return;
        Item item = event.getEntity();
        if (item.isDead()) return;

        PersistentDataContainer pdc = item.getPersistentDataContainer();
        if (pdc.has(SPAWN_TICK_KEY, PersistentDataType.LONG)) return;

        long currentTick = item.getWorld().getFullTime();
        pdc.set(SPAWN_TICK_KEY, PersistentDataType.LONG, currentTick);
        pdc.set(IS_DROPPED_KEY, PersistentDataType.BYTE, (byte) 0); // 默认非玩家丢出

        // 如果启用物品驱动，加入追踪
        if (plugin.isItemDrivenEnabled()) {
            activeItemsByWorld.computeIfAbsent(item.getWorld(), w -> ConcurrentHashMap.newKeySet()).add(item);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!shouldRun()) return;
        Item item = event.getItemDrop();
        if (item.isDead()) return;

        PersistentDataContainer pdc = item.getPersistentDataContainer();
        pdc.set(IS_DROPPED_KEY, PersistentDataType.BYTE, (byte) 1);
        pdc.set(DROPPED_BY_KEY, PersistentDataType.STRING, event.getPlayer().getUniqueId().toString());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        if (!shouldRun()) return;
        event.setCancelled(true); // 强制走自定义逻辑
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!shouldRun() || !plugin.isPlayerDriven()) return;

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.SPECTATOR) return;
        if (!activePlayers.contains(player.getUniqueId())) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX() &&
                from.getBlockY() == to.getBlockY() &&
                from.getBlockZ() == to.getBlockZ()) {
            return; // 防抖：未跨方块移动
        }

        tryPickup(player);
    }

    // ========== 核心拾取逻辑 ==========

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
        if (item.isDead() || item.getItemStack().getType() == Material.AIR) return;

        PersistentDataContainer pdc = item.getPersistentDataContainer();
        Long spawnTickObj = pdc.get(SPAWN_TICK_KEY, PersistentDataType.LONG);

        long currentTick = player.getWorld().getFullTime();
        long ticksSinceSpawn = (spawnTickObj != null) ? currentTick - spawnTickObj : Long.MAX_VALUE;

        // 投掷冷却（适用于所有物品）
        if (ticksSinceSpawn < throwCooldownTicks) {
            return;
        }

        // 自免疫逻辑（仅对玩家丢出的物品生效）
        Byte isDroppedByte = pdc.get(IS_DROPPED_KEY, PersistentDataType.BYTE);
        boolean isDropped = (isDroppedByte != null && isDroppedByte == 1);
        if (isDropped) {
            String droppedBy = pdc.get(DROPPED_BY_KEY, PersistentDataType.STRING);
            if (droppedBy != null && droppedBy.equals(player.getUniqueId().toString())) {
                if (ticksSinceSpawn < selfImmuneTicks) {
                    return;
                }
            }
        }

        // 距离检查（防止因异步或移动导致超出范围）
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
}