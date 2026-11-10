package com.crystalgraphics.command;

import java.util.List;
import java.util.Locale;

import com.crystalgraphics.mc.CgAssetReloader;
import com.crystalgraphics.platform.command.CgCommandSource;
import com.crystalgraphics.platform.service.CgModLoader;
import com.crystalgraphics.platform.service.CgShaderPacks;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.variable.CgVars;

/**
 * What {@code /cg} does, with no Brigadier: {@link CgCoreCommands} puts it behind a command tree and
 * {@link CgPlainCommands} behind plain words, so the two cannot drift. Every action answers through the source and
 * returns the command's result, 0 when it failed.
 */
public final class CgCoreActions {

    /** Operator level a change needs. */
    public static final int CHANGE_LEVEL = 2;

    private CgCoreActions() {
    }

    public static int list(CgCommandSource source, String prefix) {
        List<CgVar<?>> vars = prefix.isEmpty() ? CgVars.all() : CgVars.under(prefix);
        if (vars.isEmpty()) return fail(source, "No variable under '" + prefix + "'");
        for (CgVar<?> v : vars) source.reply(line(v));
        return vars.size();
    }

    public static int get(CgCommandSource source, String id) {
        CgVar<?> v = CgVars.get(id);
        if (v == null) return unknown(source, id);
        source.reply(line(v) + "\n" + v.description() + " (" + v.type() + " " + v.range() + ")");
        return 1;
    }

    public static int set(CgCommandSource source, String id, String text) {
        CgVar<?> v = CgVars.get(id);
        if (v == null) return unknown(source, id);
        if (!v.setText(CgVar.Source.CONSOLE, text)) {
            return fail(source, id + " takes " + v.type() + " " + v.range() + (v.has(CgVar.READ_ONLY) ? " and is read-only" : ""));
        }
        source.reply(line(v));
        return 1;
    }

    public static int reset(CgCommandSource source, String id) {
        CgVar<?> v = CgVars.get(id);
        if (v == null) return unknown(source, id);
        if (!v.clear(CgVar.Source.CONSOLE)) return fail(source, id + " is read-only");
        source.reply(line(v));
        return 1;
    }

    public static int reload(CgCommandSource source) {
        CgAssetReloader.reload();
        source.reply("Assets reloaded");
        return 1;
    }

    public static int info(CgCommandSource source) {
        source.reply("Loader: " + CgModLoader.name() + " " + CgModLoader.version() + (CgModLoader.development() ? " (dev)" : ""));
        source.reply("Mods: " + CgModLoader.mods().size());
        source.reply("Shader pack: " + CgShaderPacks.mod() + (CgShaderPacks.packInUse() ? ", in use" : ""));
        return 1;
    }

    private static int unknown(CgCommandSource source, String id) {
        return fail(source, "Unknown variable '" + id + "'");
    }

    private static int fail(CgCommandSource source, String message) {
        source.fail(message);
        return 0;
    }

    static String line(CgVar<?> v) {
        CgVar.Source from = v.source();
        return v.id() + " = " + v.text() + " (" + (from == null ? "default" : from.name().toLowerCase(Locale.ROOT)) + ")";
    }
}
