package com.crystalgraphics.probe;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgGameplayKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.cue.CgCueDefinition;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.gauntlet.CgGauntletController;
import com.crystalgraphics.gauntlet.CgGauntletState;
import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.net.object.CgAnchor;
import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.net.object.CgNetType;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.service.CgModLoader;
import com.crystalgraphics.platform.service.CgNetworkChannel;
import com.crystalgraphics.platform.service.CgServers;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequenceTarget;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.binding.CgSequenceLocator;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.event.CgSequenceEventListener;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.serialization.format.CgFiles;
import com.crystalgraphics.serialization.format.CgTextFormat;
import com.crystalgraphics.variable.CgVar;

/**
 * {@code crystalgraphics:server}: a dedicated server came up whole, and loaded nothing only a client has. Every defect
 * it was built for is a runtime property ("a client-only class is constructed on a server"), so nothing static sees
 * one: headless tests assert by absence, the harness is a client, an import guard sees imports.
 *
 * <pre>{@code
 * // a dedicated server's flags; the run stops the server when it ends
 * -Dcrystalgraphics.gauntlet=crystalgraphics:server
 * -Xlog:class+load=info:file=build/classes.log -Dcrystalgraphics.gauntlet.classlog=build/classes.log
 *
 * // a mod's own client-only classes, from a common listener's onPreInit:
 * CgServerSmoke.clientOnly("com.example.mymod.client.MyRenderer");
 * }</pre>
 *
 * <p>The classes that must not load are named, never guessed: every class a mod declares on a {@code client} line of
 * its {@code META-INF/crystalgraphics/listeners}, the game's own client class, and what mods name with
 * {@link #clientOnly}. Each name must still name a class, or it would pass for nothing.</p>
 *
 * <ul>
 *   <li>The JVM's class-load log is the authority ({@code -Xlog:class+load}); without one the class loaders are asked,
 *       which a module system can refuse: that is said in the report, never counted as a pass.</li>
 *   <li>A persisted object outlives the server that made it: the first run on a world makes one, and every run after
 *       checks it.</li>
 *   <li>{@code -Dcrystalgraphics.serverSmoke.cue=<id>} adds a mod jar's cue, mounted and compiled headless.</li>
 * </ul>
 */
public final class CgServerSmoke extends CgGauntletController {

    private static final CgVar.Text CLASSLOG = CgVar.text("crystalgraphics.gauntlet.classlog", "", CgVar.DEV | CgVar.RESTART,
            "The JVM's -Xlog:class+load file, which crystalgraphics:server reads what loaded from; empty asks the loaders.");
    private static final CgVar.Text CUE = CgVar.text("crystalgraphics.serverSmoke.cue", "", CgVar.DEV | CgVar.RESTART,
            "A mod jar's cue crystalgraphics:server checks is mounted and compiles headless; empty for none.");
    private static final String LOG_MARKER = "[class,load] ";
    /** Server ticks the tick checks watch. */
    private static final int TICKS = 40;

    private static final Set<String> CLIENT_ONLY = new LinkedHashSet<>();

    private ServerTicks ticks;

    /**
     * Names a class a dedicated server must never load. A common listener's {@code onPreInit}, before any run starts.
     * A name nothing defines fails the check: rename or drop it with the class.
     */
    public static synchronized void clientOnly(String... classNames) {
        CLIENT_ONLY.addAll(Arrays.asList(classNames));
    }

    @Override
    protected void onStateChange(CgGauntletState from, CgGauntletState to) {
        if (!to.equals(CgGauntletState.SERVER)) {
            if (!to.equals(CgGauntletState.LOADING) && !to.equals(CgGauntletState.INITIALIZED)) {
                fail("server.dedicated", "a dedicated server's test, run in state " + to);
            }
            return;
        }
        check("server.dedicated", CgServers.dedicated(), "version " + CgServers.version());
        platformChecks();
        checkModCue();
        checkNothingClientSideLoaded();
        check("server.no-device", !CgRHI.installed(), "something started a device or probed CgCapabilities on a server");
        checkPersistence();
        checkNbtRoundTrip();
        checkTextFormats();
        ticks = new ServerTicks();
    }

