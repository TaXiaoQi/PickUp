# PickUp 1.4.1 — Changelog

## English

> **Download the build matching your server core.** Loading the wrong jar will disable itself with a hint in the console.

### Added
- **Multi-platform support** — PickUp now ships dedicated builds for **Paper**, **Spigot** and **Folia** (previously Folia-only):
  - `PickUp-Paper` : Paper 1.17 – 1.21.x
  - `PickUp-Spigot` : Spigot / CraftBukkit 1.17 – 1.21.x
  - `PickUp-Folia` : Folia 1.19.4+
- Wrong-jar detection: the plugin now checks the platform on startup and disables itself with a clear message instead of misbehaving.

### Fixed
- Items could stay permanently un-pickup-able after `/up false` — player pickup is now fully restored.
- The item-driven scheduler kept running (and picking items up) after `/up false`; delayed detection tasks now stop immediately.
- Delayed config saves never fired on Folia (Bukkit scheduler is unavailable there); `/up set` changes are now persisted correctly and no longer lost on a crash.
- Ground items that existed **before** the plugin enabled could never be picked up by anyone; vanilla pickup is no longer blocked for them.
- Items no longer get stuck when both pickup modes are disabled, and `/up reload` fully restores vanilla pickup behavior whenever the plugin ends up in a disabled state.
- Fixed cross-region thread access on Folia (item-state restore and cleanup tasks are now dispatched to the owning region threads).
- Fixed an internal counter leak that prevented the item-driven scheduler from auto-disabling on empty worlds.
- Fixed a potential NPE caused by the `pickup` command alias not being registered as a standalone command.

### Changed
- All builds now target unified Java 17 bytecode (Java 17+ required; 21+ for 1.20.5+ servers).
- Death log messages use vanilla color codes for full compatibility across all three platforms.

### Performance
- Profiled on Paper 1.21.4 with 1000 dropped items: **no measurable TPS impact** vs. vanilla, even with the most aggressive item-driven configuration. Scheduler auto-sleeps when no items exist.

---

## 中文

> **请按服务端核心下载对应构建**，装错核心时插件会自动禁用并在控制台给出提示。

### 新增
- **多核心支持** — 提供 Paper / Spigot / Folia 三种独立构建（此前仅支持 Folia）：
  - `PickUp-Paper`：Paper 1.17 – 1.21.x
  - `PickUp-Spigot`：Spigot / CraftBukkit 1.17 – 1.21.x
  - `PickUp-Folia`：Folia 1.19.4+
- 平台自检：启动时检测服务端核心，不匹配则自动禁用并给出明确提示。

### 修复
- 修复 `/up false` 后物品可能永远无法被拾取的问题（玩家拾取权限未恢复）。
- 修复 `/up false` 后物品驱动调度器仍在后台运行并拾取物品的问题。
- 修复 Folia 上配置延迟保存失效的问题（Folia 不支持 Bukkit 调度器），`/up set` 的更改现在能正确落盘，崩溃不再丢失。
- 修复插件启用前已存在的掉落物无人能拾取的问题。
- 修复两种拾取模式都关闭时新物品卡死、以及 `/up reload` 后禁用状态下物品恢复不完整的问题。
- 修复 Folia 跨区域线程访问问题（物品状态恢复与清理任务现在会分发到所属区域线程执行）。
- 修复内部计数泄漏导致空世界下物品驱动调度器无法自动停用的问题。
- 修复 `pickup` 命令别名未注册为独立命令导致的潜在 NPE。

### 变更
- 全部构建统一为 Java 17 字节码（需 Java 17+，1.20.5+ 服务端需 Java 21+）。
- 死亡坐标日志改用原版颜色码，三平台兼容。

### 性能
- Paper 1.21.4 实测 1000 个掉落物：与原版相比 **TPS 无可测量差异**，即使在最激进的 item-driven 配置下也一样；场上无物品时调度器自动休眠。
