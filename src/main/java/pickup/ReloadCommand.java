package pickup;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;

@SuppressWarnings("ClassCanBeRecord")
public class ReloadCommand implements CommandExecutor {
    private final PickUp plugin;

    public ReloadCommand(PickUp plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("pickup.admin")) {
            sender.sendMessage("§c你没有权限使用此命令！");
            return true;
        }

        String commandName = command.getName().toLowerCase();

        if (commandName.equals("mc")) {
            return handleMcCommand(sender, args);
        } else if (commandName.equals("up")) {
            return handleUpCommand(sender, args);
        }

        sender.sendMessage("§c未知命令。");
        return false;
    }

    private boolean handleMcCommand(CommandSender sender, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage("§c用法: /mc reload §7- 重启服务器");
            return false;
        }

        handleServerRestart(sender);
        return true;
    }

    private boolean handleUpCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendUpUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        return switch (sub) {
            case "reload" -> {
                handlePluginReload(sender);
                yield true;
            }
            case "true" -> {
                handleStart(sender);
                yield true;
            }
            case "false" -> {
                handleStop(sender);
                yield true;
            }
            case "status" -> {
                handleStatus(sender);
                yield true;
            }
            default -> {
                sendUpUsage(sender);
                yield false;
            }
        };
    }

    private void sendUpUsage(CommandSender sender) {
        sender.sendMessage("§6========== PickUp 插件命令 (/up) ==========");
        sender.sendMessage("§e/up reload §7- 重载配置文件");
        sender.sendMessage("§e/up true   §7- 开启拾取功能");
        sender.sendMessage("§e/up false  §7- 关闭拾取功能");
        sender.sendMessage("§e/up status §7- 查看当前状态");
        sender.sendMessage("§6========================================");
    }

    private void handleServerRestart(CommandSender sender) {
        File flag = new File("restart.flag");

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

        String msg = "§c[系统] 服务器将在 3 秒后重启！";
        sender.sendMessage("服务器将在 3 秒后重启！");
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(msg));
        plugin.getLogger().info("[Server Restart] Triggered by " + sender.getName());

        Bukkit.getScheduler().runTaskLater(plugin, Bukkit::shutdown, 60L);
    }

    private void handlePluginReload(CommandSender sender) {
        plugin.reloadPickup();
        sender.sendMessage("[PickUp] 配置已重载！");
        plugin.getLogger().info("[PickUp] 配置重载完成，执行者: " + sender.getName());
    }

    private void handleStart(CommandSender sender) {
        if (!plugin.isStoppedByCommand()) {
            sender.sendMessage("§c拾取功能已经是开启状态！");
            return;
        }
        plugin.startPickup();
        sender.sendMessage("§a拾取功能已开启。");
    }

    private void handleStop(CommandSender sender) {
        if (plugin.isStoppedByCommand()) {
            sender.sendMessage("§c拾取功能已经是关闭状态！");
            return;
        }
        plugin.stopPickup();
        sender.sendMessage("§a拾取功能已关闭，恢复原版逻辑。");
    }

    private void handleStatus(CommandSender sender) {
        boolean isActive = !plugin.isStoppedByCommand();
        String status = isActive ? "§a启用" : "§c禁用";
        String manual = plugin.isStoppedByCommand() ? " §7(手动停止)" : "";

        sender.sendMessage("§6========== PickUp 状态 ==========");
        sender.sendMessage("§7拾取功能: " + status + manual);

        // 显示双驱动模式状态
        String playerMode = plugin.isPlayerDriven() ? "§a启用" : "§c禁用";
        String itemMode = plugin.isItemDrivenEnabled() ? "§a启用" : "§c禁用";
        sender.sendMessage("§7玩家驱动: " + playerMode);
        sender.sendMessage("§7物品驱动: " + itemMode);

        sender.sendMessage("§7拾取半径: §e" + plugin.getPickupRange() + " 方块");
        sender.sendMessage("§7自免疫时间: §e" + plugin.getSelfImmuneTicks() + " ticks");

        // 显示三种 delay
        sender.sendMessage("§7冷却设置:");
        sender.sendMessage("  §7• 玩家丢弃: §e" + plugin.getPlayerDropDelayTicks() + " ticks");
        sender.sendMessage("  §7• 自然掉落: §e" + plugin.getNaturalDropDelayTicks() + " ticks");
        sender.sendMessage("  §7• 即时生成: §e" + plugin.getInstantPickupDelayTicks() + " ticks");

        if (plugin.isItemDrivenEnabled()) {
            sender.sendMessage("§7物品活跃期: §e" + plugin.getActiveDetectionTicks() + " ticks");
            sender.sendMessage("§7检测频率: §e" + plugin.getPickupAttemptIntervalTicks() + " ticks");
        }

        sender.sendMessage("§6================================");
    }
}