# MiniGame

[简体中文](README.md) | [English](README.en.md)

MiniGame is a Minecraft mod project based on NeoForge. It currently provides two world-rule/minigame-rule features:

- `SameBlockBreak`: after a player or entity breaks a block, matching blocks in range are destroyed in batches.
- `ChunkPlaceBlock`: after a player places a block, the same block is placed at the same local chunk position in other chunks, with optional same-position breaking.

## Environment

- Minecraft: `26.1.2`
- NeoForge: `26.1.2.73`
- Java: `25`
- Mod ID: `minigame`

## Build And Run

```powershell
.\gradlew.bat build
```

```powershell
.\gradlew.bat runClient
```

```powershell
.\gradlew.bat runServer
```

Build outputs are written to:

```text
build/libs/
```

## Config Files

config files are generated under:

```text
config/minigame/
```

Current config files:

- `common.toml`
- `sameblockbreak.toml`
- `chunkplaceblock.toml`

`common.toml`:

| Option | Default | Description |
| --- | --- | --- |
| `debugLogging` | `false` | Enables verbose MiniGame debug logs. Keep this disabled during normal gameplay. |

## SameBlockBreak

`SameBlockBreak` creates a batched destruction task after a break event is triggered. Tasks advance over server ticks to avoid scanning or modifying too many blocks in one pass.

Current trigger sources:

- Player block breaking.
- Entity or mob block breaking.
- Explosion block breaking.
- Player picking up a water or lava source with an empty bucket.

Default behavior:

- Enabled by default.
- Matches only the same block type by default.
- Immediate tasks process only currently loaded chunks by default.
- Broken block types are remembered by default, so loaded or newly generated chunks are cleaned later.
- Multiple destruction tasks in the same dimension are allowed by default.
- When selecting the next chunk, the task dynamically prioritizes chunks closer to current players.
- Breaking water sources also handles waterlogged blocks; waterlogged blocks are changed to their dry state.
- Breaking lava sources also matches lava by fluid state.
- Natural breaking and drops are only used inside `dropRadius`; outside that range, blocks are silently removed to avoid excessive item entities.
- Unsupported neighboring blocks can be cleaned up, such as torches, saplings, and sugar cane.

### Loaded Chunks And Later Cleanup

When `loadedChunksOnly=true`, the immediate task queue only includes chunks that are already loaded when the task starts. Unloaded chunks are cleaned later when they load, based on the records kept by `rememberBrokenBlocksForever`.

When `rememberBrokenBlocksForever=true` and `matchBlockType=true`, the triggered block type is saved as a forbidden block. Later world generation, chunk loading, and normal block placement paths replace those blocks with air; water-related blocks are changed to a dry state when possible.

New chunks do not need to be force-loaded up front. With the default config, remembered blocks are blocked during generation.

### SameBlockBreak Config

`sameblockbreak.toml`:

| Option | Default | Description |
| --- | --- | --- |
| `enabled` | `true` | Enables SameBlockBreak. |
| `radius` | `3000` | Processing radius around the trigger position, in blocks. |
| `matchBlockType` | `true` | If `true`, only the same block type is destroyed; if `false`, all non-air blocks match. |
| `dropRadius` | `100` | X/Z radius around the trigger position where natural breaking and drops are used. |
| `maxNaturalBreakBlocks` | `3000` | Maximum naturally broken blocks per task; `0` disables drops, `-1` removes the limit. |
| `cleanupUnsupportedNeighbors` | `true` | Cleans neighboring blocks that can no longer survive after a block is removed. |
| `rememberBrokenBlocksForever` | `true` | Permanently remembers broken block types and prevents them from generating or being placed later. |
| `allowConcurrentTasks` | `true` | Allows multiple destruction tasks in the same dimension. |
| `chunksPerTick` | `200` | Maximum chunk entries each task attempts per tick. |
| `maxMsPerTick` | `25` | Maximum main-thread time, in milliseconds, each task may spend per tick. |
| `blocksPerTick` | `30000` | Maximum block positions each task scans per tick. |
| `blocksPerChunkStep` | `3000` | Maximum block positions scanned from one chunk before yielding. |
| `dynamicPriorityScanLimit` | `4096` | Maximum remaining chunk entries re-ranked whenever the task picks the next chunk near players. |
| `loadedChunksOnly` | `true` | Makes immediate tasks scan only loaded chunks. |
| `allowChunkGeneration` | `false` | Allows tasks to synchronously load or generate missing chunks. Not recommended with large radii. |

### SameBlockBreak Commands

These commands require game master permission:

