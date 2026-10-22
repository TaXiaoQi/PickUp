package pickup.platform.paper;

import pickup.Main;
import pickup.platform.PlatformAdapter;

/**
 * Paper 启动器（1.17+）
 */
public class PaperMain extends Main {

    @Override
    public void onEnable() {
        if (isFoliaServer()) {
            getLogger().severe("检测到 Folia 服务端！请使用 PickUp-Folia 版本的插件。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        super.onEnable();
    }

    @Override
    protected PlatformAdapter createAdapter() {
        return new PaperPlatformAdapter();
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
