# collision — questions about space in a world

Queries against a level's blocks and entities, hitboxes that live over time, and the debug view of both. **Using it:
`docs/COLLISION.md`, the guide.** This guide is where each part lives. Shapes and the exact tests underneath are
`shape/`. Design and steps: private plan `crystalgraphics/collision.md`; how the hosts answer it:
`docs/MINECRAFT_INTEGRATION.md` § *Collision*.

```java
CgCollisionWorld world = CgCollisionWorld.of(level);
world.overlap(BLAST).at(x, y, z).source(caster).living().forEach(h -> damage(h.entity(), 10f * h.falloff(2, 6)));
CgHitbox.of(CgRing.front(1.2, 0.6)).at(x, y, z).radius(0, 12, 12).onHit(h -> damage(h.entity())).start(level);
```

## Where things go

| Package | Owns |
|---|---|
| `collision` | `CgCollisionWorld` (a level, the client's frame, a test's fakes, `empty()`; `snapshot`, `batch`, `lineOfSight`, `exposure`; the thread's query scratch `PerThread`, the line-of-sight walker `Sight`, a snapshot's `FrozenHittables`) and `CgCollisionQuery` (one question: overlap, cast or ray, its filters and endings; `watch` for the show) |
| `collision.hit` | What a query or hitbox reports: `CgHit` (a mutable value, written through its where/what setters), `CgHits`, `CgHitPart`, `CgSentHit` (a hit on the wire) |
| `collision.hitbox` | `CgHitbox` (a volume over time: parts, sizes, rules, listeners), `CgHitList`, `CgHitboxType` (a networked hitbox on every side), `CgHitboxes` (each thread's running and hittable hitboxes: the one seam to `collision`), `CgSwept` (a shape and its motion) |
| `collision.entity` | `CgEntityTable` (entities by 16-block cell), `CgEntityCache` (a batch's, lazy), `CgEntitySnapshot` (a snapshot's, eager) |
| `collision.block` | `CgBlockSnapshot` (the blocks of a world with no mirror, copied on the spot), `CgBlockCells` (the cells a box covers as it moves, nearest first) |
| `collision.mirror` | `CgLevelMirror` (one per server level: the watched areas, kept from a write log replayed on a worker, published each tick as an immutable `CgMirrorVersion`), `CgMirrorHost` (the host slot), `CgMirrorColumn` (what the write hook calls), `CgMirrorShape`; inside, `MirrorSection`, `WriteLog`, `MirrorBatch`, `MirrorPalette`, `ShapeStore`, `MirrorBlocks` |
| `collision.debug` | `CgCollisionShow` (`crystalgraphics.collision.show` and F3+B: records, serves a server's lines, asks for them), `CgCollisionDebug` (lines and fills drawn on the next world frame), `CgRecentLines`, `CgLinePack`, `CgShapeLines` (a shape's edges and faces, one tessellation for both), `HullMesh` (a hull's own faces, built once) |

`collision.entity`, `collision.block` and `collision.mirror` are public only so the root and the hosts can reach them:
a mod never makes one.

## Rules

- **A world is its thread's**: `of(level)` and `client(...)` refuse a worker and a second thread. Workers read a
  snapshot, frozen, which every thread may query at once.
- **The mirror's level-thread work is a constant per write and per entity in a watched area**: copying, shapes and
  tables run on its worker, and first copies take a per-tick budget. Anything that scales with the area or with how many
  ask belongs on the worker. `crystalgraphics.mirror.stats` logs each phase's cost; `CgMirrorConsistencyTest` is the
  proof that every version is one tick exactly.
- **A query is the thread's scratch** (`PerThread.queries`, four deep): never held, never two open at one depth.
- **Hot paths allocate nothing**: a query, a hitbox tick, a hit, a batch, a snapshot's reads. Tests read
  `ThreadMXBean.getThreadAllocatedBytes`.
- **A hitbox runs once**, configured before its first tick. Started ones tick on their level's thread after the level's
  tick (`CgHitboxes`, hooked to `CgNetwork`'s ticks); an owner ticks its own with `tick`.
- **Hittable hitboxes are met as entities** standing for their owner, through one path (`CgCollisionQuery.meet`),
  live from `CgHitboxes` or frozen in a snapshot.
- **`CgHitboxes` is the only seam** between `hitbox` and `collision`: no hitbox internal is public.
- **Defaults are Minecraft's**: a query's targets are its projectiles' (alive, pickable, not a spectator); `source`
  skips the root vehicle; blocks answer as to no one unless `as(id)`; `visible()` is `lineOfSight`'s question, to the
  eyes. A followed hitbox's source is what it follows, and its sight starts at that entity's eyes.
- **Every call taking an entity id takes the entity too**, through `CgEntityQueries.requireId`.
- **The show draws what a host's world runs**: a hitbox's own queries and an empty world's are left out.
- **No renderer, GL or VFX type** outside `collision.debug` (`ShapeImportDirectionTest`): a dedicated server loads it.

## Tests

`CgCollisionWorldTest`, `CgSnapshotTest`, `mirror.CgLevelMirrorTest`, `mirror.CgMirrorConsistencyTest` (over
`MirrorFakes`), `entity.CgEntityCacheTest`, `hit.CgSentHitTest`, `debug.CgShapeLinesTest`,
`hitbox.CgHitboxTest`, `hitbox.CgHitboxTypeTest` (on `CgNetTestRig`), over `CollisionFakes`' yard. The VFX side:
`CgVfxHitsTest`, `CgVfxAuthorityTest`, `CgVfxCueTest`. The harness's `collision-shapes` scene and `CgRenderDemo`'s
`collision` draw `demo/collision/CgCollisionDemo`.

---

Also loaded with this folder:

@../../../../../../../docs/COLLISION.md
