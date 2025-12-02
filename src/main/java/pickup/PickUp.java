package pickup;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.util.Objects;

public class PickUp extends JavaPlugin {
    private PickupManager pickupManager;
    private boolean stoppedByCommand = false;

    // Player-driven settings
    private boolean playerDriven;
    private double pickupRange;
    private int selfImmuneTicks;
    private int playerDrivenScanIntervalTicks;

    // Item-driven settings
    private boolean itemDrivenEnabled;
    private int activeDetectionTicks;
    private int pickupAttemptIntervalTicks;

    // Pickup delays by source type
    private int playerDropDelayTicks;      // 玩家丢弃（Q键等）
    private int naturalDropDelayTicks;     // 怪物/方块掉落
    private int instantPickupDelayTicks;   // 命令/投掷器等（通常为0）

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

        this.stoppedByCommand = false;

        boolean enabledByConfig = getConfig().getBoolean("enabled", true);
        if (enabledByConfig) {
            pickupManager.enable();
        } else {
            pickupManager.disable();
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

        // pickup.range
        pickupRange = Math.max(0.1, Math.min(10.0, config.getDouble("pickup.range", 1.5)));

        // pickup.self-immune-ticks
        selfImmuneTicks = Math.max(0, config.getInt("pickup.self-immune-ticks", 5));

        // pickup.delays.*
        playerDropDelayTicks = Math.max(0, config.getInt("pickup.delays.player-drop", 10));
        naturalDropDelayTicks = Math.max(0, config.getInt("pickup.delays.natural-drop", 5));
        instantPickupDelayTicks = Math.max(0, config.getInt("pickup.delays.instant-pickup", 0));

        // mode.player-driven
        playerDriven = config.getBoolean("mode.player-driven", true);
        playerDrivenScanIntervalTicks = Math.max(1, config.getInt("mode.player-scan-interval", 6));

        // mode.item-driven
        itemDrivenEnabled = config.getBoolean("mode.item-driven", true);
        activeDetectionTicks = Math.max(0, config.getInt("mode.item-active-duration", 60));
        pickupAttemptIntervalTicks = Math.max(1, config.getInt("mode.item-check-interval", 2));

        if (pickupManager != null) {
            pickupManager.loadConfig();
        }
        getLogger().info("PickUp 配置已重载");
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
        }
    }

    public boolean isPickupActive() {
        if (stoppedByCommand) {
            return false;
        }
        return getConfig().getBoolean("enabled", true);
    }

    public boolean isPickupEnabled() {
        return getConfig().getBoolean("enabled", true);
    }

    public boolean isStoppedByCommand() {
        return stoppedByCommand;
    }

    // Player-driven
    public boolean isPlayerDriven() {
        return playerDriven;
    }

    public double getPickupRange() {
        return pickupRange;
    }

    public int getSelfImmuneTicks() {
        return selfImmuneTicks;
    }

    public int getPlayerDrivenScanIntervalTicks() {
        return playerDrivenScanIntervalTicks;
    }

    // Item-driven
    public boolean isItemDrivenEnabled() {
        return itemDrivenEnabled;
    }

    public int getActiveDetectionTicks() {
        return activeDetectionTicks;
    }

    public int getPickupAttemptIntervalTicks() {
        return pickupAttemptIntervalTicks;
    }

    // Delays by source
    public int getPlayerDropDelayTicks() {
        return playerDropDelayTicks;
    }

    public int getNaturalDropDelayTicks() {
        return naturalDropDelayTicks;
    }

    public int getInstantPickupDelayTicks() {
        return instantPickupDelayTicks;
    }
}