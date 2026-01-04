package pickup.feature.pickupmanager;

import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import pickup.Main;
import pickup.config.PickupConfig;
import pickup.feature.ItemSpatialIndex;

import java.util.Set;

/**
 * 玩家驱动拾取处理器
 * 处理由玩家移动事件触发的拾取逻辑
 */
public class PlayerDrivenPickupHandler {
    private final Main plugin;
    private final PickupConfig config;
    private final ItemSpatialIndex itemIndex;
    private final PickupExecutor pickupExecutor;

    private boolean active = false;

    public PlayerDrivenPickupHandler(Main plugin, PickupConfig config,
                                     ItemSpatialIndex itemIndex, PickupExecutor pickupExecutor) {
        this.plugin = plugin;
        this.config = config;
        this.itemIndex = itemIndex;
        this.pickupExecutor = pickupExecutor;
    }

    /**
     * 玩家驱动的拾取扫描
     */
    public void tryPickup(Player player) {
        if (!active) return;
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;

        // 使用索引获取附近物品
        double range = Math.sqrt(config.getPickupRange() * config.getPickupRange());
        Set<Item> nearbyItems = itemIndex.getNearbyItems(player.getLocation(), range);

        // 对每个物品尝试拾取
        for (Item item : nearbyItems) {
            pickupExecutor.performPlayerPickup(player, item);
        }
    }

    // ====== 启用/禁用控制 ======

    public void enable() {
        active = true;
        plugin.getLogger().info("玩家驱动模式已启用，移动检测间隔: " +
                config.getPlayerMoveCheckIntervalTicks() + " ticks");
    }

    public void disable() {
        active = false;
    }

    public boolean isActive() {
        return active;
    }
}