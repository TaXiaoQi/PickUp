package pickup.platform;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.plugin.Plugin;

/**
 * Folia 适配器：全部任务走区域化调度器
 */
public class FoliaPlatformAdapter implements PlatformAdapter {

    @Override
    public void runAtLocation(Plugin plugin, Location loc, Runnable task) {
        Bukkit.getRegionScheduler().execute(plugin, loc, task);
    }

    @Override
    public void runDelayedAtLocation(Plugin plugin, Location loc, Runnable task, long delayTicks) {
        Bukkit.getRegionScheduler().runDelayed(plugin, loc, t -> task.run(), Math.max(1, delayTicks));
    }

    @Override
    public PluginTask runGlobalDelayed(Plugin plugin, Runnable task, long delayTicks) {
        ScheduledTask scheduledTask = Bukkit.getGlobalRegionScheduler()
                .runDelayed(plugin, t -> task.run(), Math.max(1, delayTicks));
        return scheduledTask::cancel;
    }

    @Override
    public PluginTask runGlobalAtFixedRate(Plugin plugin, Runnable task, long initialDelayTicks, long periodTicks) {
        ScheduledTask scheduledTask = Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(plugin, t -> task.run(), Math.max(1, initialDelayTicks), Math.max(1, periodTicks));
        return scheduledTask::cancel;
    }

    @Override
    public boolean setCanPlayerPickup(Item item, boolean canPickup) {
        item.setCanPlayerPickup(canPickup);
        return true;
    }

    @Override
    public boolean isFolia() {
        return true;
    }
}
