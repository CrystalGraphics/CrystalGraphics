package com.crystalgraphics.text.font;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads OpenType/TrueType font files without a native library: the faces a file holds, each
 * face's names, weight and code point coverage, and one face of a collection as a standalone font.
 *
 * <pre>{@code
 * // what a .ttc holds
 * for (Sfnt.Face face : Sfnt.readFaces(Path.of("C:/Windows/Fonts/msgothic.ttc"))) {
 *     System.out.println(face.index() + " " + face.family() + " " + face.style());
 * }
 *
 * // one face of it, as bytes FreeType, HarfBuzz and msdfgen all read as face 0
 * byte[] uiGothic = Sfnt.extractFace(Files.readAllBytes(path), 1);
 * }</pre>
 *
 * <p>Table layouts are the OpenType specification's: the {@code ttcf} header, the table directory,
 * {@code name}, {@code OS/2}, {@code head}, {@code fvar} and {@code cmap}. A malformed file throws
 * {@link IOException} from {@link #readFaces} and {@link IllegalArgumentException} from
 * {@link #extractFace}; neither reads outside the bytes it was given.</p>
 */
public final class Sfnt {

    private static final int TTCF = 0x74746366;
    private static final int VERSION_1 = 0x00010000;
    private static final int OTTO = 0x4F54544F;
    private static final int TRUE = 0x74727565;

    private static final int NAME = 0x6E616D65;
    private static final int OS2 = 0x4F532F32;
    private static final int HEAD = 0x68656164;
    private static final int FVAR = 0x66766172;
    private static final int CMAP = 0x636D6170;
    private static final int GLYF = 0x676C7966;
    private static final int CFF = 0x43464620;
    private static final int CFF2 = 0x43464632;
    private static final int WGHT = 0x77676874;

    /** Above this a table is corrupt rather than large; the biggest real cmap is a few hundred KB. */
    private static final long MAX_TABLE = 32L << 20;

    /** Apple-platform names; where a runtime lacks the charset, ISO-8859-1 agrees with it on ASCII. */
    private static final Charset MAC_ROMAN =
            Charset.isSupported("x-MacRoman") ? Charset.forName("x-MacRoman") : StandardCharsets.ISO_8859_1;

    private Sfnt() {
    }

    /**
     * One face of a font file.
     *
     * @param family       the family as FreeType names it: the WWS family (name ID 21) of a font not
     *                     flagged WWS, else the typographic family (16), else 1 — "Segoe UI", "Sitka Small"
     * @param legacyFamily the GDI family (name ID 1), which folds weights into the name: "Segoe UI Semibold"
     * @param familyNames  every family name the face answers to: IDs 1, 16 and 21 in every language the
     *                     font is named in, {@code family} first — "Meiryo", "メイリオ". A browser matches
     *                     {@code font-family} against all of them, as DirectWrite and fontconfig do
     * @param style        the subfamily, by the same rule (22, 17, 2)
     * @param weight       CSS weight of the default instance, 1–1000
     * @param width        {@code usWidthClass}, 1–9 with 5 normal
     * @param minWeight    lowest weight a variable face reaches; equal to {@code weight} for a static one
     * @param outlines     has {@code glyf}, {@code CFF } or {@code CFF2}; a bitmap-only face (colour
     *                     emoji in {@code sbix}/{@code CBDT}) has nothing this renderer can draw
     */
    public record Face(int index, String family, String legacyFamily, List<String> familyNames, String style,
                       int weight, boolean italic, int width,
                       float minWeight, float maxWeight, boolean outlines,
                       CodePointCoverage coverage) {

        public boolean isVariableWeight() {
            return minWeight < maxWeight;
        }
    }

    /** Whether {@code data} is a font collection ({@code .ttc}/{@code .otc}). */
    public static boolean isCollection(byte[] data) {
        return data != null && data.length >= 12 && ByteBuffer.wrap(data).getInt(0) == TTCF;
    }

    /** How many faces {@code data} holds: the collection's count, or 1. */
    public static int faceCount(byte[] data) {
        return isCollection(data) ? ByteBuffer.wrap(data).getInt(8) : 1;
    }

    /**
     * Face {@code faceIndex} of a font file as a standalone font. A single-face file comes back as
     * the same array when {@code faceIndex} is 0.
     *
     * <p>Tables are copied, not shared, so a face of a 20 MB collection is nearly as large as the
     * collection. {@code head.checkSumAdjustment} is left stale; FreeType, HarfBuzz and msdfgen
     * ignore it.</p>
     *
     * @throws IllegalArgumentException for a negative or out-of-range index, a non-zero index on a
     *                                  single-face file, or a table outside the data
     */
    public static byte[] extractFace(byte[] data, int faceIndex) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (faceIndex < 0) {
            throw new IllegalArgumentException("faceIndex must be >= 0, got: " + faceIndex);
        }
        if (!isCollection(data)) {
            if (faceIndex != 0) {
                throw new IllegalArgumentException("not a font collection, so faceIndex must be 0, got: " + faceIndex);
            }
            return data;
        }

        ByteBuffer in = ByteBuffer.wrap(data);
        int numFonts = in.getInt(8);
        if (faceIndex >= numFonts) {
            throw new IllegalArgumentException("faceIndex " + faceIndex + " out of range; the collection holds " + numFonts);
        }
        long directory = u32(in, 12 + 4 * faceIndex);
        requireInside(data, directory, 12);
        int numTables = u16(in, (int) directory + 4);
        requireInside(data, directory + 12, 16L * numTables);

        int headerSize = 12 + 16 * numTables;
        long total = headerSize;
        for (int i = 0; i < numTables; i++) {
            int record = (int) directory + 12 + 16 * i;
            long offset = u32(in, record + 8);
            long length = u32(in, record + 12);
            requireInside(data, offset, length);
            total += align4(length);
        }
        if (total > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("face " + faceIndex + " is too large to extract: " + total + " bytes");
        }

        byte[] out = new byte[(int) total];
        ByteBuffer o = ByteBuffer.wrap(out);
        o.putInt(0, in.getInt((int) directory));
        o.putShort(4, (short) numTables);
        o.putShort(6, in.getShort((int) directory + 6));
        o.putShort(8, in.getShort((int) directory + 8));
        o.putShort(10, in.getShort((int) directory + 10));
        int cursor = headerSize;
        for (int i = 0; i < numTables; i++) {
            int record = (int) directory + 12 + 16 * i;
            int offset = (int) u32(in, record + 8);
            int length = (int) u32(in, record + 12);
            int target = 12 + 16 * i;
            o.putInt(target, in.getInt(record));
            o.putInt(target + 4, in.getInt(record + 4));
            o.putInt(target + 8, cursor);
            o.putInt(target + 12, length);
            System.arraycopy(data, offset, out, cursor, length);
            cursor += (int) align4(length);
        }
        return out;
    }

    /**
     * Every face in {@code file}, reading only the table directory and the tables described on
     * {@link Face} — never the whole file. A face with no family name is skipped.
     */
    public static List<Face> readFaces(Path file) throws IOException {
        try (Source source = new Source(file)) {
            ByteBuffer header = source.read(0, 12);
            int tag = header.getInt(0);
            List<Face> faces = new ArrayList<Face>();
            Map<Long, CodePointCoverage> cmaps = new HashMap<Long, CodePointCoverage>();
            if (tag == TTCF) {
                long numFonts = u32(header, 8);
                if (numFonts <= 0 || numFonts > 1024) {
                    throw new IOException("implausible face count " + numFonts + " in " + file);
                }
                ByteBuffer offsets = source.read(12, 4 * numFonts);
                for (int i = 0; i < numFonts; i++) {
                    Face face = readFace(source, u32(offsets, 4 * i), i, cmaps);
                    if (face != null) {
                        faces.add(face);
                    }
                }
            } else if (tag == VERSION_1 || tag == OTTO || tag == TRUE) {
                Face face = readFace(source, 0, 0, cmaps);
                if (face != null) {
                    faces.add(face);
                }
            } else {
                throw new IOException("not an OpenType font: " + file);
            }
            return faces;
        } catch (IndexOutOfBoundsException e) {
            throw new IOException("malformed font " + file + ": " + e.getMessage(), e);
        }
    }

    private static Face readFace(Source source, long directory, int index,
                                 Map<Long, CodePointCoverage> cmaps) throws IOException {
        ByteBuffer header = source.read(directory, 12);
        int numTables = u16(header, 4);
        ByteBuffer records = source.read(directory + 12, 16L * numTables);
        Map<Integer, long[]> tables = new HashMap<Integer, long[]>();
        for (int i = 0; i < numTables; i++) {
            tables.put(records.getInt(16 * i), new long[] {u32(records, 16 * i + 8), u32(records, 16 * i + 12)});
        }

        int weight = 400;
        int width = 5;
        boolean italic = false;
        boolean wws = false;
        ByteBuffer os2 = table(source, tables, OS2);
        if (os2 != null && os2.limit() >= 8) {
            weight = u16(os2, 4);
            width = u16(os2, 6);
            if (os2.limit() >= 64) {
                int selection = u16(os2, 62);
                italic = (selection & 0x0001) != 0 || (selection & 0x0200) != 0;
                wws = (selection & 0x0100) != 0;
            }
        } else {
            ByteBuffer head = table(source, tables, HEAD);
            if (head != null && head.limit() >= 46) {
                int macStyle = u16(head, 44);
                weight = (macStyle & 1) != 0 ? 700 : 400;
                italic = (macStyle & 2) != 0;
            }
        }
        // Fonts from the 1-9 era of usWeightClass.
        if (weight >= 1 && weight <= 9) {
            weight *= 100;
        }
        weight = Math.max(1, Math.min(1000, weight == 0 ? 400 : weight));
        if (width < 1 || width > 9) {
            width = 5;
        }

        ByteBuffer name = table(source, tables, NAME);
        if (name == null) {
            return null;
        }
        // FreeType's choice (sfobjs.c): a font flagged WWS names its family in IDs 16/17; one that is
        // not may carry WWS names in 21/22, which then win — "Sitka Small", not "Sitka".
        String legacyFamily = name(name, 1);
        String family = firstName(name, wws ? new int[] {16, 1} : new int[] {21, 16, 1});
        String style = firstName(name, wws ? new int[] {17, 2} : new int[] {22, 17, 2});
        if (family == null) {
            return null;
        }
        List<String> familyNames = familyNames(name, family);

        float minWeight = weight;
        float maxWeight = weight;
        ByteBuffer fvar = table(source, tables, FVAR);
        if (fvar != null && fvar.limit() >= 16) {
            int axesOffset = u16(fvar, 4);
            int axisCount = u16(fvar, 8);
            int axisSize = u16(fvar, 10);
            for (int i = 0; i < axisCount; i++) {
                int axis = axesOffset + i * axisSize;
                if (axis + 16 > fvar.limit()) {
                    break;
                }
                if (fvar.getInt(axis) == WGHT) {
                    minWeight = fvar.getInt(axis + 4) / 65536f;
                    maxWeight = fvar.getInt(axis + 12) / 65536f;
                }
            }
        }

        CodePointCoverage coverage = CodePointCoverage.EMPTY;
        long[] cmap = tables.get(CMAP);
        if (cmap != null) {
            coverage = cmaps.get(cmap[0]);
            if (coverage == null) {
                coverage = coverage(source.read(cmap[0], cmap[1]));
                cmaps.put(cmap[0], coverage);
            }
        }

        boolean outlines = tables.containsKey(GLYF) || tables.containsKey(CFF) || tables.containsKey(CFF2);
        return new Face(index, family, legacyFamily != null ? legacyFamily : family, familyNames,
                style != null ? style : "Regular", weight, italic, width, minWeight, maxWeight, outlines, coverage);
    }

    private static ByteBuffer table(Source source, Map<Integer, long[]> tables, int tag) throws IOException {
        long[] entry = tables.get(tag);
        return entry == null ? null : source.read(entry[0], entry[1]);
    }

    // ── name ────────────────────────────────────────────────────────────────

    private static String firstName(ByteBuffer table, int[] nameIds) {
        for (int nameId : nameIds) {
            String value = name(table, nameId);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * The name FreeType gives ID {@code nameId} ({@code tt_face_get_name}, sfobjs.c): a Windows name in
     * English of any region; an Apple one when the Windows name is in another language; a Windows name
     * in any language; an Apple one; a Unicode one. Empty records are passed over.
     */
    private static String name(ByteBuffer table, int nameId) {
        if (table.limit() < 6) {
            return null;
        }
        int count = u16(table, 2);
        int win = -1;
        int appleEnglish = -1;
        int appleRoman = -1;
        int unicode = -1;
        boolean winEnglish = false;
        for (int i = 0; i < count; i++) {
            int record = 6 + i * 12;
            if (record + 12 > table.limit()) {
                break;
            }
            if (u16(table, record + 6) != nameId || u16(table, record + 8) == 0) {
                continue;
            }
            int platform = u16(table, record);
            int encoding = u16(table, record + 2);
            int language = u16(table, record + 4);
            if (platform == 0 || platform == 2) {
                unicode = record;
            } else if (platform == 1) {
                if (language == 0) {
                    appleEnglish = record;
                } else if (encoding == 0) {
                    appleRoman = record;
                }
            } else if (platform == 3 && isUnicodeWindowsEncoding(encoding)) {
                boolean english = (language & 0x3FF) == 0x009;
                if (win < 0 || english) {
                    win = record;
                    winEnglish = english;
                }
            }
        }
        int apple = appleEnglish >= 0 ? appleEnglish : appleRoman;
        int chosen = win >= 0 && (apple < 0 || winEnglish) ? win : apple >= 0 ? apple : unicode;
        return chosen < 0 ? null : decode(table, chosen);
    }

    /** {@code family}, then every other name the font gives IDs 1, 16 and 21, in whatever language. */
    private static List<String> familyNames(ByteBuffer table, String family) {
        Map<String, String> names = new LinkedHashMap<String, String>();
        names.put(family.toLowerCase(Locale.ROOT), family);
        int count = u16(table, 2);
        for (int i = 0; i < count; i++) {
            int record = 6 + i * 12;
            if (record + 12 > table.limit()) {
                break;
            }
            int nameId = u16(table, record + 6);
            int platform = u16(table, record);
            int encoding = u16(table, record + 2);
            boolean decodable = platform == 0 || (platform == 1 && encoding == 0)
                    || (platform == 3 && isUnicodeWindowsEncoding(encoding));
            if ((nameId == 1 || nameId == 16 || nameId == 21) && decodable) {
                String name = decode(table, record);
                if (name != null) {
                    names.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
                }
            }
        }
        return List.copyOf(names.values());
    }

    private static boolean isUnicodeWindowsEncoding(int encoding) {
        return encoding == 0 || encoding == 1 || encoding == 10;
    }

    private static String decode(ByteBuffer table, int record) {
        int length = u16(table, record + 8);
        int offset = u16(table, 4) + u16(table, record + 10);
        if (length == 0 || offset + length > table.limit()) {
            return null;
        }
        byte[] raw = new byte[length];
        ByteBuffer view = table.duplicate();
        view.position(offset);
        view.get(raw);
        String value = new String(raw, u16(table, record) == 1 ? MAC_ROMAN : StandardCharsets.UTF_16BE).trim();
        return value.isEmpty() ? null : value;
    }

    // ── cmap ────────────────────────────────────────────────────────────────

    private static CodePointCoverage coverage(ByteBuffer cmap) {
        if (cmap.limit() < 4) {
            return CodePointCoverage.EMPTY;
        }
        int count = u16(cmap, 2);
        int best = -1;
        int bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            int record = 4 + i * 8;
            if (record + 8 > cmap.limit()) {
                break;
            }
            int rank = cmapRank(u16(cmap, record), u16(cmap, record + 2));
            long offset = u32(cmap, record + 4);
            if (rank < bestRank && offset + 4 <= cmap.limit() && isSupportedFormat(u16(cmap, (int) offset))) {
                bestRank = rank;
                best = (int) offset;
            }
        }
        if (best < 0) {
            return CodePointCoverage.EMPTY;
        }
        CodePointCoverage.Builder ranges = new CodePointCoverage.Builder();
        switch (u16(cmap, best)) {
            case 4:
                format4(cmap, best, ranges);
                break;
            case 6:
                format6(cmap, best, ranges);
                break;
            case 12:
                format12(cmap, best, ranges, false);
                break;
            case 13:
                format12(cmap, best, ranges, true);
                break;
            default:
                break;
        }
        return ranges.build();
    }

    /**
     * FreeType's preference: a full-repertoire Unicode map, then a BMP one. A Symbol-only font
     * ({@code 3,0}: Wingdings, Marlett) gets none, as FreeType selects no charmap for it.
     */
    private static int cmapRank(int platform, int encoding) {
        if (platform == 3 && encoding == 10) {
            return 0;
        }
        if (platform == 0 && (encoding == 4 || encoding == 6)) {
            return 1;
        }
        if (platform == 3 && encoding == 1) {
            return 2;
        }
        if (platform == 0 && encoding <= 3) {
            return 3;
        }
        return Integer.MAX_VALUE;
    }

    private static boolean isSupportedFormat(int format) {
        return format == 4 || format == 6 || format == 12 || format == 13;
    }

    private static void format4(ByteBuffer cmap, int offset, CodePointCoverage.Builder ranges) {
        int limit = cmap.limit();
        int segX2 = u16(cmap, offset + 6);
        int segCount = segX2 / 2;
        int endCodes = offset + 14;
        int startCodes = endCodes + segX2 + 2;
        int deltas = startCodes + segX2;
        int rangeOffsets = deltas + segX2;
        if (rangeOffsets + segX2 > limit) {
            return;
        }
        for (int s = 0; s < segCount; s++) {
            int end = u16(cmap, endCodes + 2 * s);
            int start = u16(cmap, startCodes + 2 * s);
            int delta = u16(cmap, deltas + 2 * s);
            int rangeOffset = u16(cmap, rangeOffsets + 2 * s);
            if (start > end || start == 0xFFFF) {
                continue;
            }
            if (rangeOffset == 0) {
                // glyph = (c + delta) mod 65536, which is .notdef for at most one c
                int unmapped = (0x10000 - delta) & 0xFFFF;
                if (unmapped >= start && unmapped <= end) {
                    ranges.add(start, unmapped - 1);
                    ranges.add(unmapped + 1, end);
                } else {
                    ranges.add(start, end);
                }
                continue;
            }
            int glyphs = rangeOffsets + 2 * s + rangeOffset;
            for (int c = start; c <= end; c++) {
                int at = glyphs + 2 * (c - start);
                if (at + 2 > limit) {
                    break;
                }
                int glyph = u16(cmap, at);
                if (glyph != 0 && ((glyph + delta) & 0xFFFF) != 0) {
                    ranges.add(c, c);
                }
            }
        }
    }

    private static void format6(ByteBuffer cmap, int offset, CodePointCoverage.Builder ranges) {
        int first = u16(cmap, offset + 6);
        int count = u16(cmap, offset + 8);
        for (int i = 0; i < count; i++) {
            int at = offset + 10 + 2 * i;
            if (at + 2 > cmap.limit()) {
                break;
            }
            if (u16(cmap, at) != 0) {
                ranges.add(first + i, first + i);
            }
        }
    }

    private static void format12(ByteBuffer cmap, int offset, CodePointCoverage.Builder ranges, boolean manyToOne) {
        long groups = u32(cmap, offset + 12);
        for (long g = 0; g < groups; g++) {
            long at = offset + 16 + 12 * g;
            if (at + 12 > cmap.limit()) {
                break;
            }
            long start = u32(cmap, (int) at);
            long end = Math.min(u32(cmap, (int) at + 4), Character.MAX_CODE_POINT);
            long glyph = u32(cmap, (int) at + 8);
            if (start > end) {
                continue;
            }
            if (manyToOne) {
                if (glyph != 0) {
                    ranges.add((int) start, (int) end);
                }
            } else {
                ranges.add((int) (glyph == 0 ? start + 1 : start), (int) end);
            }
        }
    }

    // ── bytes ───────────────────────────────────────────────────────────────

    private static int u16(ByteBuffer buffer, int index) {
        return buffer.getShort(index) & 0xFFFF;
    }

    private static long u32(ByteBuffer buffer, int index) {
        return buffer.getInt(index) & 0xFFFFFFFFL;
    }

    private static long align4(long length) {
        return (length + 3) & ~3L;
    }

    private static void requireInside(byte[] data, long offset, long length) {
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IllegalArgumentException("font table outside the data: " + offset + "+" + length
                    + " of " + data.length + " bytes");
        }
    }

    /** Random access to the file, so a scan reads kilobytes of a 20 MB collection rather than all of it. */
    private static final class Source implements Closeable {

        private final Path file;
        private final FileChannel channel;
        private final long size;

        Source(Path file) throws IOException {
            this.file = file;
            this.channel = FileChannel.open(file, StandardOpenOption.READ);
            this.size = channel.size();
        }

        ByteBuffer read(long position, long length) throws IOException {
            if (position < 0 || length < 0 || position + length > size) {
                throw new IOException("table outside the file " + file + ": " + position + "+" + length);
            }
            if (length > MAX_TABLE) {
                throw new IOException("table too large in " + file + ": " + length + " bytes");
            }
            ByteBuffer buffer = ByteBuffer.allocate((int) length);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer, position + buffer.position()) < 0) {
                    throw new EOFException(file.toString());
                }
            }
            buffer.flip();
            return buffer;
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
