plugins { `java-library` }

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}
