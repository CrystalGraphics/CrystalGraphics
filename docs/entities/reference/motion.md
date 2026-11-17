# Reference: what an entity is doing (`CgEntityProperties.MOTION`)

Every field of the engine's motion type, on every entity, on both sides. Java reads `CgEntityProperties.<NAME>`; a
graph names it `entity.<name>`. How to use them: [`../guides/what-it-is-doing.md`](../guides/what-it-is-doing.md).

Units: blocks, seconds and degrees. Yaw 0 faces +z. Nothing here is sent or saved.

<!-- generated: entity motion fields (MotionFieldsTableTest) -->
| Field | `entity.` name | Kind | Means |
|---|---|---|---|
| **Motion**, smooth across the frame | | | |
| `SPEED` | `speed` | float | along the ground, blocks a second |
| `VERTICAL_SPEED` | `vertical_speed` | float | up positive, blocks a second |
| `MOVE_DIRECTION` | `move_direction` | float | which way it moves against its body's facing, degrees: 0 ahead, 90 left, 180 back; 0 standing |
| `ACCELERATION` | `acceleration` | float | how fast its ground speed changes, blocks a second a second, over its last two ticks |
| `TURN_RATE` | `turn_rate` | float | how fast its body turns, degrees a second, left positive |
| **Intent** | | | |
| `INPUT` | `input` | float | how hard it means to move, 0 to 1: the player's stick or keys where the host has them, else from its movement |
| `INPUT_DIRECTION` | `input_direction` | float | which way it means to move against its facing, degrees, 90 left |
| `INPUT_FROM_PLAYER` | `input_from_player` | bool | whether `INPUT` is the player's own input rather than read from its movement |
| `SPRINT_HELD` | `sprint_held` | bool | the sprint key is held |
| `JUMP_HELD` | `jump_held` | bool | the jump key is held |
| **Ground and air** | | | |
| `GROUNDED` | `grounded` | bool | on the ground |
| `AIRBORNE` | `airborne` | bool | off the ground and riding nothing |
| `AIR_TIME` | `air_time` | float | seconds off the ground; 0 on it |
| `GROUND_DISTANCE` | `ground_distance` | float | its feet over the first surface under them, blocks: 0 on the ground, infinite where none is found in 64 |
| `LAND_HEIGHT` | `land_height` | float | how far it fell to its last landing, blocks, from the highest point it reached |
| **Stance** | | | |
| `LIVING` | `living` | bool | it is a living entity |
| `SNEAKING` | `sneaking` | bool | sneaking |
| `SPRINTING` | `sprinting` | bool | sprinting |
| `SWIMMING` | `swimming` | bool | in the swimming pose in water |
| `CRAWLING` | `crawling` | bool | in the swimming pose out of water: under a block too low to stand in |
| `SWIM_AMOUNT` | `swim_amount` | float | 0 standing to 1 lying in the swimming pose, eased as Minecraft eases it |
| `GLIDING` | `gliding` | bool | gliding on elytra |
| `SPIN_ATTACK` | `spin_attack` | bool | in a riptide spin |
| `IN_WATER` | `in_water` | bool | in water |
| `RIDING` | `riding` | bool | riding something |
| `VEHICLE` | `vehicle` | int | the network id of what it rides; -1 for nothing |
| `SLEEPING` | `sleeping` | bool | asleep in a bed |
| **Life** | | | |
| `DEAD` | `dead` | bool | dead |
| `DEAD_TIME` | `dead_time` | float | seconds since it died; 0 while alive |
| `HURT_TIME` | `hurt_time` | float | seconds since it was last hurt; infinite before the first hit seen |
| **Look** | | | |
| `HEAD_YAW` | `head_yaw` | float | the head's yaw against the body, degrees, left positive |
| `HEAD_PITCH` | `head_pitch` | float | the head's pitch, degrees, down positive |
| **Hands** | | | |
| `LEFT_HANDED` | `left_handed` | bool | its main hand is its left |
| `MAIN_ITEM`, `OFF_ITEM` | `main_item`, `off_item` | int | what each hand holds, by the game's raw item id: 0 for nothing (`CgItems.name`, `CgItems.matches`) |
| `MAIN_ARM`, `OFF_ARM` | `main_arm`, `off_arm` | int | how each arm is posed: an `ARM_` constant, below |
| `USING` | `using` | bool | using an item: eating, drawing a bow, blocking |
| `USE_HAND` | `use_hand` | int | the hand in use, or last in use: `MAIN` (0) or `OFF` (1) |
| `USE_ANIMATION` | `use_animation` | int | how the item in use looks in use: a `CgEntityFacts.USE_` constant |
| `USE_TIME` | `use_time` | float | seconds into the current use; 0 using nothing |
| `USE_LENGTH` | `use_length` | float | how long the current use lasts at most, seconds; 0 where the game cannot say |
| `SWINGING` | `swinging` | bool | an arm is swinging |
| `SWING` | `swing` | float | the swing's progress, 0 to 1: what Minecraft's own arm swing follows |
| `SWING_HAND` | `swing_hand` | int | the swinging hand: `MAIN` or `OFF` |
| **Events**, true for the tick or frame they happen in | | | |
| `JUMPED` | `jumped` | bool | it left the ground going up (walking off a ledge is not a jump) |
| `LANDED` | `landed` | bool | it touched the ground again; how far it fell is `LAND_HEIGHT` |
| `HURT` | `hurt` | bool | it was hurt, a second hit before the first wore off included |
| `SWING_STARTED` | `swing_started` | bool | an arm began a swing, or began it again; which is `SWING_HAND` |
| `USE_STARTED` | `use_started` | bool | it began using an item; with which hand is `USE_HAND` |
| `USE_STOPPED` | `use_stopped` | bool | it stopped using an item |
| `MOUNTED` | `mounted` | bool | it got on something, `VEHICLE`; changing mounts raises this and `DISMOUNTED` together |
| `DISMOUNTED` | `dismounted` | bool | it got off something |
| `DIED` | `died` | bool | it died |
<!-- end generated -->

## Arm poses

`MAIN_ARM` and `OFF_ARM` hold one of these, Minecraft's own rule for a player's arms (its newest version's), for every
entity:

| Constant | Value | While |
|---|---|---|
| `ARM_EMPTY` | 0 | nothing held |
| `ARM_ITEM` | 1 | holding an item |
| `ARM_BLOCK` | 2 | a shield up |
| `ARM_BOW` | 3 | a bow drawn (both arms) |
| `ARM_THROW` | 4 | a trident raised |
| `ARM_CROSSBOW_CHARGE` | 5 | a crossbow charging (both arms) |
| `ARM_CROSSBOW_HOLD` | 6 | a charged crossbow held (both arms) |
| `ARM_SPYGLASS` | 7 | looking through a spyglass |
| `ARM_TOOT_HORN` | 8 | blowing a goat horn |
| `ARM_BRUSH` | 9 | brushing |
| `ARM_SPEAR` | 10 | a spear raised |

## By version

What a version lacks reads as not happening:

| Missing before | Fields that never turn on |
|---|---|
| 1.13 | `SWIMMING`, `CRAWLING`, `SWIM_AMOUNT`, `SPIN_ATTACK` |
| 1.10.2 | `GLIDING`, `OFF_ITEM`, `OFF_ARM` |
| 1.14.3 | `ARM_CROSSBOW_CHARGE`, `ARM_CROSSBOW_HOLD` |
| 1.21.3 | a server's `INPUT_FROM_PLAYER` |
