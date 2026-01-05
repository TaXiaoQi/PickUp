package pickup.command;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;
import pickup.Main;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * 处理 /mc 服务器管理命令
 */
public class McCommand implements CommandExecutor, TabCompleter {
    private final Main plugin;

    public McCommand(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String @NonNull [] args) {
        // 权限检查
        if (!sender.hasPermission("pickup.admin")) {
            sender.sendMessage("§c你没有权限使用此命令！");
            return true;
        }

        // 检查参数格式：/mc restart
        if (args.length == 0 || !args[0].equalsIgnoreCase("restart")) {
            sender.sendMessage("§c用法: /mc restart §7- 重启服务器");
            return false;
        }

        // 处理服务器重启逻辑
        handleServerRestart(sender);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String @NonNull [] args) {
        if (!sender.hasPermission("pickup.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return Collections.singletonList("restart");
        }

        return Collections.emptyList();
    }

    /**
     * 处理服务器重启逻辑（Folia兼容版本）
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

        String msg = "§c[系统] 服务器将在 10 秒后重启！";
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(msg));
        plugin.getLogger().info("[Server Restart] Triggered by " + sender.getName());

        GlobalRegionScheduler scheduler = Bukkit.getGlobalRegionScheduler();

        // 5秒倒计时任务
        for (int i = 5; i >= 1; i--) {
            final int secondsLeft = i;
            scheduler.runDelayed(plugin, task -> {
                // 创建标题组件
                net.kyori.adventure.title.Title title = net.kyori.adventure.title.Title.title(
                        net.kyori.adventure.text.Component.empty(), // 主标题（空）
                        net.kyori.adventure.text.Component
                                .text("服务器将在 " + secondsLeft + " 秒后重启！")
                                .color(net.kyori.adventure.text.format.NamedTextColor.RED), // 副标题
                        net.kyori.adventure.title.Title.Times.times(
                                java.time.Duration.ofMillis(250),  // 淡入时间：250ms
                                java.time.Duration.ofMillis(1000), // 停留时间：1000ms
                                java.time.Duration.ofMillis(250)   // 淡出时间：250ms
                        )
                );
                for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                    player.showTitle(title); // 发送标题
                }
            }, (10 - i) * 20L); // 第5、4、3、2、1秒发送
        }

        scheduler.runDelayed(plugin, task -> {
            try {
                Bukkit.shutdown();
            } catch (Exception e) {
                plugin.getLogger().severe("关闭服务器时发生错误: " + e.getMessage());
                System.exit(0);
            }
        }, 200L);
    }
}
