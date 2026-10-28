package com.crystalgraphics.render.stage;

/**
 * Work one renderer hands another to record at a point inside its own firing, through the firing's blackboard: the
 * post stack's before-translucency materials, recorded by the world renderer after its lights and before its
 * transparent passes, as Unreal's renderer runs a blendable location's chain where it stands.
 *
 * <pre>{@code
 * stage.resources().put(CgFrameKeys.BEFORE_TRANSLUCENCY, this::recordBeforeTranslucency);   // at a lower order
 *
 * CgStageHook hook = stage.resources().get(CgFrameKeys.BEFORE_TRANSLUCENCY);              // the renderer, at its point
 * if (hook != null) hook.record(stage);
 * }</pre>
 */
@FunctionalInterface
public interface CgStageHook {

    void record(CgStageFrame stage);
}
