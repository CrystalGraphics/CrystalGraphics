package com.crystalgraphics.platform.gl.tracked;

import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlStateProvider;
import com.crystalgraphics.platform.gl.state.CgGlStateShadow;
import com.crystalgraphics.platform.gl.tracked.gl.TrackedBuffers;
import com.crystalgraphics.platform.gl.tracked.gl.TrackedRenderState;
import com.crystalgraphics.platform.gl.tracked.gl.TrackedTextures;

/**
 * The tracked backend's answer to an outermost scope's adoption: every domain read from the backend's own
 * state, with no {@code glGet} and no unit-by-unit {@code glActiveTexture} walk.
 *
 * <pre>{@code
 * CgTrackedGLBackend gl = new CgTrackedGLBackend(device, compiler, debug);
 * CgGL.init(gl);
 * CgGlState.setProvider(new CgTrackedStateProvider(gl));
 * }</pre>
 */
public final class CgTrackedStateProvider implements CgGlStateProvider {

    private final CgTrackedGLBackend gl;

    public CgTrackedStateProvider(CgTrackedGLBackend gl) {
        this.gl = gl;
    }

    @Override
    public void read(CgGlSlot slot, CgGlStateShadow t) {
        TrackedRenderState s = gl.renderState();
        switch (slot) {
            case BLEND:
                t.blendEnabled = s.blend;
                t.blendSrcRgb = s.srcRgb; t.blendDstRgb = s.dstRgb;
                t.blendSrcAlpha = s.srcAlpha; t.blendDstAlpha = s.dstAlpha;
                t.blendEqRgb = s.equationRgb; t.blendEqAlpha = s.equationAlpha;
                break;
            case DEPTH:
                t.depthTest = s.depthTest; t.depthMask = s.depthMask; t.depthFunc = s.depthFunc;
                break;
            case CULL:
                t.cullEnabled = s.cullFace; t.cullFace = s.cullMode; t.frontFace = s.frontFace;
                break;
            case STENCIL:
                t.stencilTest = s.stencilTest; t.stencilFunc = s.stencilFunc; t.stencilRef = s.stencilRef;
                t.stencilValueMask = s.stencilValueMask; t.stencilWriteMask = s.stencilWriteMask;
                t.stencilFail = s.stencilFail; t.stencilZFail = s.stencilDepthFail; t.stencilZPass = s.stencilPass;
                break;
            case ALPHA_TEST:
                t.alphaTest = s.alphaTest; t.alphaFunc = s.alphaFunc; t.alphaRef = s.alphaRef;
                break;
            case COLOR_MASK:
                t.colorMaskPacked = s.colorMasks;
                break;
            case VIEWPORT:
                t.viewportX = s.viewportX; t.viewportY = s.viewportY; t.viewportW = s.viewportWidth; t.viewportH = s.viewportHeight;
                break;
            case SCISSOR:
                t.scissorTest = s.scissorTest;
                t.scissorX = s.scissorX; t.scissorY = s.scissorY; t.scissorW = s.scissorWidth; t.scissorH = s.scissorHeight;
                break;
            case POLYGON_OFFSET:
                t.polygonOffsetFill = s.polygonOffsetFill; t.polygonOffsetLine = s.polygonOffsetLine;
                t.polygonOffsetPoint = s.polygonOffsetPoint;
                t.polygonOffsetFactor = s.offsetFactor; t.polygonOffsetUnits = s.offsetUnits;
                break;
            case POLYGON_MODE:
                t.polygonModeFront = t.polygonModeBack = s.polygonMode;
                break;
            case LINE_WIDTH:
                t.lineWidth = s.lineWidth;
                break;
            case POINT_SIZE:
                t.pointSize = s.pointSize;
                break;
            case PROGRAM:
                t.programId = gl.programObjects().currentName();
                break;
            case FBO:
                t.drawFbo = gl.framebufferObjects().drawName();
                t.readFbo = gl.framebufferObjects().readName();
                break;
            case TEXTURES: {
                TrackedTextures tex = gl.textureObjects();
                t.activeTextureUnit = tex.activeUnit();
                for (int unit = 0; unit < CgGlStateShadow.MAX_TEXTURE_UNITS; unit++) t.boundTexture2D[unit] = tex.bound2D(unit);
                break;
            }
            case VERTEX_INPUT:
                t.vertexArray = gl.vertexArrays().currentName();
                t.arrayBuffer = gl.bufferObjects().array;
                t.elementArrayBuffer = gl.vertexArrays().current().elementBuffer;
                break;
            case STORAGE_BUFFERS:
                for (int i = 0; i < CgGlStateShadow.MAX_STORAGE_BINDINGS; i++) readBinding(slot, i, t);
                break;
            case IMAGES:
                for (int i = 0; i < CgGlStateShadow.MAX_IMAGE_UNITS; i++) readBinding(slot, i, t);
                break;
            case INDIRECT_BUFFERS:
                for (int i = 0; i < CgGlStateShadow.INDIRECT_TARGETS.length; i++) readBinding(slot, i, t);
                break;
            default:
                throw new IllegalArgumentException("No tracked state for " + slot);
        }
    }

    @Override
    public void readBinding(CgGlSlot slot, int index, CgGlStateShadow t) {
        TrackedBuffers b = gl.bufferObjects();
        switch (slot) {
            case STORAGE_BUFFERS:
                t.storageBuffer[index] = b.storageName[index];
                t.storageOffset[index] = b.storageOffset[index];
                t.storageSize[index] = Math.max(0, b.storageSize[index]);
                break;
            case IMAGES: {
                int[] u = gl.textureObjects().imageUnit(index);
                t.imageTexture[index] = u[0];
                t.imageLevel[index] = u[1];
                t.imageLayer[index] = u[2];
                t.imageFormat[index] = u[3];
                t.imageAccess[index] = u[4];
                break;
            }
            case INDIRECT_BUFFERS:
                t.indirectBuffer[index] = index == 0 ? b.drawIndirect : index == 1 ? b.dispatchIndirect : b.parameter;
                break;
            default:
                throw new IllegalArgumentException(slot + " is read whole: read");
        }
    }
}
