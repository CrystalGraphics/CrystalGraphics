plugins {
    id("com.gtnewhorizons.gtnhconvention")
    `java-library` // Use java-library for reusable code
}

group = providers.gradleProperty("modGroup").orElse("io.github.somehussar").get()
version = providers.gradleProperty("modVersion").orElse("1.0.0").get()

apply(from = "repositories.gradle")
apply(from = "dependencies.gradle")

// Remove Kotlin and a Java 9 file from JOML when shadowing.
// Gradle pulls in a transitive dependency (Kotlin) and packages it for some reason.
// This is for distributing the jar.
// JOML is compiled for Java 8, so the fact I have to do this is stupid asf.
// - Hussar
// Sidenote, using the `-jdk8` published version, it might no longer try to pull that shit anymore but keeping it still
tasks.shadowJar {
    dependencies {
        exclude(dependency("org.jetbrains.kotlin:.*"))
    }

    exclude("module-info.class")
    exclude("kotlin/**")
    exclude("org/jetbrains/kotlin/**")
}

//java {
//    sourceCompatibility = JavaVersion.VERSION_1_8
//    targetCompatibility = JavaVersion.VERSION_1_8
//}
//
//tasks.withType<JavaCompile>().configureEach {
//    if (JavaVersion.current().isJava9Compatible) {
//        options.release.set(8)
//    } else {
//        sourceCompatibility = JavaVersion.VERSION_1_8.toString()
//        targetCompatibility = JavaVersion.VERSION_1_8.toString()
//    }
//}


fun findJarBySubstring(part: String): File {
    val matches = configurations.runtimeClasspath.get().files.filter {
        it.name.contains(part)
    }

    if (matches.isEmpty()) {
        throw GradleException("Cannot find JAR containing '$part' in runtimeClasspath")
    }

    return matches.first()
}

tasks.named<JavaExec>("runClient") {
    val agent = findJarBySubstring("unimixins")
    jvmArgs("-javaagent:${agent.absolutePath}")

    val hotswapAgent = findJarBySubstring("hotswap-agent")
    jvmArgs("-javaagent:${hotswapAgent.absolutePath}")
}
