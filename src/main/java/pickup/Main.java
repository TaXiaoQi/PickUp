package pickup;


import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.util.Objects;

import pickup.config.PickupConfig;
import pickup.feature.*;
import pickup.event.*;
/**
 * PickUp插件主类 - 只负责初始化和生命周期管理
 */
public class Main extends JavaPlugin {

    // 管理器实例
    private PickupManager pickupManager;
    private CustomItemMerger itemMerger;
    private PickupConfig pickupConfig;
    private PickupEvent pickupEventListener; // 新增：空间索引
    public ItemSpatialIndex itemSpatialIndex;
    // 控制标志
    private boolean stoppedByCommand = false;

    @Override
    public void onEnable() {
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
        // 保存所有待定的配置更改
        if (pickupConfig != null) {
            pickupConfig.onDisable();
        }

        // 安全停止所有模块
        shutdownModules();
        getLogger().info("PickUp 插件已卸载");
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
        // 1. 先创建配置管理器
        this.pickupConfig = new PickupConfig(this);

        // 2. 创建空间索引（必须先创建）
        this.itemSpatialIndex = new ItemSpatialIndex(this);

        // 3. 创建拾取管理器（传入空间索引）
        this.pickupManager = new PickupManager(this, pickupConfig, itemSpatialIndex);

        // 4. 创建物品合并器
        if (pickupConfig.isItemMergeEnabled()) {
            this.itemMerger = new CustomItemMerger(this,
                    pickupConfig.getItemMergeRange(),
                    pickupConfig.getItemMergeActiveDurationTicks(),
                    pickupConfig.getItemMergeScanIntervalTicks());
        }

        // 5. 注册事件监听器
        registerEventListener();

        // 6. 启动功能（如果未禁用）
        if (!isPickupDisabled()) {
            enableModules();
        }
    }

    /**
     * 注册事件监听器
     */
    private void registerEventListener() {
        // 注销可能存在的旧监听器
        unregisterEventListener();

        // 创建新的监听器
        this.pickupEventListener = new PickupEvent(this, pickupManager);
        getServer().getPluginManager().registerEvents(pickupEventListener, this);
    }

    /**
     * 注销事件监听器
     */
    public void unregisterEventListener() {
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
        if (itemMerger != null && shouldRunItemMerger()) {
            itemMerger.start();
        }
        if (itemSpatialIndex != null) {
            itemSpatialIndex.startCleanupTask(); // 启动清理任务
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
        // itemSpatialIndex 不需要停止，它会自动被垃圾回收
    }

    /**
     * 关闭时清理所有模块
     */
    private void shutdownModules() {
        disableModules();
        unregisterEventListener();

        // 清理引用
        this.pickupManager = null;
        this.itemMerger = null;
        this.pickupEventListener = null;
        this.pickupConfig = null;
        this.itemSpatialIndex = null;
    }

    /**
     * 重载插件配置和模块
     */
    public void reloadPickup() {
        // 停止当前运行的功能
        disableModules();
        unregisterEventListener();

        // 重载配置
        if (pickupConfig != null) {
            pickupConfig.reload();
        } else {
            this.pickupConfig = new PickupConfig(this);
        }

        // 重新创建空间索引
        this.itemSpatialIndex = new ItemSpatialIndex(this);

        // 重新初始化拾取管理器
        this.pickupManager = new PickupManager(this, pickupConfig, itemSpatialIndex);

        // 重新初始化物品合并器
        if (pickupConfig.isItemMergeEnabled()) {
            this.itemMerger = new CustomItemMerger(this,
                    pickupConfig.getItemMergeRange(),
                    pickupConfig.getItemMergeActiveDurationTicks(),
                    pickupConfig.getItemMergeScanIntervalTicks());
        } else {
            this.itemMerger = null;
        }

        // 重新注册事件监听器
        registerEventListener();

        // 重新启动功能（如果未禁用）
        if (!isPickupDisabled()) {
            enableModules();
        }

        getLogger().info("PickUp 配置已重载");
    }

    /**
     * 启动拾取功能（命令调用）
     */
    public void startPickup() {
        stoppedByCommand = false;
        registerEventListener();
        enableModules();
    }

    /**
     * 停止拾取功能（命令调用）
     */
    public void stopPickup() {
        stoppedByCommand = true;
        disableModules();
        unregisterEventListener();
    }

    /**
     * 注册命令处理器
     */
    private void registerCommands() {
        ReloadCommand configCommand = new ReloadCommand(this);
        Objects.requireNonNull(getCommand("pickup")).setExecutor(configCommand);
        Objects.requireNonNull(getCommand("mc")).setExecutor(configCommand);
    }

    /**
     * 检查拾取功能是否被禁用
     */
    public boolean isPickupDisabled() {
        return stoppedByCommand || !pickupConfig.isEnabled();
    }

    /**
     * 检查是否应该运行物品合并器
     */
    private boolean shouldRunItemMerger() {
        return !isPickupDisabled() && pickupConfig.isItemMergeEnabled();
    }

    // ========== Getter 方法 ==========
    public boolean isStoppedByCommand() {return stoppedByCommand;}
    public PickupConfig getPickupConfig() {return pickupConfig;}
    public CustomItemMerger getItemMerger() {return itemMerger;}
    public PickupManager getPickupManager() {return this.pickupManager;}
    public ItemSpatialIndex getItemSpatialIndex() {return this.itemSpatialIndex;}
}
