# The frame: what happens each frame, in order

For the reader who wants to know why a value set at one moment shows the next frame, or why an event read too early is
missed.

## In plain words

Every frame is a short assembly line. First the facts are gathered (what each entity is doing), then each character's
graph decides its pose on a helper thread, then the bones are placed in the world, then things that ride the bones
(effects, held items) follow, then everything is drawn. Something put on the line after its station has passed waits
for the next frame.

## The order, on a client

```
BEGIN         entities' properties worked out (speed, grounded, a jump)
PLAYS         actors' tick and reactions; your onUpdate sets variables
SEQUENCES     sequences and montages advance; their events heard
POSE          each instance: montages, onUpdate, its variables copied ─► a worker evaluates the graph
ATTACH        the pose placed in the world; what rides a socket follows
EFFECTS       effects tick, at sockets read after ATTACH
LATE_ATTACH   every followed point read again, the freshest
draw
```

On a server, each game tick: `NET_IN` (entities' properties), `GAMEPLAY` (server actors, reactions, posing), then
`NET_OUT` (what changed sent, root motion moving entities). Nothing draws.

## What follows from it

- **A variable set after `onUpdate`** is read next frame: the worker took its copy already.
- **A motion event** (`JUMPED`) is worked out in `BEGIN` or `NET_IN`. Read it in `PLAYS` or `GAMEPLAY`, not earlier.
- **Sockets are current after `ATTACH`**, so an effect on a hand never lags it.
- **A graph runs on a worker**: nothing of it may be touched from the game thread between `POSE`'s copy and the result.
- **Nothing allocates per frame** once warm: every buffer is made at the start and reused.
- **While the game is paused**, every instance moves 0 seconds and is still posed, so it still draws.

## Where next

- Reading entity events at the right time: [`../../entities/guides/what-it-is-doing.md`](../../entities/guides/what-it-is-doing.md).
- How a blend decides what shows: [`weights-and-relevance.md`](weights-and-relevance.md).
