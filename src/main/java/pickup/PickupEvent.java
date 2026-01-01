package pickup;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 拾取事件监听器类
 * 负责监听和处理与物品拾取相关的各种事件
 * 使用@SuppressWarnings("ClassCanBeRecord")抑制警告，因为此类可以设计为record类型（Java 14+）
 * 但由于兼容性考虑，仍使用传统的类定义方式
 */
public class PickupEvent implements Listener {
    // 记录每个玩家上次检测的时间（tick）
    private final Map<UUID, Long> lastCheckTicks = new ConcurrentHashMap<>();
    // 插件主类引用，用于访问配置和状态
    private final PickupManager pickupManager; // 拾取管理器，负责实际的处理逻辑
    private final PickUp plugin;               // 插件主类实例
    private final PickupConfig config;

    /**
     * 构造函数
     * @param plugin 插件主类实例，提供配置和状态信息
     * @param pickupManager 拾取管理器，处理具体的拾取逻辑
     */
    public PickupEvent(PickUp plugin, PickupManager pickupManager) {
        this.plugin = plugin;
        this.pickupManager = pickupManager;
        this.config = plugin.getPickupConfig();
    }

    /**
     * 处理物品生成事件
     * 当任何物品实体在世界中生成时触发（包括自然掉落、方块掉落等）
     *
     * @param event 物品生成事件
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        // 检查插件是否启用（防止插件禁用后仍有事件处理）
        if (!plugin.isEnabled() || plugin.isPickupDisabled()) {
            return;
        }
        // 1. 通知空间索引注册物品
        plugin.getItemSpatialIndex().registerItem(event.getEntity());

        // 2. 处理拾取逻辑
        pickupManager.handleItemSpawn(event);
    }



    /**
     * 处理玩家丢弃物品事件
     * 当玩家主动丢弃物品（按Q键）时触发
     *
     * @param event 玩家丢弃物品事件
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        // 检查插件是否启用
        if (!plugin.isEnabled() || plugin.isPickupDisabled()) {
            return;
        }

        // 委托给拾取管理器处理玩家丢弃逻辑
        pickupManager.handlePlayerDrop(event);
    }

    /**
     * 处理方块掉落物品事件
     * 当玩家挖掘方块掉落物品时触发
     *
     * @param event 方块掉落物品事件
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        // 检查插件是否启用
        if (!plugin.isEnabled() || plugin.isPickupDisabled()) {
            return;
        }

        // 委托给拾取管理器处理方块掉落逻辑
        pickupManager.handleBlockDrop(event);
    }

    /**
     * 处理实体死亡事件
     * 当实体（怪物、动物等）死亡掉落物品时触发
     *
     * @param event 实体死亡事件
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        // 检查插件是否启用
        if (!plugin.isEnabled() || plugin.isPickupDisabled()) {
            return;
        }

        // 委托给拾取管理器处理实体死亡掉落逻辑
        // 注意：此事件中的掉落物需要特殊处理，因为不会立即生成物品实体
        pickupManager.handleEntityDeath(event);
    }

    /**
     * 当玩家死亡时触发，播报死亡日志
     *
     * @param event 玩家死亡事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!config.isDeathLogEnabled()) return;

        Player player = event.getEntity();
        Location loc = player.getLocation();
        World world = loc.getWorld();
        String dimension = getDimensionName(world);
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();

        // Log to console
        plugin.getLogger().info("玩家 " + player.getName() +
                " 在 " + dimension + " (" + x + ", " + y + ", " + z + ") 死亡");

        if (config.isDeathLogSendPrivateMessage()) {
            Component original = event.deathMessage();
            if (original == null) return;

            Component coordinatePart = Component.text("[")
                    .color(NamedTextColor.DARK_GRAY)
                    .append(Component.text(dimension).color(NamedTextColor.YELLOW))
                    .append(Component.text(" (").color(NamedTextColor.DARK_GRAY))
                    .append(Component.text(x + ", " + y + ", " + z).color(NamedTextColor.GOLD))
                    .append(Component.text(")").color(NamedTextColor.DARK_GRAY))
                    .append(Component.text("]"));

            event.deathMessage(Component.empty()
                    .append(original)
                    .append(Component.space())
                    .append(coordinatePart));
        }
    }

    /**
     * 将世界名称转换为友好维度名称
     */
    private @NotNull String getDimensionName(World world) {
        if (world == null) return "未知维度 (unknown)";
        return switch (world.getEnvironment()) {
            case NORMAL -> "主世界 (world)";
            case NETHER -> "下界 (nether)";
            case THE_END -> "末地 (the_end)";
            default -> world.getName() + " (custom)";
        };
    }

