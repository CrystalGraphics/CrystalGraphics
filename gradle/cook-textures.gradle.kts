// COOKED TEXTURES: every PNG under -Pcook.from as BC7 with its mips (.cgtex, CgCookedTexture), mirrored under
// -Pcook.to. Encoded by texconv (DirectXTex, MIT), fetched once and pinned; the engine never encodes.
//
//   ./gradlew cookTextures -Pcook.from=<kit>/textures -Pcook.to=<kit>/cooked       # GPU encoder (DirectCompute)
//   ./gradlew cookTextures -Pcook.from=... -Pcook.to=... -Pcook.cpu                 # CPU encoder: minutes, every core
//
// A sidecar <name>.texture.yaml beside an image overrides its defaults (aaa-assets §26): `colour: linear` for data,
// `mips: false`, `compression: none` (no .cgtex: the PNG is loaded as it is). An image whose .cgtex is newer than it
// and its sidecar is skipped. Windows only, as texconv is: a cooked kit is committed or shipped, not cooked by every
// build. Rows stay top first, as CgTextureIO reads a PNG, so a cooked texture samples as its PNG does. Mips filter
// colour apart from alpha (-sepalpha), each a 2x2 average (-if BOX): Unreal's SimpleAverage, and GL's. An additive
// texture's colour where alpha is 0 is kept.
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

val texconvRelease = "may2026"
val texconv: File = gradle.gradleUserHomeDir.resolve("caches/crystalgraphics/texconv-$texconvRelease/texconv.exe")

