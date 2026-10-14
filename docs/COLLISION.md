# Collision — the guide

CrystalGraphics answers questions about space for you, on every version and loader it runs on: who is inside a
blast, what a bolt hits first, whether a sword's swing reaches a mob, whether a target can be seen. You describe a
shape and where it is; the engine tests it against Minecraft's blocks and entities exactly, nearest first, and hands
you the hits.

It comes in three layers:

- **Shapes**: a sphere, a cone, a capsule, a turned box, a ring, a shell, your own convex shape, Minecraft's block
  shapes. Exact tests between any two of them, with no world at all.
- **World queries**: one question to a level, read left to right. "What does this sphere overlap?" "What does this
  ray meet first?" "What does this ball, thrown along this line, hit?"
- **Hitboxes**: a volume that lives over time and hits what it reaches: a shockwave running out along the ground, a
  sword's swing, a trap, a shield. Each target is hit once (or as often as you say), nothing fast slips past it, and
  it can run on the server and every client at once.

This page is written for someone who has never written collision code. Read sections 1 to 3 first: they give you the
words and the picture that everything else uses. After that, jump to what you need: section 7 is a book of recipes.

## If you have done collision in a mod before

You know the routine. Here is a blast and a bolt the usual way, on Forge 1.20.1, and then here.

**The usual way.** A box round the blast, a list of entities, then trimming the box back to a ball by hand; a second
call for blocks, a third for entities, and a comparison to find which came first:

```java
// A blast of radius 6: Minecraft only knows boxes, so ask for a cube and cut the corners off yourself
AABB area = new AABB(x - 6, y - 6, z - 6, x + 6, y + 6, z + 6);
for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, area, e -> e != caster && e.isAlive())) {
    if (e.distanceToSqr(x, y, z) > 36) continue;              // the feet, not the nearest point of the body
    float seen = Explosion.getSeenPercent(new Vec3(x, y, z), e);
    e.hurt(damageSources.explosion(null, caster), 10f * seen);
}
// The ender dragon's parts are not in getEntity(id); its head never takes the hit unless you dig for parts.

// The first thing a bolt hits: blocks and entities separately, then compare
Vec3 from = new Vec3(x, y, z), to = from.add(dir.scale(64));
BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster));
EntityHitResult entity = ProjectileUtil.getEntityHitResult(level, caster, from, block.getLocation(),
        new AABB(from, block.getLocation()).inflate(1), e -> !e.isSpectator() && e.isPickable());
// ...a bolt is a ray here: a ball half a block wide slips between two mobs it should have hit.
```

Then the port: 1.12 calls them `getEntitiesWithinAABB`, `rayTraceBlocks` and `AxisAlignedBB`; 1.7.10 has no
`VoxelShape`, only lists of boxes; the dragon's parts move between `getParts()`, `PartEntity` and `dragonParts()`. And
a sword's swing, a cone of breath, a turned slam: Minecraft has no shape for any of them.

**Here.** On every one of those versions and loaders, unchanged:

```java
static final CgShape BLAST = CgSphere.of(6);
static final CgShape BOLT = CgSphere.of(0.25);
private final CgHit hit = new CgHit();

CgCollisionWorld world = CgCollisionWorld.of(level);
world.overlap(BLAST).at(x, y, z).source(caster).living().exposure()
        .forEach(h -> damage(h.entity(), 10f * h.exposure() * h.falloff(2, 6)));

if (world.cast(BOLT).at(x, y, z).along(dx, dy, dz, 64).source(caster).blocksToo().first(hit)) strike(hit);
```

The ball is a ball, measured to the nearest point of each body; the dragon is hit once, by its nearest part; the bolt
is half a block wide all the way; blocks are their real shapes (a stair's step, a fence's post); one call, nearest
first, nothing allocated.

### What goes away

| The struggle | Usually | Here |
|---|---|---|
| **Boxes only** | every area is an axis-aligned box; a sphere, a cone or a turned slab is filtered by hand, approximately | spheres, cones, capsules, turned boxes, cylinders, rings, shells, planes, compounds, your own convex shapes, all exact |
| **Feet, not bodies** | `distanceToSqr` measures to an entity's feet, so a tall mob's head inside the blast is missed | every test is against the real box, to its nearest point |
| **Blocks and entities apart** | `clip` for blocks, `getEntityHitResult` for entities, then a comparison | one query, nearest first; a cast with blocks stops at the first block |
| **Fast things tunnel** | a projectile moving 5 blocks a tick skips the mob between two ticks | a cast sweeps its whole path; a hitbox tests everything it swept since the last tick, a turn included |
| **Hit once** | a `Set<UUID>` per attack, cleared by hand; a dragon struck in three parts counts three times | hit lists, by whole entity, with re-hit times and caps |
| **The dragon's head** | `getEntity(id)` misses parts; damage to a part needs the part | parts found and reported; `parent()` is the dragon |
| **Who may be hit** | each mod re-derives Minecraft's projectile rule, and forgets spectators or riders | the default is Minecraft's own rule; `source` skips the caster, its mount and passengers |
| **Every port** | `AxisAlignedBB` to `AABB`, `rayTraceBlocks` to `clip`, lists of boxes to `VoxelShape` | the same calls on Forge 1.7.10 to 26.3, NeoForge and Fabric |
| **Garbage** | a new `AABB`, `Vec3` and `List` per call | nothing allocated per query, per hitbox tick, per hit |
| **Server and client disagree** | the server decides, the client guesses, hits arrive late or twice | a hitbox type runs on both: the client's own copy for feel, the server's for truth |
| **Off the main thread** | touching the level from a worker crashes one time in a hundred | a snapshot: the area frozen, read by any number of threads ([`JOBS.md`](JOBS.md)) |
| **Seeing it** | print statements | every query and hitbox drawn as lines under F3+B |
| **Testing** | launch the game and swing at a cow | an empty world and your own shapes in a JUnit test |

## Contents

