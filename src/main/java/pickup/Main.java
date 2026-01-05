package pickup;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.util.Objects;

import pickup.command.CommandManager;
import pickup.command.McCommand;
import pickup.config.PickupConfig;
import pickup.feature.*;
import pickup.event.*;
import pickup.feature.pickupmanager.PickupManager;
import org.bukkit.World;

/**
 * PickUp插件主类 - 只负责初始化和生命周期管理
 */
public class Main extends JavaPlugin {

    // 管理器实例
    private PickupManager pickupManager;
    private ItemMerger itemMerger;
    private PickupConfig pickupConfig;
    private PickupEvent pickupEventListener;
    public ItemSpatialIndex itemSpatialIndex;

    // 调度任务引用
    private ScheduledTask globalTask;

    // 控制标志
    private boolean stoppedByCommand = false;

    @Override
    public void onEnable() {
        // 检查folia支持
        if (!isFoliaSupported()) {
            getLogger().severe("Folia not supported on this server! Plugin will be disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 清理重启标志文件
        cleanupRestartFlag();

        // 初始化配置管理器
        this.pickupConfig = new PickupConfig(this);

        // 初始化功能模块
        initializeModules();

        // 注册命令
        registerCommands();

        getLogger().info("PickUp 插件已加载");
    }

    @Override
    public void onDisable() {

        // 1. 强制清理所有物品标记（确保原版拾取恢复）
        forceCleanupAllItems();

        // 2. 注销事件监听器（只在插件完全卸载时执行）
        unregisterEventListener();

        // 3. 停止空间索引清理任务
        if (itemSpatialIndex != null) {
            itemSpatialIndex.stopCleanupTask();
        }

        // 4. 保存所有待定的配置更改
        if (pickupConfig != null) {
            pickupConfig.onDisable();
        }

        // 5. 取消所有调度任务
        cancelAllScheduledTasks();

        getLogger().info("PickUp 插件已完全卸载");
    }

    /**
     * 检查folia支持
     */
    private boolean isFoliaSupported() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * 取消所有调度任务
     */
    private void cancelAllScheduledTasks() {
        if (globalTask != null) {
            globalTask.cancel();
            globalTask = null;
        }
    }

    /**
     * 清理重启标志文件
     */
    private void cleanupRestartFlag() {
        File restartFlag = new File("restart.flag");
        if (restartFlag.exists()) {
            if (restartFlag.delete()) {
                getLogger().info("已清理残留的 restart.flag 文件");
            } else {
                getLogger().warning("无法删除 restart.flag 文件");
            }
        }
    }

    /**
     * 初始化所有功能模块
     */
    private void initializeModules() {
        // 1. 创建空间索引
        this.itemSpatialIndex = new ItemSpatialIndex(this);

        // 2. 创建拾取管理器
        this.pickupManager = new PickupManager(this, pickupConfig, itemSpatialIndex);

        // 3. 创建物品合并器
        if (pickupConfig.isItemMergeEnabled()) {
            this.itemMerger = new ItemMerger(this,
                    pickupConfig.getItemMergeRange(),
                    pickupConfig.getItemMergeActiveDurationTicks(),
                    pickupConfig.getItemMergeScanIntervalTicks());
        }

        // 4. 注册事件监听器（只注册一次）
        registerEventListener();

        // 5. 启动功能（如果未禁用）
        if (!isPickupDisabled()) {
            enableModules();
        }

        // 6. 启动空间索引清理任务
        if (itemSpatialIndex != null) {
            itemSpatialIndex.startCleanupTask();
        }
    }

    /**
     * 注册事件监听器 - 只在插件启用时注册一次
     */
    private void registerEventListener() {
        if (pickupEventListener != null) {
            return; // 已经注册过，不再重复注册
        }

        this.pickupEventListener = new PickupEvent(this);
        getServer().getPluginManager().registerEvents(pickupEventListener, this);
    }

    /**
     * 注销事件监听器 - 只在插件卸载时调用
     */
    private void unregisterEventListener() {
        if (pickupEventListener != null) {
            HandlerList.unregisterAll(pickupEventListener);
            pickupEventListener = null;
        }
    }

    /**
     * 启动功能模块
     */
    private void enableModules() {
        if (pickupManager != null) {
            pickupManager.enable();
        }

        if (itemMerger != null && pickupConfig.isItemMergeEnabled()) {
            itemMerger.start();
        }

    }

    /**
     * 停止功能模块
     */
    private void disableModules() {
        if (pickupManager != null && pickupManager.isActive()) {
            pickupManager.disable();
        }

        if (itemMerger != null) {
            itemMerger.stop();
        }

    }

    /**
     * 重载插件配置和模块
     */
    public void reloadPickup() {
        getLogger().info("开始重载插件...");

        // 1. 停止当前运行的功能模块
        disableModules();

        // 2. 重载配置
        pickupConfig.reload();

        // 3. 重新初始化拾取管理器（重新订阅配置变更）
        this.pickupManager = new PickupManager(this, pickupConfig, itemSpatialIndex);

        // 4. 更新事件监听器中的管理器引用
        if (pickupEventListener != null) {
            pickupEventListener.updatePickupManager(pickupManager);
        }

        // 5. 重新初始化物品合并器
        if (pickupConfig.isItemMergeEnabled()) {
            this.itemMerger = new ItemMerger(this,
                    pickupConfig.getItemMergeRange(),
                    pickupConfig.getItemMergeActiveDurationTicks(),
                    pickupConfig.getItemMergeScanIntervalTicks());
        } else {
            this.itemMerger = null;
        }

        // 6. 重新启动功能（如果未禁用）
        if (!isPickupDisabled()) {
            enableModules();
        }

        getLogger().info("插件重载完成");
    }

    /**
     * 启动拾取功能（命令调用）
     */
    public void startPickup() {

        stoppedByCommand = false;

        // 清理之前的物品标记（确保干净状态）
        forceCleanupAllItems();

        // 启用功能模块
        enableModules();

        getLogger().info("拾取功能已启动");
    }

    /**
     * 停止拾取功能（命令调用）
     */
    public void stopPickup() {

        stoppedByCommand = true;

        // 停止功能模块
        disableModules();

        // 强制清理所有物品标记（恢复原版拾取）
        forceCleanupAllItems();

        getLogger().info("拾取功能已停止，原版拾取已恢复");
    }

    /**
     * 强制清理所有物品标记
     */
    private void forceCleanupAllItems() {

        if (pickupManager != null && pickupManager.getLifecycleManager() != null) {
            for (World world : getServer().getWorlds()) {
                pickupManager.getLifecycleManager().forceCleanupWorld(world);
            }
        }

    }

    /**
     * 注册命令处理器
     */
    private void registerCommands() {
        // 注册 /up 和 /pickup 命令（使用新的CommandManager）
        CommandManager commandManager = new CommandManager(this);

        // 注册主命令
        Objects.requireNonNull(getCommand("pickup")).setExecutor(commandManager);
        Objects.requireNonNull(getCommand("pickup")).setTabCompleter(commandManager);

        // 注册别名
        Objects.requireNonNull(getCommand("up")).setExecutor(commandManager);
        Objects.requireNonNull(getCommand("up")).setTabCompleter(commandManager);

        // 单独注册 /mc 命令
        McCommand mcCommand = new McCommand(this);
        Objects.requireNonNull(getCommand("mc")).setExecutor(mcCommand);
        Objects.requireNonNull(getCommand("mc")).setTabCompleter(mcCommand);
    }

    /**
     * 检查拾取功能是否被禁用
     */
    public boolean isPickupDisabled() {
        return stoppedByCommand || !pickupConfig.isEnabled();
    }

    // ========== Getter 方法 ==========
    public boolean isStoppedByCommand() { return stoppedByCommand; }
    public PickupConfig getPickupConfig() { return pickupConfig; }
    public ItemMerger getItemMerger() { return itemMerger; }
    public ItemSpatialIndex getItemSpatialIndex() { return this.itemSpatialIndex; }
    public PickupManager getPickupManager() { return pickupManager; }
}