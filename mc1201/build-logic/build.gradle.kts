plugins { `kotlin-dsl` }

repositories {
    gradlePluginPortal()
}

// No external classpath dependencies needed here.
// cg-mc1201-common.gradle.kts and cg-mc1201-loader.gradle.kts apply only
// cg-java17, java-library, and maven-publish — no loader DSL type access required.
