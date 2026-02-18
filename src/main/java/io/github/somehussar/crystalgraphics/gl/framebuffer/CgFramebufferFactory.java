package io.github.somehussar.crystalgraphics.gl.framebuffer;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.CgColorAttachmentSpec;
import io.github.somehussar.crystalgraphics.api.CgDepthStencilSpec;
import io.github.somehussar.crystalgraphics.api.CgFramebuffer;
import io.github.somehussar.crystalgraphics.api.CgFramebufferSpec;
import io.github.somehussar.crystalgraphics.gl.CrossApiTransition;
import io.github.somehussar.crystalgraphics.gl.state.CallFamily;

/**
 * Factory for creating {@link CgFramebuffer} instances using the best
 * available OpenGL backend.
 *
 * <p>The factory implements a <em>waterfall</em> selection strategy:
 * <strong>Core GL30 → ARB_framebuffer_object → EXT_framebuffer_object</strong>.
 * The first backend whose capabilities are available in the current GL context
 * is used.  If none are available, an {@link UnsupportedOperationException}
 * is thrown.</p>
 *
 * <p>The factory also supports <em>wrapping</em> externally-created FBO IDs
 * as non-owned {@link CgFramebuffer} instances that can be bound and queried
 * but never deleted by CrystalGraphics.</p>
 *
 * <h3>Usage Example</h3>
 * <pre>{@code
 * CgCapabilities caps = ...; // detected GL capabilities
 * CgFramebuffer fbo = CgFramebufferFactory.create(caps, 1920, 1080, true, false);
 * fbo.bind();
 * // ... render ...
 * fbo.unbind();
 * fbo.delete();
 * }</pre>
 *
 * <h3>Thread Safety</h3>
 * <p>Factory methods must only be called from the GL context thread.</p>
 *
 * @see CgFramebuffer
 * @see CoreFramebuffer
 * @see ArbFramebuffer
 * @see ExtFramebuffer
 */
public final class CgFramebufferFactory {

    /**
     * Private constructor to prevent instantiation.  All access is through
     * static factory methods.
     */
    private CgFramebufferFactory() {
    }

    /**
     * Creates a framebuffer using the best available backend for the current
     * GL capabilities.
     *
     * <p>Selection order:
     * <ol>
     *   <li><strong>Core GL30</strong>: used if {@link CgCapabilities#isCoreFbo()} is
     *       {@code true}</li>
     *   <li><strong>ARB_framebuffer_object</strong>: used if
     *       {@link CgCapabilities#isArbFbo()} is {@code true}</li>
     *   <li><strong>EXT_framebuffer_object</strong>: used if
     *       {@link CgCapabilities#isExtFbo()} is {@code true}.
     *       Note that EXT does not support MRT; if {@code mrt} is
     *       {@code true}, this path will throw.</li>
     * </ol>
     *
     * @param caps   detected GL capabilities (used for backend selection)
     * @param width  width in pixels (must be &gt; 0)
     * @param height height in pixels (must be &gt; 0)
     * @param depth  whether to attach a depth buffer
     * @param mrt    whether to allow multiple render targets
     * @return a new owned framebuffer using the best available backend
     * @throws UnsupportedOperationException if no FBO extension is available,
     *                                       or if MRT is requested but only EXT is available
     * @throws IllegalStateException         if the framebuffer is not complete
     * @throws IllegalArgumentException      if width or height is not positive
     */
    public static CgFramebuffer create(CgCapabilities caps, int width, int height,
                                       boolean depth, boolean mrt) {
        if (caps.isCoreFbo()) {
            return CoreFramebuffer.create(width, height, depth, mrt);
        }
        if (caps.isArbFbo()) {
            return ArbFramebuffer.create(width, height, depth, mrt);
        }
        if (caps.isExtFbo()) {
            return ExtFramebuffer.create(width, height, depth, mrt);
        }
        throw new UnsupportedOperationException(
                "No framebuffer object extension is available. "
                + "CrystalGraphics requires at least EXT_framebuffer_object support.");
    }

