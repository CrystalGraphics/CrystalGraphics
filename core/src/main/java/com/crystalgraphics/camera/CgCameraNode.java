package com.crystalgraphics.camera;

/**
 * One node of a running rig: Gameplay Cameras' {@code FCameraNodeEvaluator::OnRun}. It reads the pose the nodes
 * before it left and writes its own change into it.
 *
 * <pre>{@code
 * // a kind of your own, registered before any rig of it is built
 * CgCameraNodes.register("lift", (node, rig) -> {
 *     CgCameraRig.Value height = rig.value(node, "height");
 *     return (run, pose) -> pose.location().y += height.number();
 * });
 * }</pre>
 *
 * <ul>
 *   <li>Runs on the frame thread every frame its rig runs: allocate nothing.</li>
 *   <li>The pose it is given is the one blended below its rig this frame, the nodes before it applied.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgCameraNode {

    void run(CgCameraRig.Run run, CgCameraPose pose);
}
