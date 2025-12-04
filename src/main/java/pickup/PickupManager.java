package pickup;

import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.inventory.PlayerInventory;


import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PickupManager {

    private final PickUp plugin;

    private double pickupRangeSq;
    private int playerDropDelayTicks;
    private int naturalDropDelayTicks;
    private int instantPickupDelayTicks;
    private int selfImmuneTicks;
    private int activeDetectionTicks;

    private boolean active = false;

    // PersistentDataContainer keys
    private static final NamespacedKey SPAWN_TICK_KEY = new NamespacedKey("pickup", "spawn_tick");
    private static final NamespacedKey DROPPED_BY_KEY = new NamespacedKey("pickup", "dropped_by");
    private static final NamespacedKey SOURCE_KEY = new NamespacedKey("pickup", "source");

    // Player-driven mode
    private final Set<UUID> activePlayers = ConcurrentHashMap.newKeySet();
    private BukkitRunnable activePlayerUpdater = null;

    // Item-driven mode
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

    // ====== 公共入口：由 PickupEvent 调用 ======
    public void handleItemSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();

        // 从 ItemStack 继承来源标记
        ItemStack stack = item.getItemStack();
        String source = stack.getItemMeta()
                .getPersistentDataContainer()
                .get(SOURCE_KEY, PersistentDataType.STRING);

        if (source != null) {
            // 写入 Item 实体的 PDC（可选，用于后续判断）
            item.getPersistentDataContainer().set(SOURCE_KEY, PersistentDataType.STRING, source);
            item.getPersistentDataContainer().set(SPAWN_TICK_KEY, PersistentDataType.LONG, item.getWorld().getFullTime());
        } else {
            // 默认视为 INSTANT_PICKUP 或 NATURAL_DROP
            markItemAsNaturalDrop(item);
        }

        if (plugin.isItemDrivenEnabled()) {
            addToActiveItems(item);
        }
        disableVanillaPickup(item);
        notifyMerger(item);
    }

    public void handlePlayerDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        Item item = event.getItemDrop();
        markItemAsPlayerDrop(item, player.getUniqueId());
        if (plugin.isItemDrivenEnabled()) {
            addToActiveItems(item);
        }
        disableVanillaPickup(item);
        notifyMerger(item);
    }

    public void handleBlockDrop(BlockDropItemEvent event) {
        for (Item item : event.getItems()) {
            markItemAsNaturalDrop(item);
            if (plugin.isItemDrivenEnabled()) {
                addToActiveItems(item);
            }
            disableVanillaPickup(item);
            notifyMerger(item);
        }
    }

    public void handleEntityDeath(EntityDeathEvent event) {
        // 仅标记 ItemStack 来源为 "natural"
        for (ItemStack stack : event.getDrops()) {
            markItemStackAsNaturalDrop(stack); // 新增方法
        }
    }

    private void markItemStackAsNaturalDrop(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return;
        stack.editMeta(meta -> meta.getPersistentDataContainer().set(
                SOURCE_KEY,
                PersistentDataType.STRING,
                ItemSourceType.NATURAL_DROP.name()
        ));
    }

    /**
     * 供 PickupEvent.onPlayerMove() 调用，执行玩家驱动的拾取扫描
     */
    public void tryPickup(Player player) {
        if (player.getGameMode() == GameMode.SPECTATOR) return;

        Location loc = player.getLocation();
        World world = loc.getWorld();
        double range = plugin.getPickupRange();
        List<Item> nearbyItems = new ArrayList<>();

        for (Entity entity : world.getNearbyEntities(loc, range, range, range)) {
            if (entity instanceof Item) {
                nearbyItems.add((Item) entity);
            }
        }

        for (Item item : nearbyItems) {
            if (canPickupNow(player, item)) {
                performPickup(player, item);
            }
        }
    }

    // ====== 内部逻辑 ======

    private void markItemAsPlayerDrop(Item item, UUID playerId) {
        PersistentDataContainer pdc = item.getPersistentDataContainer();
        pdc.set(SPAWN_TICK_KEY, PersistentDataType.LONG, item.getWorld().getFullTime());
        pdc.set(DROPPED_BY_KEY, PersistentDataType.STRING, playerId.toString());
        pdc.set(SOURCE_KEY, PersistentDataType.STRING, ItemSourceType.PLAYER_DROP.name());
    }

    private void markItemAsNaturalDrop(Item item) {
        PersistentDataContainer pdc = item.getPersistentDataContainer();
        pdc.set(SPAWN_TICK_KEY, PersistentDataType.LONG, item.getWorld().getFullTime());
        pdc.set(SOURCE_KEY, PersistentDataType.STRING, ItemSourceType.NATURAL_DROP.name());
    }

    private void addToActiveItems(Item item) {
        activeItemsByWorld.computeIfAbsent(item.getWorld(), w -> ConcurrentHashMap.newKeySet()).add(item);
    }

    private void disableVanillaPickup(Item item) {
        try {
            Field field = getItemPickupDelayField();
            field.setAccessible(true);
            field.set(item, Integer.MAX_VALUE);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to disable vanilla pickup delay via reflection.");
        }
    }

    private void notifyMerger(Item item) {
        CustomItemMerger merger = getCustomItemMerger();
        if (merger != null) {
            merger.notifyItemReady(item);
        }
    }

    private boolean canPickupNow(Player player, Item item) {
        long currentTime = item.getWorld().getFullTime();
        PersistentDataContainer pdc = item.getPersistentDataContainer();

        Long spawnTick = pdc.get(SPAWN_TICK_KEY, PersistentDataType.LONG);
        String sourceStr = pdc.get(SOURCE_KEY, PersistentDataType.STRING);
        ItemSourceType source = parseSource(sourceStr);

        if (spawnTick == null) {
            spawnTick = currentTime;
        }

        long requiredDelay = getRequiredDelay(source);
        if (currentTime - spawnTick < requiredDelay) {
            return false;
        }

        if (source == ItemSourceType.PLAYER_DROP) {
            String droppedByStr = pdc.get(DROPPED_BY_KEY, PersistentDataType.STRING);
            if (droppedByStr != null) {
                try {
                    UUID droppedBy = UUID.fromString(droppedByStr);
                    if (droppedBy.equals(player.getUniqueId())) {
                        if (currentTime - spawnTick < selfImmuneTicks) {
                            return false;
                        }
                    }
                } catch (IllegalArgumentException ignored) {}
            }
        }

        return item.getLocation().distanceSquared(player.getLocation()) <= pickupRangeSq;
    }

    private void performPickup(Player player, Item item) {
        ItemStack stack = item.getItemStack();
        if (stack.getAmount() <= 0) return;
        int amount = stack.getAmount();

        PlayerInventory inv = player.getInventory();
        boolean merged = false;

        // 尝试合并到主背包或副手
        for (int i = 9; i < 36; i++) {
            ItemStack existing = inv.getItem(i);
            if (existing != null && existing.isSimilar(stack)) {
                int space = existing.getMaxStackSize() - existing.getAmount();
                if (space >= stack.getAmount()) {
                    existing.setAmount(existing.getAmount() + stack.getAmount());
                    merged = true;
                    break;
                } else if (space > 0) {
                    existing.setAmount(existing.getMaxStackSize());
                    stack.setAmount(stack.getAmount() - space);
                }
            }
        }

        if (!merged && stack.getAmount() > 0) {
            // 尝试放入空位（优先热键栏）
            for (int i = 0; i < 36; i++) {
                if (inv.getItem(i) == null) {
                    inv.setItem(i, stack.clone());
                    merged = true;
                    break;
                }
            }
        }

        if (!merged && stack.getAmount() > 0) {
            // 副手
            ItemStack offhand = inv.getItemInOffHand();
            if (offhand.getType() == Material.AIR || (offhand.isSimilar(stack) && offhand.getAmount() < offhand.getMaxStackSize())) {
                if (offhand.getType() == Material.AIR) {
                    inv.setItemInOffHand(stack.clone());
                } else {
                    int space = offhand.getMaxStackSize() - offhand.getAmount();
                    if (space >= stack.getAmount()) {
                        offhand.setAmount(offhand.getAmount() + stack.getAmount());
                    } else {
                        offhand.setAmount(offhand.getMaxStackSize());
                        stack.setAmount(stack.getAmount() - space);
                        // 剩余物品无法处理 → 掉落回世界（通常不会发生）
                    }
                }
                merged = true;
            }
        }

        if (merged) {
            PacketUtils.sendPickupAnimation(plugin, player, item,amount);
            item.remove();
        }
    }

    // ====== 启用/禁用控制 ======
    public void enable() {
        if (active) return;
        active = true;

        if (plugin.isPlayerDriven()) {
            startPlayerDriven();
        }
        if (plugin.isItemDrivenEnabled()) {
            startItemDriven();
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

        restoreOriginalPickupDelay();
    }

    private void startPlayerDriven() {
        int interval = plugin.getPlayerDrivenScanIntervalTicks();
        activePlayerUpdater = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getGameMode() != GameMode.SPECTATOR) {
                        activePlayers.add(player.getUniqueId());
                    }
                }
            }
        };
        activePlayerUpdater.runTaskTimer(plugin, 0, interval);
    }

    private void startItemDriven() {
        int checkInterval = plugin.getPickupAttemptIntervalTicks();
        itemDetectionTask = new BukkitRunnable() {
            @Override
            public void run() {
                long currentTime = System.currentTimeMillis();
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
                            spawnTick = item.getWorld().getFullTime();
                        }

                        long activeDuration = activeDetectionTicks;
                        if (currentTime - (spawnTick * 50L) > activeDuration * 50L) {
                            itemIter.remove();
                            continue;
                        }

                        World world = item.getWorld();
                        Location loc = item.getLocation();
                        double range = plugin.getPickupRange();
                        Player nearest = null;
                        double nearestDistSq = Double.MAX_VALUE;

                        for (Entity entity : world.getNearbyEntities(loc, range, range, range)) {
                            if (entity instanceof Player player) {
                                if (player.getGameMode() == GameMode.SPECTATOR) continue;
                                double distSq = player.getLocation().distanceSquared(loc);
                                if (distSq <= pickupRangeSq && distSq < nearestDistSq) {
                                    if (canPickupNow(player, item)) {
                                        nearest = player;
                                        nearestDistSq = distSq;
                                    }
                                }
                            }
                        }

                        if (nearest != null) {
                            performPickup(nearest, item);
                            itemIter.remove();
                        }
                    }
                    if (items.isEmpty()) {
                        worldIter.remove();
                    }
                }
            }
        };
        itemDetectionTask.runTaskTimer(plugin, 0, checkInterval);
    }

    public void restoreOriginalPickupDelay() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Item item) {
                    PersistentDataContainer pdc = item.getPersistentDataContainer();
                    if (pdc.has(SOURCE_KEY, PersistentDataType.STRING)) {
                        try {
                            Field field = getItemPickupDelayField();
                            field.setAccessible(true);
                            field.set(item, 10); // 恢复默认值（可选）
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
    }

    // ====== 反射工具 ======
    private static volatile Field cachedPickupDelayField = null;

    private static Field getItemPickupDelayField() {
        if (cachedPickupDelayField != null) {
            return cachedPickupDelayField;
        }

        Class<?> itemClass;
        try {
            itemClass = Class.forName("net.minecraft.world.entity.item.EntityItem");
        } catch (ClassNotFoundException e) {
            try {
                itemClass = Class.forName("net.minecraft.server." + getNMSVersion() + ".EntityItem");
            } catch (ClassNotFoundException ex) {
                throw new RuntimeException("Unsupported Minecraft version for reflection.", ex);
            }
        }

        Field field = null;
        for (Field f : itemClass.getDeclaredFields()) {
            if (f.getType() == int.class && !f.getName().startsWith("b")) { // heuristic
                field = f;
                break;
            }
        }

        if (field == null) {
            throw new RuntimeException("Could not find pickupDelay field in EntityItem.");
        }

        cachedPickupDelayField = field;
        return field;
    }

    private static String getNMSVersion() {
        String pkg = Bukkit.getServer().getClass().getPackage().getName();
        return pkg.substring(pkg.lastIndexOf('.') + 1);
    }

    // ====== 辅助枚举与解析 ======

    private enum ItemSourceType {
        PLAYER_DROP, NATURAL_DROP, INSTANT_PICKUP, UNKNOWN
    }

    private ItemSourceType parseSource(String str) {
        if (str == null) return ItemSourceType.UNKNOWN;
        try {
            return ItemSourceType.valueOf(str);
        } catch (IllegalArgumentException e) {
            return ItemSourceType.UNKNOWN;
        }
    }

    private long getRequiredDelay(ItemSourceType source) {
        return switch (source) {
            case PLAYER_DROP -> playerDropDelayTicks;
            case NATURAL_DROP -> naturalDropDelayTicks;
            case INSTANT_PICKUP -> instantPickupDelayTicks;
            default -> 0;
        };
    }
    public boolean isActive() {return active;}
}