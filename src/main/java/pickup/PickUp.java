package pickup;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.util.Objects;

public class PickUp extends JavaPlugin {
    private PickupManager pickupManager;
    private boolean stoppedByCommand = false; // 唯一运行时开关状态

    private boolean playerDriven;
    private double pickupRange;
    private int throwCooldownTicks;
    private boolean itemDrivenEnabled;
    private int activeDetectionTicks;
    private int pickupAttemptIntervalTicks;
    private int selfImmuneTicks;

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

        boolean enabledByDefault = getConfig().getBoolean("enabled", true);
        stoppedByCommand = !enabledByDefault;
        if (!stoppedByCommand) {
            pickupManager.enable();
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
        throwCooldownTicks = Math.max(0, config.getInt("throw-cooldown-ticks", 30));

        itemDrivenEnabled = config.getBoolean("item-driven.enabled", true);
        activeDetectionTicks = Math.max(0, config.getInt("item-driven.active-detection-ticks", 60));
        pickupAttemptIntervalTicks = Math.max(1, config.getInt("item-driven.pickup-attempt-interval-ticks", 5));
        selfImmuneTicks = Math.max(0, config.getInt("item-driven.self-immune-ticks", 5));

        pickupManager.loadConfig();

        getLogger().info("PickUp 配置已重载");
    }

    public void startPickup() {
        stoppedByCommand = false;
        pickupManager.enable();
    }

    public void stopPickup() {
        stoppedByCommand = true;
        if (pickupManager != null && pickupManager.isActive()) {
            pickupManager.disable();
        }
    }

    public boolean isPickupEnabled() {
        return !stoppedByCommand;
    }

    public boolean isStopped() {
        return stoppedByCommand;
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

    public boolean isItemDrivenEnabled() {
        return itemDrivenEnabled;
    }

    public int getActiveDetectionTicks() {
        return activeDetectionTicks;
    }

    public int getPickupAttemptIntervalTicks() {
        return pickupAttemptIntervalTicks;
    }

    public int getSelfImmuneTicks() {
        return selfImmuneTicks;
    }
}