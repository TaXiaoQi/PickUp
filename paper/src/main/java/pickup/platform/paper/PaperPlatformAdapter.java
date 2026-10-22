package pickup.platform.paper;

import org.bukkit.entity.Item;
import pickup.platform.BukkitPlatformAdapter;

/**
 * Paper 适配器：支持 Paper 独有的 canPlayerPickup API
 */
public class PaperPlatformAdapter extends BukkitPlatformAdapter {

    @Override
    public boolean setCanPlayerPickup(Item item, boolean canPickup) {
        item.setCanPlayerPickup(canPickup);
        return true;
    }
}
