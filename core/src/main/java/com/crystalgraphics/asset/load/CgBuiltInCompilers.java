package com.crystalgraphics.asset.load;

import com.crystalgraphics.animation.blend.CgAimOffsetNode;
import com.crystalgraphics.animation.blend.CgBlendSpaceCodec;
import com.crystalgraphics.animation.blend.CgBlendSpacePlayerNode;
import com.crystalgraphics.animation.blend.CgInertializationNode;
import com.crystalgraphics.animation.graph.CgAnimGraphCodec;
import com.crystalgraphics.animation.ik.CgApplyLimitsNode;
import com.crystalgraphics.animation.ik.CgBoneDrivenControllerNode;
import com.crystalgraphics.animation.ik.CgCcdIkNode;
import com.crystalgraphics.animation.ik.CgConstraintNode;
import com.crystalgraphics.animation.ik.CgCopyBoneNode;
import com.crystalgraphics.animation.ik.CgFabrikNode;
import com.crystalgraphics.animation.ik.CgFootPlacementNode;
import com.crystalgraphics.animation.ik.CgLegIkNode;
import com.crystalgraphics.animation.ik.CgOffsetRootBoneNode;
import com.crystalgraphics.animation.ik.CgOrientationWarpingNode;
import com.crystalgraphics.animation.ik.CgStrideWarpingNode;
import com.crystalgraphics.animation.locomotion.CgSequenceEvaluatorNode;
import com.crystalgraphics.animation.ik.CgLookAtNode;
import com.crystalgraphics.animation.ik.CgModifyBoneNode;
import com.crystalgraphics.animation.ik.CgAnimDynamicsNode;
import com.crystalgraphics.animation.ik.CgSpringBoneNode;
import com.crystalgraphics.animation.ik.CgTwoBoneIkNode;
import com.crystalgraphics.animation.retarget.CgRetargeter;
import com.crystalgraphics.animation.rig.CgRig;
import com.crystalgraphics.animation.state.CgAnimStateMachineNode;
import com.crystalgraphics.api.animation.CgClipDefinition;
import com.crystalgraphics.api.animation.CgSkeletonDefinition;
import com.crystalgraphics.sequence.binding.CgFigureDefinition;
import com.crystalgraphics.api.material.CgMaterialDefinitions;
import com.crystalgraphics.api.material.CgParameterCollections;
import com.crystalgraphics.render.post.CgOutline;
import com.crystalgraphics.render.scene.prefab.CgPrefabAssets;
import com.crystalgraphics.asset.camera.CgCameraRigDefinition;
import com.crystalgraphics.asset.camera.CgCameraShakeDefinition;
import com.crystalgraphics.asset.curve.CgCurveDefinition;
import com.crystalgraphics.cue.CgCueDefinition;
import com.crystalgraphics.presentation.stage.CgStageAssets;
import com.crystalgraphics.net.object.CgNetTypeCompiler;
import com.crystalgraphics.world.CgMoveLocks;
import com.crystalgraphics.world.actor.CgActorFiles;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.text.style.CgTextStyle;
import com.crystalgraphics.widget.CgWidgetCodec;

/**
 * Registers the engine's own kind compilers: materials, parameter collections, curves, camera rigs and shakes, sequences, skeletons, cues, stages, prefabs and text styles. Runs on both sides,
 * when the process's assets are made ({@link CgVfxAssets}), so a dedicated server compiles what a client does.
 *
 * <pre>{@code
 * CgBuiltInCompilers.register();                         // idempotent; CgVfxAssets calls it
 * CgAssetCompiler.of(CgLookKinds.CURVE_KIND);            // CgCurveDefinition.COMPILER
 * }</pre>
 *
 * <ul>
 *   <li>Systems register in {@code CgVfx.install}, since they compile to client playables.</li>
 *   <li>Calling it again re-registers the same compilers: harmless.</li>
 * </ul>
 */
public final class CgBuiltInCompilers {

    private CgBuiltInCompilers() {
    }

    /** Each built-in kind's compiler, replacing {@link CgAssetCompiler#RESOLVED}. */
    public static void register() {
        CgMaterialDefinitions.register();
        CgParameterCollections.register();
        CgOutline.install();   // an engine collection shaders read: declared before any material compiles
        CgCurveDefinition.register();
        CgCameraRigDefinition.register();
        CgCameraShakeDefinition.register();
        CgSequenceDefinition.register();
        CgSkeletonDefinition.register();
        CgFigureDefinition.register();
        CgClipDefinition.register();
        CgRig.register();
        CgRetargeter.register();
        CgAnimGraphCodec.register();
        CgAnimGraphCodec.registerKind("state_machine", CgAnimStateMachineNode.KIND);
        CgAnimGraphCodec.registerKind("inertialization", CgInertializationNode.KIND);
        CgBlendSpaceCodec.register();
        CgAnimGraphCodec.registerKind("blend_space_player", CgBlendSpacePlayerNode.KIND);
        CgAnimGraphCodec.registerKind("aim_offset", CgAimOffsetNode.KIND);
        CgAnimGraphCodec.registerKind("modify_bone", CgModifyBoneNode.KIND);
        CgAnimGraphCodec.registerKind("two_bone_ik", CgTwoBoneIkNode.KIND);
        CgAnimGraphCodec.registerKind("look_at", CgLookAtNode.KIND);
        CgAnimGraphCodec.registerKind("copy_bone", CgCopyBoneNode.KIND);
        CgAnimGraphCodec.registerKind("fabrik", CgFabrikNode.KIND);
        CgAnimGraphCodec.registerKind("ccd_ik", CgCcdIkNode.KIND);
        CgAnimGraphCodec.registerKind("constraint", CgConstraintNode.KIND);
        CgAnimGraphCodec.registerKind("apply_limits", CgApplyLimitsNode.KIND);
        CgAnimGraphCodec.registerKind("bone_driven_controller", CgBoneDrivenControllerNode.KIND);
        CgAnimGraphCodec.registerKind("leg_ik", CgLegIkNode.KIND);
        CgAnimGraphCodec.registerKind("foot_placement", CgFootPlacementNode.KIND);
        CgAnimGraphCodec.registerKind("spring_bone", CgSpringBoneNode.KIND);
        CgAnimGraphCodec.registerKind("anim_dynamics", CgAnimDynamicsNode.KIND);
        CgAnimGraphCodec.registerKind("stride_warping", CgStrideWarpingNode.KIND);
        CgAnimGraphCodec.registerKind("orientation_warping", CgOrientationWarpingNode.KIND);
        CgAnimGraphCodec.registerKind("offset_root_bone", CgOffsetRootBoneNode.KIND);
        CgAnimGraphCodec.registerKind("sequence_evaluator", CgSequenceEvaluatorNode.KIND);
        CgCueDefinition.register();
        CgStageAssets.register();
        CgPrefabAssets.register();   // an actor spawns its prefab on the server too
        CgNetTypeCompiler.register();
        CgActorFiles.register();
        CgMoveLocks.install();
        CgTextStyle.register();
        CgWidgetCodec.register();
    }
}
