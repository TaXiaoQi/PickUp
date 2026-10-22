package pickup.platform;

import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.plugin.Plugin;

/**
 * 平台适配器：屏蔽 Folia 区域化调度器与 Bukkit 单线程调度器的差异
 */
public interface PlatformAdapter {

    /**
     * 在目标位置所属的区域线程（Folia）或主线程（Bukkit）执行任务
     */
    void runAtLocation(Plugin plugin, Location loc, Runnable task);

    /**
     * 延迟后在目标位置所属的区域线程（Folia）或主线程（Bukkit）执行任务
     */
    void runDelayedAtLocation(Plugin plugin, Location loc, Runnable task, long delayTicks);

    /**
     * 延迟后在全局调度器执行任务（不绑定区域）
     * @return 可取消的任务句柄
     */
    PluginTask runGlobalDelayed(Plugin plugin, Runnable task, long delayTicks);

    /**
     * 以固定频率在全局调度器执行任务（不绑定区域）
     */
    PluginTask runGlobalAtFixedRate(Plugin plugin, Runnable task, long initialDelayTicks, long periodTicks);

    /**
     * 设置物品是否允许玩家拾取（Paper/Folia 支持，Spigot 无此概念返回 false）
     */
    boolean setCanPlayerPickup(Item item, boolean canPickup);

    boolean isFolia();
}
