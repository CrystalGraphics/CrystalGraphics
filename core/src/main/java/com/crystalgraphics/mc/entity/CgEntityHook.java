package com.crystalgraphics.mc.entity;

/**
 * Code of the author's around each draw of an entity picture or a scene's child entity, on the render thread with the
 * game's own entity: change it for the draw, then put it back. Unreal's {@code PreRender} and {@code PostRender} pair.
 *
 * <pre>{@code
 * CgEntityPicture knight = CgEntityPicture.of(CgEntityRef.parse("zombie"))
 *         .hook(new CgEntityHook() {
 *             public void before(Object entity, float partialTick) {
 *                 Zombie z = (Zombie) entity;
 *                 z.setItemSlot(EquipmentSlot.MAINHAND, sword);
 *                 z.setPose(Pose.CROUCHING);
 *             }
 *             public void after(Object entity) {
 *                 Zombie z = (Zombie) entity;
 *                 z.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
 *                 z.setPose(Pose.STANDING);
 *             }
 *         });
 * }</pre>
 *
 * A change for good needs no hook: {@code picture.entity()} is the same object each draw.
 *
 * <ul>
 *   <li>{@code before} runs before the pose is applied, so it sees the entity's own angles; {@code after} runs once the
 *       pose is put back, even where the draw threw.</li>
 *   <li>A live entity is the level's: what {@code before} changes, {@code after} must restore, or the world sees it.</li>
 * </ul>
 */
public interface CgEntityHook {

    /** Draws nothing different: the default. */
    CgEntityHook NONE = new CgEntityHook() { };

    default void before(Object entity, float partialTick) {
    }

    default void after(Object entity) {
    }
}
