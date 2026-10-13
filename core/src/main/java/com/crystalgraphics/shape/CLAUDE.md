# shape — shapes, poses, boxes and the exact tests between them

Regions of space and where they are: what world queries, hitboxes, draws' bounds and particle modules take. **Using
them: `docs/COLLISION.md` §4 and §13.** Worlds, queries and hitboxes are `collision/`.

```java
static final CgShape CLEAVE = CgCone.of(8, Math.toRadians(35));
CgShapes.overlaps(CLEAVE, pose.at(eyeX, eyeY, eyeZ).facing(yaw, pitch), entityBox);
```

## Where things go

| Package | Owns |
|---|---|
| `shape` | `CgShape` (immutable, its support function and bounds; `of(hostShape)`), `CgPose` (position in doubles and a rotation) |
| `shape.bounds` | `CgAABB` (mutable, reused; host boxes in and out), `CgAABBc` (read-only) |
| `shape.primitive` | The shapes: `CgSphere`, `CgBox`, `CgCapsule`, `CgCylinder`, `CgCone`, `CgShell`, `CgRing`, `CgPlane`, `CgCompound`, `CgConvex` (and its `Support`); `CgResizable` sizing them in place |
| `shape.query` | `CgShapes` (overlap, distance, contact, ray, cast) with `CgContact` and `CgCastHit`; underneath, package-private, the narrow phase: closed forms, `CgClosedRays`, `CgNonConvex`, and the Jolt ports `CgGjk`, `CgEpa`, `CgEpaHull`, `CgClosestPoint` |

## Rules

- **Shapes are immutable**; poses, boxes and results are mutable and reused. Nothing per call allocates past a
  thread's first.
- **Radians, local +Z forward, +Y up** everywhere; `CgPose.facing` follows Minecraft's directions.
- **Every shape answers its convex hull's support**, and `isConvex()` says whether that is the shape. The non-convex
  ones (shell, ring, wide cone, compound) are tested by their own rules.
- **Ports keep their source's boundaries** (Jolt, MIT): attribute in the class javadoc.
- **`shape` names nothing of CrystalGraphics but the platform SPI**, and `collision` no renderer, GL or VFX type, so a
  dedicated server takes them alone (`ShapeImportDirectionTest`).

---

Also loaded with this folder:

@../../../../../../../docs/COLLISION.md
