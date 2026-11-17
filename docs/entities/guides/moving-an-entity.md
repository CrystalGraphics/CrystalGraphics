# Moving an entity

**What you will have:** an entity pushed, launched, held in the air, teleported or turned from your gameplay code,
and its own movement (a mob's AI, a player's keys, gravity) held still while a move plays. The same calls on every
Minecraft version.

## In plain words

These are the pushes and pulls of gameplay: knock an entity back, launch it, teleport it, turn it, or hold it still
while a move plays, like a referee freezing a player until the whistle.

## The code

```java
// the server: an uppercut
CgEntityMovement.push(target, 0, 1.2, 0);                  // blocks a tick, added to its velocity, sent to its clients
CgEntityMovement.resetFall(target);                         // and no fall damage when it lands

// a dash: moved as its own movement would, stopping at walls
CgEntityMovement.move(fighter, dx, 0, dz);

// a vanish strike: placed behind the target, facing it
CgEntityMovement.teleport(fighter, behindX, y, behindZ);
CgEntityMovement.turn(fighter, yawToward(fighter, target));
```

| Call | Does |
|---|---|
| `move(e, dx, dy, dz)` | moves it by that many blocks as its own movement: swept against blocks, stepping up |
| `teleport(e, x, y, z)` | places it, unswept; its clients are told |
| `turn(e, yaw)`, `rotate(e, yaw, pitch)` | faces it, degrees, Minecraft's (yaw 0 faces +z, pitch up negative); body and head with it |
| `velocity(e, vx, vy, vz)` | sets its velocity, blocks a tick; sent to its clients |
| `push(e, vx, vy, vz)` | adds to its velocity: a knockback, a launch |
| `gravity(e, on)`, `gravityOn(e)` | whether gravity pulls it |
| `resetFall(e)` | clears how far it has fallen, so landing does no harm |
| `yaw(e)`, `pitch(e)` | where it faces now |

Every call answers whether it changed anything. `e` is the loader's own entity object.

## Variations

### Hold it in the air

```java
CgEntityMovement.velocity(fighter, 0, 0, 0);
CgEntityMovement.gravity(fighter, false);       // floats while a flight move plays
...
CgEntityMovement.gravity(fighter, true);
```

### Hold its own movement while a move plays

A mob keeps running its AI and a player keeps walking unless held. `CgMoveLocks` holds them, counted, so two moves
holding at once let go only when both have, and the entity's own state comes back after:

```java
// the server: a grab holds the victim's AI and gravity until the throw
CgMoveLocks.Hold held = CgMoveLocks.hold(victim, CgMoveLocks.AI | CgMoveLocks.GRAVITY, true);
...
held.close();

// a client: its own player's keys, while a landing plays
CgMoveLocks.Hold keys = CgMoveLocks.hold(localPlayer, CgMoveLocks.INPUT, false);
```

| Flag | Held on |
|---|---|
| `INPUT` | the client whose player it is: its movement keys read as none |
| `AI` | the server: a mob's goals and pathing |
| `GRAVITY` | the server, and a client for its own player |

The third argument says which side you are calling from. A flag that does not apply on that side is skipped, so the
same line is safe in code both sides run.

**From a clip, with no code**: a `crystalgraphics:move_lock` span on a sequence holds the entity for as long as it
plays ([`../../animation/guides/reactions-and-locks.md`](../../animation/guides/reactions-and-locks.md)).

### Moved by its animation

An entity drawn by an actor can be carried by its montages' root motion instead: the server's actor moves it each
tick, swept against blocks
([`../../animation/guides/root-motion-moves-the-entity.md`](../../animation/guides/root-motion-moves-the-entity.md)).

## What goes wrong

- **Move an entity on the side that owns it**: the server for a mob, a player's own client for that player's walking.
  A client moving another entity is undone by the server's next update.
- **Velocities are blocks a tick**, as Minecraft keeps them: 1.0 is 20 blocks a second.
- **What a version lacks answers false and changes nothing**: Forge 1.8.9 and 1.7.10 have no gravity switch, 1.7.10
  no AI switch, and Forge 1.13.2 places a `move` unswept and has no velocity setter.
- **A hold never closed holds forever.** Close it in a `finally`, or let a sequence's span do it for you.

## Where next

- Know whether it landed, and how far it fell: [`what-it-is-doing.md`](what-it-is-doing.md).
- Mark it as grabbed for everyone to see: [`tags.md`](tags.md).
