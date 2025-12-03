package pickup;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.util.Objects;

public class PickUp extends JavaPlugin {

    private PickupManager pickupManager;
    private boolean stoppedByCommand = false;
    private CustomItemMerger itemMerger;

    // ========== 玩家驱动模式配置 ==========
    private boolean playerDriven;
    private double pickupRange;
    private int selfImmuneTicks;
    private int playerDrivenScanIntervalTicks;

    // ========== 物品驱动模式配置 ==========
    private boolean itemDrivenEnabled;
    private int activeDetectionTicks;
    private int pickupAttemptIntervalTicks;

    // ========== 不同来源物品的拾取延迟（tick） ==========
    private int playerDropDelayTicks;
    private int naturalDropDelayTicks;
    private int instantPickupDelayTicks;

    // ========== 物品合并配置 ==========
    private boolean itemMergeEnabled;
    private double itemMergeRange;
    private int itemMergeIntervalTicks;

    @Override
    public void onEnable() {
        // 清理重启标记
        File restartFlag = new File("restart.flag");
        if (restartFlag.exists()) {
            if (restartFlag.delete()) {
                getLogger().info("已清理残留的 restart.flag 文件");
            } else {
                getLogger().warning("无法删除 restart.flag，请检查文件权限");
            }
        }

        // 加载配置（必须在创建 manager 前完成）
        saveDefaultConfig();
        reloadPickup();

        // 初始化核心管理器
        this.pickupManager = new PickupManager(this);
        getServer().getPluginManager().registerEvents(pickupManager, this);

        // 初始化物品合并器（如果启用）
        initializeItemMerger();

        // 注册命令
        ReloadCommand executor = new ReloadCommand(this);
        Objects.requireNonNull(getCommand("up")).setExecutor(executor);
        Objects.requireNonNull(getCommand("mc")).setExecutor(executor);

        getLogger().info("PickUp 插件已加载");
    }

    @Override
    public void onDisable() {
        if (pickupManager != null && pickupManager.isActive()) {
            pickupManager.disable();
            pickupManager.restoreOriginalPickupDelay();
        }
        if (itemMerger != null) {
            itemMerger.stop();
            itemMerger = null;
        }
        getLogger().info("PickUp 插件已卸载");
    }

    public void reloadPickup() {
        saveDefaultConfig();
        reloadConfig();
        FileConfiguration config = getConfig();

        // 拾取行为
        pickupRange = Math.max(0.1, Math.min(10.0, config.getDouble("pickup.range", 1.5)));
        selfImmuneTicks = Math.max(0, config.getInt("pickup.self-immune-ticks", 5));

        playerDropDelayTicks = Math.max(0, config.getInt("pickup.delays.player-drop", 10));
        naturalDropDelayTicks = Math.max(0, config.getInt("pickup.delays.natural-drop", 5));
        instantPickupDelayTicks = Math.max(0, config.getInt("pickup.delays.instant-pickup", 0));

        // 驱动模式
        playerDriven = config.getBoolean("mode.player-driven", true);
        playerDrivenScanIntervalTicks = Math.max(1, config.getInt("mode.player-scan-interval", 6));

        itemDrivenEnabled = config.getBoolean("mode.item-driven", true);
        activeDetectionTicks = Math.max(0, config.getInt("mode.item-active-duration", 60));
        pickupAttemptIntervalTicks = Math.max(1, config.getInt("mode.item-check-interval", 2));

        // 物品合并配置
        itemMergeEnabled = config.getBoolean("custom-item-merge.enabled", true);
        itemMergeRange = config.getDouble("custom-item-merge.range", 1.0);
        itemMergeIntervalTicks = config.getInt("custom-item-merge.interval-ticks", 10);

        // 重载 PickupManager 配置
        if (pickupManager != null) {
            if (pickupManager.isActive()) {
                pickupManager.disable();
                pickupManager.enable();
            } else {
                pickupManager.loadConfig();
            }
        }

        // 重载物品合并器
        if (itemMerger != null) {
            itemMerger.stop();
            itemMerger = null;
        }
        if (itemMergeEnabled) {
            initializeItemMerger();
        }

        getLogger().info("PickUp 配置已重载");
    }

    private void initializeItemMerger() {
        if (pickupManager == null) return; // 安全检查
        this.itemMerger = new CustomItemMerger(
                this,
                pickupManager.getActiveItemsByWorld(),
                itemMergeRange,
                itemMergeIntervalTicks
        );
        this.itemMerger.start();
    }

    public void startPickup() {
        stoppedByCommand = false;
        if (pickupManager != null) {
            pickupManager.enable();
        }
    }

    public void stopPickup() {
        stoppedByCommand = true;
        if (pickupManager != null) {
            pickupManager.disable();
            pickupManager.restoreOriginalPickupDelay();
        }
    }

    public boolean isPickupActive() {
        if (stoppedByCommand) return false;
        return getConfig().getBoolean("enabled", true);
    }

    public boolean isStoppedByCommand() {
        return stoppedByCommand;
    }

    // ========== Getter 方法 ==========
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
}