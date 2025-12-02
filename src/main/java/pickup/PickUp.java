package pickup;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.util.Objects;

public class PickUp extends JavaPlugin {
    private PickupManager pickupManager;
    private boolean stoppedByCommand = false;

    private boolean playerDriven;
    private double pickupRange;
    private int throwCooldownTicks;
    private int selfImmuneTicks;
    private int playerDrivenScanIntervalTicks; // 新增

    private boolean itemDrivenEnabled;
    private int activeDetectionTicks;
    private int pickupAttemptIntervalTicks;

    @Override
    public void onEnable() {
        File restartFlag = new File("restart.flag");
        if (restartFlag.exists()) {
            if (restartFlag.delete()) {
                getLogger().info("已清理残留的 restart.flag 文件");
            } else {
                getLogger().warning("无法删除 restart.flag，请检查文件权限");
            }
        }

        saveDefaultConfig();
        reloadPickup();

        this.pickupManager = new PickupManager(this);
        getServer().getPluginManager().registerEvents(pickupManager, this);

        boolean enabledByConfig = getConfig().getBoolean("enabled", true);
        if (enabledByConfig) {
            pickupManager.enable();
        } else {
            stoppedByCommand = true; // 等效于手动 stop
        }

        ReloadCommand executor = new ReloadCommand(this);
        Objects.requireNonNull(getCommand("up")).setExecutor(executor);
        Objects.requireNonNull(getCommand("mc")).setExecutor(executor);
        getLogger().info("PickUp 插件已加载");
    }

    @Override
    public void onDisable() {
        if (pickupManager != null && pickupManager.isActive()) {
            pickupManager.disable();
        }
        getLogger().info("PickUp 插件已卸载");
    }

    public void reloadPickup() {
        FileConfiguration config = getConfig();

        playerDriven = config.getBoolean("player-driven", true);
        pickupRange = Math.max(0.1, Math.min(10.0, config.getDouble("pickup-range", 1.5)));
        throwCooldownTicks = Math.max(0, config.getInt("throw-cooldown-ticks", 10));
        selfImmuneTicks = Math.max(0, config.getInt("self-immune-ticks", 5));
        playerDrivenScanIntervalTicks = Math.max(1, config.getInt("player-driven-scan-interval-ticks", 6));

        itemDrivenEnabled = config.getBoolean("item-driven-enabled", true);
        activeDetectionTicks = Math.max(0, config.getInt("active-detection-ticks", 60));
        pickupAttemptIntervalTicks = Math.max(1, config.getInt("pickup-attempt-interval-ticks", 2));

        if (pickupManager != null) {
            pickupManager.loadConfig();
        }
        getLogger().info("PickUp 配置已重载");
    }

    public void startPickup() {
        stoppedByCommand = false;
        if (pickupManager != null && !pickupManager.isActive()) {
            pickupManager.enable();
        }
    }

    public void stopPickup() {
        stoppedByCommand = true;
        if (pickupManager != null && pickupManager.isActive()) {
            pickupManager.disable();
        }
    }

    public boolean isPickupEnabled() {
        return getConfig().getBoolean("enabled", true);
    }
    public boolean isStopped() {
        return stoppedByCommand;
    }

    public boolean isStoppedByCommand() {
        return stoppedByCommand; // 运行时开关
    }

    public boolean isPlayerDriven() {
        return playerDriven;
    }

    public double getPickupRange() {
        return pickupRange;
    }

    public int getThrowCooldownTicks() {
        return throwCooldownTicks;
    }

    public int getSelfImmuneTicks() {
        return selfImmuneTicks;
    }

    public int getPlayerDrivenScanIntervalTicks() {
        return playerDrivenScanIntervalTicks;
    }

    public boolean isItemDrivenEnabled() {
        return itemDrivenEnabled;
    }

    public int getActiveDetectionTicks() {
        return activeDetectionTicks;
    }

    public int getPickupAttemptIntervalTicks() {
        return pickupAttemptIntervalTicks;
    }
}