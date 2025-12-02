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
    private boolean active = false;

    private static final NamespacedKey SPAWN_TICK_KEY = new NamespacedKey("pickup", "spawn_tick");
    private static final NamespacedKey DROPPED_BY_KEY = new NamespacedKey("pickup", "dropped_by");
    private static final NamespacedKey IS_DROPPED_KEY = new NamespacedKey("pickup", "is_dropped");
    private static final NamespacedKey DETECTION_START_KEY = new NamespacedKey("pickup", "detection_start");
    private static final NamespacedKey DETECTION_END_KEY = new NamespacedKey("pickup", "detection_end");

    private final Map<World, Set<ChunkCoord>> pendingChunksByWorld = new ConcurrentHashMap<>();
    private BukkitRunnable detectionTask = null;

    public PickupManager(PickUp plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        double pickupRange = plugin.getPickupRange();
        pickupRangeSq = pickupRange * pickupRange;
    }

    public void enable() {
        if (active) return;
        active = true;
        registerExistingItems();
        if (plugin.isItemDrivenEnabled()) {
            startDetectionTask();
        }

        plugin.getLogger().info("PickupManager 已启用（NBT + 双模式 + 多世界）");
    }

    private void registerExistingItems() {
        for (World world : Bukkit.getWorlds()) {
            if (world.getPlayers().isEmpty()) continue;

            Set<ChunkCoord> chunkSet = pendingChunksByWorld.computeIfAbsent(world, w -> ConcurrentHashMap.newKeySet());

            for (Chunk chunk : world.getLoadedChunks()) {
                boolean hasManagedItem = false;
                for (Entity entity : chunk.getEntities()) {
                    if (entity instanceof Item item && !item.isDead()) {
                        PersistentDataContainer pdc = item.getPersistentDataContainer();
                        if (pdc.has(SPAWN_TICK_KEY, PersistentDataType.LONG)) {
                            // 是本插件管理的物品
                            Long detectionEnd = pdc.get(DETECTION_END_KEY, PersistentDataType.LONG);
                            int currentTick = Bukkit.getCurrentTick();
                            if (detectionEnd != null && currentTick < detectionEnd) {
                                hasManagedItem = true;
                                break; // 只要有一个就注册该 chunk
                            }
                        }
                    }
                }
                if (hasManagedItem) {
                    chunkSet.add(new ChunkCoord(chunk.getX(), chunk.getZ()));
                }
            }
        }
    }

    public void disable() {
        if (!active) return;
        active = false;

        if (detectionTask != null) {
            detectionTask.cancel();
            detectionTask = null;
        }
        pendingChunksByWorld.clear();

        plugin.getLogger().info("PickupManager 已禁用");
    }

    public boolean isActive() {
        return active;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!plugin.isPickupEnabled()) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            Item item = event.getEntity();
            if (item.isDead()) return;

            PersistentDataContainer pdc = item.getPersistentDataContainer();
            if (pdc.has(SPAWN_TICK_KEY, PersistentDataType.LONG)) {
                return;
            }

            int currentTick = Bukkit.getCurrentTick();
            int throwCooldown = plugin.getThrowCooldownTicks();
            int activeDetection = plugin.getActiveDetectionTicks();

            long spawnTick = currentTick;
            long detectionStart = spawnTick + throwCooldown;
            long detectionEnd = detectionStart + activeDetection;

            pdc.set(SPAWN_TICK_KEY, PersistentDataType.LONG, spawnTick);
            pdc.set(DETECTION_START_KEY, PersistentDataType.LONG, detectionStart);
            pdc.set(DETECTION_END_KEY, PersistentDataType.LONG, detectionEnd);
            pdc.set(IS_DROPPED_KEY, PersistentDataType.BYTE, (byte) 0);

            if (plugin.isItemDrivenEnabled()) {
                Chunk chunk = item.getLocation().getChunk();
                pendingChunksByWorld.computeIfAbsent(item.getWorld(), w -> ConcurrentHashMap.newKeySet())
                        .add(new ChunkCoord(chunk.getX(), chunk.getZ()));
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!plugin.isPickupEnabled()) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            Item item = event.getItemDrop();
            if (item.isDead()) return;

            PersistentDataContainer pdc = item.getPersistentDataContainer();
            if (!pdc.has(IS_DROPPED_KEY, PersistentDataType.BYTE)) {
                pdc.set(IS_DROPPED_KEY, PersistentDataType.BYTE, (byte) 1);
                pdc.set(DROPPED_BY_KEY, PersistentDataType.STRING, event.getPlayer().getUniqueId().toString());
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        if (!plugin.isPickupEnabled()) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!plugin.isPickupEnabled() || !plugin.isPlayerDriven()) return;
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.SPECTATOR) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX() &&
                from.getBlockY() == to.getBlockY() &&
                from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        tryPlayerPickup(player);
    }

    private void tryPlayerPickup(Player player) {
        Location loc = player.getLocation();
        World world = player.getWorld();
        double range = plugin.getPickupRange();

        for (Entity entity : world.getNearbyEntities(loc, range, range, range)) {
            if (entity instanceof Item item && !item.isDead()) {
                tryPickup(player, item);
            }
        }
    }

    private void tryPickup(Player player, Item item) {
        if (item.isDead() || item.getItemStack().getType() == Material.AIR) {
            return;
        }

        PersistentDataContainer pdc = item.getPersistentDataContainer();
        Long spawnTick = pdc.get(SPAWN_TICK_KEY, PersistentDataType.LONG);
        int currentTick = Bukkit.getCurrentTick();

        if (spawnTick == null) {
            attemptPickup(player, item);
            return;
        }

        int ticksSinceSpawn = currentTick - spawnTick.intValue();
        if (ticksSinceSpawn < plugin.getThrowCooldownTicks()) {
            return;
        }

        Byte isDroppedByte = pdc.get(IS_DROPPED_KEY, PersistentDataType.BYTE);
        boolean isDropped = (isDroppedByte != null && isDroppedByte == 1);
        if (isDropped) {
            String droppedByStr = pdc.get(DROPPED_BY_KEY, PersistentDataType.STRING);
            if (droppedByStr != null) {
                try {
                    UUID droppedBy = UUID.fromString(droppedByStr);
                    if (player.getUniqueId().equals(droppedBy)) {
                        if (ticksSinceSpawn < plugin.getSelfImmuneTicks()) {
                            return;
                        }
                    }
                } catch (IllegalArgumentException ignored) {}
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

    private void startDetectionTask() {
        int interval = plugin.getPickupAttemptIntervalTicks();
        if (interval <= 0) interval = 5;

        detectionTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!plugin.isItemDrivenEnabled() || !active) return;

                int currentTick = Bukkit.getCurrentTick();

                for (Map.Entry<World, Set<ChunkCoord>> entry : pendingChunksByWorld.entrySet()) {
                    World world = entry.getKey();
                    if (world.getPlayers().isEmpty()) continue;

                    Set<ChunkCoord> chunkSet = entry.getValue();
                    Set<ChunkCoord> snapshot = new HashSet<>(chunkSet);
                    chunkSet.clear();

                    for (ChunkCoord coord : snapshot) {
                        if (!world.isChunkLoaded(coord.x, coord.z)) continue;

                        Chunk chunk = world.getChunkAt(coord.x, coord.z);
                        boolean chunkStillActive = false;

                        for (Entity entity : chunk.getEntities()) {
                            if (entity instanceof Item item && !item.isDead()) {
                                PersistentDataContainer pdc = item.getPersistentDataContainer();
                                Long detectionEnd = pdc.get(DETECTION_END_KEY, PersistentDataType.LONG);

                                if (detectionEnd != null && currentTick < detectionEnd) {
                                    processItemForPickup(item);

                                    if (!item.isDead()) {
                                        Long updatedEnd = pdc.get(DETECTION_END_KEY, PersistentDataType.LONG);
                                        if (updatedEnd != null && currentTick < updatedEnd) {
                                            chunkStillActive = true;
                                        }
                                    }
                                }
                            }
                        }

                        if (chunkStillActive) {
                            chunkSet.add(coord);
                        }
                    }
                }
            }
        };
        detectionTask.runTaskTimer(plugin, 0, interval);
    }

    private void processItemForPickup(Item item) {
        if (item.isDead()) return;

        PersistentDataContainer pdc = item.getPersistentDataContainer();
        Long detectionStart = pdc.get(DETECTION_START_KEY, PersistentDataType.LONG);
        Long detectionEnd = pdc.get(DETECTION_END_KEY, PersistentDataType.LONG);

        if (detectionStart == null || detectionEnd == null) {
            return;
        }

        int currentTick = Bukkit.getCurrentTick();
        if (currentTick < detectionStart || currentTick >= detectionEnd) {
            return;
        }

        Location loc = item.getLocation();
        World world = loc.getWorld();
        Player nearest = null;
        double minDistSq = Double.MAX_VALUE;

        for (Player player : world.getPlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR) continue;
            double distSq = player.getLocation().distanceSquared(loc);
            if (distSq <= pickupRangeSq && distSq < minDistSq) {
                nearest = player;
                minDistSq = distSq;
            }
        }

        if (nearest != null) {
            tryPickup(nearest, item);
        }
    }

    private record ChunkCoord(int x, int z) {}
}