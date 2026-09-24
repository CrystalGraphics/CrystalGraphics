// Vanilla Minecraft through Unimined, for a common node Loom cannot serve: 1.13 has no intermediary and
// no Mojang names, so it compiles against the backported ones. Applied from ./build.gradle.kts.
//
// A script plugin so that Unimined loads in a classloader of its own. Requested in common's `plugins {}`
// it sits beside Loom, whose classes it carries copies of, and Loom then fails in every fabric node that
// depends on a common node.

import org.gradle.api.tasks.SourceSetContainer
import xyz.wagyourtail.unimined.UniminedPlugin
import xyz.wagyourtail.unimined.api.UniminedExtension

buildscript {
    repositories {
        maven("https://maven.wagyourtail.xyz/releases")
        gradlePluginPortal()
    }
    dependencies { classpath("xyz.wagyourtail.unimined:unimined:1.4.1") }
}

apply<UniminedPlugin>()
the<UniminedExtension>().minecraft {
    version(property("mc.version").toString())
    mappings {
        mapping(extra["cg.backportedMojmap"] as File, "mojmap") { requires("official"); provides("mojmap" to true) }
        devNamespace("mojmap")
    }
    defaultRemapJar = false
}
// Unimined attaches Minecraft to `main` alone; the language mod's source set needs it too.
val sourceSets = the<SourceSetContainer>()
sourceSets.findByName("lang")?.let { lang ->
    the<UniminedExtension>().minecraft(lang) { combineWith(sourceSets["main"]) }
}
dependencies { "compileOnly"("com.google.code.findbugs:jsr305:3.0.2") }
