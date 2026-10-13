# Jobs — the guide

CrystalGraphics runs work off the game's thread for you and hands the result back to the thread that asked, on every
version and loader it runs on. A simulation too heavy for a tick, a search, a file to parse, a download: you say what
to compute and what to do with the answer, and the engine runs the first on a worker and the second on your thread, at
its next tick. You never make a thread, never lock anything, and never touch Minecraft from the wrong thread.

It comes in three parts:

- **Jobs**: work on a worker, its answer back on your thread. Keyed so a newer one replaces an older, debounced so a
  burst of asks runs once, repeated for a simulation that steps again and again.
- **Splitting**: one piece of work spread across every core *now*, returning when all of it has run.
- **Replies**: one shape for an answer that is not here yet (a job, a network request, a download), so they compose:
  wait for two, map one, hear a failure or a cancel.

This page is written for someone who has never written threaded code. Read sections 1 to 3 first: they give you the
words and the one picture everything else follows. After that, jump to what you need.

## If you have threaded a mod before

You know the routine. Here is debris simulated beside the game, the usual way, and then here.

**The usual way.** An executor of your own, a future, and a hop back to the server thread; and everything that can go
wrong in between:

```java
private static final ExecutorService POOL = Executors.newFixedThreadPool(4);    // never shut down: leaks on reload

CompletableFuture.supplyAsync(() -> simulate(level, pieces), POOL)               // reads the level from a worker:
        .thenAccept(next -> server.execute(() -> this.pieces = next));           //   crashes one time in a hundred
// The player leaves the world: the future still lands, on a level that is gone.
// The work throws: the exception vanishes into the future, unlogged.
// Asked again before the last one finished: two run at once, and the older one lands last.
// A pool of normal priority: four busy workers stutter the game's own threads.
```

**Here.**

```java
// List<Debris> simulate(CgCollisionWorld area, List<Debris> pieces): every piece moved one tick, a new list
List<Debris> copy = List.copyOf(pieces);

CgCollisionWorld.of(level).snapshot(x, y, z, 24)                       // the area, frozen at a tick's end
        .flatMap(area -> CgJob.keyed(this, "debris")                   // a newer step replaces this one
                .run(() -> simulate(area, copy)))                      // a worker: reads only the copies
        .then(next -> pieces = next);                                  // next: simulate's list, back on this thread
```

The world frozen first so the worker never touches the level; the answer back on the server thread; cancelled when the
world ends; a throw logged under its name; a newer ask replacing an older one; workers below the game's priority.

### What goes away

| The struggle | Usually | Here |
|---|---|---|
| **Getting back to the game thread** | `server.execute(...)`, `Minecraft.getInstance().execute(...)`, different per side and per version | `then` runs on the thread that started the job, at its next tick |
| **Touching the world from a worker** | works in testing, crashes in play | a snapshot is what the worker reads; `CgCollisionWorld.of(level)` on a worker throws at once |
| **Thread pools** | one per mod, never shut down, at normal priority | one shared pool, below the game's priority, started at the first job |
| **Leaving the world** | futures land on a dead level | every job a game thread started is cancelled when its world ends |
| **Swallowed exceptions** | a failed future says nothing | a failure reaches `onError`, or is logged under the job's name |
| **Asked again before done** | two run, the older lands last | keyed: a newer one replaces a waiting one and supersedes a running one; the older never lands |
| **Bursts of asks** | a timer of your own | `debounce(ms)`: runs once, after the last ask |
| **Urgent behind slow** | an index blocks a completion | lanes: urgent work goes first, background never takes the last thread |
| **Splitting a loop across cores** | a fork-join pool, or none | `CgJob.forEach(items, each)`, allocating nothing |
| **Progress bars** | a volatile int and a guess | `ctx.progress()`, read from any thread, with an ETA |
| **Testing threaded code** | sleeps and flakes | a loop you step by hand, with work run inline and a clock you turn |

## Contents

