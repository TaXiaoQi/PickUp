package pickup;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * 命令处理类 - 处理插件相关的所有命令
 * 支持命令：/up 和 /mc
 */
public class ReloadCommand implements CommandExecutor, TabCompleter {

    // 插件主类引用，用于访问插件功能
    private final PickUp plugin;
    private final PickupConfig config;

    /**
     * 构造函数
     * @param plugin 插件主类实例
     */
    public ReloadCommand(PickUp plugin) {
        this.plugin = plugin;
        this.config = plugin.getPickupConfig();
    }

    /**
     * 命令执行主入口
     */
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        // 控制台直接跳过权限检查
        boolean isConsole = sender instanceof org.bukkit.command.ConsoleCommandSender;

        // 非控制台需要权限检查
        if (!isConsole && !sender.hasPermission("pickup.admin")) {
            sender.sendMessage("§c你没有权限使用此命令！");
            return true;
        }

        String commandName = command.getName().toLowerCase();

        if (commandName.equals("mc")) {
            return handleMcCommand(sender, args);
        } else if (commandName.equals("up") || commandName.equals("pickup")) {
            return handleUpCommand(sender, args);
        }

        sender.sendMessage("§c未知命令。");
        return false;
    }

    /**
     * 处理 /mc 命令（服务器管理相关）
     */
    private boolean handleMcCommand(CommandSender sender, String[] args) {
        // 检查参数格式：/mc restart
        if (args.length == 0 || !args[0].equalsIgnoreCase("restart")) {
            sender.sendMessage("§c用法: /mc restart §7- 重启服务器");
            return false;
        }

        // 处理服务器重启逻辑
        handleServerRestart(sender);
        return true;
    }

    /**
     * 处理 /up 命令（插件管理相关）
     */
    private boolean handleUpCommand(CommandSender sender, String[] args) {
        // 如果没有参数，显示用法
        if (args.length == 0) {
            return true;
        }

        // 获取子命令并转为小写
        String sub = args[0].toLowerCase();

        // 使用switch表达式处理不同的子命令
        return switch (sub) {
            case "help" -> {
                // 显示帮助信息
                sendUpUsage(sender);
                yield true;
            }
            case "reload" -> {
                // 重载插件配置
                handlePluginReload(sender);
                yield true;
            }
            case "true" -> {
                // 开启拾取功能
                handleStart(sender);
                yield true;
            }
            case "false" -> {
                // 关闭拾取功能
                handleStop(sender);
                yield true;
            }
            case "status" -> {
                // 显示插件状态
                handleStatus(sender);
                yield true;
            }
            case "set" -> {
                // 动态设置配置项
                handleSetConfig(sender, args);
                yield true;
            }
            case "get" -> {
                // 获取配置值
                handleGetConfig(sender, args);
                yield true;
            }
            case "list" -> {
                // 列出所有配置项
                handleListConfig(sender);
                yield true;
            }
            default -> false;
        };
    }

    /**
     * Tab 补全
     */
    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String alias,
                                                @NotNull String[] args) {

        if (!sender.hasPermission("pickup.admin")) {
            return Collections.emptyList();
        }

        String commandName = command.getName().toLowerCase();

        if (commandName.equals("up") || commandName.equals("pickup")) {
            return completeUpCommand(args);
        } else if (commandName.equals("mc")) {
            return completeMcCommand(args);
        }

        return Collections.emptyList();
    }

    /**
     * 补全 /up 命令
     */
    private List<String> completeUpCommand(String[] args) {
        if (args.length == 1) {
            // 第一个参数：主要子命令
            List<String> completions = new ArrayList<>(Arrays.asList(
                    "help","reload", "true", "false", "status", "set", "get", "list"
            ));
            return filterCompletions(completions, args[0]);
        }

        // 处理 /up set 的子命令
        if (args.length >= 2 && args[0].equalsIgnoreCase("set")) {
            return completeSetCommand(args);
        }

        // 处理 /up get 的子命令
        if (args.length == 2 && args[0].equalsIgnoreCase("get")) {
            List<String> keys = config.getConfigKeys();
            return filterCompletions(keys, args[1]);
        }

        return Collections.emptyList();
    }

    /**
     * 补全 /up set 命令
     */
    private List<String> completeSetCommand(String[] args) {
        if (args.length == 2) {
            List<String> suggestions = new ArrayList<>();
            for (String key : config.getConfigKeys()) {
                // 根据已输入的部分进行过滤
                if (key.toLowerCase().contains(args[1].toLowerCase())) {
                    suggestions.add(key);
                }
            }
            return suggestions;
        }

        if (args.length == 3) {
            // 只对布尔值配置项进行补全
            return completeBooleanValue(args[1], args[2]);
        }

        return Collections.emptyList();
    }

    /**
     * 只对布尔值配置项进行补全
     */
    private List<String> completeBooleanValue(String key, String current) {
        List<String> suggestions = new ArrayList<>();

        // 检查是否是布尔值配置项
        boolean isBooleanConfig =
                key.endsWith(".enabled") ||
                        key.contains("player-driven") ||
                        key.contains("item-driven") ||
                        key.contains("offhand-pickup") ||
                        key.contains("send-private-message") ||
                        key.equals("enabled");

        // 只对布尔值配置项提供补全
        if (isBooleanConfig) {
            if ("true".startsWith(current.toLowerCase())) {
                suggestions.add("true");
            }
            if ("false".startsWith(current.toLowerCase())) {
                suggestions.add("false");
            }
        }

        // 数值类型（整数、小数）不提供补全，直接返回空列表

        return suggestions;
    }

    /**
     * 补全 /mc 命令
     */
    private List<String> completeMcCommand(String[] args) {
        if (args.length == 1) {
            List<String> completions = Collections.singletonList("restart");
            return filterCompletions(completions, args[0]);
        }

        return Collections.emptyList();
    }

    /**
     * 过滤补全列表
     */
    private List<String> filterCompletions(List<String> completions, String current) {
        List<String> filtered = new ArrayList<>();
        StringUtil.copyPartialMatches(current, completions, filtered);
        Collections.sort(filtered);
        return filtered;
    }

    /**
     * 显示 /up 命令的使用方法
     */
    private void sendUpUsage(CommandSender sender) {
        sender.sendMessage("§6========== PickUp 插件命令 (/up) ==========");
        sender.sendMessage("§e/up help    §7- 显示此帮助信息");
        sender.sendMessage("§e/up reload §7- 重载配置文件");
        sender.sendMessage("§e/up true   §7- 开启拾取功能（禁止原版）");
        sender.sendMessage("§e/up false  §7- 关闭拾取功能（恢复原版）");
        sender.sendMessage("§e/up status §7- 查看插件状态");
        sender.sendMessage("§e/up set <key> <value> §7- 动态设置配置项");
        sender.sendMessage("§e/up get <key> §7- 获取配置值");
        sender.sendMessage("§e/up list §7- 列出所有配置项");
        sender.sendMessage("§6========================================");
        sender.sendMessage("§7示例: §e/up set pickup.range 2.5");
        sender.sendMessage("§7示例: §e/up set mode.player-driven false");
    }

    /**
     * 处理服务器重启逻辑
     */
    private void handleServerRestart(CommandSender sender) {
        File flag = new File("restart.flag");

        // 如果标志文件已存在，尝试删除旧文件
        if (flag.exists()) {
            if (!flag.delete()) {
                sender.sendMessage("§c⚠️ 旧的 restart.flag 无法删除，请检查文件是否被占用。");
                plugin.getLogger().warning("无法删除已存在的 restart.flag");
                return;
            }
        }

        try {
            boolean created = flag.createNewFile();
            if (!created) {
                sender.sendMessage("§c❌ 无法创建 restart.flag（可能已被创建或无写入权限）");
                plugin.getLogger().warning("restart.flag 创建失败：createNewFile() 返回 false");
                return;
            }
        } catch (IOException e) {
            sender.sendMessage("§c❌ 创建 restart.flag 时发生 I/O 错误！");
            plugin.getLogger().severe("创建 restart.flag 异常: " + e.getMessage());
            return;
        }

        // 通知所有玩家服务器即将重启
        String msg = "§c[系统] 服务器将在 10 秒后重启！";
        sender.sendMessage("服务器将在 10 秒后重启！");
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(msg));
        plugin.getLogger().info("[Server Restart] Triggered by " + sender.getName());

        // 延迟10秒后关闭服务器
        Bukkit.getScheduler().runTaskLater(plugin, Bukkit::shutdown, 200L);
    }

    /**
     * 处理插件配置重载
     */
    private void handlePluginReload(CommandSender sender) {
        plugin.reloadPickup();
        sender.sendMessage("§a[PickUp] 配置已重载！");
        plugin.getLogger().info("[PickUp] 配置重载完成，执行者: " + sender.getName());
    }

    /**
     * 开启拾取功能
     */
    private void handleStart(CommandSender sender) {
        if (!plugin.isStoppedByCommand()) {
            sender.sendMessage("§c拾取功能已经是开启状态！");
            return;
        }
        plugin.startPickup();
        sender.sendMessage("§a拾取功能已开启（禁止原版拾取）。");
    }

    /**
     * 关闭拾取功能
     */
    private void handleStop(CommandSender sender) {
        if (plugin.isStoppedByCommand()) {
            sender.sendMessage("§c拾取功能已经是关闭状态！");
            return;
        }
        plugin.stopPickup();
        sender.sendMessage("§a拾取功能已关闭，恢复原版逻辑。");
    }

    /**
     * 处理设置配置命令
     */
    private void handleSetConfig(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§c用法: /up set <配置键> <值>");
            sender.sendMessage("§c示例: /up set pickup.range 2.5");
            sender.sendMessage("§c使用 /up list 查看所有配置键");
            return;
        }

        String key = args[1];
        StringBuilder valueBuilder = new StringBuilder();
        for (int i = 2; i < args.length; i++) {
            valueBuilder.append(args[i]);
            if (i < args.length - 1) {
                valueBuilder.append(" ");
            }
        }
        String value = valueBuilder.toString();

        try {
            Object parsedValue = parseConfigValue(key, value);
            boolean success = config.setConfig(key, parsedValue);

            if (success) {
                sender.sendMessage("§a配置已更新: §e" + key + " = " + parsedValue);
                sender.sendMessage("§7提示: 使用 §e/up reload §7使更改生效");
            } else {
                sender.sendMessage("§c配置更新失败，请检查控制台日志");
            }
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§c错误: " + e.getMessage());
        }
    }

    /**
     * 处理获取配置命令
     */
    private void handleGetConfig(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c用法: /up get <配置键>");
            sender.sendMessage("§c使用 /up list 查看所有配置键");
            return;
        }

        String key = args[1];
        Object value = config.getConfig(key);

        if (value == null) {
            sender.sendMessage("§c配置键不存在: " + key);
        } else {
            String typeHint = config.getValueTypeHint(key);
            sender.sendMessage("§a配置值: §e" + key + " = " + value + " §7(" + typeHint + ")");
        }
    }

    /**
     * 处理列出配置命令
     */
    private void handleListConfig(CommandSender sender) {
        List<String> keys = config.getConfigKeys();

        sender.sendMessage("§6========== 配置键列表 ==========");
        for (String key : keys) {
            Object value = config.getConfig(key);
            String typeHint = config.getValueTypeHint(key);
            sender.sendMessage("§e" + key + " §7= " + value + " §8(" + typeHint + ")");
        }
        sender.sendMessage("§6================================");
        sender.sendMessage("§7使用 §e/up set <键> <值> §7修改配置");
    }

    /**
     * 解析配置值的类型
     */
    private Object parseConfigValue(String key, String valueStr) {
        // 1. 布尔值优先
        if (valueStr.equalsIgnoreCase("true") || valueStr.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(valueStr);
        }

        // 2. 判断是否应解析为数值（适用于 range, radius, ticks, delay 等）
        boolean shouldBeNumber =
                key.contains("range") ||
                        key.contains("radius") ||
                        key.contains("delay") ||
                        key.contains("ticks") ||
                        key.contains("interval") ||
                        key.equals("pickup.range");
        if (shouldBeNumber) {
            try {
                double num = Double.parseDouble(valueStr);

                // 范围校验（可选）
                if ((key.contains("range") || key.contains("radius")) && (num < 0.1 || num > 20.0)) {
                    throw new IllegalArgumentException("拾取范围应在 0.1 - 20.0 之间");
                }
                if ((key.contains("ticks") || key.contains("delay") || key.contains("interval")) && (num < 0 || num > 1000)) {
                    throw new IllegalArgumentException("Tick 值应在 0 - 1000 之间");
                }

                return num; // 返回 Double
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("\"" + valueStr + "\" 不是有效的数字");
            }
        }

        // 3. 其他情况保留为字符串
        return valueStr;
    }

    /**
     * 显示插件详细状态信息
     */
    private void handleStatus(CommandSender sender) {
        // 获取基本状态信息
        boolean isActive = !plugin.isStoppedByCommand() && config.isEnabled();
        String status = isActive ? "§a启用" : "§c禁用";
        String manual = plugin.isStoppedByCommand() ? " §7(手动停止)" : "";
        String configEnabled = config.isEnabled() ? "§a是" : "§c否";

        // 输出状态信息
        sender.sendMessage("§6========== PickUp 状态 ==========");
        sender.sendMessage("§7拾取功能: " + status + manual);
        sender.sendMessage("§7配置启用: " + configEnabled);

        // 显示双驱动模式状态
        String playerMode = config.isPlayerDriven() ? "§a启用" : "§c禁用";
        String itemMode = config.isItemDrivenEnabled() ? "§a启用" : "§c禁用";
        sender.sendMessage("§7玩家驱动: " + playerMode);
        sender.sendMessage("§7物品驱动: " + itemMode);

        // 显示拾取参数
        sender.sendMessage("§7拾取半径: §e" + config.getPickupRange() + " 方块");
        sender.sendMessage("§7自免疫时间: §e" + config.getSelfImmuneTicks() + " ticks");

        // 显示延迟设置
        sender.sendMessage("§7冷却设置:");
        sender.sendMessage("  §7• 玩家丢弃: §e" + config.getPlayerDropDelayTicks() + " ticks");
        sender.sendMessage("  §7• 自然掉落: §e" + config.getNaturalDropDelayTicks() + " ticks");
        sender.sendMessage("  §7• 即时生成: §e" + config.getInstantPickupDelayTicks() + " ticks");

        // 如果物品驱动启用，显示相关参数
        if (config.isItemDrivenEnabled()) {
            sender.sendMessage("§7物品活跃期: §e" + config.getActiveDetectionTicks() + " ticks");
            sender.sendMessage("§7检测频率: §e" + config.getPickupAttemptIntervalTicks() + " ticks");
        }

        // 合并器状态
        sender.sendMessage("§7物品合并: " + (config.isItemMergeEnabled() ? "§a启用" : "§c禁用"));
        if (config.isItemMergeEnabled()) {
            sender.sendMessage("  §7• 合并范围: §e" + config.getItemMergeRange() + " 方块");
        }

        // 死亡日志状态
        sender.sendMessage("§7死亡日志: " + (config.isDeathLogEnabled() ? "§a启用" : "§c禁用"));

        sender.sendMessage("§6================================");
        sender.sendMessage("§7使用 §e/up set <key> <value> §7动态修改配置");
    }
}