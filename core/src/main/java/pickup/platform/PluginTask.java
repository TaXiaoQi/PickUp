package pickup.platform;

/**
 * 已调度任务的句柄（用于统一取消 BukkitTask / ScheduledTask）
 */
public interface PluginTask {
    void cancel();
}
