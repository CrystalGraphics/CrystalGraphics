# The gameplay handle: `CgEntities.of(entity)`

**What you will have:** one object per entity through which gameplay changes it: its tags, the cues it plays, and
the moves its actor plays. The server's calls are the truth. The player's own client may predict its own changes, shown
at once and taken or refused by the server. Any other client only reads.

## In plain words

The **handle** is one remote control for an entity's gameplay: add a tag, play an effect, start a move. The server's
remote always works. Your own player's remote works too, but the server double-checks each press: you see the result
at once, and it is taken back if the server says no (**prediction**). Anyone else's remote is read-only.

## The code

```java
// the server: a stun lands
CgEntities.of(zombie)
        .addTag(STUNNED)
        .executeCue(STUN_CUE);                         // stars burst over it, for everyone near

// the player's own client: they start a charge, shown now, checked by the server
CgEntities.predicted(() -> CgEntities.local()
        .addTag(CHARGING)
        .addCue(AURA_CUE));                            // a lasting aura while the server takes it

// any client: read
boolean stunned = CgEntities.of(zombieId).hasTag(STUNNED);
```

| Call | Side | Answers |
|---|---|---|
| `CgEntities.of(entity)` | server | the handle of the loader's entity object |
| `CgEntities.of(entityId)` | client | the handle of the entity with that network id, loaded yet or not |
| `CgEntities.local()` | client | your own player's handle, through respawns and dimension changes |

There is one handle per entity per side, made on first ask and dropped when the entity leaves. Ask again rather than
keeping one past the entity's life.

## The verbs

| Verb | Server | The player's own client | Any other client |
|---|---|---|---|
| `hasTag`, `hasAnyTag`, `hasAllTags`, `tags()` | reads | reads, its predictions included | reads |
| `addTag`, `removeTag`, `setTagCount` | changes | predicts; the server's rule decides | throws |
| `onTagChanged(tag, (before, now) -> ...)` | hears | hears | hears |
| `executeCue(tag[, params])` | plays for everyone near | plays here | throws |
| `addCue(tag[, params])` | starts a lasting cue for everyone near | predicts it | throws |
| `raiseEvent(name)` | its actor reacts as if the game had raised it | throws | throws |
| `properties(type)` | its object of a property type | this client's copy | this client's copy |
| `isAuthority()`, `isOwner()` | which of the three this side is | | |

Every changing verb returns the handle, so calls chain.

## Variations

### Cues

A cue is what plays when gameplay says "this happened": particles, sounds, a shake, chosen by a cue asset by its tag
([`../../vfx/networking.md`](../../vfx/networking.md) § *Cues*). On the handle it plays at the entity:

```java
CgEntities.of(target).executeCue(HIT_CUE,
        new CgCueParameters().magnitude(damage).write("size", 1.5f));      // a burst, sized by the hit

CgCuePlaying burning = CgEntities.of(target).addCue(BURNING_CUE);         // lasts until removed
...
burning.remove();                                                          // its removal plays, then it ends
```

Parameters that name no anchor are anchored on the entity, so the cue follows it.

### Predicting: one input, one window

A key press is often several changes at once. `CgEntities.predicted` groups every predicted call inside it under one
key: the server takes them all or refuses them all, and a refusal undoes every one.

```java
// the server, at start-up: what a player may change on their own player
CgEntityTags.acceptTags((player, tag, delta) -> tag == GUARDING && !isStunned(player));

// their client, on the key
CgPredictionKey key = CgEntities.predicted(() -> CgEntities.local()
        .addTag(GUARDING)
        .addCue(GUARD_CUE));
key.onRefused(() -> playDeniedSound());           // anything else to undo, or to say
```

- **The window is this thread's while `predicted` runs.** A call inside a nested `predicted` joins the outer window.
- **Outside a window**, each predicted call gets a key of its own.
- **On the server**, `predicted` just runs the calls: nothing there predicts.

### A move on its actor

An entity drawn by an actor ([`../../animation/guides/actors.md`](../../animation/guides/actors.md)) plays moves on its
body. That verb is the actor's, since only an entity with a body can play one:

```java
// the server: a zombie lunges, seen by everyone near, carried by its root motion
CgEntityActor actor = CgEntityActors.serverActor(zombieId);
if (actor != null) {
    actor.play("mymod:zombie_lunge")
         .onFinished(() -> CgEntities.of(zombie).removeTag(LUNGING));
}
```

`raiseEvent("hurt")` on the handle makes the entity's actor play its `hurt` reaction, on the server, as if the game
had hurt it.

## What goes wrong

- **`<verb> on entity#… is the server's or its owner's: this client is neither`**: a client changed an entity other
  than its own player. Change it on the server, or ask through your own command.
- **A predicted change shows, then goes back**: the server refused it. With no `acceptTags` rule the server takes
  nothing.
- **`CgEntities.local()` throws** on the title screen or while joining: there is no player yet.
- **`raiseEvent` on a client throws**: reactions on a client follow the game's own events.
- **`play` on a client throws**, and `serverActor` answers null for an entity with no actor registered.

## Where next

- Tags in depth: [`tags.md`](tags.md).
- Who decides what, and why: [`../concepts/sides-and-authority.md`](../concepts/sides-and-authority.md).
- Moves and reactions: [`../../animation/guides/reactions-and-locks.md`](../../animation/guides/reactions-and-locks.md).
