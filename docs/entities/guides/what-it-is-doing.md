# What an entity is doing

**What you will have:** for any entity, on either side, how fast it moves and which way, whether it is on the ground,
sneaking, swimming, riding or sleeping, what each hand holds and does, and the moment it jumps, lands, is hit or dies.
The same names on every Minecraft version, with nothing to send.

## In plain words

The engine watches every entity and writes down what it is doing each moment: how fast it goes, whether it stands on
the ground, what it holds, and the instant it jumps or gets hit. You just read the notes.

## The code

```java
CgNetObject m = CgEntityProperties.MOTION.of(zombie);              // the server's zombie, or a client's
float speed = m.get(CgEntityProperties.SPEED);                      // blocks a second, along the ground
boolean falling = m.get(CgEntityProperties.AIRBORNE) && m.get(CgEntityProperties.VERTICAL_SPEED) < 0;

if (m.get(CgEntityProperties.LANDED) && m.get(CgEntityProperties.LAND_HEIGHT) > 6) {
    shakeTheGround(zombie);                                         // it fell more than six blocks, this tick
}
```

On a client, by network id:

```java
CgNetObject m = CgEntityProperties.MOTION.of(entityId);
```

`CgEntityProperties.MOTION` is the engine's own property type ([`properties.md`](properties.md)), on every entity.
Each side works it out for itself from the game, so nothing crosses the network: the server each tick, a client each
frame. Every field is in [`../reference/motion.md`](../reference/motion.md).

## Reading the moments

Nine things that happen are fields that are true for the one tick (server) or frame (client) they happen in:

| Event | Read with it, the same tick | When |
|---|---|---|
| `JUMPED` | | it left the ground going up (walking off a ledge is not a jump) |
| `LANDED` | `LAND_HEIGHT`: the fall from its highest point | it touched the ground again |
| `HURT` | `HURT_TIME` from 0 | it was hit, a second hit before the first wore off included |
| `SWING_STARTED` | `SWING_HAND` | an arm began a swing, or began it again mid-swing |
| `USE_STARTED`, `USE_STOPPED` | `USE_HAND` | it began or stopped using an item: eating, drawing a bow, blocking |
| `MOUNTED`, `DISMOUNTED` | `VEHICLE` | it got on or off something; changing mounts raises both |
| `DIED` | `DEAD_TIME` from 0 | it died |

Read them from code that runs after the engine has worked them out. On the server that is the world's `GAMEPLAY`
group; on a client, `PLAYS`. An entity actor's `tick` already runs there.

```java
// the server, at init: a landing from height cracks the blocks around it
CgWorld.serverWorld().schedule().add(CgTickGroup.GAMEPLAY, "mymod:heavy_landings", dt -> {
    for (Object golem : golems) {
        CgNetObject m = CgEntityProperties.MOTION.of(golem);
        if (m.get(CgEntityProperties.LANDED) && m.get(CgEntityProperties.LAND_HEIGHT) > 3) crackAround(golem);
    }
}).onServer(true);

// a client, at init: a whoosh when the player you watch jumps
CgWorld.client().schedule().add(CgTickGroup.PLAYS, "mymod:jump_sound", dt -> {
    if (CgEntityProperties.MOTION.of(watchedId).get(CgEntityProperties.JUMPED)) whoosh(watchedId);
});
```

## Variations

### Speed against intent

`SPEED` is how fast it moves. `INPUT` (0 to 1) and `INPUT_DIRECTION` are how hard and where it *means* to move: a
slope, a shove or a wall changes the first and not the second. A locomotion graph usually keys its gait on intent.

| Where | `INPUT` comes from |
|---|---|
| a client, its own player | its keys or stick |
| a 1.21.3+ server, a player | the player's input packet (`INPUT_FROM_PLAYER` is true) |
| anywhere else | its movement against its facing: 1 moving, 0.3 sneaking, 0 standing |

`SPRINT_HELD` and `JUMP_HELD` follow the same table.

### Smooth between ticks

Minecraft moves entities 20 times a second, so a value read straight from the game steps on a 144 Hz screen. On a
client, `SPEED`, `VERTICAL_SPEED`, `MOVE_DIRECTION` and `ACCELERATION` follow the last two ticks across the frame, as
the drawn position does. `TURN_RATE` and `GROUND_DISTANCE` are read each frame. Nothing here needs smoothing again.

### Arms and hands

`MAIN_ARM` and `OFF_ARM` are Minecraft's own rule for a player's arms, the newest version's, for every entity: an
`ARM_` constant such as `ARM_BOW` while a bow is drawn, `ARM_BLOCK` with a shield up. A bow or crossbow takes both arms.
`MAIN_ITEM` and `OFF_ITEM` are the game's raw item numbers. Name or match one with `CgItems`:

```java
int held = m.get(CgEntityProperties.MAIN_ITEM);
boolean sword = CgItems.matches(held, "#minecraft:swords");     // an item tag
String name = CgItems.name(held);                                // "minecraft:bow", or null for nothing
```

### Your own events

What only your server knows (an ability fired, a hit's damage) is no motion field. Give it a `CgNetEvent` on your own
type ([`properties.md`](properties.md)) and emit it from the server.

## What goes wrong

- **An event read too early is missed.** A server behaviour of your own type, or a loader event handler, may run
  before the engine has worked out this tick. Read events in `GAMEPLAY` on the server and `PLAYS` on a client, as above.
- **The first tick an entity is seen raises nothing**, so one that comes into view in mid-air does not jump.
- **What a version lacks reads as not happening**: no swimming pose before 1.13, no gliding and no off hand before
  1.10.2, no charged crossbow before 1.14.3. Item tags start at 1.13; before that a tag matches nothing.
- **Item numbers are the session's.** Never save one: save the item's name.
- **A client's values are that client's.** Two clients read the same entity a frame apart; use the server's for game
  rules.

## Where next

- Every field and its unit: [`../reference/motion.md`](../reference/motion.md).
- Feed them to an animation graph with no code:
  [`../../animation/guides/entity-variables.md`](../../animation/guides/entity-variables.md).
- Play a montage when one happens: [`../../animation/guides/reactions-and-locks.md`](../../animation/guides/reactions-and-locks.md).
