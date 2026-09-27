// The legacy tree's one branch — Forge 1.8 to 1.12.2, a node per SRG plateau (`versions/<version>/`,
// whose gradle.properties pins Forge and MCP). Real mode compiles through Unimined's FG2 support at MCP
// names; stub mode compiles against the database and never loads Unimined. @see cgbuildlogic.LegacyTree

import cgbuildlogic.registerMcpReobf
import cgbuildlogic.registerThinRename
import cgbuildlogic.stubMode
import xyz.wagyourtail.unimined.api.UniminedExtension

plugins {
    id("cg-legacy-loader")
    id("com.gradleup.shadow")
    // Applied on a real node only: a stub build must not resolve Minecraft.
    id("xyz.wagyourtail.unimined") version "1.4.1" apply false
}

if (!stubMode) {
    apply(plugin = "xyz.wagyourtail.unimined")
    val (channel, mappings) = property("mcp.mappings").toString().split(':', limit = 2)
    the<UniminedExtension>().minecraft {
        version(property("mc.version").toString())
        mappings {
            searge()
            mcp(channel, mappings)
        }
        minecraftForge { loader(property("forge.version").toString()) }
        // The shipped jar is the thin shadow jar, renamed below.
        defaultRemapJar = false
    }
}

// The merge's input from this node: its classes at the SRG members FML runs, class names kept.
val main = sourceSets.main.get()
val thinJar = registerThinRename("thinShadowJar", "thin") {
    registerMcpReobf("thinShadowJar", "thin", main.compileClasspath)
}
tasks.named<cgbuildlogic.CheckThinJar>("checkThinJar") { jar.set(thinJar.flatMap { it.archiveFile }) }
tasks.named("assemble") { dependsOn(thinJar) }
