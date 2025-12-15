package pickup;

import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.util.Objects;

/**
 * PickUp插件主类
 * 管理物品自动拾取和合并功能
 */
public class PickUp extends JavaPlugin {

    // 核心管理器实例
    private PickupManager pickupManager;      // 拾取管理器
    private CustomItemMerger itemMerger;      // 物品合并器

    // 控制标志
    private boolean stoppedByCommand = false; // 是否被命令手动停止

    // 配置参数（从配置文件读取）
    private boolean playerDriven;             // 玩家驱动模式是否启用
    private double pickupRange;               // 拾取范围（方块）
    private boolean offhandPickupEnabled;           // 副手拾取是否启用
    private int selfImmuneTicks;              // 自身掉落物免疫时间（tick）
    private int playerDrivenScanIntervalTicks; // 玩家驱动扫描间隔（tick）

    private boolean itemDrivenEnabled;        // 物品驱动模式是否启用
    private int activeDetectionTicks;         // 物品活跃检测时间（tick）
    private int pickupAttemptIntervalTicks;   // 拾取尝试间隔（tick）

    private boolean deathLogEnabled;              // 是否启用死亡日志
    private boolean deathLogBroadcastToOps;       // 是否向OP玩家广播死亡信息
    private boolean deathLogSendPrivateMessage;   // 是否向死亡玩家发送私信

    private int playerDropDelayTicks;         // 玩家掉落延迟（tick）
    private int naturalDropDelayTicks;        // 自然掉落延迟（tick）
    private int instantPickupDelayTicks;      // 立即拾取延迟（tick）

    private double itemMergeRange;            // 物品合并范围（方块）
    private PickupEvent pickupEventListener;  // 事件是否注销

    /**
     * 插件启用时的初始化方法
     */
    @Override
    public void onEnable() {
        // 清理可能存在的重启标志文件
        File restartFlag = new File("restart.flag");
        if (restartFlag.exists()) {
            if (restartFlag.delete()) {
                getLogger().info("已清理残留的 restart.flag 文件");
            } else {
                getLogger().warning("无法删除 restart.flag 文件，请检查文件权限或是否被占用");
            }
        }

        // 直接调用重载方法来完成初始化
        reloadPickup();

        // 注册命令处理器
        ReloadCommand executor = new ReloadCommand(this);
        Objects.requireNonNull(getCommand("up")).setExecutor(executor);
        Objects.requireNonNull(getCommand("mc")).setExecutor(executor);

        getLogger().info("PickUp 插件已加载");
    }

    /**
     * 插件禁用时的清理方法
     */
    @Override
    public void onDisable() {
        // 安全停止所有运行中的管理器
        if (pickupManager != null && pickupManager.isActive()) {
            pickupManager.disable();  // 停止拾取管理器
        }
        if (itemMerger != null) {
            itemMerger.stop();  // 停止物品合并器
            itemMerger = null;  // 释放引用
        }
        getLogger().info("PickUp 插件已卸载");
    }

    /**
     * 重新加载配置文件和功能
     * 可从命令调用或在插件启动时调用
     */
    public void reloadPickup() {
        // 1. 重新加载配置文件
        saveDefaultConfig();
        reloadConfig();
        FileConfiguration config = getConfig();

        // 2. 先停止所有功能
        if (pickupManager != null && pickupManager.isActive()) {
            pickupManager.disable();
        }
        if (itemMerger != null) {
            itemMerger.stop();
            itemMerger = null;
        }

        // 3. 注销事件监听器
        unregisterPickupEvent();

        // 4. 从配置中重新读取所有参数（确保顺序正确）
        playerDriven = config.getBoolean("mode.player-driven", true);
        playerDrivenScanIntervalTicks = Math.max(1, config.getInt("mode.player-scan-interval", 6));
        itemDrivenEnabled = config.getBoolean("mode.item-driven", true);
        activeDetectionTicks = Math.max(0, config.getInt("mode.item-active-duration", 60));
        pickupAttemptIntervalTicks = Math.max(1, config.getInt("mode.item-check-interval", 2));

        // 拾取范围相关
        pickupRange = Math.max(0.1, Math.min(10.0, config.getDouble("pickup.range", 1.5)));
        selfImmuneTicks = Math.max(0, config.getInt("pickup.self-immune-ticks", 5));
        offhandPickupEnabled = config.getBoolean("pickup.offhand-pickup", false);

        // 延迟配置
        playerDropDelayTicks = Math.max(0, config.getInt("pickup.delays.player-drop", 10));
        naturalDropDelayTicks = Math.max(0, config.getInt("pickup.delays.natural-drop", 5));
        instantPickupDelayTicks = Math.max(0, config.getInt("pickup.delays.instant-pickup", 0));

        // 死亡日志配置
        deathLogEnabled = config.getBoolean("death-log.enabled", true);
        deathLogBroadcastToOps = config.getBoolean("death-log.broadcast-to-ops", false);
        deathLogSendPrivateMessage = config.getBoolean("death-log.send-private-message", true);

        // 合并相关
        itemMergeRange = config.getDouble("custom-item-merge.range", 1.0);

        // 5. 创建新的管理器实例
        this.pickupManager = new PickupManager(this);

        // 6. 必须在这里调用 loadConfig()，将配置加载到 PickupManager
        this.pickupManager.loadConfig();

        // 7. 初始化物品合并系统
        if (config.getBoolean("custom-item-merge.enabled", true)) {
            initializeItemMerger();
        }

        // 8. 创建并注册全新的事件监听器
        this.pickupEventListener = new PickupEvent(this, pickupManager);
        getServer().getPluginManager().registerEvents(pickupEventListener, this);

        // 9. 根据配置启动功能
        if (!isPickupDisabled()) {
            pickupManager.enable();
            if (itemMerger != null && shouldRunItemMerger()) {
                itemMerger.start();
            }
        } else {
            // 如果被禁用，确保恢复原版拾取延迟为0
            if (pickupManager != null) {
                pickupManager.restoreOriginalPickupDelayToZero();
            }
        }

        getLogger().info("PickUp 配置已重载");
    }

