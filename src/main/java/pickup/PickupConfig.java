package pickup;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 配置管理类 - 负责所有配置相关的操作
 */
public class PickupConfig {

    private final JavaPlugin plugin;
    private FileConfiguration config;

    // 配置变更监听器
    private final List<ConfigChangeListener> listeners = new ArrayList<>();

    public interface ConfigChangeListener {
        void onConfigChanged(String key, Object value);
    }

    // 添加监听器方法
    public void addChangeListener(ConfigChangeListener listener) {
        listeners.add(listener);
    }

    // 通知所有监听器
    private void notifyListeners(String key, Object value) {
        for (ConfigChangeListener listener : listeners) {
            listener.onConfigChanged(key, value);
        }
    }

    // 配置字段
    private boolean enabled;
    private boolean playerDriven;
    private double pickupRange;
    private boolean offhandPickupEnabled;
    private int selfImmuneTicks;
    private int playerDrivenScanIntervalTicks;
    private boolean itemDrivenEnabled;
    private int activeDetectionTicks;
    private int pickupAttemptIntervalTicks;
    private boolean deathLogEnabled;
    private boolean deathLogSendPrivateMessage;
    private int playerDropDelayTicks;
    private int naturalDropDelayTicks;
    private int instantPickupDelayTicks;
    private double itemMergeRange;
    private boolean itemMergeEnabled;
    private int itemMergeActiveDurationTicks;
    private int itemMergeScanIntervalTicks;