val fetchTexconv = tasks.register("fetchTexconv") {
    group = "cook"
    description = "Fetches texconv $texconvRelease (microsoft/DirectXTex), pinned by SHA-256."
    onlyIf { !texconv.isFile }
    doLast {
        texconv.parentFile.mkdirs()
        val url = "https://github.com/microsoft/DirectXTex/releases/download/$texconvRelease/texconv.exe"
        val part = File(texconv.path + ".part")
        uri(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        val digest = MessageDigest.getInstance("SHA-256").digest(part.readBytes()).joinToString("") { "%02x".format(it) }
        val pinned = "dcfdec10244e02cf5037fba089c55fb7e1326b1c8181742d77d15fa5cb5eef06"
        if (digest != pinned) {
            part.delete()
            throw GradleException("$url hashed $digest, not the pinned $pinned")
        }
        part.renameTo(texconv)
    }
}

tasks.register("cookTextures") {
    group = "cook"
    description = "Cooks every PNG under -Pcook.from to BC7 .cgtex files under -Pcook.to."
    dependsOn(fetchTexconv)
    doLast {
        val from = file(findProperty("cook.from") ?: throw GradleException("-Pcook.from=<folder of PNGs> is required"))
        val to = file(findProperty("cook.to") ?: throw GradleException("-Pcook.to=<folder for .cgtex files> is required"))
        if (!System.getProperty("os.name").lowercase().contains("win"))
            throw GradleException("cookTextures runs texconv, which is Windows only")
        val cpu = hasProperty("cook.cpu")

        // Each image's settings from its sidecar; the ones to cook grouped by texconv's flags.
        val groups = linkedMapOf<Pair<Boolean, Boolean>, MutableList<File>>()   // (srgb, mips) -> images
        var skipped = 0
        from.walkTopDown().filter { it.isFile && it.extension.equals("png", true) }.sorted().forEach { png ->
            val sidecar = File(png.parentFile, png.nameWithoutExtension + ".texture.yaml")
            val settings = if (sidecar.isFile) sidecar.readLines()
                .mapNotNull { Regex("^\\s*(\\w+)\\s*:\\s*([\\w-]+)").find(it.substringBefore('#'))?.destructured }
                .associate { (k, v) -> k to v } else emptyMap()
            if (settings["compression"] == "none") return@forEach
            val out = File(to, png.relativeTo(from).path.removeSuffix(".png").removeSuffix(".PNG") + ".cgtex")
            val newest = maxOf(png.lastModified(), if (sidecar.isFile) sidecar.lastModified() else 0L)
            if (out.isFile && out.lastModified() >= newest) { skipped++; return@forEach }
            val key = Pair(settings["colour"] != "linear", settings["mips"] != "false")
            groups.getOrPut(key) { mutableListOf() }.add(png)
        }

        val scratch = temporaryDir.resolve("dds").apply { deleteRecursively(); mkdirs() }
        var cooked = 0
        var bytes = 0L
        for ((key, images) in groups) {
            val (srgb, mips) = key
            // texconv writes <name>.dds flat: one batch per folder keeps two folders' same names apart.
            for ((folder, batch) in images.groupBy { it.parentFile }) {
                val list = scratch.resolve("list.txt")
                list.writeText(batch.joinToString("\n") { it.absolutePath } + "\n")
                val args = mutableListOf(texconv.absolutePath, "-nologo", "-y", "-sepalpha", "-if", "BOX", "-o", scratch.absolutePath,
                    "-m", if (mips) "0" else "1", "-f", if (srgb) "BC7_UNORM_SRGB" else "BC7_UNORM")
                if (srgb) args += "-srgb"
                if (cpu) args += "-nogpu"
                args += listOf("-flist", list.absolutePath)
                val process = ProcessBuilder(args).redirectErrorStream(true).start()
                val log = process.inputStream.bufferedReader().readText()
                if (process.waitFor() != 0) throw GradleException("texconv failed on $folder:\n$log")
                for (png in batch) {
                    val dds = scratch.resolve(png.nameWithoutExtension + ".dds")
                    if (!dds.isFile) throw GradleException("texconv wrote no ${dds.name}:\n$log")
                    val out = File(to, png.relativeTo(from).path.removeSuffix(".png").removeSuffix(".PNG") + ".cgtex")
                    out.parentFile.mkdirs()
                    val cgtex = cgtex(dds.readBytes(), srgb, png)
                    out.writeBytes(cgtex)
                    dds.delete()
                    cooked++
                    bytes += cgtex.size
                }
            }
        }
        logger.lifecycle("cookTextures: $cooked cooked (${bytes / 1024} KB), $skipped up to date, into $to")
    }
}

/** A texconv DDS (BC7 with a DX10 header) as a .cgtex: the same blocks, a level table in front. */
fun cgtex(dds: ByteArray, srgb: Boolean, source: File): ByteArray {
    val b = ByteBuffer.wrap(dds).order(ByteOrder.LITTLE_ENDIAN)
    if (b.getInt(0) != 0x20534444) throw GradleException("$source: texconv's output is not a DDS")
    val height = b.getInt(12)
    val width = b.getInt(16)
    val levels = maxOf(1, b.getInt(28))
    if (b.getInt(84) != 0x30315844) throw GradleException("$source: no DX10 header in texconv's DDS")
    val dxgi = b.getInt(128)
    if (dxgi != (if (srgb) 99 else 98)) throw GradleException("$source: DXGI format $dxgi, not BC7")
    if (width > 0xFFFF || height > 0xFFFF) throw GradleException("$source: ${width}x$height is past a .cgtex's u16")
    val sizes = IntArray(levels) { l -> ((maxOf(1, width shr l) + 3) / 4) * ((maxOf(1, height shr l) + 3) / 4) * 16 }
    val data = 148
    if (data + sizes.sum() > dds.size) throw GradleException("$source: texconv's DDS is short")
    val table = 16 + 8 * levels
    val out = ByteBuffer.allocate(table + sizes.sum()).order(ByteOrder.LITTLE_ENDIAN)
    out.put("CGTX".toByteArray()).putShort(1).putShort((if (srgb) 2 else 1).toShort())
        .putShort(width.toShort()).putShort(height.toShort()).putInt(levels)
    var at = table
    for (size in sizes) { out.putInt(at).putInt(size); at += size }
    out.put(dds, data, sizes.sum())
    return out.array()
}