1. [The words you need](#1-the-words-you-need)
2. [Quick start](#2-quick-start)
3. [The picture: sides, threads, units](#3-the-picture-sides-threads-units)
4. [Shapes](#4-shapes)
5. [Asking the world: queries](#5-asking-the-world-queries)
6. [Hits: what a query or hitbox reports](#6-hits-what-a-query-or-hitbox-reports)
7. [Recipes](#7-recipes)
8. [Hitboxes: volumes that live over time](#8-hitboxes-volumes-that-live-over-time)
9. [Shields, wards and weak spots: hittable hitboxes](#9-shields-wards-and-weak-spots-hittable-hitboxes)
10. [Hitboxes on every side: hitbox types](#10-hitboxes-on-every-side-hitbox-types)
11. [Effects that hit](#11-effects-that-hit)
12. [Many queries a tick, and off the main thread](#12-many-queries-a-tick-and-off-the-main-thread)
13. [Exact tests with no world](#13-exact-tests-with-no-world)
14. [Seeing it](#14-seeing-it)
15. [Testing without Minecraft](#15-testing-without-minecraft)
16. [Performance and limits](#16-performance-and-limits)
17. [Questions people ask](#17-questions-people-ask)

Where each class lives is `collision/CLAUDE.md`, which loads itself for anyone editing a collision file.

---

## 1. The words you need

| Word | What it is | Class |
|---|---|---|
| **Shape** | A region of space about its own origin: a ball, a cone, a box. Immutable, so one `static final` shape is shared by everything. | `CgShape` and `CgSphere`, `CgBox`, `CgCone`, ... |
| **Pose** | Where a shape is placed: a position and a rotation. Mutable and reused. | `CgPose` |
| **Box** | An axis-aligned box: an entity's body, a region, a shape's bounds. | `CgAABB` (mutable), `CgAABBc` (read-only) |
| **World** | A level, asked what shapes meet: its blocks and entities. | `CgCollisionWorld` |
| **Query** | One question to a world, read left to right: a shape, where it is, what it may hit, what to do with the hits. | `CgCollisionQuery` |
| **Overlap** | A query: what a shape placed somewhere touches. | `world.overlap(shape)` |
| **Cast** | A query: what a shape moving along a line meets first. A bolt, a body falling, a thrown sword. | `world.cast(shape)` |
| **Ray** | A query: what a line meets first. A shape of no size. | `world.ray()` |
| **Hit** | One thing a query or hitbox met: an entity, a block, a fluid's surface, the world's edge; where, from which side, how far. | `CgHit` |
| **Kind** | What a hit met: `ENTITY`, `HITBOX`, `BLOCK`, `FLUID`, `UNLOADED`, `BORDER`, `POINT`. | `CgHit.Kind` |
| **Part** | A label on hits, so a listener can tell a sword's tip from its blade, a beam from its blast. | `CgHitPart` |
| **Hitbox** | A volume that lives over time, growing, turning, following an entity, and hits what it reaches by rules: once each, again after a while, at most so many. | `CgHitbox` |
| **Hittable** | A hitbox that queries meet, as if it were an entity: a shield, a ward, a weak spot. | `CgHitbox.hittable()` |
| **Hit list** | Who a hitbox has hit, and when: shared, so a combo hits each target once between its moves. | `CgHitList` |
| **Hitbox type** | A hitbox the server starts that every client watching runs too. | `CgHitboxType` |
| **Snapshot** | A world's blocks and entities in an area as they stood at the end of a server tick, which any thread may read. | `world.snapshot(...)`, `world.watch(...)` |
| **Batch** | Many queries in one tick sharing what they learn of the entities. | `CgCollisionWorld.batch()` |
| **Entity id** | How an entity is named here: Minecraft's `entity.getId()`. Every call that takes one takes the entity too (`source(caster)`), and a hit hands out both: `entityId()`, and the entity itself, `entity()`. | `int` |

How they fit together:

```
Shapes            CgShape ─ placed by a CgPose ──▶ CgShapes: overlap, distance, contact, ray, cast (no world)

Queries           CgCollisionWorld.of(level) ─▶ overlap / cast / ray (shape) ─▶ at, facing, along
                                             ─▶ what it may hit: blocksToo, living, source, where, ...
                                             ─▶ forEach / first / any / count / into  ──▶ CgHit

Hitboxes          CgHitbox = shapes + placement + sizes over time + rules ──▶ ticked each game tick
                             ─▶ onHit / onEnter / onExit                  ──▶ CgHit
                  CgHitboxType = a hitbox the server starts and every viewer runs too
```

---

## 2. Quick start

### Everyone inside a blast

```java
static final CgShape BLAST = CgSphere.of(6);                     // once, as a constant

// server, when it goes off
CgCollisionWorld.of(level).overlap(BLAST).at(x, y, z)
        .source(caster)                                  // never the caster, their mount or passengers
        .living()                                                // mobs and players, a dragon by its whole
        .forEach(h -> {
            LivingEntity e = h.entity();                         // Minecraft's entity, or null if it went
            if (e != null) e.hurt(level.damageSources().magic(), 10f * h.falloff(2, 6));   // full within 2, none past 6
        });
```

### The first thing a bolt hits

```java
static final CgShape BOLT = CgSphere.of(0.25);
private final CgHit hit = new CgHit();                           // a field, reused

if (CgCollisionWorld.of(level).cast(BOLT).at(x, y, z).along(dx, dy, dz, 3)   // 3 blocks this tick
        .source(shooter).blocksToo().first(hit)) {
    if (hit.kind() == CgHit.Kind.ENTITY) strike(hit.entity());
    else explodeAt(hit.x(), hit.y(), hit.z());                   // a block: its real shape, a stair's step included
}
```

### A sword's swing

```java
static final CgHitPart TIP = CgHitPart.of("tip"), BLADE = CgHitPart.of("blade");

CgHitbox.follow(player, CgEntityAttachments.RIGHT_HAND_FORWARD).facing(CgHitbox.Facing.LOOK)
        .part(TIP, CgSphere.of(0.35), 0, 0, 1.1)                 // listed first: wins when both touch
        .part(BLADE, CgCapsule.of(0.5, 0.3))
        .activeFor(5)                                            // five game ticks
        .onHit(h -> damage(h.entity(), h.part() == TIP ? 9f : 5f))   // each mob once
        .start(level);
```

### A shockwave running out along the ground

```java
CgHitbox.of(CgRing.front(1.2, 0.6)).at(x, y, z)                  // a front 1.2 blocks thick
        .radius(0, 12, 12)                                       // out to 12 blocks over 12 ticks
        .source(caster)
        .query(q -> q.living().exposure())                       // only the living; how much each is behind cover
        .onHit(h -> damage(h.entity(), 8f * h.exposure()))       // each once, as the front reaches it
        .start(level);
```

That is most of what mods do. Sections 4 to 6 are the details; section 7 is many more examples.

---

## 3. The picture: sides, threads, units

**The server decides; clients show.** Damage, knockback and anything a player must agree on come from queries and
hitboxes on the server. A client runs them too, for what must feel instant (a spark the frame you strike, a crosshair
turning red), never for what is true. [§10](#10-hitboxes-on-every-side-hitbox-types) runs one hitbox on both.

| You want | Ask on | World |
|---|---|---|
| Damage, knockback, gameplay | the server thread | `CgCollisionWorld.of(serverLevel)` |
| Feedback that follows what the player sees | the client thread | `CgCollisionWorld.client(partialTick)`: entities where the frame draws them |
| Client logic at the tick (not the frame) | the client thread | `CgCollisionWorld.of(clientLevel)`: entities where they are this tick |
| Heavy work beside the game | a worker | a snapshot taken on the level's thread ([§12](#off-the-main-thread-snapshots)) |
| Only your own shapes, no level | any thread that ticks them | `CgCollisionWorld.empty()` ([§9](#a-world-of-your-own-shapes-alone)) |

### Threads

- **A world is its thread's.** `of(level)` and `client(...)` hand each thread its own, and asking one from another
  thread throws. Ask on the level's thread: the server thread, or the client (render) thread.
- **Asking is cheap.** `CgCollisionWorld.of(level)` keeps the thread's worlds; calling it every tick allocates
  nothing. Don't hold one past the tick you need it in.
- **A query is its thread's scratch.** Start one, end it (`forEach`, `first`, ...), start the next. Never keep a
  query in a field, never start two at once. A query *inside* a visitor is fine, up to four deep.
- **A worker never touches the level.** `of(level)` called on a worker throws; a worker reads a snapshot.
- **Started hitboxes run on the level's thread**, ticked after the level's own tick.

### Units

| Quantity | Unit |
|---|---|
| Positions, distances, sizes | blocks, in doubles (a world position far from the origin loses nothing) |
| Angles | radians. `facing(yaw, pitch)` follows Minecraft's directions (yaw 0 faces +Z, pitch down is positive): `facing(Math.toRadians(player.getYRot()), Math.toRadians(player.getXRot()))` |
| A shape's own axes | +Z forward, +Y up. A cone points along +Z; a capsule from `of` lies along Z |
| Hitbox time | game ticks, fractional allowed: `activeFor(5)` is a quarter of a second |
| Entities | the entity, or its id (`entity.getId()`, `-1` for none) where a call takes one. A hit's `entity()` is Minecraft's entity, asked on the level's thread |

### Nothing allocated

Every query, every hitbox tick and every hit allocates nothing, so a thousand of them a tick are fine. Three habits keep
your side of it the same:

- **Shapes are `static final` constants** (`static final CgShape BLAST = CgSphere.of(6)`), never `CgSphere.of(6)` inside
  a tick.
- **Hits you keep go into fields**: `private final CgHit hit = new CgHit();` then `first(hit)`; a list,
  `private final CgHits hits = new CgHits();` then `into(hits)`.
- **A visitor that captures nothing**, or is held in a field, allocates nothing; a capturing lambda allocates a small
  object per call, which is fine for a once-a-second blast and worth holding for a per-tick one.

---

## 4. Shapes

A shape is a region of space about its own origin. It knows nothing of where it is; a `CgPose` places it each time it
is used. Shapes are immutable: make each once, as a constant, and share it everywhere, between the server and client
threads of a singleplayer game too.

### The shapes

| Shape | Make it | What it is | For |
|---|---|---|---|
| `CgSphere` | `CgSphere.of(r)` | a ball about the origin | blasts, auras, pickups, a bolt's head |
| `CgBox` | `CgBox.of(halfX, halfY, halfZ)` | a box by its half extents, turned by its pose | slams, walls, rectangular zones |
| `CgCapsule` | `CgCapsule.of(halfLength, r)`, `CgCapsule.between(ax, ay, az, bx, by, bz, r)` | every point within `r` of a segment | sword blades, limbs, a fixed beam |
| `CgCylinder` | `CgCylinder.of(r, halfHeight)` | upright about local Y, centred on the origin | pillars, columns of fire, circles on the ground |
| `CgCone` | `CgCone.of(range, halfAngle)` | everything within `range` of the apex and `halfAngle` of +Z | breath, cleaves, shotgun blasts, sight cones |
| `CgShell` | `CgShell.of(inner, outer)`, `CgShell.front(width)` | between two spheres | what hits only at its edge: an expanding blast's front |
| `CgRing` | `CgRing.of(inner, outer, halfHeight)`, `CgRing.front(width, halfHeight)` | between two upright cylinders | a shockwave running along the ground |
| `CgPlane` | `CgPlane.of(nx, ny, nz)`, `CgPlane.of(nx, ny, nz, radius)` | solid on the side away from its normal; a disc `radius` round (1000 by default) | floors, walls, ramps |
| `CgCompound` | `CgCompound.builder().add(...).build()` | several shapes as one, each placed in its own space | a hammer's haft and head, a set of hurtboxes |
| `CgConvex` | `CgConvex.hull(points)`, `CgConvex.of(support)` | the hull of some points, or your own convex shape | wedges, lenses, anything else convex |

```java
static final CgShape BLAST = CgSphere.of(6);
static final CgShape SLAM = CgBox.of(2, 0.5, 3);                     // 4 wide, 1 high, 6 long
static final CgShape BLADE = CgCapsule.of(0.5, 0.3);                 // along +Z, 1 long, 0.3 thick
static final CgShape BEAM = CgCapsule.between(0, 0, 0, 0, 0, 12, 0.6);   // from the origin 12 blocks forward
static final CgShape PILLAR = CgCylinder.of(1.5, 4);                 // 3 across, 8 high
static final CgShape CLEAVE = CgCone.of(8, Math.toRadians(35));      // 8 long, 35 degrees either side of forward
static final CgShape HALO = CgShell.of(3, 4);                        // only between 3 and 4 blocks out
static final CgShape MOAT = CgRing.of(5, 7, 1);                      // 5 to 7 blocks out, 2 high
static final CgShape RAMP = CgPlane.of(0.4, 1, 0, 5);                // tilted; ends 5 blocks round
```

Things to know:

- **Where each shape's origin is matters**, because the pose puts the origin somewhere. A sphere, box, capsule
  (`of`), cylinder, shell and ring are centred on it; a cone's apex is the origin, so its pose is where it comes from;
  `CgCapsule.between` puts its ends where you say, so a pose at a muzzle starts a beam there.
- **A cylinder and a ring are centred vertically**: a pose at the ground puts half below it. Stand a pillar on `y`
  with `pose.at(x, y + halfHeight, z)`.
- **A cone's far end is a cap of a sphere**, not a flat disc: a target at the edge of the angle is as far as one
  straight ahead. Up to 90 degrees it is convex; wider (up to pi, a whole ball) it is tested as a ball and an angle.
- **A front** (`CgShell.front`, `CgRing.front`) is an edge `width` thick at whatever radius a hitbox gives it
  ([§8](#sizes-over-time)). On its own, before anything sizes it, it is a ball or disc of radius `width`.
- **Not every shape is convex**: a shell, a ring, a cone past 90 degrees and a compound are not. They work everywhere
  in queries and hitboxes; a few exact tests with no world take convex shapes only ([§13](#13-exact-tests-with-no-world)).
- **Every factory checks its arguments**: a negative or non-finite size, an inner radius past the outer, an angle
  outside 0 to pi, throw `IllegalArgumentException` at once.

### Placing a shape: `CgPose`

```java
private final CgPose pose = new CgPose();                       // a field: reused

pose.at(x, y, z);                                               // moved, not turned
pose.at(eyeX, eyeY, eyeZ).facing(yaw, pitch);                   // turned so +Z looks along yaw and pitch (radians)
pose.at(x, y, z).rotation(quaternion);                          // any rotation, a JOML Quaternionf or Quaterniond
pose.identity();                                                // back to the origin, unturned
```

- `facing(yaw, pitch)` follows Minecraft's directions in radians, with no roll. A player's look:
  `pose.at(eyeX, eyeY, eyeZ).facing(Math.toRadians(player.getYRot()), Math.toRadians(player.getXRot()))`.
- A rotation is normalized as it is set; position and rotation calls leave each other alone.
- Points and directions between the shape's space and the world: `toWorld`, `toLocal`, `rotate`, `unrotate`, each
  into a `Vector3d` you hold. `getRotation(out)` reads the rotation back.

```java
Vector3d tip = pose.toWorld(0, 0, 1.1, new Vector3d());        // the sword's tip in the world
Vector3d local = pose.toLocal(target.x, target.y, target.z, tmp);   // where a target is in the swing's own space
```

### Compounds: several shapes as one

```java
static final CgShape HAMMER = CgCompound.builder()
        .add(CgCapsule.of(0.6, 0.1), 0, 0, 0.6)                  // the haft, moved forward
        .add(CgBox.of(0.3, 0.2, 0.2), new CgPose().at(0, 0, 1.3))   // the head, at its end (any pose: turned too)
        .build();
```

- A compound tests each child and answers for the nearest; its poses are copied as they are added.
- For a weapon whose pieces hit differently (a tip and a blade), use a hitbox's **parts** instead
  ([§8](#parts)): each part labels its own hits. A compound is one shape, one label.

### Your own convex shape

```java
static final CgShape WEDGE = CgConvex.hull(new double[]{          // the hull of these points, as x, y, z triples
        -1, 0, 0,   1, 0, 0,   -1, 0, 2,   1, 0, 2,   -1, 1, 0,   1, 1, 0});

static final CgShape EGG = CgConvex.of((dx, dy, dz, out) -> {     // the farthest point along a direction
    double len = Math.sqrt(dx * dx * 1.0 + dy * dy * 2.25 + dz * dz * 1.0);
    return len == 0 ? out.set(0, 1.5, 0) : out.set(dx / len, dy * 2.25 / len, dz / len);   // an ellipsoid 1 x 1.5 x 1
});
```

- **The support function** answers the point of the shape farthest along `(dx, dy, dz)`, in the shape's own space.
  The direction need not be unit length; a zero direction answers any point of the shape. It must allocate nothing:
  write into `out` and return it.
- `hull` builds no hull: it keeps the points and answers the farthest. A few dozen are cheap; thousands are not.
- A support function that describes something not convex is tested as its hull.

### Minecraft's block shapes

```java
CgShape stair = CgShape.of(state.getCollisionShape(level, pos));   // a compound of its boxes, 0 to 1; null for none
if (stair != null && CgShapes.overlaps(BLAST, blastPose, stair, pose.at(pos.getX(), pos.getY(), pos.getZ()))) ...
```

`CgShape.of` takes modern's `VoxelShape`, a host box, or a `List` of host boxes (1.7.10 to 1.12), in the block's own
space, placed at the block's corner. A block's shared shape is converted once. You rarely need it: a query with
`blocksToo()` already tests every block by its real shape.

### Boxes: `CgAABB` and `CgAABBc`

An axis-aligned box: an entity's body, a region, a shape's bounds. `CgAABB` is mutable and reused; `CgAABBc` is the
read-only view an API takes when it only reads one.

```java
private final CgAABB box = new CgAABB();                         // empty, not a point at the origin

box.set(minX, minY, minZ, maxX, maxY, maxZ);
box.setCenterHalf(cx, cy, cz, hx, hy, hz);
box.grow(0.3).translate(0, 1, 0);
box.set(entity).expandTowards(0, -0.5, 0);                       // the body and half a block under it: Minecraft's name
box.empty(); for (...) box.union(px, py, pz);                    // grown over points from empty
BLAST.bounds(pose.at(x, y, z), box);                             // a shape's box in the world, exact for turned shapes

static final CgAABBc ARENA = CgAABB.of(-20, 0, -20, 20, 12, 20); // a constant nobody can change
if (ARENA.contains(px, py, pz)) ...
```

Minecraft's boxes in and out, on any version:

```java
box.set(entity.getBoundingBox());                                // an AABB (AxisAlignedBB below 1.13) into a held box
box.set(entity);                                                 // an entity: its box as it is now
CgAABB copy = CgAABB.of(entity.getBoundingBox());                // a new box, for setup
AABB mc = box.toHost();                                          // back, for Minecraft's own calls
```

- `set(...)` allocates nothing; `of(...)` makes one box, for setup; `toHost()` makes one host box.
- Every setter returns the box, and none checks min against max: an inverted box is simply empty, and an empty box
  contains and intersects nothing.
- Reading: `minX()`...`maxZ()`, `centerX()`, `halfX()`, `contains`, `intersects`, `closestPoint`, `distanceSquared`,
  `rayHit`, `isEmpty`, `boundingRadius`.
- The host conversions throw `IllegalArgumentException` for an object the host does not know, and in a test or the
  harness, which have no Minecraft.

### Shapes that change size: `CgResizable`

A hitbox sizes its own shapes ([§8](#sizes-over-time)). When you size one yourself each tick, a `CgResizable` does it
without allocating:

```java
private final CgResizable front = CgResizable.of(CgShell.front(2));   // once

CgShape now = front.radius(6);                                    // a shell from 4 to 6 blocks out
CgShape swept = front.sweptRadius(5, 6);                          // all it covered growing from 5 to 6: 3 to 6
CgShape slam = CgResizable.of(CgBox.of(2, 0.5, 3)).scale(2);      // a box has no radius: 8 x 2 x 12
```

- A radius is a sphere's, cylinder's or capsule's radius, a cone's range, a shell's or ring's outer edge (a front
  keeps its width). `scale` multiplies every length about the origin.
- **The shape handed out changes with the next call**: use it at once, never keep or share it.
- A compound, a hull and a plane cannot be resized: `of` throws.

---

## 5. Asking the world: queries

A query is one question, read left to right: **start** it with a shape, **place** it, say **what it may hit**, then
**end** it with what to do with the hits.

```java
CgCollisionWorld world = CgCollisionWorld.of(level);

world.overlap(CLEAVE)                                  // start: what this cone overlaps
     .at(eyeX, eyeY, eyeZ).facing(yaw, pitch)          // place it
     .source(player).living()                  // what it may hit
     .forEach(h -> damage(h.entity(), 6f));            // end: every hit, nearest first
```

### Starting

| Start | Asks | Needs |
|---|---|---|
| `world.overlap(shape)` | what the shape, placed, touches | `at` (and `facing` or `rotation` for a turned shape) |
| `world.overlap(box)` | what an absolute box touches: an entity's own body, a region | nothing: the box is where it is |
| `world.cast(shape)` | what the shape first meets moving along a line | `at`, then `along` |
| `world.ray()` | what a line first meets | `at`, then `along` |

- **Always place it**: a query left unplaced stands at the world's origin.
- **`along(dx, dy, dz, distance)`**: which way and how far, in blocks; the direction need not be unit length. It
  throws for a zero direction, a negative distance, or on an overlap.
- **A cast takes a convex shape**, or a compound of convex ones: a sphere, box, capsule, cylinder, narrow cone, plane
  or hull. An overlap takes any shape.

### Ending

| End | Gives | Allocates |
|---|---|---|
| `forEach(h -> ...)` | every hit, nearest first; the hit is valid during the call | nothing (a capturing lambda: one small object) |
| `first(hit)` | the nearest hit copied into your `CgHit`; false, with it untouched, for none | nothing |
| `any()` | whether anything is hit, stopping at the first | nothing |
| `count()` | how many hits | nothing |
| `into(hits)` | every hit, nearest first, into your `CgHits` (cleared first); how many | nothing once it has held as many |

Every ending ends the query: start a new one for the next question.

### What a query may hit

**By default a query meets entities only**, by Minecraft's projectile rule: alive, pickable, not a spectator.
Everything below changes that, in any order.

| Call | Meets |
|---|---|
| *(nothing)* | entities Minecraft's projectiles hit: alive, pickable, not spectators |
| `blocksToo()` | blocks as well, by their collision shapes |
| `blocksOnly()` | blocks alone: no entity, no hittable hitbox |
| `fluids()` | fluids as boxes up to their surface, blocks included: a cast or ray stops at water |
| `border()` | the world border too, as a moving entity meets it: every cell beyond it is solid |
| `living()` | living entities only; a dragon's part by its dragon |
| `attackable()` | only what a melee attack may hit, as Minecraft's `isAttackable`: no items, orbs or arrows |
| `solid()` | only what Minecraft stops other entities at: boats, minecarts, shulkers |
| `itemsToo()` | dropped items as well, which projectiles pass through |
| `allEntities()` | every entity, spectators and the dead included |
| `kinds(mask)` | only these kinds: `1 << CgEntityQuery.KIND_MONSTER \| 1 << CgEntityQuery.KIND_PLAYER`, asked of the whole entity |
| `where((Entity e) -> ...)` | only entities your test accepts, handed Minecraft's entity (the whole: a dragon for its parts): teams, PvP, anything the engine does not decide |
| `whereId(id -> ...)` | the same by id: what a snapshot's queries take |
| `except(id)` | never `id` or its parts; up to eight |
| `source(id)` | whose query it is: never them, their parts, or anyone riding with them |
| `noHitboxes()` | passes through hittable hitboxes, meeting their owners' bodies ([§9](#9-shields-wards-and-weak-spots-hittable-hitboxes)) |
| `visible()` | only entities seen past no block, to their eyes, as `lineOfSight` sees them: from the origin, or from the eyes of the entity a hitbox follows |
| `visibleFrom(x, y, z)` | the same, looking from that point |
| `inflate(d)` | grows every entity's box by `d` a side: Minecraft's arrows use 0.3, aim assist more |
| `max(n)` | at most the `n` nearest |

The kinds: `KIND_OTHER`, `KIND_PLAYER`, `KIND_MONSTER`, `KIND_ANIMAL`, `KIND_ITEM`, `KIND_PROJECTILE`,
`KIND_VEHICLE` (on `CgEntityQuery`).

What it reports beyond the hit itself:

| Call | Adds |
|---|---|
| `exposure()` | each entity hit's `exposure()`: how much of it the origin sees, 0 to 1, as Minecraft's explosions sample it |
| `part(PART)` | labels every hit with a part: several queries' hits told apart in one list |

### Blocks

- **Blocks are their real shapes**: a stair's step, a slab, a fence's post, a wall. A ray over a slab passes over it.
- **A cast or ray with blocks stops at the first block**: that block is the last hit, and nothing beyond it is. An
  overlap reports every block it touches, once each.
- **An unloaded cell** stops a cast or ray (`CgHit.Kind.UNLOADED`) and is left out of an overlap. A block is never
  loaded to answer.
- **Blocks answer as to no one** (as they do to particles) unless you say whose body is moving: `as(id)`.
  Scaffolding then holds up the entity standing on it, powder snow the entity in leather boots, exactly as Minecraft's
  movement asks them:

  ```java
  world.cast(body).at(x, y, z).along(vx, vy, vz, 1).as(mob).blocksToo().border().solid().first(hit);
  ```

- **The border** is met only when asked (`border()`), and only while the query's origin is inside it: projectiles and
  particles pass it in Minecraft.

### Line of sight and exposure

Two quick questions that need no query:

```java
boolean seen = world.lineOfSight(x, y, z, target);              // no block between the point and target's eyes
float exposed = world.exposure(x, y, z, target);                // how much of target the point sees, 0 to 1
```

Both are false and 0 for an entity that is not there. An unloaded cell blocks sight.

### Turning and moving

- `facing(yaw, pitch)` turns local +Z to a look, in radians; `rotation(q)` takes any JOML quaternion.
- A cast's or ray's direction comes from `along`; `facing` turns the shape itself (a box cast sideways, a cone
  sweeping forward).

### Nesting and threads

- A query inside another's visitor is fine, up to four deep:

  ```java
  world.overlap(BLAST).at(x, y, z).living().forEach(h ->
          world.ray().at(x, y, z).along(h.x() - x, h.y() - y, h.z() - z, h.distance()).blocksOnly().any());
  ```

- Never keep a query in a field, and never start one and leave it unended: the next query on the thread resets it.

---

## 6. Hits: what a query or hitbox reports

Each thing met is a `CgHit`.

| Reading | Means |
|---|---|
| `kind()` | `ENTITY`, `HITBOX`, `BLOCK`, `FLUID`, `UNLOADED`, `BORDER`, `POINT` |
| `entity()` | Minecraft's entity met (a dragon's part included), or a hittable hitbox's owner, cast to the type you name; null otherwise |
| `parent()` | the whole entity: the dragon for one of its parts, else `entity()`; null otherwise |
| `entityId()`, `parentId()` | the same by network id, -1 otherwise: what crosses threads and the network |
| `hitbox()`, `hitboxPart()` | the hittable hitbox met, and which of its parts; null for any other kind |
| `blockX()`, `blockY()`, `blockZ()` | the cell met, for a terrain hit (`kind().isTerrain()`: block, fluid, border, unloaded) |
| `fluid()` | `CgWorldQuery.FLUID_WATER` or `FLUID_LAVA`, for a `FLUID` hit |
| `x()`, `y()`, `z()` | the point on the target, in the world |
| `normalX()`, `normalY()`, `normalZ()` | unit length, from the query toward the target: the way to knock it back |
| `distance()` | blocks: from the query's origin to the point, or how far a cast or ray travelled |
| `fraction()` | how far along a cast or ray, 0 to 1; 0 for an overlap |
| `exposure()` | how much of the target the origin sees, 0 to 1, when asked (`exposure()`); NaN otherwise |
| `part()` | the part that hit: a hitbox's, or a query's `part(...)`; null for neither |
| `falloff(inner, outer)` | 1 within `inner` blocks, 0 from `outer` on, linear between: damage that weakens with distance |

```java
switch (hit.kind()) {
    case ENTITY -> damage(hit.entity());
    case HITBOX -> parried(hit.entity());               // a shield its owner holds up (§9)
    case BLOCK -> chip(hit.blockX(), hit.blockY(), hit.blockZ());
    case FLUID -> splash(hit.x(), hit.y(), hit.z());
    default -> { }                                      // UNLOADED, BORDER, POINT: nothing to act on
}
```

Things to know:

- **For an overlap**, the point is the target's nearest to the query's origin, and the distance is to it: 0 when the
  origin is inside. **For a cast or ray**, the point is where it first touched, and the distance is how far it travelled.
- **The normal points from the query toward the target**: push the target along it to knock it back. A surface's own
  normal, facing a ray, is its negation.
- **Starting inside** something, a cast or ray hits it at distance 0, its normal against the motion.
- **A multipart entity is hit once**, by its nearest part: `entity()` is the part (damage it, and the dragon's head
  takes its multiplier), `parent()` the dragon.
- **A visitor's hit is the query's own**, valid during the call. Keep one with `new CgHit().set(h)`, or end the query
  with `first(hit)` / `into(hits)`.
- **`entity()` asks the level the hit came from**, on its thread. A snapshot's hit throws there instead: a worker may
  not touch an entity, so keep `entityId()` and look it up on the level's thread
  (`CgEntityQueries.of(level).entity(id)`).

### Many hits: `CgHits`

```java
private final CgHits hits = new CgHits();               // a field: allocates nothing once it has held as many

int n = world.overlap(BLAST).at(x, y, z).living().into(hits);
for (int i = 0; i < n; i++) damage(hits.get(i).entity(), 10f * hits.get(i).falloff(2, 6));
```

`get(i)` hands out the hit it holds, not a copy: it changes when the list is filled again. `size()`, `isEmpty()`,
`clear()`. Gathering hits from several queries yourself: `add()` a cleared hit to fill, `removeLast()`, `remove(i)`,
`sort()` (nearest first), `truncate(n)`.

### Parts

A part labels hits, so a listener can tell which piece struck. Declare each once, as a constant: parts are compared by
identity, and the name is for logs.

```java
public static final CgHitPart PART_BEAM = CgHitPart.of("beam"), PART_BLAST = CgHitPart.of("blast");

world.overlap(BLAST).at(x, y, z).part(PART_BLAST).into(hits);    // a query's hits labelled
if (hit.part() == PART_BLAST) ...
```

### Filling a hit yourself

An effect that reports where it burst, or a hit read back from the network, fills a `CgHit` by hand: where, then what.

```java
hit.setPoint(x, y, z, PART_BURST);                       // nothing there: a POINT, no normal or distance
hit.setPoint(x, y, z, PART_BOLT).setEntity(id, id);      // an entity met at a point
hit.setPoint(x, y, z, null).setBlock(bx, by, bz);        // a block met at a point
hit.setPlace(x, y, z, nx, ny, nz, distance, fraction);   // where alone, normal and distance included
```

- `setPoint` clears what was met and the normal; a "what" setter (`setEntity`, `setHitbox`, `setBlock`, `setFluid`,
  `setUnloaded`, `setBorder`) keeps the place. `setExposure`, `setPart`, `reset()`, `set(other)`.

### Hits over the network: `CgSentHit`

A hit as it crosses the wire: about 20 bytes, its part as an index into a list both sides hold. Hitbox types and VFX
cues send their hits this way; use it for an event of your own:

```java
static final CgNetEvent<CgSentHit> HIT = CgNetEvent.of("mymod:hit", CgSentHit.wire());
private final CgSentHit sent = new CgSentHit();
private final CgHit arrived = new CgHit();

obj.emit(HIT, sent.set(hit, PARTS.indexOf(hit.part())));                                  // server
type.onEvent(HIT, (obj, s) -> hear(s.read(arrived, s.part() < 0 ? null : PARTS.get(s.part()))));   // client
```

No normal or distance travels; a hittable hitbox arrives as its owner, without `hitbox()`.

---

## 7. Recipes

Each recipe is complete: copy it, change the numbers. `world` is `CgCollisionWorld.of(level)` on the server unless
said; `hit` and `hits` are fields (`new CgHit()`, `new CgHits()`).

### An explosion: falloff, cover and knockback

```java
static final CgShape BLAST = CgSphere.of(8);

world.overlap(BLAST).at(x, y, z).source(caster).living().exposure().forEach(h -> {
    float damage = 20f * h.falloff(2, 8) * h.exposure();          // weaker with distance, none behind full cover
    if (damage <= 0) return;
    LivingEntity e = h.entity();
    if (e == null) return;
    e.hurt(level.damageSources().explosion(null, caster), damage);
    e.push(h.normalX() * damage * 0.1, 0.3, h.normalZ() * damage * 0.1);   // away from the blast
});
```

### A cleave in front of the player

```java
static final CgShape CLEAVE = CgCone.of(4, Math.toRadians(50));     // 4 blocks, 100 degrees wide

double yaw = Math.toRadians(player.getYRot());
world.overlap(CLEAVE).at(player.getX(), player.getEyeY(), player.getZ()).facing(yaw, 0)   // level, ignoring pitch
        .source(player).living().visible()                  // nobody behind a wall
        .forEach(h -> damage(h.entity(), 7f));
```

### A hitscan gun that pierces two targets

```java
int n = world.ray().at(eyeX, eyeY, eyeZ).along(lookX, lookY, lookZ, 64)
        .source(shooter).blocksToo()                        // stops at the first block
        .into(hits);
int pierced = 0;
for (int i = 0; i < n && pierced < 3; i++) {
    CgHit h = hits.get(i);
    if (h.kind() == CgHit.Kind.ENTITY) { damage(h.entity(), 8f - 2f * pierced); pierced++; }
    else { sparkAt(h.x(), h.y(), h.z(), -h.normalX(), -h.normalY(), -h.normalZ()); break; }   // the wall, facing back
}
```

### A projectile you move yourself, every tick

A cast sweeps the whole step, so a fast projectile never skips what it passes:

```java
static final CgShape ARROW = CgSphere.of(0.15);

void tick() {
    double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
    if (world.cast(ARROW).at(x, y, z).along(vx, vy, vz, speed).source(owner).blocksToo().inflate(0.3).first(hit)) {
        x = hit.x(); y = hit.y(); z = hit.z();                       // stop where it touched
        if (hit.kind() == CgHit.Kind.ENTITY) stick(hit.entity()); else land(hit);
        return;
    }
    x += vx; y += vy; z += vz;
    vy -= 0.05;                                                      // gravity
}
```

### What the player is looking at

```java
if (CgCollisionWorld.client(partialTick).ray().at(eyeX, eyeY, eyeZ).along(lookX, lookY, lookZ, 5)
        .source(me).blocksToo().inflate(0.1).first(hit)) {
    if (hit.kind() == CgHit.Kind.ENTITY) outline(hit.entity());     // where the frame draws it
    else highlight(hit.blockX(), hit.blockY(), hit.blockZ());
}
```

### Is anyone standing here? Can I teleport there?

```java
static final CgShape BODY = CgBox.of(0.3, 0.9, 0.3);               // a player-sized box, centred

boolean clear = !world.overlap(BODY).at(tx, ty + 0.9, tz).blocksToo().solid().any();
// or with the player's own box, moved:
box.set(player).translate(tx - player.getX(), ty - player.getY(), tz - player.getZ());
boolean free = !world.overlap(box).blocksOnly().any();
```

### The ground under something

```java
if (world.ray().at(x, y, z).along(0, -1, 0, 16).blocksOnly().first(hit)) groundY = hit.y();   // a slab's top, a stair's step
boolean standing = world.cast(BODY).at(x, y + 0.9, z).along(0, -1, 0, 0.05).blocksOnly().any();
```

### Only enemies

Teams and PvP are your game's to decide; `where` asks you:

```java
world.overlap(AURA).at(x, y, z).living()
        .where((Entity e) -> !e.isAlliedTo(caster))                 // handed the entity; take Entity, narrow inside
        .forEach(h -> curse(h.entity()));

world.overlap(BLAST).at(x, y, z).kinds(1 << CgEntityQuery.KIND_MONSTER).forEach(...);   // monsters only
```

### Aim assist

```java
// the nearest living thing within half a block of the crosshair line, 32 blocks out
if (world.ray().at(eyeX, eyeY, eyeZ).along(lookX, lookY, lookZ, 32).source(me).living().inflate(0.5)
        .visible().first(hit)) snapTo(hit.entity());
```

### An AI that only attacks what it can see

```java
boolean canSee = world.lineOfSight(mob.getX(), mob.getEyeY(), mob.getZ(), target);
float cover = world.exposure(bomb.x, bomb.y, bomb.z, target);           // 0.3: mostly behind something
```

### Pull everything in, then blast it

Queries nest, and a hit's normal points away from the query:

```java
world.overlap(VORTEX).at(x, y, z).living().forEach(h -> pull(h.entity(), -h.normalX(), -h.normalY(), -h.normalZ()));
```

### A beam that hits everything along it

```java
static final CgShape BEAM = CgCapsule.between(0, 0, 0, 0, 0, 30, 0.5);   // 30 blocks along +Z from the origin

world.overlap(BEAM).at(muzzleX, muzzleY, muzzleZ).facing(yaw, pitch).source(caster).living()
        .forEach(h -> burn(h.entity()));
// a beam that stops at the first wall is a cast instead:
world.cast(CgSphere.of(0.5)).at(muzzleX, muzzleY, muzzleZ).along(lookX, lookY, lookZ, 30).blocksOnly().first(hit);
```

### Water

```java
if (world.ray().at(x, y, z).along(0, -1, 0, 32).fluids().first(hit) && hit.kind() == CgHit.Kind.FLUID) {
    boolean lava = hit.fluid() == CgWorldQuery.FLUID_LAVA;
    splash(hit.x(), hit.y(), hit.z(), lava);
}
```

---

## 8. Hitboxes: volumes that live over time

A query is one question at one moment. A **hitbox** is a volume that lives: it grows, turns, follows an entity, and
each tick tests everything it swept since the last, so nothing fast slips past it. Its **rules** decide which touches
are hits: each target once by default.

```java
CgHitbox.of(CgShell.front(2)).at(x, y, z)          // a shape, placed
        .radius(0, 10, 8)                          // its size over time
        .activeFor(8)                              // how long it lives, in game ticks
        .source(caster)                    // whose it is
        .query(q -> q.living())                    // what it may hit, as a query says it
        .onHit(h -> damage(h.entity(), 6f))        // what to do: each target once
        .start(level);                             // the engine ticks it on the level's thread
```

### Its life

1. **Configure** it: shape or parts, placement, sizes, rules, listeners. All before its first tick: parts and sizes
   are fixed from then.
2. **Start** it: `start(level)` (a Minecraft level, server or client), and the engine ticks it after each of the
   level's ticks. Or tick it yourself ([below](#ticked-by-its-owner)).
3. It hits what its rules allow, each tick.
4. **It ends**: after `activeFor` ticks, on `stop()`, when the entity it follows goes, when its level unloads.

- **A hitbox runs once.** Starting it again throws (`IllegalStateException`); make a new one.
- **A hitbox started on a server runs only there**; one started on a client only there. To run one on both, see
  [§10](#10-hitboxes-on-every-side-hitbox-types).
- **Open-ended until `stop()`** unless `activeFor` is given: a zone that lasts.
- **The hit handed to a listener is valid during the call.**

### Time

Game ticks, fractional allowed:

| Call | Means |
|---|---|
| `activeFrom(t)` | hits nothing for its first `t` ticks: a wind-up |
| `activeFor(t)` | stops after `t` active ticks |
| `rehit(t)` | a target may be hit again `t` ticks after its last hit |
| `radius(from, to, t)`, `scale(from, to, t)` | sizes over the first `t` active ticks |
| `age()` | ticks since it started |

### Placing it

| Call | Places it |
|---|---|
| `at(x, y, z)` | fixed at a point |
| `follow(entity, point)` | on an attachment point of an entity, moving with it every tick; that entity is its `source` unless given |
| `facing(yaw, pitch)`, `rotation(q)` | a fixed turn, as a pose's |
| `facing(Facing.LOOK)` | where the followed entity looks |
| `facing(Facing.LOOK_LEVEL)` | where the followed entity looks, held level: a cleave that ignores the pitch |
| `facing(Facing.BODY)` | where the followed entity's body faces, level |
| `facingOnce(Facing...)` | as `facing`, taken at its first tick and held: a swing turning after cannot drag round |
| `pose(pose)` | at a pose you compute, set before each tick (an owner-ticked hitbox) |

Attachment points (`CgEntityAttachments`): `FEET`, `CENTRE`, `CHEST`, `HEAD`, `EYES`, `RIGHT_HAND`, `LEFT_HAND`,
`RIGHT_HAND_FORWARD`, `LEFT_HAND_FORWARD`, `HANDS_FORWARD`, and points your mod declares (`docs/VFX.md` §4), among
them a point at a share of the body's height. On a client a hitbox sits where the point is drawn: a model that marks
its hand (`CgEntityAttachments.mark`) moves the swing with the animation, on every client that draws it. A server draws
nothing, so it places from the entity's proportions; for a claimed hitbox the padding covers the difference.

```java
static final int STRIKE = CgEntityAttachments.declareAtHeight("mymod:strike", 0.7);   // seven tenths up, scaling

// a blow 3 deep from the strike point, aimed where the attacker looked as it began
CgHitbox.follow(attacker, STRIKE).facingOnce(CgHitbox.Facing.LOOK_LEVEL).activeFor(1).maxTargets(3)
        .part(BLOW, CgBox.of(0.75, 0.75, 1.5), 0, 0, 1.5)
        .query(q -> q.attackable().visible())                    // seen from the attacker's eyes, not the box's middle
        .onHit(h -> damage(h.entity(), 6f))
        .start(level);
```

```java
CgHitbox.follow(player, CgEntityAttachments.CHEST)                  // an aura round the player
        .part(AURA, CgSphere.of(3))
        .rehit(20)                                                  // each target once a second
        .onHit(h -> burn(h.entity()))
        .start(level);
```

### Parts

A hitbox of one shape: `CgHitbox.of(shape)` (its hits have no part) or `CgHitbox.of(PART, shape)`. A hitbox of several:
`follow(...)` or `of(...)`, then `part(...)` for each.

```java
static final CgHitPart TIP = CgHitPart.of("tip"), BLADE = CgHitPart.of("blade");

CgHitbox.follow(player, CgEntityAttachments.RIGHT_HAND_FORWARD).facing(CgHitbox.Facing.LOOK).activeFor(5)
        .part(TIP, CgSphere.of(0.35), 0, 0, 1.1)        // offset in the hitbox's own space, +Z forward
        .part(BLADE, CgCapsule.of(0.5, 0.3))            // at its origin
        .onHit(h -> damage(h.entity(), h.part() == TIP ? 9f : 5f))
        .start(level);
```

- **The first part listed wins** a tick in which several touch one target, and `h.part()` names it.
- A target is one target across parts: the tip and the blade together still hit each mob once.

### Sizes over time

```java
CgHitbox.of(CgShell.front(2)).at(x, y, z).radius(0, 10, 8)          // a blast front running out to 10 over 8 ticks
CgHitbox.of(CgRing.front(1.2, 0.6)).at(x, y, z).radius(0, 12, 12)   // a ground wave
CgHitbox.of(CgBox.of(1, 0.5, 1)).at(x, y, z).scale(1, 4, 10)        // a slam widening to 4 times its size
CgHitbox.of(CgSphere.of(1)).at(x, y, z)
        .radius(CgKeyframes.start(0f, 0f).to(4f, 6f, CgEasings.OUT_QUAD).to(12f, 2f, CgEasings.LINEAR).build())
                                                                    // swells to 6 by tick 4, shrinks to 2 by tick 12
```

- **A radius** applies to every shape with one: a sphere, cylinder, capsule, cone (its range), shell or ring (its
  outer edge). A front keeps its width; any other shell or ring keeps its proportions. **A box has none**: use `scale`.
- **What it swept is tested**: a front growing from 5 to 6 in one tick hits everything between 3 and 6, never
  skipping a mob it passed. A part moving fast, or turning (a 170 degree swing in one tick), is followed along its arc.

### Rules: who is hit, how often

| Call | Rule |
|---|---|
| *(nothing)* | each target once |
| `rehit(t)` | again, `t` ticks after its last hit |
| `maxHits(n)` | at most `n` hits on one target, with `rehit` |
| `maxTargets(n)` | no new target once `n` have been hit, nearest first: a pierce count |
| `hitList(list)` | its hits kept in a `CgHitList` shared with other hitboxes |
| `source(entity)` | never its owner, their parts, or anyone riding with them; the entity it follows unless given |
| `query(q -> ...)` | what it may hit, as a query says it ([§5](#what-a-query-may-hit)) |

```java
// a flamethrower: each target burned twice a second, at most six times
CgHitbox.follow(player, CgEntityAttachments.HANDS_FORWARD).facing(CgHitbox.Facing.LOOK)
        .part(FLAME, CgCone.of(6, Math.toRadians(20)))
        .rehit(10).maxHits(6)
        .query(q -> q.living().visible())
        .onHit(h -> burn(h.entity()))
        .start(level);

// a spear thrust that pierces the three nearest
CgHitbox.follow(player, CgEntityAttachments.RIGHT_HAND_FORWARD).facing(CgHitbox.Facing.LOOK).activeFor(4)
        .part(SPEAR, CgCapsule.between(0, 0, 0, 0, 0, 3, 0.25)).maxTargets(3)
        .onHit(h -> damage(h.entity(), 7f))
        .start(level);

// a combo: three moves, each mob hit once between them
CgHitList combo = new CgHitList();
CgHitbox.of(SLASH).at(x, y, z).hitList(combo).onHit(this::damage).start(level);
CgHitbox.of(THRUST).at(x, y, z).hitList(combo).onHit(this::damage).start(level);   // never the same target twice
```

- **`query(...)` only configures**: it runs on every sub-step, so it must set filters and nothing else. Its query is
  already placed and named for the hitbox's source.
- **A hitbox's targets are entities and hittable hitboxes**: a block its query meets (with `blocksToo()`) does not
  count as a hit; ask a query for blocks.
- **A `CgHitList` is keyed by the whole entity**, and its times are the asking hitbox's age: hitboxes sharing one
  should start together. `contains(id)`, `count(id)`, `lastHit(id)`, `size()`, `clear()`.

### Listening

| Listener | Hears |
|---|---|
| `onHit(h -> ...)` | each hit its rules allow, nearest first |
| `onEnter(h -> ...)` | a target touching it that did not the tick before |
| `onExit(id -> ...)` | a target that touched it the tick before and no longer does, or was inside when it stopped |

```java
// a healing zone, told who comes and goes until stopped
CgHitbox zone = CgHitbox.of(CgCylinder.of(4, 2)).at(x, y + 2, z)
        .query(CgCollisionQuery::living)
        .onEnter(h -> startHealing(h.entity()))
        .onExit(id -> stopHealing(id))
        .start(level);
// later
zone.stop();                                         // exits fire for everyone still inside
```

### Ticked by its owner

An effect, an entity or a machine of yours can tick a hitbox itself, on frames as well as ticks: never `start()` it,
call `tick` instead.

```java
private final CgHitbox slam = CgHitbox.of(SLAM).radius(0, 6, 8).onHit(this::hurt);
private final CgPose pose = new CgPose();

void tick(float deltaTicks) {                        // a fraction of a tick on a frame, 1 on a tick
    slam.pose(pose.at(x, y, z).facing(yaw, 0)).tick(CgCollisionWorld.of(level), deltaTicks);
}
void remove() { slam.stop(); }
```

### Drawing its outline

`parts(sink)` hands each part where its last tick placed it, sized as it was then: what a debug view draws
([§14](#14-seeing-it)), or your own outline.

```java
slam.parts((name, shape, pose) -> drawOutline(shape, pose));
```

---

## 9. Shields, wards and weak spots: hittable hitboxes

A **hittable** hitbox is met by queries as if it were an entity, standing for its owner: every ray, cast, overlap and
other hitbox meets it, nearest first. A shield held in front of a player stops a sword's swing, and the swing counts the
player as hit, through the shield, as a `HITBOX` hit.

```java
static final CgHitPart SHIELD = CgHitPart.of("shield");

CgHitbox shield = CgHitbox.follow(player, CgEntityAttachments.HANDS_FORWARD).facing(CgHitbox.Facing.LOOK)
        .part(SHIELD, CgBox.of(0.6, 0.7, 0.1))
        .hittable()                                   // queries meet it
        .passive()                                    // it hits nothing itself
        .start(level);

// anything that strikes tells a parried hit from a real one
swing.onHit(h -> {
    if (h.kind() == CgHit.Kind.HITBOX) parried(h.entity(), h.hitboxPart());   // h.entity() is the shield's owner
    else damage(h.entity(), 6f);
});
// later: the player lowers it
shield.stop();
```

Rules:

- **It needs an owner**: the entity it follows, else its `source`. Its first tick throws without one.
- **It is met while active and until it stops.** An owner ticking one itself must stop it.
- **Its owner's queries never meet it**, nor queries excepting its owner. Filters a query asks of an entity
  (`living()`, `kinds`, `where`) are asked of its owner; `inflate` does not grow it.
- **A query asking `noHitboxes()` or `blocksOnly()` passes through it**: a heal that goes round shields, a ground
  check.
- **A hitbox meeting it first** counts its owner as hit by the hittable, not the body: the shield takes the blow, and
  the body behind it is not hit again by that hitbox.
- **`passive()`** makes any hitbox hit nothing: it only moves and sizes. Its listeners never hear.

A weak spot: a hittable hitbox on a boss's head that doubles damage.

```java
CgHitbox.follow(boss, CgEntityAttachments.HEAD).part(WEAK, CgSphere.of(0.6)).hittable().passive().start(level);

arrowQuery.forEach(h -> damage(h.entity(), h.kind() == CgHit.Kind.HITBOX && h.hitboxPart() == WEAK ? 2 * dmg : dmg));
```

### A world of your own shapes alone

`CgCollisionWorld.empty()` is a world with no level's blocks or entities: only the hittable hitboxes ticked in it,
each standing for its `source`. Use it for queries that should meet only your own shapes (a minigame's targets, a
puzzle's mirrors, a boss's armour plates), in the game beside a level's world, or in a test with no level at all.

```java
CgCollisionWorld arena = CgCollisionWorld.empty();
CgHitbox target = CgHitbox.of(CgSphere.of(0.5)).source(TARGET_ID).hittable().passive();
target.pose(pose.at(4, 1, 0)).tick(arena, 1);                    // placed, and met from now on

if (arena.ray().at(0, 1, 0).along(1, 0, 0, 10).first(hit)) score(hit.entityId());   // TARGET_ID
```

- Its hitboxes are met by queries on the thread that ticks them.
- Entity filters (`living()`, `kinds`) pass everything: there are no entities to ask.

---

## 10. Hitboxes on every side: hitbox types

A **hitbox type** is a hitbox the server starts and every client that sees it runs too:

- **the server's hits are the real ones**: damage happens there, and only there;
- **each client's own copy hits at once**, against the targets where that client sees them, for what must feel
  instant (sparks, a hit-stop);
- **the server's hits reach every client**, for what must be true (a hit marker, a kill feed).

It is a networked object type ([`NETWORKING.md`](NETWORKING.md) §6) whose objects are hitboxes, built on every side by
the same factory.

```java
// common, at init: both sides run this
public static final CgNetField<Float> RADIUS = CgNetField.ofFloat("radius");
public static final CgHitboxType SLAM = CgHitboxType.define("mymod:slam").fields(RADIUS)
        .hitbox(obj -> CgHitbox.of(CgRing.front(1.2, 0.6)).radius(0, obj.getFloat(RADIUS), 12)
                .query(CgCollisionQuery::living))
        .build();

SLAM.onHit((obj, h) -> damage(h.entity()));                         // server, at init: the only place damage happens

// client, at init, from client code
SLAM.onPredictedHit((obj, h) -> sparks(h.x(), h.y(), h.z()));       // this client's own copy, at once
SLAM.onConfirmedHit((obj, h) -> hitMarker(h.entity()));             // the server's hits, as they arrive

// server, whenever
SLAM.spawn(CgAnchor.at(level, x, y, z)).source(caster).setFloat(RADIUS, 12f).start();
```

### Declaring one

| Call | Does |
|---|---|
| `CgHitboxType.define("namespace:path")` | starts the declaration |
| `.fields(...)` | its own fields, read by the factory: a radius, a level, a colour |
| `.hitbox(obj -> ...)` | what it is on every side: a hitbox built from the object, not yet started |
| `.range(blocks)` | seen by players within that range of a point anchor; the whole dimension without one |
| `.outlivesSource()` | keeps running when its source is gone: a trap that outlives its maker |
| `.build()` | done: declare at init, as a constant, in common code |

- **The factory must build the same hitbox on every side** from the object's fields: shape, sizes, rules. Placement,
  facing and source come from the spawn, not the factory.
- **Hits are heard on the type** (`onHit`, `onPredictedHit`, `onConfirmedHit`), never through the hitbox's own
  listeners: give the factory's hitbox none.
- Install `onPredictedHit` and `onConfirmedHit` from client code.

### Spawning one

```java
SLAM.spawn(CgAnchor.at(level, x, y, z)).source(caster).setFloat(RADIUS, 12f).start();   // at a point

SWING.spawn(CgAnchor.on(player)).point(CgEntityAttachments.RIGHT_HAND_FORWARD)            // on an entity
        .facing(CgHitbox.Facing.LOOK).source(player).start();
```

| Spawn call | Does |
|---|---|
| `source(entity)`, `source(networkId)` | whose it is: never hit, with its vehicle and passengers; it ends with them |
| `point(attachmentPoint)` | on an entity anchor, the point it sits at; its feet by default |
| `facing(Facing)`, `facing(yaw, pitch)`, `rotation(q)` | which way it turns, over the factory's choice |
| `set(field, value)`, `setInt`, `setFloat`, `setDouble`, `setBool` | its own fields, read by the factory on every side |
| `start()` | live: running here, and on every viewer from the end of this tick; returns the `CgNetObject` |

- **It ends** when its hitbox does (`activeFor`), when its anchor's entity goes, when its source goes (unless
  `outlivesSource()`), or on `despawn()`: gracefully, so the last tick's hits still arrive.
- **Moved by the server**: a point-anchored hitbox the server steers each tick takes
  `CgHitboxType.pose(obj, pose)`; the move reaches viewers as that tick's change.
- `CgHitboxType.hitbox(obj)` is this side's hitbox of an object (null before its first tick here).

### Late viewers

A player who comes into view after the spawn catches up silently: their copy re-runs the last two seconds with no
listener, so no stale sparks. One arriving within half a second of the spawn (the delivery's own delay) replays it
aloud instead.

### The attacker's own client, at once

The client that swings can show its hitbox before the server answers, when the server allows it:

```java
SWING.acceptPredictionsFrom((peer, obj) -> obj.entityId() == entityOf(peer));            // server, at init

SWING.predict(CgAnchor.onEntity(myId)).point(CgEntityAttachments.RIGHT_HAND_FORWARD)      // client
        .facing(CgHitbox.Facing.LOOK).source(myId).start();
```

Accepted, the server's copy takes over the prediction and its hits are the real ones; refused, the client's copy plays
out alone and damages nothing.

### Melee at any ping: claimed hitboxes

A prediction shows the swing at once, but the server still judges it against targets that have moved on since the
attacker saw them: at 150 ms a blade passes through a mob and nothing happens. A **claimed** type turns that round,
as Minecraft's own attack packet does: the attacker's client decides what its swing hit, and the server checks each
claim.

```java
// common, at init: both sides
public static final CgHitboxType PUNCH = CgHitboxType.define("mymod:punch")
        .hitbox(obj -> CgHitbox.of(CgSphere.of(0.45)).activeFor(6).query(CgCollisionQuery::living))
        .claimed()                                    // optional: .claimWindow(ticks), .claimPadding(blocks)
        .build();

PUNCH.acceptPredictionsFrom((peer, obj) -> obj.entityId() == CgEntityQueries.idOf(peer.player()));   // server
PUNCH.onHit((obj, h) -> damage(obj.owner(), h.entity()));                     // server: each claim it accepts
PUNCH.onRefusedClaim((obj, claim) -> Anticheat.note(obj.owner(), claim.reason()));   // server, optional

// client, from client code
PUNCH.onPredictedHit((obj, h) -> { sparks(h.x(), h.y(), h.z()); HitStop.play(); });   // at once
PUNCH.onConfirmedHit((obj, h) -> damageNumber(h.entity()));                           // the server's word
PUNCH.onRefusedHere((obj, claim) -> HitStop.cancel());                                 // its owner alone

// client, on the attack key
PUNCH.predict(CgAnchor.onEntity(me)).point(CgEntityAttachments.RIGHT_HAND_FORWARD).facing(CgHitbox.Facing.LOOK)
        .source(me).start();
```

- **Only the owner's copy claims**: the predicting player, or the server spawn's `owner(player)`, or a player given as
  `source(player)`. Every other viewer's copy is cosmetic, as for any hitbox type. The server's copy hits nothing
  itself: it keeps where the hitbox was, to check against.
- **With no owner, the server hits** as for an unclaimed type, so players and mobs share one type and one call:
  `STRIKE.spawn(CgAnchor.on(attacker)).source(attacker).start()` claims for a player and hits on the server for a mob.
- **A claim counts** when its target is alive (`Entity.isAlive`: armour stands and boats too), its age is within the
  window of the server's copy (20 ticks unless `claimWindow` says), the target's box grown by the padding (3 blocks
  unless `claimPadding` says, Forge's attack padding) touched where the hitbox was over the window, and the hitbox's own
  query and rules allow it there: `living`, the source, `rehit`, `maxHits`, `maxTargets`. A claim never hits twice what
  the hitbox hits once. `visible()` looks from the attacker's eyes as they were at the claim's tick.
- **Fast targets**: `claimPaddingPerSpeed(ticks)` adds the target's speed (how far it moved last tick) times `ticks` to
  the padding, so an honest claim on a flier lands while the padding for a still target stays tight.
- **Accepted**, the hit lands on the target's body where the server sees it, so every viewer's spark is on the body
  they see. It reaches `onHit` and the viewers' `onConfirmedHit`, as a server hit does. Inside `onHit`,
  `PUNCH.ticksLate(obj)` is how many ticks ago it was made, to judge a guard or a dodge as of the swing.
- **Refused**, it is counted by reason (`PUNCH.refusedClaims(CgHitClaim.Refusal.OUT_OF_RANGE)`), heard by
  `onRefusedClaim`, and sent to its owner alone for `onRefusedHere`. The reasons, in the order they are checked:
  `NO_TARGET`, `OUT_OF_WINDOW`, `OUT_OF_RANGE`, `RULE`. A claim from someone else, or past the rate, is refused by
  the command layer first (`NETWORKING.md` §9).
- **A prediction's claims wait** until the server makes it real, then go. A refused prediction's never leave, and its
  owner hears each in `onRefusedHere` as `PREDICTION_REFUSED`. One player's predictions are judged in the order sent.
- **The server's sizes, not the client's**: the accept rule runs before the server's factory, with the client's fields
  written, so a field it sets is what the server's hitbox is built from. What the hit belongs to rides along with
  `obj.local(...)`, kept on its side and never sent:

```java
PUNCH.acceptPredictionsFrom((peer, obj) -> {
    obj.setFloat(REACH, reachOf(peer.player())).local(swingOf(peer));
    return true;
});
PUNCH.onHit((obj, h) -> obj.<Swing>local().land(h, PUNCH.ticksLate(obj)));
```

- **The server's copy outlives its hitbox by the window**, so a slow connection's claim still finds it.
- **It trusts the attacker within the padding.** The stronger answer, the server rewinding every target to where the
  attacker saw it (Valve's lag compensation), is not built: it needs every entity's past positions every tick.
- A dev run feels the latency with `-Dcrystalgraphics.net.latency=<ms>` on each process ([`DEBUG_FLAGS.md`](DEBUG_FLAGS.md)).

---

## 11. Effects that hit

A VFX effect strikes through this same engine. Inside an effect (`CgVfxEffect`):

- **`world()`** is the effect's world: the server's level for an authoritative effect, the client's frame otherwise.
- **`hitbox(part, shape)`** makes a hitbox the effect owns: ticked with it after each `tick`, stopped when it dies, its
  hits the effect's own, heard by `onHit(part, ...)`.

```java
public static final CgHitPart PART_BOLT = SCHEMA.part("bolt"), PART_BLAST = SCHEMA.part("blast");

@Override protected void tick(float dt) {
    if (world().cast(BOLT).at(x, y, z).along(dx, dy, dz, 64).source(followingId()).blocksToo().part(PART_BOLT).first(hit)) {
        hit(hit);                                                   // reported as the effect's hit
        hitbox(PART_BLAST, CgShell.front(2)).at(hit.x(), hit.y(), hit.z()).radius(0, 10, 8).activeFor(8)
                .source(followingId());                               // no listener: its hits are the effect's
    }
}
```

The whole story (parts, authority on the server, confirmed hits on clients) is [`VFX.md`](VFX.md) §8.

---

## 12. Many queries a tick, and off the main thread

### Batches: many queries sharing what they learn

Many queries in one tick (a hundred spells, a thousand hitboxes) each ask Minecraft for the entities in their area. A
**batch** shares the answers: past its first 160 asks, each 16-block cell is asked of Minecraft once, and each entity's
box and flags read once.

```java
try (CgCollisionWorld.Batch ignored = CgCollisionWorld.batch()) {
    for (Spell s : spells) world.overlap(s.shape).at(s.x, s.y, s.z).forEach(s::hit);
}
```

- **Hitboxes the engine ticks, and effects, already run in one.** Batch your own loops of many queries.
- **What a hit changes is seen**: an entity handed to a listener or a `forEach` is asked of Minecraft again for the
  rest of the batch, so what a hit knocks back or kills is seen where it now is. Code that moves any *other* entity
  inside a batch says so: `world.changed(id)`, or `world.changed()` for all of them.

  ```java
  try (CgCollisionWorld.Batch ignored = CgCollisionWorld.batch()) {
      world.overlap(PULL).at(x, y, z).forEach(h -> pull(h.entity()));   // re-read already
      teleport(bystander);
      world.changed(bystander);
      world.overlap(BLAST).at(x, y, z).forEach(this::hurt);              // sees the bystander where it went
  }
  ```

- Batches nest; the outermost decides. Opening one allocates nothing. Measured: 1,000 overlaps over 200 entities about
  30% faster batched; a hundred, no faster. Use it for many, not a few.

### Off the main thread: snapshots

Work too heavy for a tick (a hundred debris pieces bouncing for a second, a search for safe ground) can run on a
worker while the game moves on. A worker may not touch the level; it reads a **snapshot**: the blocks and entities in
an area as they stood at the end of a server tick, which any number of threads may query at once.

The work is a plain method that takes the snapshot and your own values, and returns new values:

```java
static final CgShape PEBBLE = CgSphere.of(0.1);
record Debris(double x, double y, double z, double vx, double vy, double vz) { }

// the worker's half: every piece moved one tick through the frozen area, into a new list
static List<Debris> simulate(CgCollisionWorld area, List<Debris> pieces) {
    List<Debris> next = new ArrayList<>(pieces.size());
    CgHit hit = new CgHit();
    for (Debris d : pieces) {
        double speed = Math.sqrt(d.vx() * d.vx() + d.vy() * d.vy() + d.vz() * d.vz());
        if (speed > 0 && area.cast(PEBBLE).at(d.x(), d.y(), d.z()).along(d.vx(), d.vy(), d.vz(), speed).blocksOnly().first(hit)) {
            next.add(new Debris(hit.x(), hit.y(), hit.z(), 0, 0, 0));          // landed where it touched
        } else {
            next.add(new Debris(d.x() + d.vx(), d.y() + d.vy(), d.z() + d.vz(), d.vx(), d.vy() - 0.04, d.vz()));
        }
    }
    return next;
}
```

The level's thread asks for the area, hands the work its own copy of the pieces, and keeps what comes back:

```java
List<Debris> copy = List.copyOf(pieces);                         // the worker's own: this field may change meanwhile

CgCollisionWorld.of(level).snapshot(x, y, z, 24)                 // CgReply<CgCollisionWorld>
        .flatMap(area -> CgJob.run(() -> simulate(area, copy)))  // area: the frozen world; simulate runs on a worker
        .then(next -> pieces = next);                            // next: simulate's List<Debris>, on this thread
```

`snapshot` answers with a reply, not a world: at once when the area is already mirrored, else at the end of a later
tick, once it is. `flatMap` starts the job when the area arrives; `then` hears the job's answer.

**A simulation that runs every tick watches its area** instead of asking each time: the watch keeps it, and `latest()`
is the newest tick's world.

```java
CgCollisionWorld.Watch arena = CgCollisionWorld.of(level).watch(ARENA);   // once
CgCollisionWorld area = arena.latest();                                   // any thread: the newest tick's, or null at first
arena.move(next);                                                         // a boss walking: any thread
arena.close();                                                            // when done
```

- **A server level keeps one mirror** of every area anyone asked for, updated as blocks change, so a hundred
  simulations cost the tick what one does. What the tick pays: a few nanoseconds per block written in a watched area,
  up to 0.25 ms a tick warming new areas, and about 0.5 µs per entity inside watched areas, read once at the tick's
  end. Copying, shapes and tables run on a worker.
- **A snapshot is one instant**: every block and entity as they stood at the end of the same server tick, never a mix.
- **A cold area takes a few ticks** to warm: copying is spread over ticks (`crystalgraphics.mirror.baseBudget`, 250 µs a
  level a tick), nearest first. `watch.latest()` meanwhile reads the columns not yet mirrored as unloaded, and
  `watch.complete()` says when it is whole; `watch.ready()` is a reply for that moment.
- **A one-off's area is kept** while it is asked for again, and let go 200 ticks after the last ask
  (`crystalgraphics.mirror.idleTicks`). Mirrors share 64 MB (`crystalgraphics.mirror.memory`); past it the longest
  idle one-offs go first, and a watch larger than that alone is refused.
- **Outside the snapshot there is nothing**, and every cell is loaded: a cast leaving it flies through air. A column
  inside it that is not loaded reads as unloaded.
- **It knows the entities in its box**, and the whole entities their parts belong to, where they stood at the tick's
  end. Any other id reads as none.
- **A snapshot holds hittable hitboxes as they were** at the tick's end.
- **Blocks in a snapshot answer as to no one** (`as(id)` is not kept): what scaffolding shows an entity depends on where
  it is when it asks.
- **A world with no mirror copies on the spot**, on its thread, and its reply is settled already: a client's level, a
  test's fakes, a level whose write hook did not apply. That copy costs the level's thread (on Forge 1.20.1 about 4 ms
  for a radius of 32, 26 ms for 60) and stops at 128 blocks a side (`CgBlockSnapshot.MAX_SIDE`).
- A snapshot of a snapshot is itself; `snapshot(bounds)` takes a box instead.

Jobs, keys, repeating simulations and splitting work across threads are their own guide: [`JOBS.md`](JOBS.md).

---

## 13. Exact tests with no world

`CgShapes` tests placed shapes against each other directly: no level, no entities, any thread. It is what queries and
hitboxes run on each candidate.

```java
private final CgPose a = new CgPose(), b = new CgPose();
private final CgContact contact = new CgContact();
private final CgCastHit cast = new CgCastHit();

boolean touching = CgShapes.overlaps(BLAST, a.at(x, y, z), WARD, b.at(wx, wy, wz));
boolean inBox = CgShapes.overlaps(CLEAVE, a.at(ex, ey, ez).facing(yaw, pitch), entityBox);   // a CgAABBc
double gap = CgShapes.distance(BLAST, a, WARD, b);                                          // 0 when they overlap

if (CgShapes.contact(BLADE, a, HEAD, b, contact)) push(contact.normal, contact.depth);      // move b by depth to separate
if (CgShapes.castRay(WARD, b, eyeX, eyeY, eyeZ, lookX, lookY, lookZ, 32, cast)) spark(cast.point, cast.normal);
if (CgShapes.cast(BOLT, a, vx, vy, vz, WARD, b, cast)) stopAt(cast.fraction);               // the bolt moving by v
if (CgShapes.cast(BOLT, a, vx, vy, vz, entityBox, cast)) ...                                 // against a box
```

| Test | Answers | Takes |
|---|---|---|
| `overlaps(a, poseA, b, poseB)`, `overlaps(shape, pose, box)` | whether they touch | any pair except two non-convex shapes |
| `distance(a, poseA, b, poseB)` | the gap, 0 when overlapping | convex shapes and compounds of them |
| `contact(a, poseA, b, poseB, out)` | the deepest points and the way to separate them; false when only touching | convex shapes and compounds |
| `castRay(shape, pose, origin, dir, max, out)` | the first point along a ray | any shape, shells and rings included |
| `cast(a, poseA, move, b, poseB, out)`, `cast(shape, pose, move, box, out)` | where a moving shape first touches | convex shapes and compounds |

- **`CgContact`**: `normal` (unit, from a toward b), `depth`, `pointA`, `pointB` (on each surface, in the world).
- **`CgCastHit`**: `point`, `normal` (facing the ray or mover), `distance`, `fraction` (of the ray's maximum, or of the
  motion). Starting inside: distance 0, normal against the motion.
- **A cast with no motion** is an overlap test: a hit at 0 with a zero normal.
- A compound answers for its nearest child, its deepest, or its first touched.
- A wrong pair throws `IllegalArgumentException`, naming the shape.
- Allocates nothing past each thread's first call; safe from any thread. The engine underneath is a port of Jolt
  Physics' GJK and EPA, with closed forms for the common pairs.

---

## 14. Seeing it

### Every query and hitbox, at once

Press **F3+B** in Minecraft (its own hitbox view), or turn on `crystalgraphics.collision.show`:

```
-Dcrystalgraphics.collision.show=true        // or from the console, or CgCollisionShow.SHOW.set(true)
-Dcrystalgraphics.collision.style=transparent     // hitboxes filled in; wireframe, the default, draws their edges alone
-Dcrystalgraphics.collision.linger=250            // a stopped hitbox kept 250 ms, fading; none by default
```

| You see | Colour |
|---|---|
| This client's hitboxes | green |
| This client's queries | blue, red where they stop and what they hit (a cross and the normal) |
| The server's hitboxes and queries | orange |
| A hitbox given `showColour(argb)` | that colour, on every side: what its hits mean at a glance |
| The way each hitbox faces | a blue arrow, a block and a half from its first part, on every side: F3+B's look line for hitboxes |

- **Where green and orange part, a prediction is off**: the client's copy is not where the server's is.
- A cast is drawn where it starts and where it stops, joined; a ray as its path; an overlap as its shape.
- A query a hitbox runs is left out: its parts are drawn instead. Queries on an empty world are not drawn.
- `crystalgraphics.collision.style` is how each client draws hitboxes, and the shapes `CgCollisionDebug` draws:
  `wireframe` (the default) draws the edges alone, `transparent` fills the faces in see-through under them at a
  quarter of their colour's alpha. A player's setting, kept in their settings file.
- A hitbox that should read as a volume for everyone fills itself with `showFill(alpha)`, whatever each player's
  style: its faces take that share of its colour's alpha, 0 for its edges alone. Unset, it follows the style.

  ```java
  CgHitbox.follow(attacker, STRIKE).showColour(0xFF4040).showFill(0.25f)...   // red, filled for every player
  ```
- Hitboxes are drawn as their last tick tested them, and only while active: what is drawn could still hit. A blow too
  short to see can linger, fading, so it reads as over: `showLinger(ticks)` on the hitbox, or
  `crystalgraphics.collision.linger` milliseconds for every hitbox that sets none. The server's linger by its own
  setting.

  ```java
  CgHitbox.follow(player, STRIKE).activeFor(1).showLinger(6)...   // a one-tick blow, seen fading for 0.3 s after
  ```
- The server sends a player what is within 64 blocks of them while their client is showing, recording only while
  someone is. `crystalgraphics.collision.serve=false` keeps a server's own to itself unless its
  `crystalgraphics.collision.show` is on.

### Drawing your own

`CgCollisionDebug` draws on the next world frame only: call it every frame you want something seen. Render thread;
colours are ARGB.

```java
CgCollisionDebug.shape(CgSphere.of(2), pose.at(x, y, z), 0xFFFF4040);    // a red ball
CgCollisionDebug.hitbox(slam, 0xFF40FF60);                               // each part as its last tick tested it
CgCollisionDebug.line(x, y, z, x, y + 3, z, 0xFFFFFFFF);
if (world.ray().at(x, y, z).along(0, -1, 0, 8).first(hit)) CgCollisionDebug.mark(hit, 0xFFFF4040);   // a cross and its normal
```

### A recorder of your own

`CgCollisionQuery.watch(watcher)` hands every query a level's world runs to one listener, as it ends: the show uses it.
One listener for the process; a hitbox's queries and an empty world's are never handed over.

---

## 15. Testing without Minecraft

Collision code runs in a plain JUnit test: no game, no level.

**Your own shapes** go in an empty world as hittable hitboxes, each standing for an id:

```java
@Test
public void theConeHitsWhatIsInFrontOnly() {
    CgCollisionWorld arena = CgCollisionWorld.empty();
    CgPose pose = new CgPose();
    CgHitbox front = CgHitbox.of(CgSphere.of(0.5)).source(1).hittable().passive();
    CgHitbox behind = CgHitbox.of(CgSphere.of(0.5)).source(2).hittable().passive();
    front.pose(pose.at(0, 0, 3)).tick(arena, 1);
    behind.pose(pose.at(0, 0, -3)).tick(arena, 1);

    CgHits hits = new CgHits();
    arena.overlap(CgCone.of(5, Math.toRadians(30))).at(0, 0, 0).into(hits);   // +Z forward
    assertEquals(1, hits.size());
    assertEquals(1, hits.get(0).entityId());
    front.stop();
    behind.stop();
}
```

**Shapes alone** need nothing at all: `CgShapes.overlaps(...)` and friends ([§13](#13-exact-tests-with-no-world)).

**A world of blocks and entities** of your own: `CgCollisionWorld.of(worldQuery, entityQuery)` takes anything that
answers the platform's `CgWorldQuery` and `CgEntityQuery`: a test's fakes of a yard with a stair and three mobs.

**Hitbox types** run on the networking test rig, `CgNetTestRig`: a server and clients in one JUnit test, latency
included ([`NETWORKING.md`](NETWORKING.md) §14).

### In a running game: the collision probe

`CgCollisionProbe` checks collision against a real level. It summons two armour stands, a painting, an ender dragon
and a grid of a hundred stands beside the player, then checks them on both sides:

- a dragon's parts, and its head met first by a ray from in front;
- a painting's thin box on its wall, and a ray stopping at its face;
- a growing ring hitting each stand once, the nearer one first.

It also logs how long 1000 overlaps take live and batched. When it is done it clears what it summoned.

```bash
# a dev client's autotest, or prodSmoke -PcgSmokeProps=crystalgraphics.collisionprobe=true
./gradlew :runtime:mc:modern:forge:1.20.1:runClient -Dcrystalgui.autotest=true "-Dcrystalgui.autotest.world=*" -Dcrystalgraphics.collisionprobe=true
```

The verdict is `collision probe: true (10 passed, 0 failed)` in the client's log.

---

## 16. Performance and limits

### What it costs

Measured on a desktop, warm:

| Work | Cost |
|---|---|
| A sphere against a box | 11 ns |
| A capsule against a box, by GJK | 235 ns |
| A 6-block blast among 50 entities | 1.7 µs |
| A 10-block ray through block cells | 259 ns |
| A moving hitbox among 50 entities, a tick | 1.7 µs |
| A hit sent over the network | about 24 bytes |
| Snapshots and watches from a level's mirror, on the level's thread (Fabric 1.20.1, 100 watches and 20 snapshots a tick) | about 0.3 ms a tick, plus about 0.5 µs per entity in watched areas (4,000 entities: 2.4 ms) |
| A snapshot with no mirror, copied on the spot, radius 32 / 60 (Forge 1.20.1, surface / underground) | 4.3 / 7.3 ms, 26 / 29 ms |

Every query, hitbox tick and hit allocates nothing (measured: 50,000 hitbox ticks, 0 bytes).

### Limits

| Limit | What happens |
|---|---|
| A query's cells | past 32,768 block cells a walk stops, warned once: a query over a volume no tick can afford |
| `except(id)` | up to eight; a ninth throws |
| Nested queries | four deep |
| A watch or snapshot | mirrors share 64 MB (`crystalgraphics.mirror.memory`): a single area past it is refused |
| A snapshot with no mirror | 128 blocks a side (`CgBlockSnapshot.MAX_SIDE`) |
| Batches | worth it past a few hundred queries a tick |

### Keeping it fast

- **Entities only by default**: block walks cost more than entity tests. Add `blocksToo()` when blocks matter.
- **Size the shape to the question**: a 64-block overlap with blocks walks a lot of cells; a ray walks only the cells
  it crosses.
- **`any()` and `first()` stop early**; `max(n)` keeps the nearest few.
- **Batch a loop of many queries**; the engine already batches its hitboxes and effects.
- **Shapes and hits in fields**, never made per tick.

---

## 17. Questions people ask

### Query or hitbox?

| You want | Use |
|---|---|
| An answer now, once: who is in the blast, what the bolt hits | a query |
| Something that lasts, grows, turns or follows: a swing, a wave, an aura, a zone | a hitbox |
| Each target hit once (or every so often) without bookkeeping | a hitbox |
| A shape queries should meet: a shield, a weak spot | a hittable hitbox |
| A hitbox the server decides and every client shows | a hitbox type |

### Overlap, cast or ray?

An **overlap** asks what is inside a shape where it stands. A **cast** asks what a shape moving along a line meets
first (a ball thrown, a body falling): a thick ray. A **ray** asks what a line meets first: a gun, a crosshair, the
ground below.

### Does it run on the server?

Wherever you call it. Queries and started hitboxes run on the thread that asks: the server thread for gameplay, the
client thread for feedback. A hitbox type runs on both ([§10](#10-hitboxes-on-every-side-hitbox-types)).

### Why did my query hit nothing?

- **It was never placed**: `at(...)` is missing, so it stands at the world's origin.
- **It asks entities only** (the default), and the target is a block: add `blocksToo()`.
- **The default filter skipped it**: items (`itemsToo()`), spectators or the dead (`allEntities()`), or something not
  pickable. Or `source`, `except`, `living()`, `kinds`, `where` left it out.
- **It is a hittable hitbox's owner behind its shield**: the shield took the hit (`kind() == HITBOX`).
- **A cast or ray stopped at a block first**, or at an unloaded cell (`kind() == UNLOADED`).
- **Turn on the show** ([§14](#14-seeing-it)) and look: the query is drawn, red where it stopped.

### Why is my hitbox hitting only once?

That is the default: each target once. `rehit(ticks)` lets a target be hit again; `maxHits(n)` caps it.

### Do blocks count as a hitbox's hits?

No: a hitbox's targets are entities and hittable hitboxes. For blocks, ask a query.

### Can I collide with my own entity's custom shape?

Make it a hittable hitbox following your entity ([§9](#9-shields-wards-and-weak-spots-hittable-hitboxes)): every query
then meets that shape, standing for your entity, instead of its box.

### Does a client's hit damage anything?

Only if your code damages on it. Damage belongs on the server; a client's queries and its own hitbox copies are for
feedback. A hitbox type keeps the two apart for you. A claimed type ([§10](#melee-at-any-ping-claimed-hitboxes)) lets
the attacker's client decide what it hit, and the server still decides whether that counts.

### What about particles?

A particle's contact is the VFX engine's, simulated on the GPU and read back a few frames late, client only: never base
gameplay on it ([`VFX.md`](VFX.md) §8).

### Is that how other engines do it?

Yes. Unity's `Physics.OverlapSphere`, `SphereCast` and `Raycast` with layer masks, Unreal's overlaps, sweeps and traces
with collision channels, PhysX's scene queries and Jolt's narrow phase are the same three questions; the shapes and the
GJK and EPA underneath are ported from Jolt Physics. Hitboxes with hit lists and re-hit times are what fighting and
action games build on top (Unreal's GAS ability targeting, Fortnite's melee traces); hitbox types are Unreal's
server-authoritative, client-predicted pattern.

### Where is the code?

`com.crystalgraphics.shape` (shapes, poses, boxes, exact tests), `com.crystalgraphics.collision` (worlds, queries,
hits, hitboxes, the show). Where each class lives: `collision/CLAUDE.md`.