    /**
     * 处理容器（如漏斗）自动拾取物品事件
     * 清理带有拾取标记的 ItemStack，确保其能正常堆叠
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        if (!plugin.isEnabled() || plugin.isPickupDisabled()) {
            return;
        }

        Item item = event.getItem();

        ItemStack original = item.getItemStack();
        if (original.getType().isAir()) return;

        if (pickupManager.hasPickupMark(original)) {
            ItemStack clean = pickupManager.createCleanStack(original);
            item.setItemStack(clean);
        }
    }

    /**
     * 处理玩家移动事件 - 用于玩家驱动模式
     * 当玩家移动时触发，用于检测附近的物品并尝试拾取
     *
     * @param event 玩家移动事件
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!plugin.isEnabled() || plugin.isPickupDisabled() || !config.isPlayerDriven()) {
            return;
        }

        Player player = event.getPlayer();
        if (!player.isOnline() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return;
        }

        // 如果当前世界没有可拾取的物品，直接跳过后续所有逻辑
        if (!pickupManager.hasPickupableItems(player.getWorld())) {
            return;
        }

        // ====== 移动距离检查 ======
        double minMoveDistance = config.getPlayerMinMoveDistance();
        double minMoveDistanceSq = minMoveDistance * minMoveDistance;

        if (event.getFrom().distanceSquared(event.getTo()) > minMoveDistanceSq) {
            pickupManager.tryPickup(player);
        }

        // ====== 新增：时间间隔控制 ======
        UUID playerId = player.getUniqueId();
        long currentTick = player.getWorld().getFullTime(); // 获取世界当前tick
        long lastCheck = lastCheckTicks.getOrDefault(playerId, 0L);
        int checkInterval = config.getPlayerMoveCheckIntervalTicks(); // 读取配置

        // 检查是否达到时间间隔
        if ((currentTick - lastCheck) < checkInterval) {
            return; // 时间未到，跳过本次检测
        }

        // 记录本次检测时间
        lastCheckTicks.put(playerId, currentTick);

        // 执行拾取检测
        pickupManager.tryPickup(player);
    }

    /**
     * 拦截并取消所有原版物品拾取行为
     * 插件启用时，所有玩家都无法通过原版机制拾取任何物品
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        // 双重检查：必须插件启用且拾取功能未禁用
        if (!plugin.isEnabled() || plugin.isPickupDisabled()) {
            return;
        }

        // 记录调试信息（可选）
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("EntityPickupItemEvent 被取消 - " +
                    event.getEntity().getName() + " 拾取 " +
                    event.getItem().getItemStack().getType());
        }

        // 取消原版拾取
        event.setCancelled(true);
    }

    /** * 处理物品自然消失事件 * 当物品因超时而从世界中移除时触发 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDespawn(org.bukkit.event.entity.ItemDespawnEvent event) {
        // 只有当插件启用且物品是由我们管理的，才需要更新计数
        if (!plugin.isEnabled() || plugin.isPickupDisabled()) {
            return;
        }
        Item item = event.getEntity();
        // 1. 从空间索引中移除（重要！）
        plugin.getItemSpatialIndex().unregisterItem(item);
    }

    /// 事件优先级说明：
    /// - LOWEST: 最早执行，用于处理基础的物品生成和掉落事件
    /// - MONITOR: 最后执行，用于玩家移动后的拾取检测，避免干扰其他插件
    /// ignoreCancelled = true: 如果事件被其他插件取消，则跳过处理
    /// 这样可以避免在事件已被取消的情况下仍然执行不必要的逻辑
}