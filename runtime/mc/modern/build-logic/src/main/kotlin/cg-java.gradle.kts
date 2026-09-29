import cgbuildlogic.nodeJava

// The Java a node builds for is its MINECRAFT's: 17 up to 1.20.4, 21 from 1.20.5, where Minecraft's own
// classes -- and NeoForm's published variants -- are Java 21, so a 17 build cannot even read them.
// @see cgbuildlogic.nodeJava
plugins { `java-library` }

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(nodeJava)) }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(nodeJava)
}