     /**
      * Creates a framebuffer from a detailed specification using the best
      * available backend for the current GL capabilities.
      *
      * <p>This overload allows fine-grained control over attachment sizing,
      * formats, and depth/stencil configuration through a {@link CgFramebufferSpec}.
      * Comprehensive validation is performed on the specification against
      * the detected capabilities:</p>
      *
      * <ul>
      *   <li>If the specification requests multiple color attachments and
      *       the preferred backend is EXT, throws
      *       {@code UnsupportedOperationException}.</li>
      *   <li>If the number of requested color attachments exceeds
      *       {@link CgCapabilities#getMaxColorAttachments()}, throws
      *       {@code IllegalArgumentException}.</li>
      *   <li>For each color attachment, computed size is validated against
      *       {@link CgCapabilities#getMaxTextureSize()}. Size computation:
      *       {@code max(1, (int)Math.ceil(baseWidth * scaleX))} and similarly for
      *       height. If either dimension exceeds the maximum, throws
      *       {@code IllegalArgumentException}.</li>
      * </ul>
      *
      * <p><strong>Note:</strong> Spec-based creation is a placeholder pending
      * implementation of spec-aware constructors in backend classes
      * (Tasks 7, 8, 9). This method currently throws
      * {@code UnsupportedOperationException} after validation.  Legacy
      * {@link #create(CgCapabilities, int, int, boolean, boolean)} continues
      * to work unchanged.</p>
      *
      * @param caps the detected GL capabilities (used for validation and backend selection)
      * @param spec the detailed framebuffer specification with attachment config
      * @return a new owned framebuffer using the best available backend
      * @throws IllegalArgumentException if the specification violates capability
      *                                  constraints (e.g., attachment count exceeds
      *                                  max, computed sizes exceed max texture size)
      * @throws UnsupportedOperationException if spec-based creation is not yet
      *                                       implemented for the selected backend,
      *                                       or if MRT is requested but only EXT
      *                                       is available
      * @throws IllegalStateException if the framebuffer is not complete
      *
      * @see #create(CgCapabilities, int, int, boolean, boolean)
      */
     public static CgFramebuffer create(CgCapabilities caps, CgFramebufferSpec spec) {
         if (caps == null) {
             throw new IllegalArgumentException("Capabilities must not be null");
         }
         if (spec == null) {
             throw new IllegalArgumentException("Specification must not be null");
         }

         // Determine the preferred backend
         CgCapabilities.Backend backend = caps.preferredFboBackend();

         // ── Validation: Check MRT compatibility with EXT backend ────────
         int colorAttachmentCount = spec.getColorAttachmentCount();
         if (colorAttachmentCount > 1 && backend == CgCapabilities.Backend.EXT_FBO) {
             throw new UnsupportedOperationException(
                     "EXT_framebuffer_object backend does not support MRT. "
                     + "Requested " + colorAttachmentCount + " color attachments "
                     + "but EXT backend supports at most 1.");
         }

         // ── Validation: Check color attachment count against capability ──
         int maxColorAttachments = caps.getMaxColorAttachments();
         if (colorAttachmentCount > maxColorAttachments) {
             throw new IllegalArgumentException(
                     "Requested " + colorAttachmentCount + " color attachments "
                     + "but hardware supports at most " + maxColorAttachments);
         }

         // ── Validation: Check computed attachment sizes ──────────────────
         int maxTextureSize = caps.getMaxTextureSize();
         int baseWidth = spec.getBaseWidth();
         int baseHeight = spec.getBaseHeight();

         for (int i = 0; i < colorAttachmentCount; i++) {
             CgColorAttachmentSpec attachment = spec.getColorAttachment(i);
             float scaleX = attachment.getScaleX();
             float scaleY = attachment.getScaleY();

             // Compute actual dimensions using Math.ceil and max(1, ...)
             int computedWidth = Math.max(1, (int) Math.ceil(baseWidth * scaleX));
             int computedHeight = Math.max(1, (int) Math.ceil(baseHeight * scaleY));

             // Validate width
             if (computedWidth > maxTextureSize) {
                 throw new IllegalArgumentException(
                         "Color attachment " + i + " width exceeds max texture size: "
                         + "computed width " + computedWidth + " exceeds "
                         + maxTextureSize + " (base width " + baseWidth
                         + " * scale " + scaleX + ")");
             }

             // Validate height
             if (computedHeight > maxTextureSize) {
                 throw new IllegalArgumentException(
                         "Color attachment " + i + " height exceeds max texture size: "
                         + "computed height " + computedHeight + " exceeds "
                         + maxTextureSize + " (base height " + baseHeight
                         + " * scale " + scaleY + ")");
             }
         }

          // ── Validation: Check depth texture capability ─────────────────
          CgDepthStencilSpec dsSpec = spec.getDepthStencil();
          if (dsSpec.isDepthTexture() && !caps.hasDepthTexture()) {
              throw new UnsupportedOperationException(
                      "Depth texture attachments require GL_ARB_depth_texture "
                      + "which is not available on this hardware.");
          }

          // ── Backend routing ──────────────────────────────────────────────
          switch (backend) {
              case CORE_GL30:
                  return CoreFramebuffer.createFromSpec(spec);
              case ARB_FBO:
                  return ArbFramebuffer.createFromSpec(spec);
              case EXT_FBO:
                  return ExtFramebuffer.createFromSpec(spec);
              default:
                  throw new UnsupportedOperationException(
                          "Unknown backend for spec-based creation: " + backend);
          }
     }

