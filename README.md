# MiniGame

[简体中文](README.md) | [English](README.en.md)

MiniGame 是一个基于 NeoForge 的 Minecraft 模组项目，目前包含两个偏“世界规则/小游戏规则”的功能：

- `SameBlockBreak`：玩家或实体破坏方块后，批量破坏范围内匹配的方块。
- `ChunkPlaceBlock`：玩家放置方块后，在其它区块的相同区块内坐标尝试放置同样的方块，并可选同步相同位置的破坏。

## 环境

- Minecraft：`26.1.2`
- NeoForge：`26.1.2.73`
- Java：`25`
- Mod ID：`minigame`

## 构建与运行

```powershell
.\gradlew.bat build
```

```powershell
.\gradlew.bat runClient
```

```powershell
.\gradlew.bat runServer
```

构建产物位于：

```text
build/libs/
```

## 配置文件

配置文件生成在：

```text
config/minigame/
```

当前配置文件：

- `common.toml`
- `sameblockbreak.toml`
- `chunkplaceblock.toml`

`common.toml`：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `debugLogging` | `false` | 是否启用 MiniGame 的详细 debug 日志。正常游玩建议保持关闭。 |

## SameBlockBreak

`SameBlockBreak` 会在破坏事件触发后创建一个分批执行的破坏任务。任务按 tick 推进，避免一次性扫描或修改大量方块导致服务器长时间卡死。

当前支持的触发来源：

- 玩家破坏方块。
- 生物或实体破坏方块。
- 爆炸破坏方块。
- 玩家用空桶拾取水源或岩浆源。

默认行为：

- 默认启用。
- 默认只匹配同种方块。
- 默认即时任务只处理当前已经加载的区块。
- 默认记住已破坏的方块类型，之后区块加载或生成时会继续清理这些方块。
- 默认允许同一维度中存在多个破坏任务。
- 任务选择下一个区块时，会根据当前玩家位置实时调整优先级，优先处理离玩家更近的区块。
- 破坏水源时，也会处理含水方块；含水方块会被改为不含水状态。
- 破坏岩浆源时，也会按流体状态匹配岩浆相关方块。
- 掉落物只在 `dropRadius` 范围内使用自然破坏产生，范围外使用静默移除，避免大量掉落物卡服。
- 可清理失去支撑后无法存活的相邻方块，例如火把、树苗、甘蔗等。

### 已加载区块与后续清理

`loadedChunksOnly=true` 时，即时任务队列只包含触发时范围内已经加载的区块。未加载但以后被加载的区块，会在区块加载事件中根据 `rememberBrokenBlocksForever` 记录进行补清理。

`rememberBrokenBlocksForever=true` 且 `matchBlockType=true` 时，触发破坏的方块类型会被保存为禁止方块。之后世界生成、区块加载、普通方块放置路径中遇到这些方块时，会替换为空气；水相关方块会尽量变为不含水状态。

未生成的新区块不需要提前强制加载。默认配置会在生成过程中阻止被记住的方块再次出现。

### SameBlockBreak 配置

`sameblockbreak.toml`：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 是否启用 SameBlockBreak。 |
| `radius` | `3000` | 触发点周围的处理半径，单位为方块。 |
| `matchBlockType` | `true` | `true` 只破坏同种方块；`false` 会匹配所有非空气方块。 |
| `dropRadius` | `100` | 距离触发点 X/Z 多远以内使用自然破坏并允许掉落。 |
| `maxNaturalBreakBlocks` | `3000` | 单次任务最多自然破坏多少方块；`0` 表示不产生掉落，`-1` 表示不限制。 |
| `cleanupUnsupportedNeighbors` | `true` | 是否清理失去支撑后不能存活的相邻方块。 |
| `rememberBrokenBlocksForever` | `true` | 是否永久记住已破坏的方块类型，并阻止其之后生成或补放。 |
| `allowConcurrentTasks` | `true` | 是否允许同一维度同时存在多个破坏任务。 |
| `chunksPerTick` | `200` | 每个任务每 tick 最多尝试处理多少个区块条目。 |
| `maxMsPerTick` | `25` | 每个任务每 tick 最多占用多少毫秒主线程时间。 |
| `blocksPerTick` | `30000` | 每个任务每 tick 最多扫描多少个方块位置。 |
| `blocksPerChunkStep` | `3000` | 单个区块每次最多扫描多少个方块位置，达到后让出执行权。 |
| `dynamicPriorityScanLimit` | `4096` | 每次取下一个区块时，从剩余队列中最多重排多少个区块来优先靠近玩家。 |
| `loadedChunksOnly` | `true` | 即时任务是否只扫描已加载区块。 |
| `allowChunkGeneration` | `false` | 是否允许处理任务同步加载或生成缺失区块。大半径下不建议开启。 |

### SameBlockBreak 命令

这些命令需要游戏管理员权限：

