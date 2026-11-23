package com.crystalgraphics.mc.modern.platform;

//? if >=1.21.6 <26.1 {
/*import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.state.BlitRenderState;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix3x2f;
*///?} elif >=26.1 <26.2 {
/*import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.joml.Matrix3x2f;
*///?} elif >=26.2 {
/*import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
*///?}

/**
 * A page of pictures on a recorded GUI (1.21.6 on), drawn by one {@code GuiRenderer} render: every cell recorded into
 * one {@code State}, each under its cell's pose and inside its scissor.
 *
 * <p>A picture-in-picture element (an entity, a skin, a book) is placed by its own coordinates whatever the pose, and
 * vanilla keeps one renderer per kind, so a second of a kind in one render draws over the first. The state records
 * each as a {@code Placed} carrying its cell's pose; {@code Pictures}, the renderer's only picture-in-picture renderer,
 * keeps one of the loader's renderers per kind and per element of a frame, and moves each one's blit onto its cell.
 * Each element's render is zoned {@code pictures.pip}.
 */
final class RecordedPages {
    //? if >=1.21.6 {
    /*private RecordedPages() {
    }

    /^* The state of one page's render: records a cell's picture-in-picture elements as placed on the cell. ^/
    static final class State extends GuiRenderState {
        private final List<Placed> placed = new ArrayList<>();
        private int placedCount;
        // The pose of the cell recording; null between pages
        @Nullable
        private Matrix3x2f cell;
        // The element whose renderer is preparing: its blit lands on its cell
        @Nullable
        private Placed preparing;

        /^* Records from here as cell {@code pose}'s, a pose the caller keeps until the next call; null ends the page. ^/
        void cell(@Nullable Matrix3x2f pose) {
            if (cell == null && pose != null) placedCount = 0;
            cell = pose;
        }

        //? if <26.1 {
        @Override
        public void submitPicturesInPictureState(PictureInPictureRenderState state) {
            super.submitPicturesInPictureState(cell == null ? state : next().set(state, cell));
        }

        @Override
        public void submitBlitToCurrentLayer(BlitRenderState blit) {
            super.submitBlitToCurrentLayer(preparing == null ? blit : preparing.place(blit));
        }
        //?} else {
        /^@Override
        public void addPicturesInPictureState(PictureInPictureRenderState state) {
            super.addPicturesInPictureState(cell == null ? state : next().set(state, cell));
        }

        @Override
        public void addBlitToCurrentLayer(BlitRenderState blit) {
            super.addBlitToCurrentLayer(preparing == null ? blit : preparing.place(blit));
        }
        ^///?}

        private Placed next() {
            if (placedCount == placed.size()) placed.add(new Placed());
            return placed.get(placedCount++);
        }
    }

    /^*
     * A picture-in-picture element of a cell: the element as recorded, its bounds and blit on the page by the cell's
     * pose. Kept by the state and set again each page.
     ^/
    static final class Placed implements PictureInPictureRenderState {
        private PictureInPictureRenderState element;
        private final Matrix3x2f pose = new Matrix3x2f(), blitPose = new Matrix3x2f();
        @Nullable
        private ScreenRectangle bounds;

        Placed set(PictureInPictureRenderState element, Matrix3x2f cell) {
            this.element = element;
            pose.set(cell);
            ScreenRectangle box = new ScreenRectangle(element.x0(), element.y0(), element.x1() - element.x0(),
                    element.y1() - element.y0()).transformAxisAligned(pose);
            ScreenRectangle scissor = element.scissorArea();
            bounds = scissor != null ? scissor.intersection(box) : box;
            return this;
        }

        // The element's blit, moved onto the cell; its scissor was recorded on the page already
        BlitRenderState place(BlitRenderState blit) {
            blitPose.set(pose).mul(blit.pose());
            ScreenRectangle b = blit.bounds();
            return new BlitRenderState(blit.pipeline(), blit.textureSetup(), blitPose, blit.x0(), blit.y0(), blit.x1(),
                    blit.y1(), blit.u0(), blit.u1(), blit.v0(), blit.v1(), blit.color(), blit.scissorArea(),
                    b == null ? null : b.transformAxisAligned(pose));
        }

        @Override public int x0() { return element.x0(); }
        @Override public int x1() { return element.x1(); }
        @Override public int y0() { return element.y0(); }
        @Override public int y1() { return element.y1(); }
        @Override public float scale() { return element.scale(); }
        @Override @Nullable public ScreenRectangle scissorArea() { return element.scissorArea(); }
        @Override @Nullable public ScreenRectangle bounds() { return bounds; }
        //? if <26.2 {
        @Override public Matrix3x2f pose() { return element.pose(); }
        //?} else {
        /^@Override public Matrix3x2fc pose() { return element.pose(); }
        ^///?}
    }

    /^*
     * The page renderer's one picture-in-picture renderer: each {@link Placed} element drawn by a renderer of the
     * loader's for its kind, one per element of the kind this frame, kept for the next frame. {@link #frame} before
     * each frame's pages.
     ^/
    static final class Pictures extends PictureInPictureRenderer<Placed> {
        // Renderers past a frame's need, given back after this many frames
        private static final int KEPT_FRAMES = 600;
        private final Map<Class<?>, Pool> pools = new HashMap<>();
        // The same pools, walked each frame without an iterator
        private final List<Pool> poolList = new ArrayList<>();
        private final State state;

        //? if <26.2 {
        Pictures(Map<Class<?>, Supplier<PictureInPictureRenderer<?>>> factories, State state,
                 MultiBufferSource.BufferSource buffers) {
            super(buffers);
        //?} else {
        /^Pictures(Map<Class<?>, Supplier<PictureInPictureRenderer<?>>> factories, State state) {
        ^///?}
            this.state = state;
            for (Map.Entry<Class<?>, Supplier<PictureInPictureRenderer<?>>> e : factories.entrySet()) {
                Pool pool = new Pool(e.getValue());
                pools.put(e.getKey(), pool);
                poolList.add(pool);
            }
        }

        void frame() {
            for (int i = 0; i < poolList.size(); i++) poolList.get(i).frame();
        }

        //? if <26.2 {
        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public void prepare(Placed placed, GuiRenderState gui, int guiScale) {
            Pool pool = pools.get(placed.element.getClass());
            if (pool == null) return;
            PictureInPictureRenderer renderer = pool.next();
            state.preparing = placed;
            long zone = CgTrace.begin(CgChannels.PICTURES, "pictures.pip");
            try {
                renderer.prepare(placed.element, gui, guiScale);
            } finally {
                CgTrace.end(zone);
                state.preparing = null;
            }
        }

        @Override
        protected void renderToTexture(Placed placed, PoseStack poseStack) {
        }
        //?} else {
        /^@Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public void prepare(Placed placed, GuiRenderState gui, FeatureRenderDispatcher features, int guiScale) {
            Pool pool = pools.get(placed.element.getClass());
            if (pool == null) return;
            PictureInPictureRenderer renderer = pool.next();
            state.preparing = placed;
            long zone = CgTrace.begin(CgChannels.PICTURES, "pictures.pip");
            try {
                renderer.prepare(placed.element, gui, features, guiScale);
            } finally {
                CgTrace.end(zone);
                state.preparing = null;
            }
        }

        @Override
        protected void renderToTexture(Placed placed, PoseStack poseStack, SubmitNodeCollector collector) {
        }
        ^///?}

        @Override
        public Class<Placed> getRenderStateClass() {
            return Placed.class;
        }

        @Override
        protected String getTextureLabel() {
            return "crystalgraphics pictures";
        }

        // NeoForge's pool closes a renderer a render did not reuse; this one is every slot's and lives with the host
        @Override
        public void close() {
        }

        private static final class Pool {
            private final Supplier<PictureInPictureRenderer<?>> factory;
            private final List<PictureInPictureRenderer<?>> made = new ArrayList<>();
            private int used, idleFrames;

            Pool(Supplier<PictureInPictureRenderer<?>> factory) {
                this.factory = factory;
            }

            PictureInPictureRenderer<?> next() {
                if (used == made.size()) made.add(factory.get());
                return made.get(used++);
            }

            void frame() {
                if (used < made.size() && ++idleFrames > KEPT_FRAMES) {
                    for (int i = made.size() - 1; i >= used; i--) made.remove(i).close();
                    idleFrames = 0;
                } else if (used == made.size()) {
                    idleFrames = 0;
                }
                used = 0;
            }
        }
    }
    *///?}
}
