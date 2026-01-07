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

        // 使用索引获取附近物品
        double range = config.getPickupRange();
        Set<Item> nearbyItems = itemIndex.getNearbyItems(player.getLocation(), range);

        // 对每个物品尝试拾取
        for (Item item : nearbyItems) {
            if (item.isValid() && !item.isDead()) {
                // 新增：在拾取前检查延迟
                // 这样可以避免执行完整的canPickupNow逻辑
                if (isPickupDelayOver(item)) {
                    pickupExecutor.performPlayerPickup(player, item);
                }
            }
        }
    }

    /**
     * 检查物品是否已过拾取延迟
     */
    private boolean isPickupDelayOver(Item item) {
        // 获取物品元数据
        ItemSpatialIndex.ItemMetadata meta = itemIndex.getItemMetadata(item);
        if (meta == null) {
            return false;
        }

        long currentTick = item.getWorld().getGameTime();
        long spawnTick = meta.spawnTick;
        ItemSpatialIndex.ItemSourceType source = meta.source;

        // 根据来源类型获取所需延迟
        long requiredDelay = getRequiredDelay(source);

        // 检查是否已过延迟
        return (currentTick - spawnTick) >= requiredDelay;
    }

    /**
     * 根据物品来源类型获取所需的延迟
     */
    private long getRequiredDelay(ItemSpatialIndex.ItemSourceType source) {
        return switch (source) {
            case PLAYER_DROP -> config.getPlayerDropDelayTicks();
            case NATURAL_DROP -> config.getNaturalDropDelayTicks();
            default -> config.getInstantPickupDelayTicks();
        };
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