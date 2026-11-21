# Sync groups: feet in step

**What you will have:** a walk and a run blended by speed with the left foot down at the same moment in both, so the
blend never shuffles, and a limp that follows the walk's stride.

## In plain words

Mix a walk and a run where one has its left foot down and the other its right, and the legs tangle into a shuffle. A
**sync group** makes clips march in step, like soldiers matching left-right: the strongest clip leads and the others
line their feet up with it. **Sync markers** are little flags on each clip saying "the left foot lands here".

## The code

```java
CgTwoWayBlendNode.of(
        CgSequencePlayerNode.of(WALK).loop().sync(CgSyncGroup.of("feet")),
        CgSequencePlayerNode.of(RUN).loop().sync(CgSyncGroup.of("feet")), SPEED);
```

```yaml
nodes:
  walk: { kind: sequence_player, clip: mymod:hero/walk, loop: true, sync_group: feet }
  run:  { kind: sequence_player, clip: mymod:hero/run, loop: true, sync_group: feet }
  move: { kind: two_way_blend, a: walk, b: run, alpha: speed }
```

Players in one group move together: the heaviest leads, the others follow its **sync markers**, or its fraction of its
clip where they have none. Mark the feet on each clip ([`clips.md`](clips.md)):

```yaml
# assets/mymod/clips/models/hero/walk.yaml
_v: 1
sync_markers: [ { name: foot_l, at: 0.0 }, { name: foot_r, at: 0.5 } ]
```

## Variations

### Never the leader

```java
CgSequencePlayerNode.of(LIMP).loop().sync(CgSyncGroup.of("feet", CgAnimGroupRole.ALWAYS_FOLLOWER));
```

In a file: `group_role: always_follower`. `TRANSITION_LEADER` and `TRANSITION_FOLLOWER` join only once fully blended
in.

### Play rate by speed

```java
CgSequencePlayerNode.of(RUN).loop().playRate(STRIDE);      // a float variable, read each frame
```

### A montage leaving the feet in place

A montage with `sync_group` leads the graph's group while it blends out, so the run under it picks up where it left
the feet ([`montages-and-slots.md`](montages-and-slots.md)).

## What goes wrong

- **A group's markers** are those its leader and every marked member share; the rest are stepped over. A clip marked
  `foot_left` beside one marked `foot_l` never lines up.
- **Without markers**, clips sync by fraction: fine for clips of the same rhythm, wrong for a walk of two steps beside
  a run of four.

## Where next

- Blend spaces sync by themselves: [`blend-spaces.md`](blend-spaces.md).
- Feet planted on the ground: [`locomotion.md`](locomotion.md).
