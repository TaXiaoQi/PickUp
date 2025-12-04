package pickup;

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
    private int selfImmuneTicks;              // 自身掉落物免疫时间（tick）
    private int playerDrivenScanIntervalTicks; // 玩家驱动扫描间隔（tick）

    private boolean itemDrivenEnabled;        // 物品驱动模式是否启用
    private int activeDetectionTicks;         // 物品活跃检测时间（tick）
    private int pickupAttemptIntervalTicks;   // 拾取尝试间隔（tick）

    private int playerDropDelayTicks;         // 玩家掉落延迟（tick）
    private int naturalDropDelayTicks;        // 自然掉落延迟（tick）
    private int instantPickupDelayTicks;      // 立即拾取延迟（tick）

    private double itemMergeRange;            // 物品合并范围（方块）

    /**
     * 插件启用时的初始化方法
     */
    @Override
    public void onEnable() {
        // 清理可能存在的重启标志文件（用于热重启检测）
        File restartFlag = new File("restart.flag");
        if (restartFlag.exists()) {
            if (restartFlag.delete()) {
                getLogger().info("已清理残留的 restart.flag 文件");
            } else {
                getLogger().warning("无法删除 restart.flag，请检查文件权限");
            }
        }

        // 加载配置文件
        saveDefaultConfig();  // 如果配置文件不存在，保存默认配置
        reloadPickup();       // 加载配置到内存

        // 初始化管理器
        this.pickupManager = new PickupManager(this);

        // 初始化物品合并系统
        initializeItemMerger();

        // 注册事件监听器
        PickupEvent pickupEvent = new PickupEvent(this, pickupManager);
        getServer().getPluginManager().registerEvents(pickupEvent, this);

        // 根据配置启用功能
        if (!isPickupDisabled()) {
            pickupManager.enable();  // 启用拾取管理器
        }
        if (shouldRunItemMerger()) {
            itemMerger.start();  // 启用物品合并器
        }

        // 注册命令处理器
        ReloadCommand executor = new ReloadCommand(this);
        Objects.requireNonNull(getCommand("up")).setExecutor(executor);  // /up 命令
        Objects.requireNonNull(getCommand("mc")).setExecutor(executor);  // /mc 命令

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
        // 重新加载配置文件
        saveDefaultConfig();  // 确保默认配置文件存在
        reloadConfig();       // 从磁盘重新加载配置
        FileConfiguration config = getConfig();  // 获取配置对象

        // 加载拾取相关配置（带有范围限制和默认值）
        pickupRange = Math.max(0.1, Math.min(10.0, config.getDouble("pickup.range", 1.5)));
        selfImmuneTicks = Math.max(0, config.getInt("pickup.self-immune-ticks", 5));

        // 加载延迟相关配置
        playerDropDelayTicks = Math.max(0, config.getInt("pickup.delays.player-drop", 10));
        naturalDropDelayTicks = Math.max(0, config.getInt("pickup.delays.natural-drop", 5));
        instantPickupDelayTicks = Math.max(0, config.getInt("pickup.delays.instant-pickup", 0));

        // 加载玩家驱动模式配置
        playerDriven = config.getBoolean("mode.player-driven", true);
        playerDrivenScanIntervalTicks = Math.max(1, config.getInt("mode.player-scan-interval", 6));

        // 加载物品驱动模式配置
        itemDrivenEnabled = config.getBoolean("mode.item-driven", true);
        activeDetectionTicks = Math.max(0, config.getInt("mode.item-active-duration", 60));
        pickupAttemptIntervalTicks = Math.max(1, config.getInt("mode.item-check-interval", 2));

        // 加载物品合并配置
        boolean itemMergeEnabled = config.getBoolean("custom-item-merge.enabled", true);
        itemMergeRange = config.getDouble("custom-item-merge.range", 1.0);

        // 重新配置拾取管理器
        if (pickupManager != null) {
            pickupManager.loadConfig();  // 通知管理器重新加载配置

            // 根据配置状态控制管理器启停
            if (!isPickupDisabled()) {
                // 如果应该启用拾取功能
                if (pickupManager.isActive()) {
                    pickupManager.disable();  // 先停止（如果需要重新配置）
                }
                pickupManager.enable();  // 重新启用
            } else {
                // 如果拾取功能被禁用
                if (pickupManager.isActive()) {
                    pickupManager.disable();  // 停止管理器
                    pickupManager.restoreOriginalPickupDelay();  // 恢复原版拾取延迟
                }
            }
        }

        // 重新配置物品合并器
        if (itemMerger != null) {
            itemMerger.stop();  // 停止当前合并器
            itemMerger = null;  // 释放引用
        }
        if (itemMergeEnabled) {
            initializeItemMerger();  // 重新初始化合并器
            if (shouldRunItemMerger()) {
                itemMerger.start();  // 启动合并器
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

        // 创建物品合并器实例
        this.itemMerger = new CustomItemMerger(this, itemMergeRange, activeDuration, scanInterval);
    }

    /**
     * 启动拾取功能
     * 用于命令控制或在配置重载后启用
     */
    public void startPickup() {
        stoppedByCommand = false;  // 重置停止标志
        pickupManager.enable();    // 启用拾取管理器

        // 如果物品合并功能启用，也启动合并器
        if (itemMerger != null && getConfig().getBoolean("custom-item-merge.enabled", true)) {
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
            pickupManager.restoreOriginalPickupDelay();  // 恢复原版拾取延迟
        }
        if (itemMerger != null) {
            itemMerger.stop();  // 停止物品合并器
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
}