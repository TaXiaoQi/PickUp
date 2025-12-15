package pickup;

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
     * 当玩家死亡时触发，播报死亡日志
     *
     * @param event 玩家死亡事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        // 检查死亡日志功能是否启用
        if (!plugin.isDeathLogEnabled()) {  // 使用 Getter 方法
            return;
        }

        Player player = event.getEntity();

        // 获取死亡位置信息
        Location deathLocation = player.getLocation();
        String worldName = deathLocation.getWorld().getName();
        String dimension = getDimensionName(worldName); // 转换为友好维度名称
        int x = deathLocation.getBlockX();
        int y = deathLocation.getBlockY();
        int z = deathLocation.getBlockZ();

        // 1. 控制台日志（固定格式）
        plugin.getLogger().info("玩家死亡日志 - 玩家: " + player.getName() +
                " 在 " + dimension + " 死亡 (" + x + ", " + y + ", " + z + ")");

        // 2. 向OP玩家广播（如果需要）
        if (plugin.isDeathLogBroadcastToOps()) {  // 使用 Getter 方法
            String opMessage = String.format("§c[死亡日志] §f%s §7在 §e%s §7死亡 (§6%d, %d, %d§7)",
                    player.getName(), dimension, x, y, z);

            // 向所有在线OP玩家发送消息
            for (Player onlinePlayer : plugin.getServer().getOnlinePlayers()) {
                if (onlinePlayer.isOp()) {
                    onlinePlayer.sendMessage(opMessage);
                }
            }
        }

        // 3. 私信死亡玩家（如果需要）
        if (plugin.isDeathLogSendPrivateMessage()) {
            // 延迟1tick确保玩家能看到消息
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    // 格式：❌ 你刚才在 主世界(123, 64, -456) 死亡
                    player.sendMessage("§e❌ §f你刚才在 §a" + dimension + "§6(" + x + "§8, §6" + y + "§8, §6" + z + "§6) §f死亡");
                }
            }, 1L);
        }
    }

    /**
     * 将世界名称转换为友好维度名称
     * @param worldName 世界名称
     * @return 友好维度名称
     */
    private String getDimensionName(String worldName) {
        if (worldName == null || worldName.isEmpty()) {
            return "未知维度";
        }

        String lowerWorldName = worldName.toLowerCase();
        switch (lowerWorldName) {
            case "world":
                return "主世界";
            case "world_nether":
                return "下界";
            case "world_the_end":
                return "末地";
            default:
                // 尝试从名称中提取维度信息
                if (lowerWorldName.contains("nether")) return "下界";
                if (lowerWorldName.contains("the_end") || lowerWorldName.contains("end")) return "末地";
                return worldName; // 返回原始名称
        }
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
        if (!plugin.isEnabled() || plugin.isPickupDisabled() || !plugin.isPlayerDriven()) {
            return;
        }

        Player player = event.getPlayer();
        if (!player.isOnline() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return;
        }

        if (event.getFrom().getBlockX() != event.getTo().getBlockX() ||
                event.getFrom().getBlockY() != event.getTo().getBlockY() ||
                event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            pickupManager.tryPickup(player);
        }
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

        // 🔒 取消原版拾取（因为我们插件接管拾取）
        event.setCancelled(true);
    }

    /// 事件优先级说明：
    /// - LOWEST: 最早执行，用于处理基础的物品生成和掉落事件
    /// - MONITOR: 最后执行，用于玩家移动后的拾取检测，避免干扰其他插件
    /// ignoreCancelled = true: 如果事件被其他插件取消，则跳过处理
    /// 这样可以避免在事件已被取消的情况下仍然执行不必要的逻辑
}