    @Override
    protected void onTick(float seconds) {
        if (ticks != null && ticks.after()) endTest();
    }

    private void platformChecks() {
        // The one that was fatal: the bundle built all its services eagerly and died at the first, taking every
        // dependent mod's pre-init with it.
        String platform = "";
        try {
            CgPlatform.ensureCreated();
        } catch (Throwable notRegistered) {
            platform = String.valueOf(notRegistered);
        }
        check("server.platform", platform.isEmpty(), platform.isEmpty() ? "registered" : platform);
        // An unavailable channel is a warning inside the network's install: the flag below alone hides the cause.
        boolean channel;
        try {
            channel = CgPlatform.get(CgNetworkChannel.SERVICE).isAvailable();
        } catch (Throwable noChannel) {
            channel = false;
        }
        check("server.channel", channel, "the loader's CgNetworkChannel provider");
        check("server.connections", CgNetwork.isInstalled(), "the network installed at init");
    }

    // ── what loaded ─────────────────────────────────────────────────────────────

    private void checkNothingClientSideLoaded() {
        List<String> named;
        synchronized (CgServerSmoke.class) {
            named = new ArrayList<>(CLIENT_ONLY);
        }
        named.addAll(CgEngineLifecycle.clientListenerClasses());
        List<String> missing = new ArrayList<>();
        for (String name : named) if (!exists(name)) missing.add(name);
        check("server.client-names-exist", missing.isEmpty(), named.size() + " named"
                + (missing.isEmpty() ? "" : "; gone: " + missing + ", which can never load, so they passed for nothing"));

        List<String> subjects = new ArrayList<>(named);
        // Naming it from a common path is the commonest spelling of the bug; a real server ships without it.
        subjects.add("net.minecraft.client.Minecraft");
        Set<String> logged = loadedFromJvmLog();
        List<String> loaded = new ArrayList<>(), unknown = new ArrayList<>();
        for (String name : subjects) {
            Boolean is = logged != null ? Boolean.valueOf(logged.contains(name)) : loadedByAnyLoader(name);
            if (is == null) unknown.add(name);
            else if (is) loaded.add(name);
        }
        if (logged != null) info("server.class-log", logged.size() + " classes defined this run, from the JVM's own log");
        if (!unknown.isEmpty()) {
            // Said, never counted: a check that cannot run and reports green is worse than none.
            info("server.load-state-unknown", unknown.size() + " class(es), findLoadedClass refused: " + unknown);
        }
        check("server.no-client-class", loaded.isEmpty(), subjects.size() + " checked"
                + (loaded.isEmpty() ? "" : "; loaded: " + loaded + ", a NoClassDefFoundError on a real server"));
    }