    /**
     * 构造函数
     */
    public PickupConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /**
     * 重新加载配置
     */
    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        loadAllFields();
    }

    /**
     * 加载所有配置字段
     */
    private void loadAllFields() {
        // 模式配置
        this.enabled = config.getBoolean("enabled", true);
        this.playerDriven = config.getBoolean("mode.player-driven", true);
        this.playerDrivenScanIntervalTicks = Math.max(1, config.getInt("mode.player-scan-interval", 6));
        this.itemDrivenEnabled = config.getBoolean("mode.item-driven", true);
        this.activeDetectionTicks = Math.max(0, config.getInt("mode.item-active-duration", 60));
        this.pickupAttemptIntervalTicks = Math.max(1, config.getInt("mode.item-check-interval", 2));

        // 拾取配置
        this.pickupRange = Math.max(0.1, Math.min(20.0, config.getDouble("pickup.range", 1.5)));
        this.selfImmuneTicks = Math.max(0, config.getInt("pickup.self-immune-ticks", 5));
        this.offhandPickupEnabled = config.getBoolean("pickup.offhand-pickup", false);

        // 延迟配置
        this.playerDropDelayTicks = Math.max(0, config.getInt("pickup.delays.player-drop", 15));
        this.naturalDropDelayTicks = Math.max(0, config.getInt("pickup.delays.natural-drop", 10));
        this.instantPickupDelayTicks = Math.max(0, config.getInt("pickup.delays.instant-pickup", 0));

        // 死亡日志配置
        this.deathLogEnabled = config.getBoolean("death-log.enabled", true);
        this.deathLogSendPrivateMessage = config.getBoolean("death-log.send-private-message", true);

        // 物品合并配置
        this.itemMergeRange = config.getDouble("custom-item-merge.range", 1.0);
        this.itemMergeEnabled = config.getBoolean("custom-item-merge.enabled", true);
        this.itemMergeActiveDurationTicks = config.getInt("custom-item-merge.active-duration-ticks", 10);
        this.itemMergeScanIntervalTicks = config.getInt("custom-item-merge.scan-interval-ticks", 2);

        // 加载完成后通知监听器配置已完全重载
        notifyListeners("__RELOAD_ALL__", null);
    }

    /**
     * 设置配置值并保存
     */
    public boolean setConfig(String key, Object value) {
        try {
            // 验证配置键是否存在
            if (!getConfigKeys().contains(key)) {
                throw new IllegalArgumentException("配置键不存在: " + key);
            }

            // 设置并保存配置
            config.set(key, value);
            plugin.saveConfig();
            plugin.reloadConfig();

            // 更新对应的内存字段
            updateField(key, value);

            // 通知监听器单个配置变更
            notifyListeners(key, value);

            plugin.getLogger().info("配置已更新: " + key + " = " + value + " (执行者: " +
                    (plugin.getServer().getConsoleSender().getName()) + ")");

            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("设置配置失败: " + key + " = " + value);
            plugin.getLogger().warning("错误信息: " + e.getMessage());
            return false;
        }
    }

    /**
     * 获取配置值
     */
    public Object getConfig(String key) {
        return config.get(key);
    }

    /**
     * 获取所有配置键
     */
    public List<String> getConfigKeys() {
        return Arrays.asList(
                "enabled",
                "mode.player-driven",
                "mode.player-scan-interval",
                "mode.item-driven",
                "mode.item-active-duration",
                "mode.item-check-interval",
                "pickup.range",
                "pickup.self-immune-ticks",
                "pickup.offhand-pickup",
                "pickup.delays.player-drop",
                "pickup.delays.natural-drop",
                "pickup.delays.instant-pickup",
                "death-log.enabled",
                "death-log.send-private-message",
                "custom-item-merge.enabled",
                "custom-item-merge.range",
                "custom-item-merge.active-duration-ticks",
                "custom-item-merge.scan-interval-ticks"
        );
    }

    /**
     * 获取配置值类型提示
     */
    public String getValueTypeHint(String key) {
        if (key.contains("range") || key.contains("radius")) {
            return "小数 (0.1-20.0)";
        } else if (key.contains("ticks") || key.contains("interval") || key.contains("delay")) {
            return "整数 (ticks)";
        } else if (key.endsWith(".enabled") ||
                key.contains("player-driven") ||
                key.contains("item-driven") ||
                key.contains("offhand-pickup") ||
                key.contains("send-private-message")) {
            return "布尔值 (true/false)";
        } else if (key.equals("enabled")) {
            return "布尔值 (true/false)";
        } else {
            return "字符串";
        }
    }

    /**
     * 更新内存中的字段
     */
    private void updateField(String key, Object value) {
        try {
            switch (key) {
                case "enabled":
                    this.enabled = (boolean) value;
                    break;
                case "mode.player-driven":
                    this.playerDriven = (boolean) value;
                    break;
                case "mode.player-scan-interval":
                    this.playerDrivenScanIntervalTicks = (int) value;
                    break;
                case "mode.item-driven":
                    this.itemDrivenEnabled = (boolean) value;
                    break;
                case "mode.item-active-duration":
                    this.activeDetectionTicks = (int) value;
                    break;
                case "mode.item-check-interval":
                    this.pickupAttemptIntervalTicks = (int) value;
                    break;
                case "pickup.range":
                    this.pickupRange = (double) value;
                    break;
                case "pickup.self-immune-ticks":
                    this.selfImmuneTicks = (int) value;
                    break;
                case "pickup.offhand-pickup":
                    this.offhandPickupEnabled = (boolean) value;
                    break;
                case "pickup.delays.player-drop":
                    this.playerDropDelayTicks = (int) value;
                    break;
                case "pickup.delays.natural-drop":
                    this.naturalDropDelayTicks = (int) value;
                    break;
                case "pickup.delays.instant-pickup":
                    this.instantPickupDelayTicks = (int) value;
                    break;
                case "death-log.enabled":
                    this.deathLogEnabled = (boolean) value;
                    break;
                case "death-log.send-private-message":
                    this.deathLogSendPrivateMessage = (boolean) value;
                    break;
                case "custom-item-merge.enabled":
                    this.itemMergeEnabled = (boolean) value;
                    break;
                case "custom-item-merge.range":
                    this.itemMergeRange = (double) value;
                    break;
                case "custom-item-merge.active-duration-ticks":
                    this.itemMergeActiveDurationTicks = (int) value;
                    break;
                case "custom-item-merge.scan-interval-ticks":
                    this.itemMergeScanIntervalTicks = (int) value;
                    break;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("更新配置字段失败: " + key);
        }
    }

    // ========== Getter 方法 ==========

    public boolean isEnabled() { return enabled; }
    public boolean isPlayerDriven() { return playerDriven; }
    public double getPickupRange() { return pickupRange; }
    public boolean isOffhandPickupEnabled() { return offhandPickupEnabled; }
    public int getSelfImmuneTicks() { return selfImmuneTicks; }
    public int getPlayerDrivenScanIntervalTicks() { return playerDrivenScanIntervalTicks; }
    public boolean isItemDrivenEnabled() { return itemDrivenEnabled; }
    public int getActiveDetectionTicks() { return activeDetectionTicks; }
    public int getPickupAttemptIntervalTicks() { return pickupAttemptIntervalTicks; }
    public boolean isDeathLogEnabled() { return deathLogEnabled; }
    public boolean isDeathLogSendPrivateMessage() { return deathLogSendPrivateMessage; }
    public int getPlayerDropDelayTicks() { return playerDropDelayTicks; }
    public int getNaturalDropDelayTicks() { return naturalDropDelayTicks; }
    public int getInstantPickupDelayTicks() { return instantPickupDelayTicks; }
    public double getItemMergeRange() { return itemMergeRange; }
    public boolean isItemMergeEnabled() { return itemMergeEnabled; }
    public int getItemMergeActiveDurationTicks() { return itemMergeActiveDurationTicks; }
    public int getItemMergeScanIntervalTicks() { return itemMergeScanIntervalTicks; }

}