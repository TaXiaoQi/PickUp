package pickup;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置管理类 - 负责所有配置相关的操作
 */
public class PickupConfig {

    private final JavaPlugin plugin;
    private FileConfiguration config;

    // 配置变更监听器
    private final List<ConfigChangeListener> listeners = new ArrayList<>();

    // 延迟保存机制
    private final Map<String, Object> pendingChanges = new ConcurrentHashMap<>();
    private BukkitRunnable delayedSaveTask = null;
    private static final long SAVE_DELAY_TICKS = 600L; // 30秒 = 20 ticks/秒 * 30

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
    private double playerMinMoveDistance;
    private int playerMoveCheckIntervalTicks;

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

        // 取消任何待定的保存任务
        cancelDelayedSave();
        pendingChanges.clear();
    }

    /**
     * 加载所有配置字段
     */
    private void loadAllFields() {
        // 模式配置
        this.enabled = config.getBoolean("enabled", true);
        this.playerDriven = config.getBoolean("mode.player-driven", true);
        this.playerMoveCheckIntervalTicks = Math.max(1, config.getInt("mode.player-move-check-interval", 6));
        this.itemDrivenEnabled = config.getBoolean("mode.item-driven", true);
        this.activeDetectionTicks = Math.max(0, config.getInt("mode.item-active-duration", 60));
        this.pickupAttemptIntervalTicks = Math.max(1, config.getInt("mode.item-check-interval", 2));

        // 拾取配置
        this.pickupRange = Math.max(0.1, Math.min(20.0, config.getDouble("pickup.range", 1.5)));
        this.selfImmuneTicks = Math.max(0, config.getInt("pickup.self-immune-ticks", 5));
        this.offhandPickupEnabled = config.getBoolean("pickup.offhand-pickup", false);
        this.playerMinMoveDistance = Math.max(0.0, config.getDouble("mode.player-min-move-distance", 0.25));

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
     * 设置配置值并保存（延迟保存）
     */
    public boolean setConfig(String key, Object value) {
        try {
            // 验证配置键是否存在
            if (!getConfigKeys().contains(key)) {
                throw new IllegalArgumentException("配置键不存在: " + key);
            }

            // 1. 立即更新内存中的配置对象（用于getConfig()能获取最新值）
            config.set(key, value);

            // 2. 更新对应的内存字段（立即生效）
            updateField(key, value);

            // 3. 添加到待保存队列
            pendingChanges.put(key, value);

            // 4. 通知监听器配置变更（立即通知）
            notifyListeners(key, value);

            // 5. 启动或重置延迟保存任务
            scheduleDelayedSave();

            plugin.getLogger().info("配置已更新（内存）: " + key + " = " + value + " (执行者: " +
                    (plugin.getServer().getConsoleSender().getName()) + ")");

            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("设置配置失败: " + key + " = " + value);
            plugin.getLogger().warning("错误信息: " + e.getMessage());
            return false;
        }
    }

    /**
     * 获取配置值（从内存中的最新配置）
     */
    public Object getConfig(String key) {
        // 优先从内存中的配置对象获取，保证获取的是最新值
        return config.get(key);
    }

    /**
     * 立即保存所有待定的配置变更到磁盘
     * @return 是否保存成功
     */
    public boolean saveNow() {
        if (pendingChanges.isEmpty()) {
            return true; // 没有需要保存的更改
        }

        try {
            // 1. 将所有待定更改应用到配置对象（虽然已经在setConfig时应用过，但这里再确认一次）
            for (Map.Entry<String, Object> entry : pendingChanges.entrySet()) {
                config.set(entry.getKey(), entry.getValue());
            }

            // 2. 保存到磁盘
            plugin.saveConfig();

            // 3. 清空待保存队列
            int savedCount = pendingChanges.size();
            pendingChanges.clear();

            // 4. 取消延迟保存任务
            cancelDelayedSave();

            plugin.getLogger().info("配置已保存到磁盘 (" + savedCount + " 个更改)");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("保存配置到磁盘失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 安排延迟保存任务
     */
    private void scheduleDelayedSave() {
        // 取消现有的任务（如果有）
        cancelDelayedSave();

        // 创建新的延迟保存任务
        delayedSaveTask = new BukkitRunnable() {
            @Override
            public void run() {
                saveNow();
            }
        };

        // 延迟SAVE_DELAY_TICKS后执行
        delayedSaveTask.runTaskLater(plugin, SAVE_DELAY_TICKS);
        plugin.getLogger().fine("延迟保存任务已安排，将在 " + (SAVE_DELAY_TICKS / 20) + " 秒后执行");
    }

    /**
     * 取消延迟保存任务
     */
    private void cancelDelayedSave() {
        if (delayedSaveTask != null) {
            delayedSaveTask.cancel();
            delayedSaveTask = null;
        }
    }

    /**
     * 插件禁用时立即保存所有配置
     */
    public void onDisable() {
        if (!saveNow()) {
            plugin.getLogger().warning("插件禁用时保存配置失败，部分配置可能丢失！");
        }
        cancelDelayedSave();
    }

    /**
     * 获取所有配置键
     */
    public List<String> getConfigKeys() {
        return new ArrayList<>(config.getKeys(true));
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
                    this.enabled = getBooleanValue(value);
                    break;
                case "mode.player-driven":
                    this.playerDriven = getBooleanValue(value);
                    break;
                case "mode.item-driven":
                    this.itemDrivenEnabled = getBooleanValue(value);
                    break;
                case "mode.item-active-duration":
                    this.activeDetectionTicks = getIntValue(value);
                    break;
                case "mode.item-check-interval":
                    this.pickupAttemptIntervalTicks = getIntValue(value);
                    break;
                case "pickup.range":
                    this.pickupRange = getDoubleValue(value);
                    break;
                case "pickup.offhand-pickup":
                    this.offhandPickupEnabled = getBooleanValue(value);
                    break;
                case "pickup.delays.player-drop":
                    this.playerDropDelayTicks = getIntValue(value);
                    break;
                case "pickup.delays.natural-drop":
                    this.naturalDropDelayTicks = getIntValue(value);
                    break;
                case "pickup.delays.instant-pickup":
                    this.instantPickupDelayTicks = getIntValue(value);
                    break;
                case "death-log.enabled":
                    this.deathLogEnabled = getBooleanValue(value);
                    break;
                case "death-log.send-private-message":
                    this.deathLogSendPrivateMessage = getBooleanValue(value);
                    break;
                case "custom-item-merge.enabled":
                    this.itemMergeEnabled = getBooleanValue(value);
                    break;
                case "custom-item-merge.range":
                    this.itemMergeRange = getDoubleValue(value);
                    break;
                case "custom-item-merge.active-duration-ticks":
                    this.itemMergeActiveDurationTicks = getIntValue(value);
                    break;
                case "custom-item-merge.scan-interval-ticks":
                    this.itemMergeScanIntervalTicks = getIntValue(value);
                    break;
                case "mode.player-min-move-distance":
                    this.playerMinMoveDistance = getDoubleValue(value);
                    break;
                case "mode.player-move-check-interval":
                    this.playerMoveCheckIntervalTicks = getIntValue(value);
                    break;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("更新配置字段失败: " + key + ", 值: " + value + ", 错误: " + e.getMessage());
        }
    }

    /**
     * 安全获取布尔值
     */
    private boolean getBooleanValue(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        } else if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        } else if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        return false;
    }

    /**
     * 安全获取整数值
     */
    private int getIntValue(Object value) {
        if (value instanceof Integer) {
            return (Integer) value;
        } else if (value instanceof Double) {
            return ((Double) value).intValue();
        } else if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return 0;
            }
        } else if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    /**
     * 安全获取双精度值
     */
    private double getDoubleValue(Object value) {
        if (value instanceof Double) {
            return (Double) value;
        } else if (value instanceof Integer) {
            return ((Integer) value).doubleValue();
        } else if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        } else if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0.0;
    }

    // ========== Getter 方法 ==========
    public boolean isEnabled() { return enabled; }
    public boolean isPlayerDriven() { return playerDriven; }
    public double getPickupRange() { return pickupRange; }
    public boolean isOffhandPickupEnabled() { return offhandPickupEnabled; }
    public int getSelfImmuneTicks() { return selfImmuneTicks; }
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
    public double getPlayerMinMoveDistance() {return playerMinMoveDistance;}
    public int getPlayerMoveCheckIntervalTicks() { return playerMoveCheckIntervalTicks; }
}