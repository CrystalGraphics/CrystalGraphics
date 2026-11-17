# Tags: marking what an entity is

**What you will have:** an entity marked `stunned`, `flying` or `charging`, counted so that two sources can grant one
tag, seen by every client near it, and read by an animation graph with no code.

## In plain words

A **tag** is a label you put on an entity, like a name sticker: "stunned", "flying", "charging". It is **counted**:
two things can each stick the same label on, and it stays until both peel theirs off. Labels nest, so "flying.fast"
also counts as "flying".

## The code

```java
public static final CgGameplayTag STUNNED = CgTagRegistry.global().register("mymod:state.stunned");
public static final CgGameplayTag FLYING = CgTagRegistry.global().register("mymod:state.flying");
public static final CgGameplayTag FAST = CgTagRegistry.global().register("mymod:state.flying.fast");
```

```java
// the server: a shockwave stuns every zombie it reaches
CgEntities.of(zombie).addTag(STUNNED);
...
CgEntities.of(zombie).removeTag(STUNNED);                 // three seconds later

// either side: ask
CgEntity e = CgEntities.of(zombie);                       // a client: CgEntities.of(zombieId)
if (e.hasTag(FLYING)) { ... }                             // true for mymod:state.flying.fast too
```

- **Tags are declared once, in common code**, at start-up, before anything sets them: the server sends them to clients
  as numbers both sides agree on.
- **A tag is counted.** Added twice, it is owned until removed twice. A root and a potion can each grant `FAST`, and
  the entity is fast until both have taken it back.
- **A tag includes the tags under it.** Asked about `mymod:state.flying`, an entity with only
  `mymod:state.flying.fast` answers yes.

## Variations

### Counts

```java
CgEntity e = CgEntities.of(zombie);
e.addTag(RAGE, 3);                         // three stacks
e.removeTag(RAGE);                         // two
e.setTagCount(RAGE, 5);                    // five, whatever it was
int stacks = e.tags().count(RAGE);         // the tags under it counted too
int own = e.tags().explicitCount(RAGE);    // only RAGE's own
boolean any = e.hasAnyTag(STUNNED, FROZEN);
boolean all = e.hasAllTags(FLYING, FAST);
```

`removeTag` of a tag the entity does not own itself does nothing: a parent is never removed through its child.

### Hearing a change

```java
CgEntityTags.Watch watch = CgEntities.of(zombie).onTagChanged(STUNNED, (before, now) -> {
    if (before == 0 && now > 0) dizzyStars(zombie);
    if (now == 0) stopDizzyStars(zombie);
});
...
watch.cancel();
```

It runs on the side you ask on: on the server as the server changes it, on a client as the server's change arrives,
and at once for the player's own predicted change.

### A player's own client asking

A player's client may change its own player's tags, shown at once, if the server's rule allows it:

```java
CgEntityTags.acceptTags((entity, tag, delta) -> tag == CHARGING || tag == GUARDING);   // the server, at start-up
CgEntities.predicted(() -> CgEntities.local().addTag(GUARDING));                     // the player's client
```

How prediction works, and what a refusal undoes: [`the-gameplay-handle.md`](the-gameplay-handle.md).

### Read by an animation graph

A graph's `tags:` block turns a tag into a variable every frame, on both sides: a bool for owned, an int or float for
its count.

```yaml
# assets/mymod/anim_graphs/zombie.yaml
variables:
  stunned: { type: bool }
  rage:    { type: int }
tags:
  stunned: { tag: mymod:state.stunned }
  rage:    { tag: mymod:buff.rage }
```

[`../../animation/guides/entity-variables.md`](../../animation/guides/entity-variables.md) has the rest.

### Without the handle

`CgEntityTags` is what the handle calls. Use it where you hold only the entity and want no handle:

```java
CgEntityTags.add(zombie, STUNNED);                 // server
CgEntityTags.count(zombie, FLYING);                // server
CgEntityTags.client(zombieId).has(FLYING);         // a client: what the server last sent
```

## What goes wrong

- **A tag set before it is registered throws.** Register every tag in common code at start-up.
- **A client calling `addTag` on any entity but its own player throws**, naming the verb. A client asks for its own
  player's tags only, through `CgEntities.local()`.
- **With no `acceptTags` rule, the server takes nothing** from a client: the tag shows on that client, then goes back.
- **Tags end with the entity**: its death, its leaving the level or the dimension. They are not saved. Keep what must
  survive in a saved property ([`saving.md`](saving.md)) and add the tag back from it.
- **A client missing a tag the server has** (a mod on one side only) logs it once and reads it as absent.

## Where next

- The handle's other verbs, cues and prediction: [`the-gameplay-handle.md`](the-gameplay-handle.md).
- Data that is a number rather than a flag: [`properties.md`](properties.md).
