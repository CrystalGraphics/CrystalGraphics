package com.crystalgraphics.audio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgKitFiles;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.asset.load.CgLoadPriority;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.object.validate.CgValidation;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.widget.CgWidgetTree;

/**
 * How a game draws its subtitles: a kit's {@code subtitle_style.yaml}, naming the game's own subtitle widget, the text
 * blocks a line and its speaker are set into, and the widget's animations that show and hide a line. Where everything
 * sits and how it scales is the widget's tree and the kit's {@code ui.yaml}. A line whose kit has none is drawn in
 * Unreal's canvas look.
 *
 * <pre>{@code
 * # assets/mygame_kit/subtitle_style.yaml
 * _v: 1
 * widget: mygame_kit:hud/subtitles          # a widget asset
 * line: Txt_Line
 * name: Txt_Name
 * with_name: [Img_Underline]                # shown only while a line has a speaker
 * show: mygame_kit:hud/subtitles/in         # sequences of the widget's role
 * hide: mygame_kit:hud/subtitles/out
 * }</pre>
 *
 * <pre>{@code
 * CgSubtitleStyle.registerKit("mygame_kit", Paths.get("assets/mygame_kit"));   // the asset registry does this
 * CgSubtitleStyle style = CgSubtitleStyle.of("mygame_kit");                       // null: the canvas look
 * CgWidgetTree tree = style.widget().loaded();                                   // null until it has loaded
 * }</pre>
 *
 * <ul>
 *   <li>A file that does not validate fails the kit's registration, naming it and each issue.</li>
 *   <li>The widget and its sequences are requested as the kit registers; a line is drawn once they have loaded.</li>
 *   <li>{@code line}, {@code name} and each of {@code withName} name widgets of the tree: a name it lacks fails the
 *       first line drawn, naming it.</li>
 * </ul>
 *
 * @param namespace its kit, whose UI scale sizes the widget
 * @param widget    the subtitle widget
 * @param line      the text block a line is set into
 * @param name      the text block the speaker's name is set into, or null
 * @param withName  widgets shown only while a line has a speaker
 * @param lines     lines shown at once
 * @param show      the widget's animation that brings a line in, or null
 * @param hide      the widget's animation that takes it out, or null
 */
public record CgSubtitleStyle(String namespace, CgAssetRef<CgWidgetTree> widget, String line, @Nullable String name,
                              List<String> withName, int lines, @Nullable CgAssetRef<CgSequenceDefinition> show,
                              @Nullable CgAssetRef<CgSequenceDefinition> hide) {

    /** The role a show or hide sequence binds the widget as. */
    public static final String ROLE = "widget";

    private static final String FILE = "subtitle_style.yaml";
    private static final Map<String, CgSubtitleStyle> BY_KIT = new ConcurrentHashMap<>();

    /** The style of the kit {@code namespace}; null where it has none, or {@code namespace} is null. */
    @Nullable
    public static CgSubtitleStyle of(@Nullable String namespace) {
        return namespace == null ? null : BY_KIT.get(namespace);
    }

    /** {@code folder}'s {@code subtitle_style.yaml}, the kit {@code namespace}'s; nothing where it ships none. */
    public static void registerKit(String namespace, Path folder) {
        Path file = folder.resolve(FILE);
        if (!Files.isRegularFile(file)) return;
        CgSubtitleStyle style = read(namespace, file);
        BY_KIT.put(namespace, style);
        // Read now, at the kit's registration: the first subtitle drawn is mid-move
        style.widget().request(CgLoadPriority.PREFETCH);
        if (style.show() != null) style.show().request(CgLoadPriority.PREFETCH);
        if (style.hide() != null) style.hide().request(CgLoadPriority.PREFETCH);
    }

    /** {@code file}, the kit {@code namespace}'s, validated against {@link CgKitFiles#SUBTITLE_STYLE}. */
    public static CgSubtitleStyle read(String namespace, Path file) {
        Map<?, ?> root;
        try {
            Object tree = CgAssetYaml.read(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            if (!(tree instanceof Map<?, ?> m)) throw new IllegalArgumentException(file + ": expected a map");
            root = m;
        } catch (IOException e) {
            throw new IllegalArgumentException(file + ": " + e.getMessage(), e);
        }
        CgValidation v = CgAssetData.validate(file.toString(), CgKitFiles.SUBTITLE_STYLE, root, CgValidation.Usecase.LOAD);
        if (!v.valid()) throw new IllegalArgumentException(file + ": " + v.issues());
        List<String> withName = new ArrayList<>();
        if (root.get("with_name") instanceof List<?> l) for (Object o : l) withName.add(String.valueOf(o));
        return new CgSubtitleStyle(namespace, CgVfxAssets.ref(CgLookKinds.WIDGET, (String) root.get("widget")),
                (String) root.get("line"), root.get("name") instanceof String n ? n : null, List.copyOf(withName),
                root.get("lines") instanceof Number n ? n.intValue() : 1, sequence(root.get("show")),
                sequence(root.get("hide")));
    }

    @Nullable
    private static CgAssetRef<CgSequenceDefinition> sequence(@Nullable Object id) {
        return id instanceof String s ? CgVfxAssets.ref(CgSequenceKinds.SEQUENCE, s) : null;
    }
}
