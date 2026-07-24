plugins {
    `java-library`
}

group = "com.crystalgraphics"
version = rootProject.version.toString()

// Version shorthands from gradle.properties
val lombokVer     = rootProject.properties["dep.lombok"].toString()
val jomlVer       = rootProject.properties["dep.joml"].toString()
val lwjglVer      = rootProject.properties["dep.lwjgl"].toString()
val log4jVer      = rootProject.properties["dep.log4j"].toString()
val jabelVer      = rootProject.properties["dep.jabel"].toString()
val junitVer      = rootProject.properties["dep.junit"].toString()
val jdkVersion    = rootProject.properties["dep.jdk.toolchain"].toString().toInt()
val downgraderVer = rootProject.properties["dep.jvmdowngrader"]?.toString() ?: "0.9.0"

// 1. TOOLCHAIN SETUP
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    toolchain {
        // Jabel is stable on 17 and 21. It is not stable on 25.
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<Jar>().configureEach {
    archiveBaseName.set("graphicsCore")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
//// 2. CONFIGURATIONS & VARIANTS
//val jvmAttr = Attribute.of("org.gradle.jvm.version", Int::class.javaObjectType)
//
//// Isolated tool configurations (won't leak into compilation/runtime)
//val downgraderConfig by configurations.creating
//val jabelConfig by configurations.creating
//
//val java8Variant by configurations.creating {
//    isCanBeConsumed = true
//    isCanBeResolved = false
//    attributes {
//        attribute(jvmAttr, 8)
//        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
//    }
//}
//
//val java17Variant by configurations.creating {
//    isCanBeConsumed = true
//    isCanBeResolved = false
//    attributes {
//        attribute(jvmAttr, 17)
//        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
//    }
//}

// 3. REPOSITORIES
repositories {
    maven {
        name = "GTNH Maven"
        url = uri("https://nexus.gtnewhorizons.com/repository/public/")
    }
    maven {
        name = "WagYourTail Maven"
        url = uri("https://maven.wagyourtail.xyz/releases")
    }
    maven {
        name = "Minecraft Libraries"
        url = uri("https://libraries.minecraft.net/")
    }
    mavenCentral()
}

// 4. DEPENDENCIES
dependencies {

    // --- STANDARD DEPENDENCIES ---
    compileOnly("xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api:$downgraderVer:downgraded-8")

    compileOnly(project(":freetype-msdfgen-harfbuzz-bindings"))
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    compileOnly("de.javagl:obj:0.4.0")
    compileOnly("de.javagl:jgltf-model:2.0.4")
    compileOnly("commons-io:commons-io:2.4")
    compileOnly("org.hotswapagent:hotswap-agent-core:1.4.1")

    compileOnly("org.lwjgl.lwjgl:lwjgl:$lwjglVer")
    compileOnly("org.joml:joml-jdk8:$jomlVer")

    compileOnly("org.projectlombok:lombok:1.18.44")
    annotationProcessor("org.projectlombok:lombok:1.18.44")
    testCompileOnly("org.projectlombok:lombok:1.18.44")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.44")

    // Keep compileOnly so `import ...Desugar` doesn't crash the pure Java 17 compile
    // Notice: We NO LONGER have annotationProcessor(jabel) here.
    compileOnly("com.github.bsideup.jabel:jabel-javac-plugin:$jabelVer")

    compileOnly(project(":platform"))
    compileOnly("org.apache.logging.log4j:log4j-api:$log4jVer")

    testImplementation("junit:junit:$junitVer")
    testImplementation("org.lwjgl.lwjgl:lwjgl:$lwjglVer")
    testImplementation("org.joml:joml-jdk8:$jomlVer")
    testImplementation(project(":freetype-msdfgen-harfbuzz-bindings"))
    testImplementation(project(":platform"))
}

//// 5. PIPELINE A: PURE JAVA 17 COMPILATION
//val compileJava = tasks.named<JavaCompile>("compileJava") {
////    options.release.set(17)
//
//    // No Jabel flags here. Just pure Java 17 + Lombok.
//
//    val srcRoot: String = layout.projectDirectory.dir("src/main/java").asFile.absolutePath
//    doLast {
//        val violations = File(srcRoot).walkTopDown()
//            .filter { it.isFile && it.extension == "java" }
//            .filter { f ->
//                f.readLines().any { line ->
//                    val trimmed = line.trimStart()
//                    trimmed.startsWith("import ") && (
//                        trimmed.contains("net.minecraft") ||
//                            trimmed.contains("cpw.mods.fml") ||
//                            trimmed.contains("net.minecraftforge")
//                        )
//                }
//            }
//            .toList()
//        if (violations.isNotEmpty()) error("MC imports found in core/: ${violations.map { it.name }}")
//    }
//}
//
//// 6. PIPELINE B: JAVA 8 INTERMEDIATE (Jabel Active)
//val compileJabel by tasks.registering(JavaCompile::class) {
//    // FORCE THIS TASK TO USE JDK 17
//    javaCompiler.set(javaToolchains.compilerFor {
//        languageVersion.set(JavaLanguageVersion.of(17))
//    })
//
//    source = sourceSets.main.get().java
//    classpath = sourceSets.main.get().compileClasspath
//    destinationDirectory.set(layout.buildDirectory.dir("classes/java8/intermediate"))
//
//    // Target Java 8 (via Jabel)
//    options.release.set(8)
//
//    // Activate Jabel
//    options.compilerArgs.add("-Xplugin:jabel")
//
//    // Merge standard processors (Lombok) with Jabel
//    options.annotationProcessorPath = sourceSets.main.get().annotationProcessorPath.plus(jabelConfig)
//}

//// 7. PIPELINE C: JVMDOWNGRADER POST-PROCESSING
//val downgradeClasses by tasks.registering(Exec::class) {
//    // This task pulls from compileJabel, NOT compileJava
//    dependsOn(compileJabel)
//
//    val inputDir = compileJabel.get().destinationDirectory.get().asFile
//    val outputDir = layout.buildDirectory.dir("classes/java8/final").get().asFile
//
//    inputs.dir(inputDir)
//    outputs.dir(outputDir)
//    inputs.files(downgraderConfig)
//    inputs.files(sourceSets.main.get().compileClasspath)
//
//    doFirst {
//        val javaExe = javaToolchains.launcherFor {
//            languageVersion.set(JavaLanguageVersion.of(jdkVersion))
//        }.get().executablePath.asFile.absolutePath
//
//        val cliJar = downgraderConfig.files
//            .find { it.name.contains("jvmdowngrader") && it.name.endsWith(".jar") }
//            ?.absolutePath
//            ?: throw GradleException("Could not find the jvmdowngrader jar in downgraderConfig")
//
//        val cpPath = sourceSets.main.get().compileClasspath.asPath
//
//        // Execute WagYourTail Downgrader
//        commandLine(
//            javaExe,
//            "-jar", cliJar,
//            "-c", "52", // Target Class Version 52 (Java 8)
//            "downgrade",
//            "-t", inputDir.absolutePath,
//            outputDir.absolutePath,
//            "-cp", cpPath
//        )
//    }
//}
//
//// 7. UPDATED TASK
//val java8Jar by tasks.registering(Jar::class) {
//    archiveClassifier.set("java8")
//
//    // Include the downgraded classes
//    from(layout.buildDirectory.dir("classes/java8/final"))
//
//    // FIX: Reference the task directly.
//    // Passing 'tasks.processResources' tells Gradle: "I need the output of this task."
//    // This creates the implicit dependency and stops the validation error.
//    from(tasks.processResources)
//
//    // Ensure this runs after the downgrader
//    dependsOn(downgradeClasses)
//}
//
//// 8. CLEAN ARTIFACT MAPPING
//artifacts {
//    // Java 8 Consumers get the JAR
//    add(java8Variant.name, java8Jar) {
//        builtBy(java8Jar)
//    }
//
//    // Java 17 Consumers get the raw, untouched Java 17 compile
//    add(java17Variant.name, compileJava.map { it.destinationDirectory.get() }) {
//        builtBy(compileJava)
//    }
//}
