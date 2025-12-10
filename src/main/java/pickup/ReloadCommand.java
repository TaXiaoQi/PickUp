package pickup;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;

/**
 * 命令处理类 - 处理插件相关的所有命令
 * 支持两个主要命令：/mc 和 /up

 * &#064;SuppressWarnings("ClassCanBeRecord")  - 该类可以设计为Java 14+的record类型
 * 但由于兼容性考虑，仍使用传统的类结构
 */
@SuppressWarnings("ClassCanBeRecord")
public class ReloadCommand implements CommandExecutor {
    // 插件主类引用，用于访问插件功能
    private final PickUp plugin;

    /**
     * 构造函数
     * @param plugin 插件主类实例
     */
    public ReloadCommand(PickUp plugin) {
        this.plugin = plugin;
    }

    /**
     * 命令执行主入口
     * @param sender 命令发送者（玩家或控制台）
     * @param command 被执行的命令对象
     * @param label 命令别名
     * @param args 命令参数数组
     * @return true表示命令处理成功，false表示处理失败或需要显示用法
     */
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        // 权限检查：需要pickup.admin权限才能使用所有命令
        if (!sender.hasPermission("pickup.admin")) {
            sender.sendMessage("§c你没有权限使用此命令！");
            return true; // 返回true表示命令已处理（权限不足也算处理）
        }

        // 获取命令名称并转为小写（不区分大小写）
        String commandName = command.getName().toLowerCase();

        // 根据命令名称路由到不同的处理函数
        if (commandName.equals("mc")) {
            return handleMcCommand(sender, args);
        } else if (commandName.equals("up")) {
            return handleUpCommand(sender, args);
        }