     /**
      * Wraps an externally-created framebuffer ID as a non-owned
      * {@link CgFramebuffer}.
      *
      * <p>The returned object can be used for binding operations but will
      * <strong>never</strong> delete the underlying GL object.  Calling
      * {@link CgFramebuffer#delete()} on a wrapped framebuffer throws
      * {@link IllegalStateException}.</p>
      *
      * <p>This is intended for interacting with FBOs created by other mods,
      * by Minecraft itself, or by any code outside CrystalGraphics' control.</p>
      *
      * @param fboId  the external framebuffer object ID
      * @param width  known width in pixels (informational only — not validated)
      * @param height known height in pixels (informational only — not validated)
      * @param family the {@link CallFamily} used to create/bind this external FBO
      * @return a wrapped framebuffer that must not be deleted
      * @throws IllegalArgumentException if {@code family} is {@code null} or not
      *                                  a framebuffer family
      */
     public static CgFramebuffer wrap(int fboId, int width, int height, CallFamily family) {
        if (family == null) {
            throw new IllegalArgumentException("CallFamily must not be null");
        }
        if (!family.isFramebufferFamily()) {
            throw new IllegalArgumentException(
                    "CallFamily must be a framebuffer family, got: " + family);
        }

        switch (family) {
            case CORE_GL30:
                return new WrappedFramebuffer(fboId, width, height, CallFamily.CORE_GL30);
            case ARB_FBO:
                return new WrappedFramebuffer(fboId, width, height, CallFamily.ARB_FBO);
            case EXT_FBO:
                return new WrappedFramebuffer(fboId, width, height, CallFamily.EXT_FBO);
            case OPENGLHELPER_WRAPPER:
                // OpenGlHelper internally delegates to Core/ARB/EXT, but we
                // treat it as Core for binding purposes since the constants
                // are identical.
                return new WrappedFramebuffer(fboId, width, height, CallFamily.OPENGLHELPER_WRAPPER);
            default:
                throw new IllegalArgumentException(
                        "Unsupported call family for FBO wrapping: " + family);
        }
    }

    // ── Inner class for wrapped (non-owned) FBOs ───────────────────────

    /**
     * Minimal framebuffer wrapper for externally-created FBO IDs.
     *
     * <p>This implementation is non-owned, non-deletable, non-resizable, and
     * does not support MRT.  It exists solely to allow CrystalGraphics to
     * bind/unbind an external FBO through the unified {@link CgFramebuffer}
     * interface.</p>
     */
    private static final class WrappedFramebuffer extends AbstractCgFramebuffer {

        /** {@code GL_FRAMEBUFFER} target. */
        private static final int GL_FRAMEBUFFER = 0x8D40;

        /** The call family used for binding this wrapped FBO. */
        private final CallFamily family;

        /**
         * Creates a wrapped framebuffer.
         *
         * @param fboId  the external framebuffer ID
         * @param width  known width (informational)
         * @param height known height (informational)
         * @param family the call family for binding
         */
        WrappedFramebuffer(int fboId, int width, int height, CallFamily family) {
            super(fboId, width, height, false, false);
            this.family = family;
        }

        /**
         * {@inheritDoc}
         *
         * @return the call family specified at wrapping time
         */
        @Override
        protected CallFamily callFamily() {
            return family;
        }

        /**
         * No-op — wrapped framebuffers do not own any GL resources.
         */
        @Override
        protected void freeGlResources() {
            // Wrapped FBOs own nothing — this should never be called because
            // delete() throws IllegalStateException for non-owned FBOs, but
            // the method is required by the abstract contract.
        }

        /**
         * Always throws {@link UnsupportedOperationException}.
         *
         * <p>Wrapped framebuffers cannot be resized because CrystalGraphics
         * does not own the underlying GL resources.</p>
         *
         * @param newWidth  ignored
         * @param newHeight ignored
         * @throws UnsupportedOperationException always
         */
        @Override
        public void resize(int newWidth, int newHeight) {
            throw new UnsupportedOperationException(
                    "Cannot resize a wrapped (non-owned) framebuffer");
        }
    }
}
