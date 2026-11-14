# animation.state — Unreal's state machines

> Using it: `docs/animation/guides/graphs.md` § *State machines*. The graph it runs in is `animation.graph`.

| Type | Role |
|---|---|
| `CgAnimStateMachine` | `FBakedAnimationStateMachine`: states in order, transitions, the initial state, max transitions per frame, skip-first-update, reinitialize-on-relevant; each state's exits ordered by priority, then as made |
| `CgAnimState` | a state (a pose, `alwaysResetOnEntry`) or a conduit (an entry rule) |
| `CgAnimTransition` | `FAnimationTransitionBetweenStates` with its exit rule: rule or `automatic`, crossfade, `CgAlphaBlend`, inertialization, priority, trigger time, overshoot correction, re-entry, self-inertialization; Unreal's editor defaults |
| `CgAnimRule` | the rule graph as a tree: variables, `time_remaining`, its fraction, `state_time`, `event`, `all`/`any`/`not`; its data form (`read`) |
| `CgAnimStateMachineNode` | `FAnimNode_StateMachine`: transitions found, entries stacked and crossfaded, states updated at their weights and evaluated once, inertialization requested; `Machine`, its state in one instance; transition events; the `state_machine` kind's reader (`KIND`) |

## Rules

- **Unreal decides**: each part cites AnimNode_StateMachine.cpp's lines (update :380-656, `FindValidTransition`
  :741-896, the entry :56-158, `GetStateWeight` :1414-1453, `SetState` :1192-1291, `TransitionToState` :1293-1412).
  A behaviour Unreal has is ported, never approximated.
- **The relevant player** for `automatic` and `time_remaining` is the highest weighted player a state ticked in its
  last update, inside the machine's own player scope: a nested machine's players are not the outer state's.
- **Clocks are the instance's** (`CgAnimGraphInstance.seconds`): re-entry and event lifetimes are deterministic.
- **0 B a frame**: entries are pooled, the search is stamped, states' poses are made once; an event allocates only when
  it is requested, on the game thread.
- **Not played, failing at load**: blend profiles, custom curves, custom transition graphs, state and transition
  notifies (A5's).
- Gates: `CgAnimStateMachineTest` (headless, every number hand-worked), `CgAnimGraphCensusGateTest` (an imported file
  against its census, `-PanimGraphFile` `-PanimGraphCensus`, and `-PanimGraphParent` for a child graph).