        // 未知命令（理论上不会执行到这里，因为命令已在plugin.yml注册）
        sender.sendMessage("§c未知命令。");
        return false;
    }

    /**
     * 处理 /mc 命令（服务器管理相关）
     * @param sender 命令发送者
     * @param args 命令参数
     * @return 处理结果
     */
    private boolean handleMcCommand(CommandSender sender, String[] args) {
        // 检查参数格式：/mc reload
        if (args.length == 0 || !args[0].equalsIgnoreCase("restart")) {
            sender.sendMessage("§c用法: /mc reload §7- 重启服务器");
            return false; // 返回false会显示命令用法
        }

        // 处理服务器重启逻辑
        handleServerRestart(sender);
        return true;
    }

    /**
     * 处理 /up 命令（插件管理相关）
     * @param sender 命令发送者
     * @param args 命令参数
     * @return 处理结果
     */
    private boolean handleUpCommand(CommandSender sender, String[] args) {
        // 如果没有参数，显示用法
        if (args.length == 0) {
            sendUpUsage(sender);
            return true;
        }

        // 获取子命令并转为小写
        String sub = args[0].toLowerCase();

        // 使用switch表达式（Java 14+特性）处理不同的子命令
        return switch (sub) {
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
            default -> {
                // 未知子命令，显示用法
                sendUpUsage(sender);
                yield false;
            }
        };
    }

    /**
     * 显示 /up 命令的使用方法
     * @param sender 命令发送者
     */
    private void sendUpUsage(CommandSender sender) {
        sender.sendMessage("§6========== PickUp 插件命令 (/up) ==========");
        sender.sendMessage("§e/up reload §7- 重载配置文件");
        sender.sendMessage("§e/up true   §7- 开启拾取功能");
        sender.sendMessage("§e/up false  §7- 关闭拾取功能");
        sender.sendMessage("§e/up status §7- 查看当前状态");
        sender.sendMessage("§6========================================");
    }

    /**
     * 处理服务器重启逻辑
     * 创建一个标志文件，然后延迟10秒关闭服务器
     * 外部启动脚本检测到该文件时会自动重启服务器
     * @param sender 命令发送者
     */
    private void handleServerRestart(CommandSender sender) {
        // 创建标志文件对象
        File flag = new File("restart.flag");

        // 如果标志文件已存在，尝试删除旧文件
        if (flag.exists()) {
            if (!flag.delete()) {
                // 文件删除失败（可能被占用或无权限）
                sender.sendMessage("§c⚠️ 旧的 restart.flag 无法删除，请检查文件是否被占用。");
                plugin.getLogger().warning("无法删除已存在的 restart.flag");
                return; // 停止重启流程
            }
        }

        try {
            // 创建新的标志文件
            boolean created = flag.createNewFile();
            if (!created) {
                // 文件创建失败（可能已存在或无权限）
                sender.sendMessage("§c❌ 无法创建 restart.flag（可能已被创建或无写入权限）");
                plugin.getLogger().warning("restart.flag 创建失败：createNewFile() 返回 false");
                return; // 停止重启流程
            }
        } catch (IOException e) {
            // I/O异常处理
            sender.sendMessage("§c❌ 创建 restart.flag 时发生 I/O 错误！");
            plugin.getLogger().severe("创建 restart.flag 异常: " + e.getMessage());
            return; // 停止重启流程
        }

        // 通知所有玩家服务器即将重启
        String msg = "§c[系统] 服务器将在 10 秒后重启！";
        sender.sendMessage("服务器将在 10 秒后重启！");
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(msg));
        plugin.getLogger().info("[Server Restart] Triggered by " + sender.getName());

        /// 延迟10秒后关闭服务器
        /// 200 ticks = 10秒（1 tick = 0.05秒）
        /// 使用runTaskLater实现延迟执行
        Bukkit.getScheduler().runTaskLater(plugin, Bukkit::shutdown, 200L);
    }

    /**
     * 处理插件配置重载
     * @param sender 命令发送者
     */
    private void handlePluginReload(CommandSender sender) {
        // 调用主类的重载方法
        plugin.reloadPickup();
        sender.sendMessage("[PickUp] 配置已重载！");
        plugin.getLogger().info("[PickUp] 配置重载完成，执行者: " + sender.getName());
    }

    /**
     * 开启拾取功能
     * @param sender 命令发送者
     */
    private void handleStart(CommandSender sender) {
        // 检查是否已经是开启状态
        if (!plugin.isStoppedByCommand()) {
            sender.sendMessage("§c拾取功能已经是开启状态！");
            return;
        }
        // 调用主类的开启方法
        plugin.startPickup();
        sender.sendMessage("§a拾取功能已开启。");
    }

    /**
     * 关闭拾取功能
     * @param sender 命令发送者
     */
    private void handleStop(CommandSender sender) {
        // 检查是否已经是关闭状态
        if (plugin.isStoppedByCommand()) {
            sender.sendMessage("§c拾取功能已经是关闭状态！");
            return;
        }
        // 调用主类的关闭方法
        plugin.stopPickup();
        sender.sendMessage("§a拾取功能已关闭，恢复原版逻辑。");
    }

    /**
     * 显示插件详细状态信息
     * @param sender 命令发送者
     */
    private void handleStatus(CommandSender sender) {
        // 获取基本状态信息
        boolean isActive = !plugin.isStoppedByCommand();
        String status = isActive ? "§a启用" : "§c禁用";
        String manual = plugin.isStoppedByCommand() ? " §7(手动停止)" : "";

        // 输出状态信息
        sender.sendMessage("§6========== PickUp 状态 ==========");
        sender.sendMessage("§7拾取功能: " + status + manual);

        // 显示双驱动模式状态
        String playerMode = plugin.isPlayerDriven() ? "§a启用" : "§c禁用";
        String itemMode = plugin.isItemDrivenEnabled() ? "§a启用" : "§c禁用";
        sender.sendMessage("§7玩家驱动: " + playerMode);
        sender.sendMessage("§7物品驱动: " + itemMode);

        // 显示拾取参数
        sender.sendMessage("§7拾取半径: §e" + plugin.getPickupRange() + " 方块");
        sender.sendMessage("§7自免疫时间: §e" + plugin.getSelfImmuneTicks() + " ticks");

        // 显示延迟设置
        sender.sendMessage("§7冷却设置:");
        sender.sendMessage("  §7• 玩家丢弃: §e" + plugin.getPlayerDropDelayTicks() + " ticks");
        sender.sendMessage("  §7• 自然掉落: §e" + plugin.getNaturalDropDelayTicks() + " ticks");
        sender.sendMessage("  §7• 即时生成: §e" + plugin.getInstantPickupDelayTicks() + " ticks");

        // 如果物品驱动启用，显示相关参数
        if (plugin.isItemDrivenEnabled()) {
            sender.sendMessage("§7物品活跃期: §e" + plugin.getActiveDetectionTicks() + " ticks");
            sender.sendMessage("§7检测频率: §e" + plugin.getPickupAttemptIntervalTicks() + " ticks");
        }

        sender.sendMessage("§6================================");
    }
}