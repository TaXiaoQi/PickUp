package pickup;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;

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

    // ========== /mc 命令：仅支持 reload（重启服务器） ==========
    private boolean handleMcCommand(CommandSender sender, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage("§c用法: /mc reload §7- 重启服务器");
            return false;
        }

        handleServerRestart(sender);
        return true;
    }

    // ========== /up 命令：管理插件 ==========
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
        try {
            flag.createNewFile();
        } catch (IOException e) {
            sender.sendMessage("§c❌ 无法创建重启标志文件！");
            plugin.getLogger().warning("创建 restart.flag 失败: " + e.getMessage());
            return;
        }

        String msg = "§c[系统] 服务器将在 3 秒后重启！";
        sender.sendMessage("§a✅ 服务器重启已触发...");
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(msg));
        plugin.getLogger().info("[Server Restart] Triggered by " + sender.getName());

        Bukkit.getScheduler().runTaskLater(plugin, Bukkit::shutdown, 60L);
    }

    private void handlePluginReload(CommandSender sender) {
        plugin.reloadPickup();
        sender.sendMessage("§a[PickUp] 配置已重载！");
        plugin.getLogger().info("[PickUp] 配置重载完成，执行者: " + sender.getName());
    }

    private void handleStart(CommandSender sender) {
        if (plugin.isPickupEnabled()) {
            sender.sendMessage("§c拾取功能已经是开启状态！");
            return;
        }
        plugin.startPickup();
        sender.sendMessage("§a拾取功能已开启。");
    }

    private void handleStop(CommandSender sender) {
        if (!plugin.isPickupEnabled()) {
            sender.sendMessage("§c拾取功能已经是关闭状态！");
            return;
        }
        plugin.stopPickup();
        sender.sendMessage("§a拾取功能已关闭，恢复原版逻辑。");
    }

    private void handleStatus(CommandSender sender) {
        boolean enabled = plugin.isPickupEnabled();
        String status = enabled ? "§a启用" : "§c禁用";
        String manual = !enabled && plugin.isStopped() ? " §7(手动停止)" : "";

        sender.sendMessage("§6========== PickUp 状态 ==========");
        sender.sendMessage("§7拾取功能: " + status + manual);
        sender.sendMessage("§7拾取模式: " + (plugin.isPlayerDriven() ? "§e玩家驱动" : "§e物品驱动"));
        sender.sendMessage("§7拾取半径: §e" + plugin.getPickupRange() + " 方块");
        sender.sendMessage("§7投掷冷却: §e" + plugin.getThrowCooldownTicks() + " ticks");
        if (plugin.isItemDrivenEnabled()) {
            sender.sendMessage("§7主动检测: §a开启 §7(" + plugin.getActiveDetectionTicks() + " ticks)");
        } else {
            sender.sendMessage("§7主动检测: §c关闭");
        }
        sender.sendMessage("§6================================");
    }
}