package pickup;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.util.Objects;

/**
 * PickUp 插件主类：负责初始化、配置管理、命令注册及拾取系统控制。
 */
public class PickUp extends JavaPlugin {
    private PickupManager pickupManager;      // 核心拾取逻辑管理器
    private boolean stoppedByCommand = false; // 是否被 /up stop 命令强制关闭

    // ========== 玩家驱动模式配置 ==========
    private boolean playerDriven;                     // 是否启用玩家驱动拾取
    private double pickupRange;                       // 拾取半径（单位：方块）
    private int selfImmuneTicks;                      // 玩家丢弃物品后自身免疫拾取的时间（tick）
    private int playerDrivenScanIntervalTicks;        // 玩家驱动模式下扫描附近物品的频率（tick）

    // ========== 物品驱动模式配置 ==========
    private boolean itemDrivenEnabled;                // 是否启用物品驱动拾取
    private int activeDetectionTicks;                 // 物品被视为“活跃”的最大存活时间（tick）
    private int pickupAttemptIntervalTicks;           // 物品驱动模式下尝试拾取的间隔（tick）

    // ========== 不同来源物品的拾取延迟（tick） ==========
    private int playerDropDelayTicks;      // 玩家主动丢弃（如按 Q 键）后的最小拾取延迟
    private int naturalDropDelayTicks;     // 怪物死亡、方块破坏等自然掉落的延迟
    private int instantPickupDelayTicks;   // 命令、投掷器、漏斗等即时生成物品的延迟（通常为 0）

    /**
     * 插件启用时执行：初始化配置、清理残留文件、注册事件与命令
     */
    @Override
    public void onEnable() {
        // 清理上次重启留下的标志文件（用于热重载检测）
        File restartFlag = new File("restart.flag");
        if (restartFlag.exists()) {
            if (restartFlag.delete()) {
                getLogger().info("已清理残留的 restart.flag 文件");
            } else {
                getLogger().warning("无法删除 restart.flag，请检查文件权限");
            }
        }

        // 加载默认配置并重载所有设置
        saveDefaultConfig();
        reloadPickup();

        // 初始化拾取管理器并注册监听器
        this.pickupManager = new PickupManager(this);
        getServer().getPluginManager().registerEvents(pickupManager, this);

        // 重置命令停止标志
        this.stoppedByCommand = false;

        // 根据配置决定是否自动启用拾取系统
        boolean enabledByConfig = getConfig().getBoolean("enabled", true);
        if (enabledByConfig) {
            pickupManager.enable();
        } else {
            pickupManager.disable();
        }

        // 注册命令执行器（/up 和 /mc 共用同一个处理器）
        ReloadCommand executor = new ReloadCommand(this);
        Objects.requireNonNull(getCommand("up")).setExecutor(executor);
        Objects.requireNonNull(getCommand("mc")).setExecutor(executor);

        getLogger().info("PickUp 插件已加载");
    }

    /**
     * 插件禁用时执行：安全关闭拾取系统
     */
    @Override
    public void onDisable() {
        // 若拾取系统正在运行，则先禁用
        if (pickupManager != null && pickupManager.isActive()) {
            pickupManager.disable();
        }
        getLogger().info("PickUp 插件已卸载");
    }

    /**
     * 重新加载配置文件，并将新值同步到内存变量中
     * 同时触发 PickupManager 的配置更新或重启
     */
    public void reloadPickup() {
        saveDefaultConfig(); // 确保默认配置存在
        reloadConfig();      // 从磁盘重新读取 config.yml
        FileConfiguration config = getConfig();

        // 安全读取并限制范围的配置项
        pickupRange = Math.max(0.1, Math.min(10.0, config.getDouble("pickup.range", 1.5)));
        selfImmuneTicks = Math.max(0, config.getInt("pickup.self-immune-ticks", 5));

        playerDropDelayTicks = Math.max(0, config.getInt("pickup.delays.player-drop", 10));
        naturalDropDelayTicks = Math.max(0, config.getInt("pickup.delays.natural-drop", 5));
        instantPickupDelayTicks = Math.max(0, config.getInt("pickup.delays.instant-pickup", 0));

        playerDriven = config.getBoolean("mode.player-driven", true);
        playerDrivenScanIntervalTicks = Math.max(1, config.getInt("mode.player-scan-interval", 6));

        itemDrivenEnabled = config.getBoolean("mode.item-driven", true);
        activeDetectionTicks = Math.max(0, config.getInt("mode.item-active-duration", 60));
        pickupAttemptIntervalTicks = Math.max(1, config.getInt("mode.item-check-interval", 2));

        // ★★★ 关键逻辑：若 PickupManager 已激活，则重启以应用新配置（包括开关变化）★★★
        if (pickupManager != null && pickupManager.isActive()) {
            pickupManager.disable(); // 先停用旧任务
            pickupManager.enable();  // 再用新配置启动
        } else if (pickupManager != null) {
            // 若未激活，则仅更新内部参数（避免空指针）
            pickupManager.loadConfig();
        }

        getLogger().info("PickUp 配置已重载");
    }

    /**
     * 通过命令手动启动拾取系统（覆盖 stoppedByCommand 标志）
     */
    public void startPickup() {
        stoppedByCommand = false; // 清除“被命令停止”状态
        if (pickupManager != null) {
            pickupManager.enable();
        }
    }

    /**
     * 通过命令手动停止拾取系统（设置 stoppedByCommand = true）
     */
    public void stopPickup() {
        stoppedByCommand = true; // 设置强制停止标志
        if (pickupManager != null) {
            pickupManager.disable();
        }
    }

    /**
     * 判断当前拾取系统是否应处于激活状态：
     * - 若被 /up stop 命令停止，则返回 false；
     * - 否则遵循 config.yml 中的 "enabled" 配置。
     */
    public boolean isPickupActive() {
        if (stoppedByCommand) {
            return false; // 命令优先级最高
        }
        return getConfig().getBoolean("enabled", true);
    }

    /**
     * 返回是否被命令强制停止（用于命令反馈）
     */
    public boolean isStoppedByCommand() {
        return stoppedByCommand;
    }

    // ========== Getter 方法：供 PickupManager 读取配置 ==========

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

    public boolean isItemDrivenEnabled() {
        return itemDrivenEnabled;
    }

    public int getActiveDetectionTicks() {
        return activeDetectionTicks;
    }

    public int getPickupAttemptIntervalTicks() {
        return pickupAttemptIntervalTicks;
    }

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