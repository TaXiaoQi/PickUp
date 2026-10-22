package pickup.platform;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Bukkit 调度器适配器（Paper 与 Spigot 共用）
 * Bukkit 为单线程调度模型，位置任务与全局任务统一走主线程调度
 */
public class BukkitPlatformAdapter implements PlatformAdapter {

    @Override
    public void runAtLocation(Plugin plugin, Location loc, Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public void runDelayedAtLocation(Plugin plugin, Location loc, Runnable task, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(1, delayTicks));
    }

    @Override
    public PluginTask runGlobalDelayed(Plugin plugin, Runnable task, long delayTicks) {
        BukkitTask bukkitTask = Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(1, delayTicks));
        return bukkitTask::cancel;
    }

    @Override
    public PluginTask runGlobalAtFixedRate(Plugin plugin, Runnable task, long initialDelayTicks, long periodTicks) {
        BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, task,
                Math.max(1, initialDelayTicks), Math.max(1, periodTicks));
        return bukkitTask::cancel;
    }

    @Override
    public boolean setCanPlayerPickup(Item item, boolean canPickup) {
        // Spigot 无 canPlayerPickup 概念，由拾取延迟机制兜底
        return false;
    }

    @Override
    public boolean isFolia() {
        return false;
    }
}
