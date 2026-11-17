# Root motion moves the entity

**What you will have:** a lunge, a dash or a slam whose travel is authored in the clip, carrying the Minecraft entity
exactly as far as the animation moves, swept against blocks, on the server and for the player's own client at once.

## In plain words

**Root motion** means the animation itself says how far the body travels ([`root-motion.md`](root-motion.md)). Here
that travel moves the real Minecraft entity: a lunge drawn three blocks long carries the mob three blocks, and it stops
at a wall like anything walking would.

## The code

```yaml
# assets/mymod/actors/fighter.yaml
_v: 1
binds: { entities: [ mymod:fighter ] }
prefab: mymod:fighter
anim_graph: mymod:fighter_locomotion
root_motion: body                         # the prefab node whose montages carry the entity
```

```java
// the server, when the fighter attacks: its lunge carries it onto its target, stopping at arm's length
CgEntityActor fighter = CgEntityActors.serverActor(fighterId);
fighter.animation().motionWarping().target("opponent", x, y, z, yaw).standOff("opponent", 0.9f);
fighter.play("mymod:fighter_lunge");
```

In Java, an actor with code names its body with `rootMotion`:

```java
@Override protected void onSpawn(CgEntity fighter) {
    body = rootMotion(add(scene().skeletal(FIGHTER).materials(SKINS::get)));
}
```

## How it moves

- **The server's actor moves its entity** each tick by its body's montage root motion: swept against blocks as the
  entity's own movement is, then turned. Every client sees the entity move as the server sends it.
- **On a client, only the player's own actor moves its player**, at once, so the player's own moves never lag. Any
  other actor follows its server.
- **The travel is what is drawn**: the body's world transform puts the clip's motion in the world, so a body scaled up
  under the root carries the entity further.
- **Only montages carry it.** A looping walk in the graph does not; give the locomotion graph root motion only through
  montages ([`root-motion.md`](root-motion.md)).

## Variations

### Landing on a target: motion warping

`motionWarping()` bends the clip's travel inside its warp windows so the move ends where the target is, nearer or
further than authored ([`motion-warping.md`](motion-warping.md)).

### The rest of a move's motion

What the clip does not carry is the entity's movement API, on the side that owns the entity:

```java
CgEntityMovement.push(victim, 0, 0.8, 1.2);       // the knockback the lunge deals
CgEntityMovement.gravity(fighter, false);         // hold the fighter in the air for an aerial combo
```

[`../../entities/guides/moving-an-entity.md`](../../entities/guides/moving-an-entity.md) has every call.

### Holding the entity's own movement

A mob's AI and gravity keep running while a move carries it. Hold them for the move with a `crystalgraphics:move_lock`
span in its sequence ([`reactions-and-locks.md`](reactions-and-locks.md)).

## What goes wrong

- **Nothing moves**: the actor names no `root_motion` body, or the move plays on a body other than it.
- **The fighter slides while its AI walks**: hold the AI with a `move_lock` span while the move plays.
- **Forge 1.13.2** places a moved entity unswept: it can pass into a wall there.
- **`play` on a client throws**: moves are the server's. The player's own client plays its own through prediction
  only for tags and cues today.

## Where next

- Root motion in a clip, its modes and extraction: [`root-motion.md`](root-motion.md).
- Landing on targets: [`motion-warping.md`](motion-warping.md).
