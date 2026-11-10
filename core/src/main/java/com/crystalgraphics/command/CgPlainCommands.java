package com.crystalgraphics.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.crystalgraphics.platform.command.CgCommandSource;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.variable.CgVars;

/**
 * {@code /cg} as plain words, for a host with no Brigadier (1.7.10 and Forge 1.8.9-1.12.2): one command, {@code cg},
 * whose arguments come to {@link #run} and {@link #complete} already split. Same actions as {@link CgCoreCommands};
 * completion is by prefix, without tooltips.
 *
 * <pre>{@code
 * // a host's ICommand named "cg"
 * CgPlainCommands.run(source, args);                  // {"var", "set", "test.batch", "9"}
 * CgPlainCommands.complete(source, args);             // {"var", "s"} -> ["set"]
 * }</pre>
 *
 * <p>Changing anything needs operator level {@link CgCoreActions#CHANGE_LEVEL}; the usage is sent for a line it cannot
 * read.</p>
 */
public final class CgPlainCommands {

    private static final String USAGE = "/cg var list [prefix] | var get <id> | var set <id> <value> | var reset <id> | reload | info";

    private CgPlainCommands() {
    }

    /** Runs {@code args}; the command's result, 0 when it failed. */
    public static int run(CgCommandSource source, String[] args) {
        boolean may = source.level() >= CgCoreActions.CHANGE_LEVEL;
        if (args.length >= 1 && args[0].equals("info")) return CgCoreActions.info(source);
        if (args.length >= 1 && args[0].equals("reload")) return may ? CgCoreActions.reload(source) : denied(source);
        if (args.length >= 2 && args[0].equals("var")) {
            switch (args[1]) {
                case "list":
                    return CgCoreActions.list(source, args.length > 2 ? args[2] : "");
                case "get":
                    if (args.length == 3) return CgCoreActions.get(source, args[2]);
                    break;
                case "set":
                    if (args.length >= 4) return may ? CgCoreActions.set(source, args[2], join(args, 3)) : denied(source);
                    break;
                case "reset":
                    if (args.length == 3) return may ? CgCoreActions.reset(source, args[2]) : denied(source);
                    break;
                default:
                    break;
            }
        }
        source.fail("Usage: " + USAGE);
        return 0;
    }

    /** The completions of the last word of {@code args}, for what {@code source} may run. */
    public static List<String> complete(CgCommandSource source, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 0) return out;
        boolean may = source.level() >= CgCoreActions.CHANGE_LEVEL;
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            add(out, last, "var", "info");
            if (may) add(out, last, "reload");
        } else if (args[0].equals("var") && args.length == 2) {
            add(out, last, "list", "get");
            if (may) add(out, last, "set", "reset");
        } else if (args[0].equals("var") && args.length == 3) {
            for (CgVar<?> v : CgVars.all()) {
                if (v.id().toLowerCase(Locale.ROOT).startsWith(last)) out.add(v.id());
            }
        } else if (args[0].equals("var") && args.length == 4 && args[1].equals("set")) {
            CgVar<?> v = CgVars.get(args[2]);
            if (v instanceof CgVar.Bool) {
                add(out, last, "true", "false");
            } else if (v instanceof CgVar.Choice) {
                for (Enum<?> option : ((CgVar.Choice<?>) v).options()) add(out, last, option.name().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static void add(List<String> out, String typed, String... words) {
        for (String word : words) {
            if (word.startsWith(typed)) out.add(word);
        }
    }

    private static int denied(CgCommandSource source) {
        source.fail("You need operator level " + CgCoreActions.CHANGE_LEVEL);
        return 0;
    }

    private static String join(String[] args, int from) {
        StringBuilder s = new StringBuilder();
        for (int i = from; i < args.length; i++) s.append(i > from ? " " : "").append(args[i]);
        return s.toString();
    }
}