    /** As a resource, never {@code Class.forName}: looking one up by name would load it. */
    private static boolean exists(String name) {
        String resource = name.replace('.', '/') + ".class";
        if (CgServerSmoke.class.getClassLoader().getResource(resource) != null) return true;
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null && context.getResource(resource) != null) return true;
        for (Path root : CgModLoader.roots()) if (Files.isRegularFile(root.resolve(resource))) return true;
        return false;
    }

    /** Every class the JVM defined, from {@code -Xlog:class+load}; null without one. */
    @Nullable
    private static Set<String> loadedFromJvmLog() {
        String log = CLASSLOG.get();
        if (log.isEmpty()) return null;
        Path file = Paths.get(log);
        if (!Files.isRegularFile(file)) return null;
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.map(CgServerSmoke::classNameIn).filter(name -> name != null).collect(Collectors.toSet());
        } catch (Throwable unreadable) {
            return null;
        }
    }

    /** {@code [0.1s][info][class,load] java.lang.Object source: ...} to {@code java.lang.Object}. */
    @Nullable
    private static String classNameIn(String line) {
        int start = line.indexOf(LOG_MARKER);
        if (start < 0) return null;
        start += LOG_MARKER.length();
        int end = line.indexOf(" source:", start);
        return (end < 0 ? line.substring(start) : line.substring(start, end)).trim();
    }

    /** Whether a loader this engine or the context sees defined {@code name}; null when it cannot be asked. */
    @Nullable
    private static Boolean loadedByAnyLoader(String name) {
        try {
            Method find = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
            find.setAccessible(true);
            for (ClassLoader start : new ClassLoader[]{CgServerSmoke.class.getClassLoader(),
                    Thread.currentThread().getContextClassLoader()}) {
                for (ClassLoader loader = start; loader != null; loader = loader.getParent()) {
                    if (find.invoke(loader, name) != null) return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        } catch (Throwable notAvailable) {
            return null;
        }
    }

    // ── the engine's server-side stack ──────────────────────────────────────────

    private void checkModCue() {
        String id = CUE.get();
        if (id.isEmpty()) {
            skip("server.cue", "-Dcrystalgraphics.serverSmoke.cue=<id> names a mod jar's cue");
            return;
        }
        boolean mounted = CgVfxAssets.instance().registry().entry(CgGameplayKinds.CUE, id) != null;
        check("server.cue-mounted", mounted, id + " among " + CgVfxAssets.instance().registry().namespaces());
        if (!mounted) return;
        String detail;
        boolean ok;
        try {
            CgCueDefinition cue = CgVfxAssets.<CgCueDefinition>ref(CgGameplayKinds.CUE, id).get();
            ok = cue.burst().shake() != null && cue.burst().shake().definition() != null;
            detail = ok ? "its shake resolved" : "its shake did not resolve";
        } catch (RuntimeException e) {
            ok = false;
            detail = e.toString();
        }
        check("server.cue-compiles", ok, detail);
    }

    /** Saved when this server stops, so the first run on a fresh world makes one and every run after finds it. */
    private static final CgNetField<String> KEPT_VALUE = CgNetField.of("value", CgCodecs.STRING);
    private static final CgNetType KEPT = CgNetType.define("crystalgraphics:probe/kept").fields(KEPT_VALUE).persisted().build();

    private void checkPersistence() {
        int found = KEPT.size();
        if (found == 0) {
            KEPT.spawn(CgAnchor.at("minecraft:overworld", 0, 64, 0)).set(KEPT_VALUE, "kept").start();
            skip("server.persisted", "made one; the next run on this world checks it");
            return;
        }
        String[] value = new String[1];
        KEPT.forEach(obj -> {
            if (value[0] == null) value[0] = obj.get(KEPT_VALUE);
        });
        check("server.persisted", found == 1 && "kept".equals(value[0]), found + " found, expected the one it saved");
    }

    /** Every kind of value a codec writes, as a map: what a mod saves into an entity's tags. */
    private static final CgCodec<Map<String, Object>> SAMPLE = new CgCodec<Map<String, Object>>() {
        private final CgCodec<List<Long>> longs = CgCodecs.listOf(CgCodecs.LONG);
        private final CgCodec<Map<String, Integer>> counts = CgCodecs.mapOf(CgCodecs.STRING, CgCodecs.INT);

        @Override
        @SuppressWarnings("unchecked")
        public <T> T encode(CgDynamicOps<T> ops, Map<String, Object> v) {
            return CgCodecs.map(ops).field("s", CgCodecs.STRING, (String) v.get("s"))
                    .field("i", CgCodecs.INT, (Integer) v.get("i")).field("f", CgCodecs.FLOAT, (Float) v.get("f"))
                    .field("d", CgCodecs.DOUBLE, (Double) v.get("d")).field("b", CgCodecs.BOOL, (Boolean) v.get("b"))
                    .field("bytes", CgCodecs.BYTES, (byte[]) v.get("bytes"))
                    .field("longs", longs, (List<Long>) v.get("longs"))
                    .field("counts", counts, (Map<String, Integer>) v.get("counts")).build();
        }

        @Override
        public <T> Map<String, Object> decode(CgDynamicOps<T> ops, T in) {
            CgCodecs.MapCodecReader<T> r = CgCodecs.read(ops, in);
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("s", r.field("s", CgCodecs.STRING));
            v.put("i", r.field("i", CgCodecs.INT));
            v.put("f", r.field("f", CgCodecs.FLOAT));
            v.put("d", r.field("d", CgCodecs.DOUBLE));
            v.put("b", r.field("b", CgCodecs.BOOL));
            v.put("bytes", r.field("bytes", CgCodecs.BYTES));
            v.put("longs", r.field("longs", longs));
            v.put("counts", r.field("counts", counts));
            return v;
        }
    };

    /** A codec's value crosses this version's own tags and back, read twice: reading leaves the tag as it was. */
    private void checkNbtRoundTrip() {
        String detail;
        boolean ok = false;
        try {
            Map<String, Object> want = sample();
            Object tag = CgFiles.toNbt(SAMPLE, want);
            Map<String, Object> first = CgFiles.fromNbt(SAMPLE, tag);
            Map<String, Object> second = CgFiles.fromNbt(SAMPLE, tag);
            ok = same(want, first) && same(want, second);
            detail = "tag " + tag.getClass().getSimpleName() + ": " + tag + (ok ? "" : ", read back as " + first + " then " + second);
        } catch (Throwable failed) {
            detail = String.valueOf(failed);
        }
        check("server.nbt", ok, detail);
    }

    /** JSON through this Minecraft's own Gson, YAML through the SnakeYAML CrystalGraphics carries relocated. */
    private void checkTextFormats() {
        for (CgTextFormat format : Arrays.asList(CgFiles.JSON, CgFiles.YAML)) {
            String name = format == CgFiles.JSON ? "json" : "yaml";
            String detail = "";
            boolean ok = false;
            try {
                Map<String, Object> want = sample();
                ok = same(want, format.fromText(SAMPLE, format.toText(SAMPLE, want)));
                detail = ok ? "round-trips" : "read back differently";
            } catch (Throwable failed) {
                detail = String.valueOf(failed);
            }
            check("server." + name, ok, detail);
        }
        String detail;
        boolean kept = false;
        try {
            CgCodec<Integer> commented = CgCodecs.record(
                    CgCodecs.comment("ours").field("level", CgCodecs.INT, (Integer level) -> level), (Integer level) -> level);
            Path file = Files.createTempFile("cg-smoke", ".yml");
            Files.write(file, "# theirs\nlevel: 1\n".getBytes(StandardCharsets.UTF_8));
            CgFiles.YAML.write(file, commented, 9);
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Files.delete(file);
            kept = text.contains("# theirs") && CgFiles.YAML.fromText(commented, text) == 9;
            detail = kept ? "a person's comment kept" : text.replace('\n', '|');
        } catch (Throwable failed) {
            detail = String.valueOf(failed);
        }
        check("server.yaml-comment", kept, detail);
    }

    private static Map<String, Object> sample() {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("s", "kamehameha");
        sample.put("i", -42);
        sample.put("f", 1.5f);
        sample.put("d", Math.PI);
        sample.put("b", true);
        sample.put("bytes", new byte[]{1, -2, 3});
        sample.put("longs", Arrays.asList(Long.MIN_VALUE, 0L, Long.MAX_VALUE));
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("goku", 9001);
        sample.put("counts", counts);
        return sample;
    }

    private static boolean same(Map<String, Object> want, Map<String, Object> got) {
        for (Map.Entry<String, Object> e : want.entrySet()) {
            Object v = got.get(e.getKey());
            boolean same = e.getValue() instanceof byte[]
                    ? v instanceof byte[] && Arrays.equals((byte[]) e.getValue(), (byte[]) v)
                    : e.getValue().equals(v);
            if (!same) return false;
        }
        return true;
    }

    /**
     * The server world's schedule as the server's tick drives it: an {@code onServer} function in {@code AUTHORITY}
     * runs every game tick, one that did not ask never does, no frame group runs; and a move's sequence plays headless.
     */
    private final class ServerTicks {
        final CgWorld world = CgWorld.serverWorld();
        final int[] authority = {0}, notOnServer = {0}, frame = {0};
        final HeadlessSequence move = new HeadlessSequence(world);
        int seen;

        ServerTicks() {
            world.schedule().add(CgTickGroup.AUTHORITY, "crystalgraphics:smoke.authority", dt -> authority[0]++).onServer(true);
            world.schedule().add(CgTickGroup.GAMEPLAY, "crystalgraphics:smoke.client-only", dt -> notOnServer[0]++);
            world.schedule().add(CgTickGroup.EFFECTS, "crystalgraphics:smoke.frame", dt -> frame[0]++).onServer(true);
        }

        /** True once, after {@link #TICKS} server ticks, with its checks stated. */
        boolean after() {
            if (++seen < TICKS) return false;
            // The run ticks before the world does, so the world has ticked one fewer time.
            check("server.world-ticks", authority[0] >= TICKS - 1 && world.ticks() >= TICKS - 1,
                    authority[0] + " runs over " + world.ticks() + " world ticks of " + TICKS + " server ticks");
            check("server.client-ticks-idle", notOnServer[0] == 0, notOnServer[0] + " runs of a function without onServer");
            check("server.no-frames", frame[0] == 0 && world.frames() == 0,
                    frame[0] + " frame-group runs, " + world.frames() + " frames");
            move.check();
            return true;
        }
    }

    /** A move's sequence played by the server world's sequencer: its {@code rpg} events heard in order, {@code ours} never. */
    private final class HeadlessSequence {
        static final String MOVE = "_v: 1\ntick_resolution: 1000\ndisplay_rate: 50\nrange: [0, 1500]\n"
                + "roles:\n  - { id: b1, name: caster, kind: caster }\ntracks:\n"
                + "  - { id: t1, kind: event, side: rpg, binding: { role: caster }, sections: [\n"
                + "      { id: a, keys: [[150, { event: crystalgraphics:release, args: { power: 3 } }], [1200, crystalgraphics:recover]] },\n"
                + "      { id: b, range: [300, 700], event: crystalgraphics:hit_window } ] }\n"
                + "  - { id: t2, kind: event, side: ours, binding: { role: caster }, sections: [ { id: c, keys: [[150, crystalgraphics:rumble]] } ] }\n";
        final List<String> expected = Arrays.asList("INSTANT crystalgraphics:release", "BEGIN crystalgraphics:hit_window",
                "END crystalgraphics:hit_window", "INSTANT crystalgraphics:recover");
        final List<String> rpg = new ArrayList<>(), ours = new ArrayList<>();
        final Thread server = Thread.currentThread();
        boolean offThread;
        CgSequencePlayer player;
        String failed;

        HeadlessSequence(CgWorld world) {
            try {
                CgSequencer sequencer = world.add(new CgSequencer());
                sequencer.listen(CgSequenceEventListener.Side.RPG, (p, phase, event, args) -> {
                    rpg.add(phase + " " + event);
                    offThread |= Thread.currentThread() != server;
                });
                sequencer.listen(CgSequenceEventListener.Side.OURS, (p, phase, event, args) -> ours.add(event));
                CgSequenceDefinition move = CgSequenceDefinition.compile(CgAssetData.of("crystalgraphics:smoke/move",
                        CgSequenceKinds.SEQUENCE, (Map<?, ?>) CgAssetYaml.read(MOVE), null));
                Map<String, CgSequenceTarget> roles = new LinkedHashMap<>();
                roles.put("caster", new CgSequenceLocator("caster"));
                player = sequencer.play(move, roles);
            } catch (RuntimeException e) {
                failed = String.valueOf(e);
            }
        }

        void check() {
            CgServerSmoke.this.check("server.sequence-plays", failed == null && player != null && player.seconds() > 1.2,
                    failed != null ? failed : "at " + (player == null ? "nothing" : player.seconds() + " s"));
            CgServerSmoke.this.check("server.sequence-events", rpg.equals(expected) && !offThread,
                    rpg + (offThread ? ", some off the server thread" : ""));
            CgServerSmoke.this.check("server.sequence-ours-silent", ours.isEmpty(), ours.toString());
        }
    }
}
