package pickup.platform.folia;

import pickup.Main;
import pickup.platform.FoliaPlatformAdapter;
import pickup.platform.PlatformAdapter;

/**
 * Folia 启动器（1.19.4+）
 */
public class FoliaMain extends Main {

    @Override
    public void onEnable() {
        if (!isFoliaServer()) {
            getLogger().severe("未检测到 Folia 服务端！Bukkit 调度器无法在 Folia 上运行，请使用 PickUp-Paper 或 PickUp-Spigot 版本的插件。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        super.onEnable();
    }

    @Override
    protected PlatformAdapter createAdapter() {
        return new FoliaPlatformAdapter();
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
