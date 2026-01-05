package pickup.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import pickup.Main;
import pickup.config.PickupConfig;

import java.util.*;

/**
 * 命令管理器 - 统一管理所有子命令
 */
public class CommandManager implements CommandExecutor, TabCompleter {
    private final Main plugin;
    private final PickupConfig config;

    // 子命令处理器映射
    private final Map<String, SubCommand> subCommands = new HashMap<>();

    public CommandManager(Main plugin) {
        this.plugin = plugin;
        this.config = plugin.getPickupConfig();

        // 注册所有子命令
        registerSubCommands();
    }

    /**
     * 注册所有子命令
     */
    private void registerSubCommands() {
        // 基础管理命令
        register("reload", new ReloadSubCommand());
        register("true", new EnableSubCommand());
        register("false", new DisableSubCommand());
        register("status", new StatusSubCommand());
        register("save", new SaveSubCommand());
        register("help", new HelpSubCommand());

        // 配置管理命令
        register("set", new SetSubCommand());
        register("get", new GetSubCommand());
        register("list", new ListSubCommand());
    }

    /**
     * 注册单个子命令
     */
    private void register(String name, SubCommand command) {
        subCommands.put(name.toLowerCase(), command);
    }

    /**
     * 主命令执行入口
     */
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String @NonNull [] args) {
        // 权限检查
        if (!sender.hasPermission("pickup.admin")) {
            sender.sendMessage("§c你没有权限使用此命令！");
            return true;
        }

        // 无参数时显示帮助
        if (args.length == 0) {
            subCommands.get("help").execute(sender, args);
            return true;
        }

        // 获取子命令处理器
        String sub = args[0].toLowerCase();
        SubCommand handler = subCommands.get(sub);

        if (handler == null) {
            sender.sendMessage("§c未知子命令，使用 §e/up help §c查看帮助");
            return true;
        }

        try {
            String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
            return handler.execute(sender, subArgs);
        } catch (Exception e) {
            sender.sendMessage("§c执行命令时出错: " + e.getMessage());

            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "执行命令时发生异常", e);

            return true;
        }
    }

    /**
     * Tab补全入口
     */
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String @NonNull [] args) {
        if (!sender.hasPermission("pickup.admin")) {
            return Collections.emptyList();
        }

        // 第一层：补全子命令
        if (args.length == 1) {
            List<String> completions = new ArrayList<>(subCommands.keySet());
            return StringUtil.copyPartialMatches(args[0], completions, new ArrayList<>());
        }

        // 第二层及以上：委托给子命令处理器
        if (args.length >= 2) {
            String sub = args[0].toLowerCase();
            SubCommand handler = subCommands.get(sub);
            if (handler instanceof TabCompleter) {
                // 传递剩余参数给子命令处理器
                String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
                return ((TabCompleter) handler).onTabComplete(sender, command, alias, subArgs);
            }
        }

        return Collections.emptyList();
    }

    // ========== 子命令接口 ==========

    /**
     * 子命令接口
     */
    public interface SubCommand {
        boolean execute(CommandSender sender, String[] args);
    }

    /**
     * 可tab补全的子命令接口
     */
    public interface TabCompletableSubCommand extends SubCommand, TabCompleter {
    }

    // ========== 子命令实现 ==========

    /**
     * 帮助命令
     */
    private static class HelpSubCommand implements SubCommand {
        @Override
        public boolean execute(CommandSender sender, String[] args) {
            sender.sendMessage("§6========== PickUp 插件命令 (/up) ==========");
            sender.sendMessage("§e/up help    §7- 显示此帮助信息");
            sender.sendMessage("§e/up reload §7- 重载配置文件");
            sender.sendMessage("§e/up true   §7- 开启拾取功能（禁止原版）");
            sender.sendMessage("§e/up false  §7- 关闭拾取功能（恢复原版）");
            sender.sendMessage("§e/up status §7- 查看插件状态");
            sender.sendMessage("§e/up set <key> <value> §7- 动态设置配置项");
            sender.sendMessage("§e/up get <key> §7- 获取配置值");
            sender.sendMessage("§e/up list §7- 列出所有配置项");
            sender.sendMessage("§e/up save §7- 立即保存未保存的配置");
            sender.sendMessage("§6========================================");
            sender.sendMessage("§7示例: §e/up set pickup.range 2.5");
            sender.sendMessage("§7示例: §e/up set mode.player-driven false");
            return true;
        }
    }

    /**
     * 重载命令
     */
    private class ReloadSubCommand implements SubCommand {
        @Override
        public boolean execute(CommandSender sender, String[] args) {
            sender.sendMessage("§a[PickUp] 配置重载中...");
            plugin.reloadPickup();
            sender.sendMessage("§a[PickUp] 配置已重载！");
            return true;
        }
    }

    /**
     * 开启拾取功能
     */
    private class EnableSubCommand implements SubCommand {
        @Override
        public boolean execute(CommandSender sender, String[] args) {
            if (!plugin.isStoppedByCommand()) {
                sender.sendMessage("§c拾取功能已经是开启状态！");
                return true;
            }
            plugin.startPickup();
            sender.sendMessage("§a拾取功能已开启（禁止原版拾取）。");
            return true;
        }
    }

    /**
     * 关闭拾取功能
     */
    private class DisableSubCommand implements SubCommand {
        @Override
        public boolean execute(CommandSender sender, String[] args) {
            if (plugin.isStoppedByCommand()) {
                sender.sendMessage("§c拾取功能已经是关闭状态！");
                return true;
            }
            plugin.stopPickup();
            sender.sendMessage("§a拾取功能已关闭，恢复原版逻辑。");
            return true;
        }
    }

    /**
     * 状态查看命令
     */
    private class StatusSubCommand implements SubCommand {
        @Override
        public boolean execute(CommandSender sender, String[] args) {
            boolean isActive = !plugin.isStoppedByCommand() && config.isEnabled();
            String status = isActive ? "§a启用" : "§c禁用";
            String manual = plugin.isStoppedByCommand() ? " §7(手动停止)" : "";
            String configEnabled = config.isEnabled() ? "§a是" : "§c否";

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
            return true;
        }
    }

    /**
     * 立即保存命令
     */
    private class SaveSubCommand implements SubCommand {
        @Override
        public boolean execute(CommandSender sender, String[] args) {
            boolean success = config.saveNow();
            if (success) {
                sender.sendMessage("§a所有未保存的配置已立即保存到磁盘");
            } else {
                sender.sendMessage("§c保存配置失败，请检查控制台日志");
            }
            return true;
        }
    }

    /**
     * 设置配置命令（支持Tab补全）
     */
    private class SetSubCommand implements TabCompletableSubCommand {
        @Override
        public boolean execute(CommandSender sender, String[] args) {
            if (args.length < 2) {
                sender.sendMessage("§c用法: /up set <配置键> <值>");
                sender.sendMessage("§c示例: /up set pickup.range 2.5");
                sender.sendMessage("§c使用 /up list 查看所有配置键");
                return false;
            }

            String key = args[0];
            StringBuilder valueBuilder = new StringBuilder();
            for (int i = 1; i < args.length; i++) {
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
            return true;
        }

        @Override
        public List<String> onTabComplete(@NonNull CommandSender sender, @NonNull Command command,
                                          @NonNull String alias, String[] args) {
            if (args.length == 1) {
                // 补全配置键
                List<String> keys = config.getConfigKeys();
                return StringUtil.copyPartialMatches(args[0], keys, new ArrayList<>());
            }

            if (args.length == 2) {
                // 补全值类型
                String key = args[0];
                return completeValueType(key, args[1]);
            }

            return Collections.emptyList();
        }

        private Object parseConfigValue(String key, String valueStr) {
            // 布尔值优先
            if (valueStr.equalsIgnoreCase("true") || valueStr.equalsIgnoreCase("false")) {
                return Boolean.parseBoolean(valueStr);
            }

            // 判断是否应解析为数值
            boolean shouldBeNumber = key.contains("range") ||
                    key.contains("radius") ||
                    key.contains("delay") ||
                    key.contains("ticks") ||
                    key.contains("interval") ||
                    key.equals("pickup.range");

            if (shouldBeNumber) {
                try {
                    return getNum(key, valueStr); // 直接返回，无需中间变量
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("\"" + valueStr + "\" 不是有效的数字");
                }
            }

            // 其他情况保留为字符串
            return valueStr;
        }

        private static double getNum(String key, String valueStr) {
            double num = Double.parseDouble(valueStr);

            // 范围校验
            if ((key.contains("range") || key.contains("radius")) && (num < 0.1 || num > 20.0)) {
                throw new IllegalArgumentException("拾取范围应在 0.1 - 10.0 之间");
            }
            if ((key.contains("ticks") || key.contains("delay") || key.contains("interval")) && (num < 0 || num > 1000)) {
                throw new IllegalArgumentException("Tick 值应在 0 - 10 之间");
            }
            return num;
        }
    }

    /**
     * 获取配置命令（支持Tab补全）
     */
    private class GetSubCommand implements TabCompletableSubCommand {
        @Override
        public boolean execute(CommandSender sender, String[] args) {
            if (args.length < 1) {
                sender.sendMessage("§c用法: /up get <配置键>");
                sender.sendMessage("§c使用 /up list 查看所有配置键");
                return false;
            }

            String key = args[0];
            Object value = config.getConfig(key);

            if (value == null) {
                sender.sendMessage("§c配置键不存在: " + key);
            } else {
                String typeHint = config.getValueTypeHint(key);
                sender.sendMessage("§a配置值: §e" + key + " = " + value + " §7(" + typeHint + ")");
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(@NonNull CommandSender sender, @NonNull Command command,
                                          @NonNull String alias, String[] args) {
            if (args.length == 1) {
                List<String> keys = config.getConfigKeys();
                return StringUtil.copyPartialMatches(args[0], keys, new ArrayList<>());
            }
            return Collections.emptyList();
        }
    }

    /**
     * 列出配置命令
     */
    private class ListSubCommand implements SubCommand {
        @Override
        public boolean execute(CommandSender sender, String[] args) {
            List<String> keys = config.getConfigKeys();

            sender.sendMessage("§6========== 配置键列表 ==========");
            for (String key : keys) {
                Object value = config.getConfig(key);
                String typeHint = config.getValueTypeHint(key);
                sender.sendMessage("§e" + key + " §7= " + value + " §8(" + typeHint + ")");
            }
            sender.sendMessage("§6================================");
            sender.sendMessage("§7使用 §e/up set <键> <值> §7修改配置");
            return true;
        }
    }

    /**
     * 补全值类型
     */
    private List<String> completeValueType(String key, String current) {
        List<String> suggestions = new ArrayList<>();

        // 检查是否是布尔值配置项
        boolean isBooleanConfig = key.endsWith(".enabled") ||
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

        return suggestions;
    }
}
