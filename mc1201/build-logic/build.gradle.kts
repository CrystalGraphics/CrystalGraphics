plugins { `kotlin-dsl` }

repositories {
    gradlePluginPortal()
    maven("https://maven.neoforged.net/releases") // ModDevGradle — needed for neoFormRuntime {} in cg-mc1201-common
}

dependencies {
    // ModDevGradle NeoForm mode: provides MC classes as compileOnly without the NeoForge modloader.
    // Used by cg-mc1201-common.gradle.kts to put MC 1.20.1 on the compileOnly classpath of :mc1201:common.
    implementation("net.neoforged:moddev-gradle:2.0.141")

    // Shadow, so ShadowJar is a type these scripts can name. The version matches the pin in
    // settings.gradle.kts that every loader applies.
    implementation("com.gradleup.shadow:shadow-gradle-plugin:9.2.2")
}
