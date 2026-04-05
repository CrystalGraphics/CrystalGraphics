package io.github.somehussar.crystalgraphics.harness.config;

/**
 * Declarative metadata for a harness scene or diagnostic mode.
 *
 * <p>Each registered entry declares its mode id, human description,
 * lifecycle mode, and optional FBO/depth requirements. The managed
 * runtime reads these fields to drive setup before delegating to
 * the scene's render logic.</p>
 */
public  class SceneDescriptor {

    public enum LifecycleMode {
        /** Harness manages FBO/viewport/clear/capture. Scene only renders. */
        MANAGED,
        /** Scene owns its entire GL lifecycle. Harness provides config + output dir only. */
        FULL_OVERRIDE,
        /** Diagnostic tool — may or may not use GL. Harness provides config + output dir. */
        DIAGNOSTIC
    }

    public enum Category {
        /** Rendering scene that produces PNG artifacts. */
        SCENE,
        /** Diagnostic tool that produces report/dump artifacts. */
        DIAGNOSTIC_TOOL
    }

    private final String id;
    private final String description;
    private final LifecycleMode lifecycleMode;
    private final Category category;
    private final boolean needsFbo;
    private final boolean needsDepthBuffer;
    private final int defaultWidth;
    private final int defaultHeight;
    private final float[] clearColor; // r,g,b,a

    private SceneDescriptor(Builder b) {
        if (b.id == null || b.id.isEmpty()) {
            throw new IllegalArgumentException("SceneDescriptor id must not be null or empty");
        }
        if (b.description == null || b.description.isEmpty()) {
            throw new IllegalArgumentException("SceneDescriptor description must not be null or empty");
        }
        if (b.lifecycleMode == null) {
            throw new IllegalArgumentException("SceneDescriptor lifecycleMode must not be null");
        }
        this.id = b.id;
        this.description = b.description;
        this.lifecycleMode = b.lifecycleMode;
        this.category = b.category != null ? b.category : Category.SCENE;
        this.needsFbo = b.needsFbo;
        this.needsDepthBuffer = b.needsDepthBuffer;
        this.defaultWidth = b.defaultWidth > 0 ? b.defaultWidth : 800;
        this.defaultHeight = b.defaultHeight > 0 ? b.defaultHeight : 600;
        this.clearColor = b.clearColor != null ? b.clearColor : new float[]{0.1f, 0.1f, 0.1f, 1.0f};
    }

    public String getId() { return id; }
    public String getDescription() { return description; }
    public LifecycleMode getLifecycleMode() { return lifecycleMode; }
    public Category getCategory() { return category; }
    public boolean needsFbo() { return needsFbo; }
    public boolean needsDepthBuffer() { return needsDepthBuffer; }
    public int getDefaultWidth() { return defaultWidth; }
    public int getDefaultHeight() { return defaultHeight; }
    public float[] getClearColor() { return clearColor; }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static class Builder {
        private final String id;
        private String description;
        private LifecycleMode lifecycleMode;
        private Category category;
        private boolean needsFbo;
        private boolean needsDepthBuffer;
        private int defaultWidth = 800;
        private int defaultHeight = 600;
        private float[] clearColor;

        public Builder(String id) { this.id = id; }

        public Builder description(String val) { this.description = val; return this; }
        public Builder lifecycleMode(LifecycleMode val) { this.lifecycleMode = val; return this; }
        public Builder category(Category val) { this.category = val; return this; }
        public Builder needsFbo(boolean val) { this.needsFbo = val; return this; }
        public Builder needsDepthBuffer(boolean val) { this.needsDepthBuffer = val; return this; }
        public Builder defaultWidth(int val) { this.defaultWidth = val; return this; }
        public Builder defaultHeight(int val) { this.defaultHeight = val; return this; }
        public Builder clearColor(float r, float g, float b, float a) {
            this.clearColor = new float[]{r, g, b, a};
            return this;
        }

        public SceneDescriptor build() { return new SceneDescriptor(this); }
    }
}
