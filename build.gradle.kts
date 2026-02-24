plugins {
    id("com.gtnewhorizons.gtnhconvention")
    `java-library` // Use java-library for reusable code
}

group = providers.gradleProperty("modGroup").orElse("io.github.somehussar").get()
version = providers.gradleProperty("modVersion").orElse("1.0.0").get()

apply(from = "repositories.gradle")
apply(from = "dependencies.gradle")

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
}