```mcfunction
/minigame sameblockbreak status
/minigame sameblockbreak cancel
/minigame sameblockbreak cancelall
/minigame sameblockbreak enable
/minigame sameblockbreak disable
```

## ChunkPlaceBlock

`ChunkPlaceBlock` copies a placed source block to the same local chunk position in other chunks within range.

"Same local chunk position" means:

- Same local X coordinate inside the chunk.
- Same Y coordinate.
- Same local Z coordinate inside the chunk.

For example, if a player places a chest at `(localX=5, y=64, localZ=8)` in one chunk, other target chunks will try to place the same chest at `(localX=5, y=64, localZ=8)` if that target position is air.

Default behavior:

- Enabled by default.
- Immediate tasks modify only currently loaded chunks by default.
- Immediate tasks do not synchronously load unloaded chunks by default.
- Placement rules are remembered by default, so target chunks are filled later when they load or generate.
- Blocks are placed only into air and never overwrite existing blocks.
- Supports normal block placement and multi-place events.
- Supports water bucket and lava bucket source placement.
- Copies block entity data by default, such as chest contents.
- Later source block entity data changes are synced to other managed same-position blocks.
- Can optionally destroy same-type blocks at the same position in other chunks when the source block is broken.

### ChunkPlaceBlock Config

`chunkplaceblock.toml`:

| Option | Default | Description |
| --- | --- | --- |
| `enabled` | `true` | Enables ChunkPlaceBlock. |
| `radius` | `3000` | Processing radius around the trigger position, in blocks. |
| `syncBlockEntityData` | `true` | Copies and syncs block entity data, such as chest contents. |
| `applyToFutureChunkLoads` | `true` | Remembers rules and applies placement/breaking later when chunks load or generate. |
| `destroySamePositionOnBreak` | `false` | When the source block is broken, destroys same-type blocks at the same position in other chunks. This is automatically disabled while SameBlockBreak is enabled. |
| `placementDelayTicks` | `1` | Ticks to wait after placement before reading the source block and block entity data. |
| `blockEntitySyncDelayTicks` | `1` | Ticks to delay block entity sync after data changes, debouncing frequent updates. |
| `allowConcurrentTasks` | `true` | Allows multiple placement or sync tasks in the same dimension. |
| `chunksPerTick` | `200` | Maximum chunk entries each task attempts per tick. |
| `maxMsPerTick` | `20` | Maximum main-thread time, in milliseconds, each task may spend per tick. |
| `loadedChunksOnly` | `true` | Makes immediate placement/break tasks process only loaded chunks. |
| `modifyUnloadedChunksImmediately` | `false` | Allows immediate tasks to synchronously load saved but unloaded chunks. This can cause stalls with large radii. |
| `allowChunkGeneration` | `false` | Allows synchronous generation of missing chunks. Not recommended with large radii. |

### ChunkPlaceBlock Commands

These commands require game master permission:

```mcfunction
/minigame chunkplaceblock enable
/minigame chunkplaceblock disable
```

## Feature Interaction

Both `SameBlockBreak` and `ChunkPlaceBlock` can respond to block breaking. To avoid running two batch-breaking systems for the same break event:

- When `SameBlockBreak` is enabled, `ChunkPlaceBlock` automatically sets `destroySamePositionOnBreak` to `false`.
- To use ChunkPlaceBlock's same-position breaking feature, disable `SameBlockBreak` first.

## Performance Notes

Both features can scan or modify many chunks. Larger radii and more concurrent tasks increase main-thread load.

Recommendations:

- Keep `loadedChunksOnly=true` with large radii.
- Keep `allowChunkGeneration=false` to avoid synchronously generating many new chunks.
- Keep `modifyUnloadedChunksImmediately=false` for ChunkPlaceBlock with large radii.
- If the server lags, reduce `radius`, `chunksPerTick`, `blocksPerTick`, `blocksPerChunkStep`, or `maxMsPerTick` first.
- Larger `dynamicPriorityScanLimit` values make SameBlockBreak prioritize chunks near players more accurately, but each chunk selection costs more CPU time.
- For large destruction tasks, lower `dropRadius` or `maxNaturalBreakBlocks` to reduce item entity spam.
- Keep `debugLogging=false` in `common.toml` during normal gameplay.

## Project Structure

Main source layout:

```text
src/main/java/com/xiaoyu/minigame/
  config/                         Common config
  gamefeature/common/             Shared utilities
  gamefeature/sameblockbreak/     SameBlockBreak feature
  gamefeature/chunkplaceblock/    ChunkPlaceBlock feature
  mixin/                          Mixins required by both features
```
