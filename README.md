# MiniGame

MiniGame is a NeoForge mod for special-rule Minecraft servers and custom minigames. It adds world-scale block rules that can turn normal building and mining actions into synchronized events across loaded chunks.

This mod is powerful by design. Test it on a copy of your world before using it on a long-running survival server.

## Features

### Same Block Break

When an entity breaks a block, MiniGame can clean matching blocks across the world.

- Player block breaks can trigger the cleanup.
- Living-entity block destruction, such as withers or dragons, can also trigger it.
- Bucket pickup of fluids can trigger fluid cleanup.
- The cleanup is budgeted per tick to avoid doing all work in one server tick.
- Optional persistent cleanup rules continue scanning chunks that load later.
- Unsupported blocks, such as torches or vegetation left floating after cleanup, can be removed automatically.

### Chunk Place Block

When an entity places a block, MiniGame can mirror that block to the same local position in every loaded chunk.

- Normal block placements are mirrored.
- Multi-block placements, such as beds and doors, are supported.
- Bucket fluid placements can be mirrored.
- Persistent placement rules apply the latest synchronized block to chunks that load later.
- Mirrored breaking can remove corresponding blocks at the same local chunk position.
- Container block entities, such as chests and barrels, can keep their inventories synchronized between mirrored copies.

## Requirements

- Minecraft: `26.1.2`
- NeoForge: `26.1.2.73` or newer in the same supported version line
- Java: `25`

## Installation

1. Install NeoForge for the supported Minecraft version.
2. Put the MiniGame jar into the `mods` folder.
3. Start the game or server once so the config files are generated.
4. Edit the server config files under `serverconfig/minigame/` for the world you want to run.

## Configuration

MiniGame uses server-side config files:

- `serverconfig/minigame/common.toml`
- `serverconfig/minigame/sameblockbreak.toml`
- `serverconfig/minigame/chunkplaceblock.toml`

Important options:

- `common.enableDebugLogs`: enables verbose internal debug logging. Keep this disabled unless you are diagnosing a problem.
- `sameblockbreak.enabled`: enables same-block cleanup.
- `sameblockbreak.persistCleanupRules`: keeps cleanup rules active for chunks loaded later.
- `sameblockbreak.maxSectionsPerTick`, `maxScannedBlocksPerTick`, and `maxBlockChangesPerTick`: tune cleanup speed and server load.
- `chunkplaceblock.enabled`: enables chunk-position placement and break synchronization.
- `chunkplaceblock.persistPlacementRules`: keeps mirrored placement rules active for chunks loaded later.
- `chunkplaceblock.syncContainerContents`: synchronizes inventories for mirrored container block entities.
- `chunkplaceblock.preventNeighborChunkLoading`: keeps mirrored block updates conservative so they do not load neighboring chunks through update logic.

## Commands

Commands require administrator permissions.

```text
/minigame sameblockbreak status
/minigame sameblockbreak start <block>
/minigame sameblockbreak forget <block>
/minigame sameblockbreak clearRules

/minigame chunkplaceblock status
/minigame chunkplaceblock clearRules

/minigame debug getLoadedChunks
/minigame debug getWorldHeight
```

Block arguments use registry ids such as `minecraft:stone`.

## Notes For Server Owners

- These features intentionally affect many chunks and many blocks. Start with conservative per-tick limits if your server has many players or a large simulation distance.
- Mirrored placement only changes currently loaded chunks immediately. Persistent rules handle chunks that load later when persistence is enabled.
- Container synchronization works for loaded matching containers at the same local chunk position.
- By default, mirrored breaking does not drop resources. This avoids accidental item duplication.
- Debug logging is disabled by default. Enable `common.enableDebugLogs` only while investigating issues, because placement and chunk tracking logs can become noisy.

## License

MIT
