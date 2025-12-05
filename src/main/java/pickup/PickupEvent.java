package pickup;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * 拾取事件监听器类
 * 负责监听和处理与物品拾取相关的各种事件
 * 使用@SuppressWarnings("ClassCanBeRecord")抑制警告，因为此类可以设计为record类型（Java 14+）
 * 但由于兼容性考虑，仍使用传统的类定义方式
 */
@SuppressWarnings("ClassCanBeRecord")
public class PickupEvent implements Listener {

    // 插件主类引用，用于访问配置和状态
    private final PickupManager pickupManager; // 拾取管理器，负责实际的处理逻辑
    private final PickUp plugin;               // 插件主类实例

    /**
     * 构造函数
     * @param plugin 插件主类实例，提供配置和状态信息
     * @param pickupManager 拾取管理器，处理具体的拾取逻辑
     */
    public PickupEvent(PickUp plugin, PickupManager pickupManager) {
        this.plugin = plugin;
        this.pickupManager = pickupManager;
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

        // 委托给拾取管理器处理具体的生成逻辑
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
     * 处理玩家移动事件 - 用于玩家驱动模式
     * 当玩家移动时触发，用于检测附近的物品并尝试拾取
     *
     * @param event 玩家移动事件
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // 检查插件是否启用
        if (!plugin.isEnabled() || plugin.isPickupDisabled()) {
            return;
        }

        // 检查玩家驱动模式是否启用（配置项）
        if (!plugin.isPlayerDriven()) return;

        Player player = event.getPlayer();
        if (!player.isOnline()) {
            return;
        } else {
            player.getWorld();
        }

        // 旁观者模式玩家不触发拾取（游戏规则）
        if (player.getGameMode().equals(org.bukkit.GameMode.SPECTATOR)) return;

        /// 仅当位置变化显著时触发拾取检测，优化性能
        /// 通过检查坐标是否变化来避免高频调用（例如：原地旋转、抬头低头等）
        /// 只检查方块坐标变化，忽略微小的位置变化（如重力影响）
        if (event.getFrom().getBlockX() != event.getTo().getBlockX() ||
                event.getFrom().getBlockY() != event.getTo().getBlockY() ||
                event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            // 委托给拾取管理器尝试拾取附近物品
            pickupManager.tryPickup(player);
        }

        /// 注意：这里使用 MONITOR 优先级，确保在其他插件处理完移动事件后执行
        /// 这样不会干扰其他插件的移动相关逻辑，同时能正确拾取物品
        }

    /// 事件优先级说明：
    /// - LOWEST: 最早执行，用于处理基础的物品生成和掉落事件
    /// - MONITOR: 最后执行，用于玩家移动后的拾取检测，避免干扰其他插件
    /// ignoreCancelled = true: 如果事件被其他插件取消，则跳过处理
    /// 这样可以避免在事件已被取消的情况下仍然执行不必要的逻辑
    }