# Input

How a host's input reaches code that is not the host's: a callback registered on a **stage**, fired by the hosts. The same
shape as `CgRenderStage`, with one difference: a render callback always runs, an input callback may **consume** the event,
and then nobody after it, the game included, sees it.

```java
// A mod: take the clicks that land on its own panel, from whatever screen is in front
CgInputStage.Registration taking = CgInputStage.SCREEN.register(0, new CgInputListener() {
    @Override public boolean mouseButton(CgSystemInput.Mouse.Event e) { return panel.contains(e.x(), e.y()) && panel.click(e); }
    @Override public boolean key(CgSystemInput.Keyboard.Event e) { return panel.focused() && panel.type(e); }
});
taking.close();   // stops it
```

## The listener

`CgInputListener` has one method per kind of event; each returns **whether it consumed the event**, and each defaults to
false, so a listener writes only what it takes.

| Method | Event |
|---|---|
| `mouseMoved(Mouse.Event)` | the pointer moved. Delivered to every listener, never consumed |
| `mouseButton(Mouse.Event)` | a button went down or up |
| `scrolled(Mouse.Event)` | the wheel turned |
| `key(Keyboard.Event)` | a key with no character, down or up |
| `character(Keyboard.Event)` | a character with no key |
| `composition(String text, int caret)` | an input method's run in progress; an empty text ends it |

- **One vocabulary, the engine's.** Events are `CgSystemInput`'s records. Positions are raw surface pixels from the top
  left, keys are `CgKeyCodes`, buttons are `CgMouseCodes`, a wheel notch is ±1 with positive rolled down, and a move
  carries no click time. The host converts, once: a listener never sees GLFW, SDL, LWJGL 2 or a GUI scale.
- **A listener holds no host type.** What it needs of the game goes through the platform services.

## Stages

| Stage | Fired for |
|---|---|
| `CgInputStage.SCREEN` | an event heading for the screen in front, before the screen sees it |

A mod defines its own the way it defines a render stage, `CgInputStage.define("mymod:radial")`, and fires it from a hook
of its own.

- **Order.** `register(order, listener)`: lower first, ties in registration order. The first listener to consume stops
  the event; a move reaches all of them.
- **Consumed means the host does not act.** A consumed `SCREEN` event never reaches the screen.
- **Thread.** Fired on the thread the host delivers input on, the render thread on every version.
- **A listener that throws** is logged and treated as not having consumed; it is not removed.
- **The pointer.** `CgInputStage.pointerGrabbed()` is whether the game holds the cursor, the one question a listener
  cannot answer from an event.
- **Wanting input.** `CgInputListener.wants()` (default true) says whether the listener wants input right now.
  `CgInputStage.wanted()` is whether any does; a host that must do work to produce input asks it first (legacy Forge
  drains a foreign screen's queue each frame only while some listener wants).

## The frame's move

Minecraft 1.20 posts no move event to a foreign screen. Modern hosts therefore fire one `mouseMoved` per frame, with the
pointer where it is, from the hook that fires the `SCREEN` render stage; legacy and 1.7.10 get a move per queued LWJGL
event. A listener never polls.

## What each host does

| Era | Hooks it owns |
|---|---|
| Forge 1.13.2-26.3 | the screen input events (`ScreenEvent.*` / `GuiScreenEvent.*`, per generation): `ScreenInputForge` |
| NeoForge 1.20.2-26.3 | `ScreenEvent.*`: `ScreenInputNeoForge` |
| Fabric 1.14.4-26.3 | the window's callbacks, chained at `CLIENT_STARTED`; SDL's event filter from 26.3: `ScreenInputFabric` |
| Forge 1.8-1.12.2 | `GuiScreenEvent.MouseInputEvent.Pre`, `KeyboardInputEvent.Pre`, once per queued event: `ScreenInputLegacy` |
| Forge 1.7.10 | a mixin on `GuiScreen.handleInput`, which has no event: `MixinGuiScreen`, `ScreenInput1710` |

Each hook builds the event (`ScreenInputModern` on every modern loader) and fires the stage; none decides anything.
The decision of who takes it is the listeners'.

## Mistakes that are easy to make

- A listener that consumes a move: it is delivered to all and the return is ignored.
- Reading coordinates from a screen callback: they are GUI-scaled. The host reads the raw ones.
- A registration kept past its owner: `close` it, or it fires into a dead object.
- Consuming a key without owning the keyboard: the game's controls go dead until the player clicks away.
