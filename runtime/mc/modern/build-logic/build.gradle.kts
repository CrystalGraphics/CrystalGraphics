plugins { `kotlin-dsl` }

repositories {
    gradlePluginPortal()
    maven("https://maven.neoforged.net/releases") // ModDevGradle
}

dependencies {
    // ModDevGradle: Minecraft on each node's classpath -- NeoForm or legacyForge per node, chosen by
    // the shared useModernMinecraft, which takes it compileOnly and relies on this line.
    implementation("net.neoforged:moddev-gradle:2.0.141")

    // Shadow, so ShadowJar is a type these scripts can name. The version matches the pin in
    // settings.gradle.kts that every loader applies.
    // The shared single-jar tasks: CheckSingleJar and ModDescriptor. @see CrystalGraphics/singlejar-logic
    implementation("com.crystalgraphics.build:singlejar-logic")

    implementation("com.gradleup.shadow:shadow-gradle-plugin:9.2.2")

    // jvmDowngrader, for the single jar: core and platform are Java 17 bytecode and FML 1.7.10's
    // ASM 5 scanner reads every entry of every jar. 1.3.5 is what the shipped manifests already say.
    implementation("xyz.wagyourtail.jvmdowngrader:gradle-plugin:1.3.5")
}
