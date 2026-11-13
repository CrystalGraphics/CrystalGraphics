# The gauntlet: running the game unattended

A run plays the game with nobody at the keyboard: it starts the game, plays the tests named on the command line one
after another, writes a report, and quits through the game's own shutdown. Each test drives the game itself (it
enters a world, opens a screen, photographs the frame) and states checks. Unreal's Gauntlet is the model: its
module makes the controllers `-gauntlet=` names, broadcasts the game's state to them and ticks them, and each ends
with `EndTest`; something outside the game launches it and reads the result.

| Piece | What it is |
|---|---|
| `gauntlet.CgGauntlet` | The run: reads the flags, makes the tests, broadcasts states, ticks, writes the report, quits |
| `gauntlet.CgGauntletController` | A test: its moments (`onInit`, `onStateChange`, `onTick`, `onEnd`), its checks, `endTest` |
| `gauntlet.CgGauntletState` | Where the game is: `LOADING`, `TITLE`, `IN_WORLD`, `SERVER`, or a mod's own |
| `gauntlet.CgGauntletCapture` | A photograph of the frame as the player sees it, read back by the engine on every device |
| `platform.service.CgGameSession` | What a test asks of the game: ready, in a world, enter one, quit. Its screens are `CgScreens`' (`anyOpen`, `closeFront`, `openChat`), the pointer `CgInput`'s (`grabPointer`, `movePointer`) |

## 1. Running one

```bash
# a dev client: boot, then the world probe; the report and photographs under build/run/
./gradlew :runtime:mc:modern:forge:1.20.1:runClient -Dcrystalgraphics.gauntlet=crystalgraphics:boot,crystalgraphics:world \
    -Dcrystalgraphics.gauntlet.out=build/run

# any client, any launcher: the same flags as JVM arguments
-Dcrystalgraphics.gauntlet=crystalgraphics:play -Dcrystalgraphics.gauntlet.play.seconds=90
```

