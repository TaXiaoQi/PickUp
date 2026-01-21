# **PickUp - Smart Pickup Mod**

## **Primary Purpose**

A plugin that allows fully customizable pickup behavior for both players and mobs, completely rewriting and replacing Minecraft's vanilla item pickup logic. It supports customizing pickup range, item merging, pickup delay, and more—delivering better performance than the vanilla system while being fully compatible with Folia's multi-threaded architecture.


## **Key Controls**

- **`/up` or `/pickup`**: Main command for all pickup management functions
- **`/up true`**: Enable pickup functionality (disables vanilla pickup)
- **`/up false`**: Disable pickup functionality (restores vanilla mechanics)
- **`/up status`**: View plugin status and current configuration
- **`/up set <key> <value>`**: Dynamically modify configuration settings
- **`/up reload`**: Reload configuration from disk

## **Advanced Controls**
- **`/mc restart`**: Restart the Minecraft server (requires an external startup script)

## **Features**
- **Player-Driven Mode**: Players automatically pick up nearby items when moving (configurable range and interval)
- **Item-Driven Mode**: Items actively seek nearby players/mobs (smart scheduling with delay management)
- **Item Merging**: Automatically merges nearby identical items (configurable range)
- **Offhand Support**: Dropped items can be actively picked up into the offhand (enable in config).
- **Auto Equipment**: Mobs automatically equip better armor/weapons

## **Advanced Features**
- **Death Logging**: Enhanced death messages with coordinates (toggleable)
- **Server Restart**: Restart the Minecraft server using an external script (see Server Reload Principle & External Script Explanation).

## **Performance & Optimization**
- **Folia Multi-Threading**: Full support for Folia's region-based threading model
- **Spatial Indexing**: Efficient item lookup system using chunk-based indexing
- **Smart Scheduling**: Configurable tick intervals for optimal performance
- **Memory Management**: Automatic cleanup of invalid items and references

## **Key Advantages**

- ✅ **Dual-Mode System**: Choose between player-driven or item-driven pickup (or both)
- ✅ **Folia-Ready**: Full multi-threading support for high-performance servers
- ✅ **Non-Intrusive**: Disables vanilla pickup only when active, fully restorable
- ✅ **Real-Time Configuration**: All settings adjustable via commands
- ✅ **Smart Item Management**: Automatic merging, cleanup, and optimization
- ✅ **High Compatibility**: Works with Paper 1.17+ and Folia 1.19.4+

## **Important Notes**

- **Requires Paper/Folia**: Does not work with Spigot or CraftBukkit
- **Permission Required**: `pickup.admin` permission for all commands (default: OP)
- **Configuration**: All settings in `config.yml`, hot-reload with `/up reload`

## **Installation**

1. Ensure you're running **Paper 1.17+** or **Folia 1.19.4+**
2. Place `PickUp.jar` in your `plugins/` folder
3. Restart your server
4. Use `/up status` to verify installation
5. Configure settings via `/up set` or edit `config.yml`

## **Quick Start Commands**

```bash
/up help                    # Show all commands
/up true                    # Enable smart pickup
/up set pickup.range 2.5    # Set pickup range to 2.5 blocks
/up set mode.player-driven true  # Enable player-driven mode
/up reload                  # Apply configuration changes
```

## Server Reload Principle & External Script Explanation

### Why Not Use Bukkit's `/reload`?
Bukkit/Spigot's native `/reload` command carries a significant risk of memory leaks and is no longer recommended by the official teams. To ensure long-term server stability, the PickUp plugin employs a "soft shutdown + external restart" mechanism for safe reloading.

### How It Works
1. A player executes `/mc restart`
2. The plugin performs the following actions:
    - Stops all event listeners
    - Cleans up temporary in-memory data
    - Creates a <code>restart.flag</code> marker file in the plugin directory
    - Calls <code>Bukkit.shutdown()</code> to gracefully shut down the server
3. The **external startup script** detects the existence of <code>restart.flag</code> → automatically restarts the server
4. After restart, the plugin loads normally, configuration takes effect, with no leftover state

✅ The entire process is **free of memory leaks** and **class loading conflicts**, suitable for production environments.

### Windows Startup Script Example (`start.bat`)

```bat
@echo off
chcp 936 >nul
title Minecraft服务器
cd /d "%~dp0"

echo [%date% %time%] 正在启动服务器...
java -Xmx2G -Xms1G -Dfile.encoding=GBK -jar folia-1.21.11-9.jar nogui

echo [%date% %time%] 服务器已关闭。

if exist restart.flag (
    del restart.flag
    echo [%date% %time%] 检测到重启请求，5秒后重新启动...
    timeout /t 5 >nul
    goto restart
)

echo.
echo 未检测到重启请求，5秒后自动退出...
timeout /t 5 >nul
```
> 📝 **Usage Instructions**:
> - Save this script as `start.bat` in the same directory as your `paper-*.jar`
> - **Always start the server by double-clicking `start.bat`**
> - After executing `/mc reload`, the server will automatically restart and apply new configuration

### 🐧 For Linux / macOS Users?

Simply write an equivalent shell script (`start.sh`) with the same logic:

```bash
#!/bin/bash
while true; do
  java -Xmx1G -Xms1G -jar paper-1.21.10-115.jar nogui
  if [ -f "restart.flag" ]; then
    rm -f restart.flag
    echo "Restart request detected, restarting in 5 seconds..."
    sleep 5
  else
    echo "Normal exit."
    break
  fi
done
```

## **License**

This project is licensed under the GPL-3.0 License - see the LICENSE file for details.
