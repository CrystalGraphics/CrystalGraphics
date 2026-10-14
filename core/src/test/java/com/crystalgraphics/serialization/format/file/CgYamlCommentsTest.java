package com.crystalgraphics.serialization.format.file;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgObjectCodec;
import com.crystalgraphics.serialization.format.CgFormats;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A codec's comments written, and a person's kept, when a mod writes its YAML again. */
public class CgYamlCommentsTest {

    record Audio(float volume, boolean subtitles) {
        static final CgCodec<Audio> CODEC = CgCodecs.record(
                CgCodecs.comment("Master volume, 0 to 1.").field("volume", CgCodecs.FLOAT, Audio::volume),
                CgCodecs.field("subtitles", CgCodecs.BOOL, Audio::subtitles),
                Audio::new);
    }

    static class Settings {
        Audio audio = new Audio(1f, false);
        final List<String> muted = new ArrayList<>();
        int legacy;

        static final CgObjectCodec<Settings> CODEC = CgCodecs.object(Settings::new)
                .comment("Sound.\nAll of it.").field("audio", Audio.CODEC, s -> s.audio, (s, v) -> s.audio = v)
                .into("muted", CgCodecs.listOf(CgCodecs.STRING), s -> s.muted)
                .build();
    }

    static class More extends Settings {
        int extra;
        static final CgObjectCodec<More> CODEC = CgCodecs.object(More::new)
                .extend(Settings.CODEC)
                .comment("Only on More.").field("extra", CgCodecs.INT, m -> m.extra, (m, v) -> m.extra = v)
                .build();
    }

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static Settings settings(float volume, String... muted) {
        Settings s = new Settings();
        s.audio = new Audio(volume, false);
        s.muted.addAll(Arrays.asList(muted));
        return s;
    }

    @Test
    public void aCodecsCommentsAreWrittenAboveTheirKeys() {
        String text = CgFormats.YAML.toText(Settings.CODEC, settings(0.5f, "vegeta"));
        assertTrue(text, text.startsWith("# Sound.\n# All of it.\naudio:\n"));
        assertTrue(text, text.contains("  # Master volume, 0 to 1.\n  volume: 0.5\n"));
        assertEquals(0.5f, CgFormats.YAML.fromText(Settings.CODEC, text).audio.volume(), 0f);
        assertFalse(CgFormats.JSON.toText(Settings.CODEC, settings(0.5f)).contains("#"));
    }

    @Test
    public void aCommentedKeyHasABlankLineAboveItTheFirstExcepted() throws IOException {
        String text = CgFormats.YAML.toText(More.CODEC, new More());
        assertTrue(text, text.startsWith("# Sound."));
        assertTrue(text, text.contains("\n\n# Only on More.\nextra:"));
        Path file = folder.getRoot().toPath().resolve("more.yml");
        CgFormats.YAML.write(file, More.CODEC, new More());
        CgFormats.YAML.write(file, More.CODEC, CgFormats.YAML.read(file, More.CODEC));
        assertEquals("written again, the blank line is not doubled", text,
                new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    @Test
    public void aPersonsCommentsSurviveTheModWritingTheFileAgain() throws IOException {
        Path file = folder.newFile("mymod.yml").toPath();
        Files.write(file, ("# my own: quiet at night\n"
                + "audio:\n"
                + "  volume: 0.2   # turned down\n"
                + "  subtitles: false\n"
                + "muted:\n"
                + "  - vegeta\n"
                + "# about a key the mod no longer writes\n"
                + "legacy: 3\n").getBytes(StandardCharsets.UTF_8));
        Settings live = CgFormats.YAML.read(file, Settings.CODEC);
        live.muted.add("goku");
        live.audio = new Audio(0.3f, true);
        CgFormats.YAML.write(file, Settings.CODEC, live);

        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(text, text.startsWith("# my own: quiet at night\naudio:\n"));      // theirs, over the codec's
        assertTrue(text, text.contains("# Master volume, 0 to 1."));                  // ours, where they wrote none
        assertTrue(text, text.contains("volume: 0.3 # turned down"));
        assertFalse(text, text.contains("no longer writes"));
        Settings back = CgFormats.YAML.read(file, Settings.CODEC);
        assertEquals(Arrays.asList("vegeta", "goku"), back.muted);
        assertTrue(back.audio.subtitles());
    }

    @Test
    public void aFileThatIsNotYamlIsReplacedWithoutItsComments() throws IOException {
        Path file = folder.newFile("broken.yml").toPath();
        Files.write(file, "audio: [unclosed\n".getBytes(StandardCharsets.UTF_8));
        CgFormats.YAML.write(file, Settings.CODEC, settings(0.7f));
        assertEquals(0.7f, CgFormats.YAML.read(file, Settings.CODEC).audio.volume(), 0f);
    }

    @Test
    public void aSubclassCommentLeavesItsParentAlone() {
        assertTrue(CgFormats.YAML.toText(More.CODEC, new More()).contains("# Only on More.\nextra:"));
        assertFalse(CgFormats.YAML.toText(Settings.CODEC, new Settings()).contains("Only on More."));
    }

    @Test
    public void theCodecsOwnCommentIsTheFilesHeader() {
        CgObjectCodec<Settings> headed = CgCodecs.object(Settings::new)
                .comment("Players you never see.").into("muted", CgCodecs.listOf(CgCodecs.STRING), s -> s.muted)
                .build()
                .comment("mymod settings.");
        String text = CgFormats.YAML.toText(headed, settings(1f, "vegeta"));
        assertTrue(text, text.startsWith("# mymod settings.\n\n# Players you never see.\nmuted:"));
    }

    @Test
    public void aCommentInsideAListElementIsWritten() {
        CgCodec<List<Audio>> holder = CgCodecs.record(
                CgCodecs.field("tracks", CgCodecs.listOf(Audio.CODEC), (List<Audio> l) -> l), l -> l);
        String text = CgFormats.YAML.toText(holder, Arrays.asList(new Audio(0.5f, true)));
        assertTrue(text, text.contains("# Master volume, 0 to 1."));
    }
}