    /**
     * 初始化物品合并器
     * 从配置文件读取参数并创建新的合并器实例
     */
    private void initializeItemMerger() {
        int activeDuration = getConfig().getInt("custom-item-merge.active-duration-ticks", 10);
        int scanInterval = getConfig().getInt("custom-item-merge.scan-interval-ticks", 2);

        // 仅创建物品合并器实例，假设其构造函数不再自动 start()
        this.itemMerger = new CustomItemMerger(this, itemMergeRange, activeDuration, scanInterval);
        // 注意：此时 itemMerger 是 created but not started.
    }

    /**
     * 启动拾取功能
     * 用于命令控制或在配置重载后启用
     */
    public void startPickup() {
        stoppedByCommand = false;

        // 注销可能存在的旧事件监听器
        unregisterPickupEvent();

        // 重新注册事件监听器
        this.pickupEventListener = new PickupEvent(this, pickupManager);
        getServer().getPluginManager().registerEvents(pickupEventListener, this);

        pickupManager.enable();
        if (itemMerger != null && shouldRunItemMerger()) {
            itemMerger.start();
        }
    }

    /**
     * 停止拾取功能
     * 用于命令控制暂时禁用插件功能
     */
    public void stopPickup() {
        stoppedByCommand = true;  // 设置停止标志

        if (pickupManager != null) {
            pickupManager.disable();  // 停止拾取管理器
        }
        if (itemMerger != null) {
            itemMerger.stop();  // 停止物品合并器
        }

        // 注销事件监听器，防止继续设置pickupDelay=6000
        unregisterPickupEvent();
    }

    /**
     * 注销拾取事件监听器
     */
    public void unregisterPickupEvent() {
        if (pickupEventListener != null) {
            HandlerList.unregisterAll(pickupEventListener);
            pickupEventListener = null;
            getLogger().info("PickupEvent 事件监听器已注销");
        }
    }

    /**
     * 检查拾取功能是否被禁用
     * @return true如果被禁用（通过命令或配置）
     */
    public boolean isPickupDisabled() {
        return stoppedByCommand || !getConfig().getBoolean("enabled", true);
    }


    /**
     * 检查是否应该运行物品合并器
     * @return true如果应该运行（拾取未禁用且合并功能启用）
     */
    private boolean shouldRunItemMerger() {
        return !isPickupDisabled() && getConfig().getBoolean("custom-item-merge.enabled", true);
    }

    // ========== Getter 方法 ==========
    // 提供对配置参数的访问，供其他类使用
    public boolean isPlayerDriven() { return playerDriven; }
    public double getPickupRange() { return pickupRange; }
    public int getSelfImmuneTicks() { return selfImmuneTicks; }
    public int getPlayerDrivenScanIntervalTicks() { return playerDrivenScanIntervalTicks; }
    public boolean isItemDrivenEnabled() { return itemDrivenEnabled; }
    public int getActiveDetectionTicks() { return activeDetectionTicks; }
    public int getPickupAttemptIntervalTicks() { return pickupAttemptIntervalTicks; }
    public int getPlayerDropDelayTicks() { return playerDropDelayTicks; }
    public int getNaturalDropDelayTicks() { return naturalDropDelayTicks; }
    public int getInstantPickupDelayTicks() { return instantPickupDelayTicks; }
    public CustomItemMerger getItemMerger() { return itemMerger; }
    public boolean isStoppedByCommand() { return stoppedByCommand; }
    public boolean isOffhandPickupEnabled() { return offhandPickupEnabled; }
    public boolean isDeathLogEnabled() { return deathLogEnabled; }
    public boolean isDeathLogBroadcastToOps() { return deathLogBroadcastToOps; }
    public boolean isDeathLogSendPrivateMessage() { return deathLogSendPrivateMessage; }
}
