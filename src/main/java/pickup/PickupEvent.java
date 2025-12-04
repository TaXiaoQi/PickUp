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

@SuppressWarnings("ClassCanBeRecord")
public class PickupEvent implements Listener {

    private final PickupManager pickupManager;
    private final PickUp plugin;

    public PickupEvent(PickUp plugin, PickupManager pickupManager) {
        this.plugin = plugin;
        this.pickupManager = pickupManager;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!plugin.isEnabled()) return;
        pickupManager.handleItemSpawn(event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!plugin.isEnabled()) return;
        pickupManager.handlePlayerDrop(event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        if (!plugin.isEnabled()) return;
        pickupManager.handleBlockDrop(event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!plugin.isEnabled()) return;
        pickupManager.handleEntityDeath(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!plugin.isEnabled()) return;
        if (!plugin.isPlayerDriven()) return;

        Player player = event.getPlayer();
        if (player.getGameMode().equals(org.bukkit.GameMode.SPECTATOR)) return;

        // 仅当位置变化显著时触发（避免高频调用）
        if (event.getFrom().getBlockX() != event.getTo().getBlockX() ||
                event.getFrom().getBlockY() != event.getTo().getBlockY() ||
                event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            pickupManager.tryPickup(player);
        }
    }
}