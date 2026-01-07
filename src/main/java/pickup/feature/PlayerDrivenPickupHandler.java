package pickup.feature;

import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import pickup.Main;
import pickup.config.PickupConfig;

import java.util.Set;

/**
 * 玩家驱动拾取处理器
 */
public class PlayerDrivenPickupHandler {
    private final Main plugin;
    private final PickupConfig config;
    private final ItemSpatialIndex itemIndex;
    private final pickup.feature.PickupExecutor pickupExecutor;

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
        if (!player.isOnline()) return;

        // 使用索引获取附近物品 - 修复范围计算
        double range = config.getPickupRange();
        Set<Item> nearbyItems = itemIndex.getNearbyItems(player.getLocation(), range);

        // 对每个物品尝试拾取
        for (Item item : nearbyItems) {
            if (item.isValid() && !item.isDead()) {
                pickupExecutor.performPlayerPickup(player, item);
            }
        }
    }

    // ====== 启用/禁用控制 ======

    public void enable() {
        if (active) return;
        active = true;
    }

    public void disable() {
        if (!active) return;
        active = false;
        plugin.getLogger().info("玩家驱动模式已禁用");
    }

    public boolean isActive() {
        return active;
    }
}