```mcfunction
/minigame sameblockbreak status
/minigame sameblockbreak cancel
/minigame sameblockbreak cancelall
/minigame sameblockbreak enable
/minigame sameblockbreak disable
```

## ChunkPlaceBlock

`ChunkPlaceBlock` 会在玩家放置方块后，将源方块复制到范围内其它区块的相同区块内坐标。

“相同区块内坐标”指：

- 区块内 X 坐标相同。
- Y 坐标相同。
- 区块内 Z 坐标相同。

例如玩家在某区块内 `(localX=5, y=64, localZ=8)` 放置箱子，其它目标区块的 `(localX=5, y=64, localZ=8)` 如果是空气，就会尝试放置同样的箱子。

默认行为：

- 默认启用。
- 默认即时任务只修改当前已经加载的区块。
- 默认不会为了即时任务同步加载未加载区块。
- 默认会记录放置规则，之后目标区块加载或生成时继续补放。
- 只会放置到空气位置，不覆盖已有方块。
- 支持普通方块放置和多方块放置事件。
- 支持水桶和岩浆桶放置源方块。
- 默认复制方块实体数据，例如箱子内容。
- 源方块实体数据之后变化时，会把数据同步到其它已管理的同位置方块。
- 可选在破坏源方块时，同步破坏其它区块相同位置的同种方块。

### ChunkPlaceBlock 配置

`chunkplaceblock.toml`：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 是否启用 ChunkPlaceBlock。 |
| `radius` | `3000` | 触发点周围的处理半径，单位为方块。 |
| `syncBlockEntityData` | `true` | 是否复制并同步方块实体数据，例如箱子内容。 |
| `applyToFutureChunkLoads` | `true` | 是否记住规则，并在之后区块加载或生成时补放/补破坏。 |
| `destroySamePositionOnBreak` | `false` | 破坏源方块时，是否同步破坏其它区块同位置的同种方块。SameBlockBreak 启用时会自动关闭此项。 |
| `placementDelayTicks` | `1` | 放置后等待多少 tick 再读取源方块和方块实体数据。 |
| `blockEntitySyncDelayTicks` | `1` | 方块实体数据变化后延迟多少 tick 再同步，起到合并频繁变化的作用。 |
| `allowConcurrentTasks` | `true` | 是否允许同一维度同时存在多个放置或同步任务。 |
| `chunksPerTick` | `200` | 每个任务每 tick 最多尝试处理多少个区块条目。 |
| `maxMsPerTick` | `20` | 每个任务每 tick 最多占用多少毫秒主线程时间。 |
| `loadedChunksOnly` | `true` | 即时放置/破坏任务是否只处理已加载区块。 |
| `modifyUnloadedChunksImmediately` | `false` | 是否允许即时任务同步加载已保存但未加载的区块。大半径下容易造成卡顿。 |
| `allowChunkGeneration` | `false` | 是否允许同步生成缺失区块。大半径下不建议开启。 |

### ChunkPlaceBlock 命令

这些命令需要游戏管理员权限：

```mcfunction
/minigame chunkplaceblock enable
/minigame chunkplaceblock disable
```

## 两个功能的关系

`SameBlockBreak` 和 `ChunkPlaceBlock` 都可能响应破坏行为。为了避免同一破坏事件同时走两套批量破坏逻辑：

- 当 `SameBlockBreak` 启用时，`ChunkPlaceBlock` 的 `destroySamePositionOnBreak` 会自动设为 `false`。
- 如果要使用 `ChunkPlaceBlock` 自己的同位置破坏功能，需要先关闭 `SameBlockBreak`。

## 性能建议

这两个功能都会扫描或修改大量区块。半径越大、并发任务越多，对服务器主线程压力越高。

建议：

- 大半径下保持 `loadedChunksOnly=true`。
- 保持 `allowChunkGeneration=false`，避免任务同步生成大量新区块。
- `ChunkPlaceBlock` 大半径下保持 `modifyUnloadedChunksImmediately=false`。
- 服务器卡顿时，优先降低 `radius`、`chunksPerTick`、`blocksPerTick`、`blocksPerChunkStep` 或 `maxMsPerTick`。
- `SameBlockBreak` 的 `dynamicPriorityScanLimit` 越大，越能优先处理靠近玩家的区块，但每次选区块的计算开销也越高。
- 大规模破坏时适当降低 `dropRadius` 或 `maxNaturalBreakBlocks`，避免掉落物过多。
- 正常游玩时保持 `common.toml` 的 `debugLogging=false`。

## 项目结构

主要代码结构：

```text
src/main/java/com/xiaoyu/minigame/
  config/                         通用配置
  gamefeature/common/             共用工具
  gamefeature/sameblockbreak/     SameBlockBreak 功能
  gamefeature/chunkplaceblock/    ChunkPlaceBlock 功能
  mixin/                          两个功能需要的 Mixin
```