1. [The words you need](#1-the-words-you-need)
2. [Quick start](#2-quick-start)
3. [The picture: copy, compute, apply](#3-the-picture-copy-compute-apply)
4. [Running a job](#4-running-a-job)
5. [Handing work the world](#5-handing-work-the-world)
6. [Keyed jobs and debounce](#6-keyed-jobs-and-debounce)
7. [Lanes: what goes first](#7-lanes-what-goes-first)
8. [Repeating jobs: a simulation beside the game](#8-repeating-jobs-a-simulation-beside-the-game)
9. [Splitting work now: forEach](#9-splitting-work-now-foreach)
10. [Cancelling and progress](#10-cancelling-and-progress)
11. [Replies: answers that are not here yet](#11-replies-answers-that-are-not-here-yet)
12. [Fire and forget](#12-fire-and-forget)
13. [Loops of your own](#13-loops-of-your-own)
14. [Testing](#14-testing)
15. [Performance and settings](#15-performance-and-settings)
16. [Questions people ask](#16-questions-people-ask)

Where each class lives is `job/CLAUDE.md`, which loads itself for anyone editing a job file.

---

## 1. The words you need

| Word | What it is | Class |
|---|---|---|
| **Job** | Work that runs on a worker and hands its result back to the thread that started it. | `CgJob` |
| **Work** | What the job computes: a lambda returning the answer. | `Supplier`, `Runnable`, `CgJob.Work` |
| **Worker** | One of the engine's threads that run work: `crystalgraphics-job-*`. | — |
| **Loop** | Where jobs report back: a thread's list of jobs, stepped at each drain. Every thread has its own. | `CgJobLoop` |
| **Drain** | A loop stepping its jobs: delivering what finished, starting what is due. Minecraft's server and client threads drain every tick. | `CgJob.drain()`, `CgJobLoop.drain()` |
| **Reply** | An answer that is not here yet: `then` hears it, `onError` a failure, `always` either. A job is one. | `CgReply` |
| **Key** | An owner and a kind that make two jobs "the same job": starting it again replaces the older. | `CgJobKey` |
| **Debounce** | Waiting a while after the last start before running, so a burst runs once. | `Builder.debounce` |
| **Lane** | How urgent a job is: which a free worker takes first. | `CgJobLane` |
| **Context** | What the work is handed: whether anyone still wants the answer, and where to report progress. | `CgJobContext` |
| **Progress** | How far along work is, read from any thread. | `CgProgress`, `CgProgressState` |
| **Snapshot** | Part of the world copied on its thread, which a worker may read. | `CgCollisionWorld.snapshot` |
| **forEach** | One piece of work split across every core now, returning when all has run. | `CgJob.forEach` |

How they fit together:

```
your thread                       a worker                        your thread, next tick
-----------                       --------                        ----------------------
copy what the work needs  ──▶     work(copy) → answer      ──▶    then(answer)       (at the loop's drain)
  (a snapshot, your values)         reads only the copy             applies it to the world

CgJob.forEach(items, each): your thread and the workers take items together; returns when all have run.
```

---

## 2. Quick start

### Work beside the game, its answer back here

```java
CgJob.run(() -> findSafeGround(snapshot))            // a worker
     .then(pos -> teleport(player, pos));            // this thread, at its next tick
```

### A job that keeps being asked for: the last one wins

```java
CgJob.keyed(document, "reparse")                     // a newer reparse of this document replaces this one
     .debounce(300)                                  // runs 300 ms after the last keystroke
     .run(ctx -> parse(text, ctx))                   // the work polls ctx to stop early
     .then(tree -> document.setTree(tree));
```

### A simulation stepped again and again

```java
CgCollisionWorld.Watch arena = CgCollisionWorld.of(level).watch(ARENA);   // the area, kept fresh every tick
CgJob.repeat(arena::latest)                       // this thread, before each run: the newest tick's world
     .work(area -> simulate(area, pieces))        // a worker: returns the next List<Debris>
     .then(next -> pieces = next)                 // this thread
     .named("debris");
```

### A loop spread across every core, now

```java
CgJob.forEach(shards, s -> s.step(area));            // returns once every shard has stepped
```

That is most of what mods do. Everything else builds on these four.

---

## 3. The picture: copy, compute, apply

**Copy on the owner, compute on a worker, apply on the owner.** This is the one idea to hold on to. Minecraft's own
chunk meshing works this way, and so do Unity's batched queries and Bevy's async tasks.

1. **Copy**, on your thread: everything the work needs. A snapshot of the world, a copy of your list, plain values.
2. **Compute**, on a worker: the work reads only what it was handed, and returns an answer. It touches nothing else.
3. **Apply**, on your thread, at its next tick: `then` gets the answer and changes the world.

### Threads

| Call | Thread |
|---|---|
| `CgJob.run`, `keyed(...).run`, `repeat(...).work` | the thread the job reports to: the server thread, the client thread, or a loop you entered |
| The work | a worker |
| `then`, `onError`, `always`, `failed` | the thread that started the job, at its loop's next drain |
| `CgJob.forEach` | the calling thread and the workers together; returns when all items have run |
| `cancel()` | any thread for a job (it settles at the next drain); the starting thread for a reply |
| `progress()`, `isRunning()` | any thread |

### The rules

- **A worker sees only what you hand it.** Never touch Minecraft from the work: its level, entities, chunks and
  players belong to their thread. `CgCollisionWorld.of(level)` called on a worker throws, to catch the common mistake
  at once; a raw Minecraft call cannot be caught, so the rule is yours to keep.
- **What you hand a worker is its until `then` runs.** Don't change it meanwhile. Return the next state from the work
  rather than editing the old one, so your thread keeps drawing the last state it was handed.
- **Start a job on the thread it reports to**, never from inside another job's work: starting one on a worker throws.
  Inside work, split with `forEach`.
- **A job ends with its world.** Leaving a world, a client disconnecting, or the server stopping cancels every job a
  game thread started. Its `then` never runs on a world that is gone.
- **A failure is never swallowed.** `onError` (or `failed`) hears it; with neither, it is logged as
  `[cg-job] <name> failed`.

### Who drains

Minecraft's server thread and client thread drain their loops every tick, so jobs started there just work. Any other
thread that starts jobs drains its own loop (`CgJob.drain()`) or a loop you made ([§13](#13-loops-of-your-own)).

---

## 4. Running a job

```java
CgJob<Path> job = CgJob.run(() -> exportMap(snapshot))   // a Supplier: returns the answer
     .then(path -> chat("Saved to " + path))             // the answer
     .onError(e -> chat("Export failed: " + e.detail())) // failed, or cancelled
     .always(spinner::hide)                              // either way, after the others
     .named("map export");                               // what the log calls it
```

### Three kinds of work

| Form | Use |
|---|---|
| `CgJob.run(() -> answer)` | work that returns an answer |
| `CgJob.run(() -> { ... })` | work that returns nothing; `then(() -> ...)` hears when it is done |
| `CgJob.run(ctx -> answer)` | long work that polls `ctx` to stop when nobody wants it, and reports progress ([§10](#10-cancelling-and-progress)) |

### Hearing the answer

| Call | Runs |
|---|---|
| `then(answer -> ...)`, `then(() -> ...)` | with the answer |
| `onError(e -> ...)` | with a `CgReplyError`: the work threw (`CgReplyError.FAILED`, its throwable in `e.cause()`), or it was cancelled (`CgReplyError.CANCELLED`) |
| `failed(throwable -> ...)` | with what the work threw; never for a cancel |
| `always(() -> ...)` | either way |
| `map(answer -> other)` | a new reply with the answer turned into something else |

- Listeners run on the starting thread, in the order you added them. **A listener added after the job settled runs at
  once**, so it never matters whether the work happened to finish first.
- `isDone()`, `result()` and `error()` read a settled job; `isRunning()` says whether its work is on a worker now.
- **One listener throwing spares the others**; the throw is logged.

### Stopping one

```java
job.cancel();
```

Nothing of a cancelled job lands: its `then` never runs, `onError` hears `CANCELLED`, `always` runs. Work that polls its
context stops early; work that doesn't runs to its end for nothing, its answer dropped. Cancel on the starting thread
settles at once; from another thread, at its loop's next drain.

---

## 5. Handing work the world

The work needs the world, and may not touch it. Hand it a copy.

### A snapshot of the blocks and entities

```java
CgCollisionWorld.of(level).snapshot(x, y, z, 24)          // CgReply<CgCollisionWorld>: the area, frozen
        .flatMap(area -> CgJob.run(() -> {                // area arrives here; the job runs on a worker
            CgHits hits = new CgHits();                   // the worker's own
            area.overlap(BLAST).at(x, y, z).living().into(hits);   // query the snapshot as the level
            return hits;                                  // the job's answer: a CgHits
        }))
        .then(hits -> hits.forEach(h -> hurt(h.entity())));   // hits: what the work returned, on this thread
```

A snapshot is a world any number of threads may query at once, frozen at the end of a server tick: entities where they
stood, blocks as they were. It arrives as a reply: at once when the area is already mirrored, else at a later tick's
end. A server level keeps one mirror for everyone who asks, so asking costs the tick next to nothing; a simulation that
runs every tick watches its area instead ([§8](#8-repeating-jobs-a-simulation-beside-the-game)). The full story is
[`COLLISION.md`](COLLISION.md) §12.

### Your own values

```java
List<Debris> copy = List.copyOf(pieces);              // an immutable copy: the worker cannot see later changes
int seed = level.random.nextInt();                    // plain values are copies already
CgJob.run(() -> simulate(copy, seed)).then(next -> pieces = next);
```

- **Return the next state**, never edit the one you handed over: your thread keeps drawing the old one until `then`.
- **Records and immutable lists are ideal**: nobody can change them under the worker.

---

## 6. Keyed jobs and debounce

Some work keeps being asked for: a reparse per keystroke, a path recomputed as the target moves, a preview per slider
drag. Only the latest answer matters. **Key** it, and a newer start replaces the older.

```java
CgJob.keyed(document, "reparse").run(ctx -> parse(text, ctx)).then(document::setTree);
```

- **A key is an owner and a kind.** The owner is a live object, compared by identity (a document, an entity, a
  machine), so two documents' reparses never cancel each other. The kind names the work.
- **A newer start replaces a waiting job**, which never runs, and **supersedes a running one**: its context is
  cancelled, its answer dropped whether it polled or not, and its reply **never settles**. Wait on the newest one.
- The newer job waits for the superseded one's work to return before it starts, so two never run for one key.

### Debounce: run once, after the last ask

```java
CgJob.keyed(this, "path").debounce(250).run(() -> findPath(snapshot, target)).then(this::follow);
```

The job starts 250 ms after the last start of its key; each new start pushes it out. A burst of asks runs once.
Measured on the loop's clock, checked at its drain.

### Cancelling by key

```java
CgJobLoop.current().cancel(document, "reparse");      // that one, waiting or running
CgJobLoop.current().cancelAll(document);              // every job keyed to the document: what closing it calls
```

### A builder, reused

`CgJob.keyed(...)`, `CgJob.lane(...)` and `CgJob.on(...)` return a builder. Each `run` starts a job with its settings,
so a builder can be kept and started again:

```java
private final CgJob.Builder reparse = CgJob.keyed(document, "reparse").debounce(300);
reparse.run(ctx -> parse(text, ctx)).then(document::setTree);       // every keystroke
```

---

## 7. Lanes: what goes first

A free worker takes the most urgent job waiting.

| Lane | For |
|---|---|
| `INTERACTIVE` | someone is mid-gesture and blocked on it: a completion, a drag's preview. Keep it short: it runs ahead of everything |
| `LATENCY` | visible, but a few frames late is fine: a simulation step, a reparse. **The default** |
| `BACKGROUND` | nobody is watching: an index, a download, a cache warmed |

```java
CgJob.lane(CgJobLane.INTERACTIVE).run(() -> complete(prefix)).then(popup::show);
CgJob.lane(CgJobLane.BACKGROUND).keyed(this, "index").run(() -> indexRecipes()).then(this::setIndex);
```

- **A job waiting past two seconds goes first, whatever its lane**, so background work under a steady stream of
  urgent work still runs.
- **`BACKGROUND` never takes the last worker**: long work can never occupy every thread an urgent job needs.

---

## 8. Repeating jobs: a simulation beside the game

A simulation that steps again and again, its state handed round the loop:

```java
CgCollisionWorld.Watch arena = CgCollisionWorld.of(level).watch(ARENA);

CgJob.Repeating<List<Debris>> sim = CgJob.repeat(arena::latest)   // read: this thread, a CgCollisionWorld
        .work(area -> simulate(area, pieces))                     // work: a worker, returns a List<Debris>
        .then(next -> pieces = next)                              // apply: this thread, next is that list
        .named("debris");

sim.cancel();                                                     // when done
arena.close();
```

1. **`read`** runs on your thread before each run: what this step needs, fresh. Here the watch's newest world.
2. **`work`** runs on a worker with what `read` returned, and returns the next state.
3. **`then`** runs on your thread with it.

- **A `read` answering null skips the run** and is asked again next tick: a watch's `latest()` before its first tick.

- **One run at a time, at most one a tick.** A step slower than a tick simply runs less often; the next begins after
  the last one's `then`.
- **It starts at your thread's next tick** and runs until `cancel()`, a failure (of `read`, the work or `then`, which
  `failed(...)` hears and which ends it), or the world ending.
- **Hand the state round**: `work` returns a new state, `then` keeps it, and the next `read` or `work` uses it. Never
  edit the state the worker holds.
- **A per-tick job is a `repeat`**, never a `run` per tick: `run` allocates a job each time; `repeat` allocates once.

---

## 9. Splitting work now: forEach

A job's answer arrives next tick. Sometimes you need the work done *now*, faster than one thread can: stepping a
thousand shards inside this tick. `forEach` spreads the items over your thread and the workers, and returns once every
item has run.

```java
CgJob.forEach(shards, s -> s.step(area));             // a list's items

private final IntConsumer stepEach = i -> shards[i].step(area);   // held in a field: a lambda per call allocates
CgJob.forEach(shards.length, stepEach);               // indices 0..n-1, allocating nothing
```

- **Items run in any order, on any thread.** One item must never read what another writes: give each its own output
  slot (`results[i] = ...`).
- **Your thread works too**: it takes items alongside the workers, so nothing waits idle.
- **Inside a job's work**, split with `forEach` (never start a job there). A `forEach` inside a `forEach` item runs
  inline.
- **It has its own pool** (`crystalgraphics-each-*`), apart from jobs': a helper waiting behind a long job would hold
  your frame.
- With a single item, or one thread configured, it all runs on your thread.

---

## 10. Cancelling and progress

Long work takes a context: `CgJob.run(ctx -> ...)`.

### Stopping when nobody wants it

```java
CgJob.keyed(this, "index").run(ctx -> {
    for (Path file : files) {
        ctx.throwIfCancelled();                       // cancelled or superseded: stop here, nothing is reported
        index(file);
    }
    return index;
});
```

- **Cancelling is cooperative.** Poll at loop heads and between phases. Work that never polls still has its answer
  dropped; it only runs to the end for nothing.
- `throwIfCancelled()` throws `CgJobContext.CancelledException`: control flow, not a failure, so nothing is logged.
  `isCancelled()` asks without throwing. Once cancelled, it stays cancelled.

### Reporting how far along

```java
CgJob<Index> job = CgJob.run(ctx -> {
    CgProgress progress = ctx.progress();
    progress.begin("Indexing recipes", recipes.size());
    for (int i = 0; i < recipes.size(); i++) {
        ctx.throwIfCancelled();
        progress.detail(recipes.get(i).id());
        add(recipes.get(i));
        progress.advance(i + 1);                      // absolute: done so far, never a delta
    }
    return index;
});

// any thread, any frame
CgProgressState state = job.progress();               // null until the work calls begin
if (state != null) {
    bar.set(state.fraction());                        // 0..1; negative when there is no total: draw a stripe
    label.set(state.what() + " " + state.detail());
    sizeLine.set(state.summary());                    // "12.4 MB of 48.9 MB · 30s left" for bytes; null for items
}
```

- **`advance` takes the absolute count**: a dropped delta would desync the bar for good.
- **A download announces itself before the size is known**, then retargets:

  ```java
  progress.begin("Downloading pack", -1, CgProgress.Unit.BYTES);               // indeterminate while connecting
  progress.begin("Downloading pack", response.length(), CgProgress.Unit.BYTES);
  ```

- **Each report makes a reading**: report on a threshold (every percent, every file), not per 8 KB chunk.
- A reading (`CgProgressState`) is whole and immutable: `what`, `detail`, `done`, `total`, `fraction()`,
  `isIndeterminate()`, `secondsRemaining` (refreshed about once a second, negative before there is one), `summary()`.
- **Progress outside a job**: a `CgProgressTracker` keeps the latest reading for readers on any thread, with the same
  ETA. One writer at a time.

  ```java
  CgProgressTracker progress = new CgProgressTracker(System::currentTimeMillis);
  download(url, progress);                            // reports on its own thread
  CgProgressState now = progress.state();             // read from the frame
  ```

---

## 11. Replies: answers that are not here yet

A job, a network request (`CgRequest.ask`), a download: each is an answer that arrives later. They share one shape,
`CgReply`, so they compose.

```java
CgReply<Stats> stats = STATS.ask(machinePos);                       // a network request (NETWORKING.md §5)
CgReply<Recipe[]> recipes = CgJob.run(() -> loadRecipes());         // a job

CgReply.both(stats, recipes).then(pair -> screen.show(pair.first(), pair.second()));
CgReply.all(List.of(loadIcons, loadFonts)).then(() -> open());      // several, no answers needed
CgReply<Integer> count = recipes.map(r -> r.length);                // a view over one
```

| Call | Does |
|---|---|
| `then(answer -> ...)`, `then(() -> ...)` | hears the answer |
| `onError(e -> ...)` | hears a `CgReplyError`: a failure, a timeout or a cancel |
| `always(() -> ...)` | hears either |
| `map(f)` | a reply of the answer turned into something else |
| `cancel()` | settles it `CANCELLED`, so `always` still runs and anything waiting on it completes |
| `isDone()`, `result()`, `error()` | a settled reply |
| `CgReply.both(a, b)` | both answers, as `pair.first()` and `pair.second()` |
| `CgReply.all(replies)` | every reply settled |
| `CgReply.of(value)`, `CgReply.failed(error)` | one already settled: for code that sometimes answers at once |

- **Listeners run on the thread that owns the reply**: for a job, the thread it reports to; for a request, the asking
  side's game thread. Replies are not thread-safe, deliberately: register and cancel from that thread.
- **A listener registered after it settled runs at once.**

### Why it failed: `CgReplyError`

```java
reply.onError(e -> {
    if (e.is(CgReplyError.CANCELLED)) return;        // nobody is waiting: nothing to say
    status.show("Failed: " + e.detail());
});
```

A code to branch on (`CANCELLED`, `FAILED`, `TIMEOUT`, or your own), a detail to show, the throwable behind it
(`cause()`), and data the code needs to recover (`data()`):

```java
reply.fail(new CgReplyError("CONFLICT", "the file changed", null, currentEtag));
if (e.is("CONFLICT")) reread((String) e.data());
```

### Answering a request from a job

A network request whose answer is too heavy for the tick answers from a job:

```java
STATS.onServer((peer, pos, reply) -> reply.from(CgJob.run(() -> scan(snapshotAt(pos)))));
```

### Making your own reply

Something of yours that answers later (a connection, a callback API) hands out a `CgReply` and settles it itself:

```java
CgPendingReply<Page> reply = new CgPendingReply<>(() -> request.abort());   // what cancel tells the producer
connection.onPage(page -> reply.resolve(page));                            // on the owning thread
connection.onClose(why -> reply.fail(new CgReplyError("CLOSED", why, null)));
return reply;
```

- **Settle on the thread that owns it**, where its listeners run: never from a worker.
- **The second settle is ignored**, so a cancel racing an answer is harmless.
- `fail` returns whether anyone heard it, so an unheard failure can be logged.

### Answers in pieces: `CgStream`

A reply that arrives in pieces: each piece to `onPartial` in order, the whole list to `then` once.

```java
CgStream<Entry> listing = files.list(dir);
listing.onPartial(tree::append)                       // each page as it lands
       .then(all -> status.show(all.size() + " files"));

// the producer's half
CgPendingStream<Entry> out = new CgPendingStream<>(request::abort);
connection.onPage(page -> page.forEach(out::emit));
connection.onEnd(out::finish);
```

---

## 12. Fire and forget

Work whose result nobody needs (a cache warmed, a file written, a log flushed) can skip the reply:

```java
CgJob.executor(CgJobLane.BACKGROUND).execute(() -> sheet.parseEveryValue());
```

The pool as a plain `Executor`, its tasks queued in a lane beside every job's. A task that throws is logged. Anything
whose result the caller needs is a `run` job instead.

---

## 13. Loops of your own

Most code never names a loop: `CgJob.run` uses the current thread's, and Minecraft's threads drain theirs every tick.
Make your own when work runs as a sequence of tasks on whichever pool thread is free (a UI that frames on a sequence,
CrystalGUI's `UiSequence`), or when a thread that is not a game thread starts jobs.

```java
CgJobLoop jobs = CgJobLoop.create("desktop");

sequence.execute(() -> {
    try (CgJobLoop.Scope ignored = jobs.enter()) {     // jobs started in here report to `jobs`
        jobs.drain();                                  // then()s of finished jobs run here
        document.frame(delta, w, h);                   // CgJob.run inside reports back to `jobs`
    }
});

jobs.close();                                          // cancels everything and refuses new jobs
```

| Call | Does |
|---|---|
| `CgJobLoop.create(name)` | a loop on the shared pool and a monotonic clock |
| `CgJobLoop.current()` | the loop entered on this thread, else the thread's own |
| `enter()` | makes it the thread's current loop until the scope closes |
| `drain()` | steps each job once in start order: delivers, starts what is due, settles what was cancelled; allocates nothing |
| `cancel(owner, kind)`, `cancelAll(owner)` | cancels by key |
| `pendingCount()` | jobs not yet ended |
| `close()` | cancels every job and refuses new ones |
| `CgJob.on(loop)` | starts a job reporting to that loop rather than the current one |

- **Start, drain and cancel on the loop's own thread or sequence**, never two threads at once.
- **A thread's own loop ends its jobs with the game session**; a loop from `create` does not, and ends them at
  `close()`.
- A job started by a `then` waits for the next drain.

---

## 14. Testing

A test steps a loop by hand, with work run inline and a clock it turns: no threads, no sleeps, no flakes.

```java
@Test
public void aBurstOfEditsParsesOnce() {
    long[] now = {0};
    CgJobLoop loop = CgJobLoop.create("test", Runnable::run, () -> now[0]);   // work inline, a clock you turn
    List<Tree> trees = new ArrayList<>();
    Object doc = new Object();

    for (String text : List.of("a", "ab", "abc")) {
        CgJob.on(loop).keyed(doc, "parse").debounce(300).run(() -> parse(text)).then(trees::add);   // replaces the last
    }

    now[0] += 300;
    loop.drain();                                      // due: runs inline
    loop.drain();                                      // delivers
    assertEquals(1, trees.size());                     // "abc" alone: the replaced two never ran
}
```

- `CgJobLoop.create(name, executor, clockMillis)` runs every job on your executor and measures debounces on your clock;
  lanes are ignored.
- A job's work and its delivery are two drains: the first runs what is due, the second delivers what finished.
- Collision work in a test needs no Minecraft: an empty world or a world of fakes ([`COLLISION.md`](COLLISION.md) §15).

---

## 15. Performance and settings

| Setting | Default | Does |
|---|---|---|
| `crystalgraphics.job.threads` | half the cores past two, at least one | threads jobs' work runs on |
| `crystalgraphics.job.forEachThreads` | two fewer than the cores | threads `forEach` and effects' emitters run on, your thread one of them; 1 runs all on your thread |

Both are read at launch (`-Dcrystalgraphics.job.threads=4`; [`DEBUG_FLAGS.md`](DEBUG_FLAGS.md)).

- **Workers run a step below normal priority**, so the game's own threads come first. Both pools are daemon threads,
  started at their first use: a server that never runs a job starts none.
- **`drain()` and `forEach` allocate nothing.** `run` allocates its job and its reply: fine for an occasional job, not
  for one per tick. A per-tick job is a `repeat`.
- **Handing work the world costs the tick little**: a server level's snapshots come from one shared mirror
  ([`COLLISION.md`](COLLISION.md) §12, §16). Size the area to what the work reaches all the same: the entities in it
  are read each tick.

---

## 16. Questions people ask

### Job, repeat or forEach?

| You want | Use |
|---|---|
| One answer, later, without holding up the tick | `CgJob.run` |
| The latest answer only, as the input keeps changing | `CgJob.keyed(...).debounce(...).run` |
| A simulation stepping beside the game | `CgJob.repeat(read).work(step).then(apply)` |
| A loop finished faster, inside this tick | `CgJob.forEach` |
| Work nobody waits for | `CgJob.executor(lane).execute` |

### Can my work read Minecraft's world?

No: copy it first. A collision snapshot covers blocks and entities; anything else (an inventory, a block entity's data)
you copy into your own values before `run`. `CgCollisionWorld.of(level)` throws on a worker to catch the common
mistake.

### Where does `then` run, exactly?

On the thread that started the job, at its loop's next drain after the work finished: the start of the next server or
client tick for jobs started on Minecraft's threads.

### What happens to my job when the player leaves the world?

It is cancelled: `then` never runs, `onError` hears `CANCELLED`, `always` runs. Work that polls its context stops.

### Why did my keyed job never answer?

A newer start of the same key superseded it. Superseded jobs never settle: listen on the newest one.

### Can I wait for a job to finish?

Not by blocking: a game thread that blocks stalls the game. Put what comes next in `then`, or combine replies with
`CgReply.both` / `all`.

### Is that how other engines do it?

Yes. Unity's job system copies data into jobs and completes them on the main thread; Bevy's async compute tasks are
polled and applied by a later system; Minecraft builds chunk meshes from copied sections on workers and uploads them on
the render thread. The keys, debounce and lanes are how editors (VS Code, IntelliJ) schedule reparses and completions;
the progress estimate follows Chromium's download rate estimator.

### Where is the code?

`com.crystalgraphics.job` (jobs, loops, lanes, `forEach`), `job.reply` (replies, streams, errors), `job.progress`
(progress and its ETA). Where each class lives: `job/CLAUDE.md`.