| Flag | Default | Does |
|---|---|---|
| `crystalgraphics.gauntlet` | empty | The tests, by id and in order, separated by `,` or by `+` (a launcher config that splits commas: Prism's). Empty: no run |
| `crystalgraphics.gauntlet.out` | `<game>/crystalgraphics/gauntlet` | Where the report and photographs go |
| `crystalgraphics.gauntlet.world` | empty | The save a test enters; empty for the first on disk, or one the host makes |
| `crystalgraphics.gauntlet.timeout` | 300 | Seconds a test has to end before it fails |
| `crystalgraphics.gauntlet.heartbeat` | 0 | Seconds between heartbeat lines (`gauntlet heartbeat: active ...`); 0 for none |

**The report**, `<out>/report.txt`: its first line is `PASS` or `FAIL`, then a line per test and one per check. **An
absent report is a failure**: a game that died before its run ended wrote none. The log carries every line too, and
ends the run with Unreal's `**** TEST COMPLETE. EXIT CODE: 0 ****`.

```
PASS
crystalgraphics:boot: PASS (2 passed, 0 failed, 0 skipped)
  boot.engine: true (the graphics started)
  boot.capture: true (build/run/crystalgraphics-boot-title.png)
crystalgraphics:world: PASS (31 passed, 0 failed, 4 skipped)
  entity.camera: true (eye at the view position)
  events.explosion: skipped (not reported on this version)
```

- **Tests take turns**, in the order named, each on the state the last left: one that needs a world asks for it and
  finds it loaded when another already did. Unreal ticks its controllers together; here they share one game, and the
  net probe kills the player, so they cannot.
- **A name nothing registered fails the run** when its turn comes, naming the registered ones: a client listener may
  register its tests after the run starts.
- **A dedicated server** plays its run on the server's ticks and stops the server at the end.

## 2. The engine's tests

| Id | Where | Proves |
|---|---|---|
| `crystalgraphics:boot` | client, server | The game started, the engine with it, and the title screen drew; photographed |
| `crystalgraphics:play` | client | A world entered and played for `crystalgraphics.gauntlet.play.seconds` (60; 0 until closed), then photographed: a profile's run |
| `crystalgraphics:pipelines` | client | The shipped pipeline list recorded: every demo scene drawn in turn, twice round, `crystalgraphics.gauntlet.pipelines.seconds` (5) each once built; the cache's `recorded.txt` copied into the run's folder (`demo.CgDemoPipelinesTest`, `PRECACHE.md` § *The shipped pipeline list*) |
| `crystalgraphics:world` | client | Every world seam the host answers, against the others (`probe.CgWorldProbe`) |
| `crystalgraphics:collision` | client | Collision against real entities: a ring on stands, a dragon's parts, a painting (`docs/COLLISION.md`) |
| `crystalgraphics:net` | client, server | Networked objects across a player's death, and how late a change arrives (`docs/NETWORKING.md`) |
| `crystalgraphics:server` | dedicated server | The server-side stack is up and no client class loaded (`probe.CgServerSmoke`) |

`crystalgraphics:server` watches the classes every mod declares on a `client` line of its
`META-INF/crystalgraphics/listeners`, the game's own client class, and any a mod names:

```java
CgServerSmoke.clientOnly("com.example.mymod.client.MyRenderer");   // a common listener's onPreInit
```

It reads what loaded from the JVM's own log where there is one (`-Xlog:class+load=info:file=<log>
-Dcrystalgraphics.gauntlet.classlog=<log>`, Java 9 and up), else asks the class loaders, and says so when it cannot.

## 3. Writing a test

```java
public final class SpawnTest extends CgGauntletController {
    @Override protected void onStateChange(CgGauntletState from, CgGauntletState to) {
        if (to.equals(CgGauntletState.TITLE)) enterWorld();
    }

    @Override protected void onTick(float seconds) {
        if (!state().equals(CgGauntletState.IN_WORLD) || timeInState() < 3) return;
        check("spawn.player", CgEntityQueries.client().localPlayer() >= 0, "the local player's entity");
        endTest();
    }
}

// a listener's onPreInit: a client listener's for a client test, a common one's for a server's
CgGauntlet.register("mymod:spawn", SpawnTest::new);
```

A test that photographs waits for the file before it ends:

```java
private CgGauntletCapture.Shot shot;

@Override protected void onTick(float seconds) {
    if (shot == null) shot = capture("title");                    // <out>/mymod-spawn-title.png
    else if (shot.done()) {
        check("spawn.capture", shot.written(), shot.failure());
        endTest();
    }
}
```

- **Its moments run on the render thread**, or a dedicated server's thread; `check`, `skip`, `info` and `endTest` may
  be called from any thread (a server half, an asynchronous answer).
- **A check that cannot be asked here is skipped with its reason**, never passed: a host that does not answer a seam on
  its version, a check that needs a dedicated server.
- **`info` is a measurement**, logged as `name is detail` and never counted.
- **Release what it registered** (a stage renderer, a tick function) in `onEnd`.
- **Something it must set up before the run starts** (a network type, declared at init and named in every
  connection's hello) asks `CgGauntlet.plays("mymod:spawn")` at init.
- **A mod's own state**: `CgGauntlet.broadcastStateChange(new CgGauntletState("mymod:arena"))`, heard by the test at
  the next tick, as Unreal's `BroadcastStateChange`.

## 4. What a host answers: `CgGameSession`

| Call | Modern (1.13.2+) | Legacy Forge, 1.7.10 |
|---|---|---|
| `ready()` | Mojang's loading overlay down | the main menu up, or a world |
| `enterWorld(save)` | a server the game was pointed at, else the save, else the first on disk; **no save is an error**: a new level's settings moved across versions | the save, else the first, else a new flat creative world, `crystalgraphics-gauntlet` |
| `quit()` | `Minecraft.stop()` | `Minecraft.shutdown()` |

`GameSessionModern`, `GameSessionLegacy`, `GameSession1710`, provided with each era's client slots. What a test drives
besides is the services that own it: a screen of the game's through `CgScreens.anyOpen()`, `closeFront()` and
`openChat()` (`ScreensModern`, `ScreensLegacy`, `Screens1710`), and the pointer through `CgInput.grabPointer()` and
`movePointer(x, y)`, each toolkit's (`InputGlfw`, `InputSdl`, `InputLwjgl2` with its bottom-left origin; modern
Minecraft grabs through its own mouse handler, `InputModern`). **The engine
photographs, never the host**: `CgGauntletCapture` reads back the target the frame's stages drew into, at the
host's frame end, so the host's own GUI (which a host may batch and draw after our stages) is in it, and every version
and device takes the same picture.

## 5. CrystalGUI's runner

CrystalGUI launches runs and reads their reports: `prodSmoke` (every installed client on the shipped jar, each
playing `crystalgraphics:boot,crystalgui:desktop` and any `-PcgSmokeTests`), `serverSmoke` (a dedicated server
playing `crystalgraphics:server,crystalgui:server`) and `connectionProbe`. Its `docs/CGUI_BUILD.md` has each.
