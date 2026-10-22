package pickup.platform.spigot;

import pickup.Main;
import pickup.platform.BukkitPlatformAdapter;
import pickup.platform.PlatformAdapter;

/**
 * Spigot 启动器（1.17+）
 */
public class SpigotMain extends Main {

    @Override
    public void onEnable() {
        if (isFoliaServer()) {
            getLogger().severe("检测到 Folia 服务端！Bukkit 调度器在 Folia 上不可用，请使用 PickUp-Folia 版本的插件。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        super.onEnable();
    }

    @Override
    protected PlatformAdapter createAdapter() {
        return new BukkitPlatformAdapter();
    }

    private boolean isFoliaServer() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
