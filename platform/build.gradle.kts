import cgbuildlogic.abstractModule
import cgbuildlogic.publishedModule

plugins { `java-library` }

group = "com.crystalgraphics"
version = rootProject.version.toString()

// Version shorthands from gradle.properties
val lombokVer     = rootProject.properties["dep.lombok"].toString()
val log4jVer      = rootProject.properties["dep.log4j"].toString()
val jabelVer      = rootProject.properties["dep.jabel"].toString()
val downgraderVer = rootProject.properties["dep.jvmdowngrader"]?.toString() ?: "0.9.0"

// An abstract module: Java 25, with a Java 8 copy for every consumer below it. @see cgbuildlogic.abstractModule
abstractModule("com/crystalgraphics/jvmdg/platform")
publishedModule("CrystalGraphics Platform", "The SPI a loader implements for CrystalGraphics: GL dispatch, input, lifecycle.")

repositories {
    maven {
        name = "WagYourTail Maven"
        url = uri("https://maven.wagyourtail.xyz/releases")
    }
    mavenCentral()
}

dependencies {
    compileOnly("xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api:$downgraderVer:downgraded-8")

    // The state manager's deduplication decisions are pure logic — no GL context, no backend — so they are
    // unit-testable here. Whether CgGL calls them from the right places still needs a live context.
    testImplementation("junit:junit:${rootProject.properties["dep.junit"]}")

    compileOnly("org.projectlombok:lombok:1.18.44")
    annotationProcessor("org.projectlombok:lombok:1.18.44")
    testCompileOnly("org.projectlombok:lombok:1.18.44")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.44")

    // compileOnly so an `import ...Desugar` compiles under the plain pass, exactly as in
    // :core. No annotationProcessor(jabel) here either — the dual pipeline is not wired up there.
    compileOnly("com.github.bsideup.jabel:jabel-javac-plugin:$jabelVer")

    // compileOnly, like :core. dep.log4j is 2.0-beta9 (Minecraft 1.7.10's), so exporting it at runtime
    // puts a 1.7.10 version on every consumer -- and NeoForge requires log4j-api {strictly 2.19.0},
    // which fails its whole runtime graph. Every host supplies its own log4j; the harness declares
    // 2.26.1 explicitly.
    compileOnly("org.apache.logging.log4j:log4j-api:$log4jVer")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.jar {
    manifest {
        attributes(
            "Manifest-Version" to "1.0",
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version
        )
    }

    // Native libraries in src/main/resources/natives/ are automatically included
    // by the standard processResources task — no explicit from() needed.
}

tasks.named<Test>("test") {
    useJUnit()
}

// ── Sources, for CrystalGUI's Quick Documentation popup (CrystalGUI M13 §25.4) ───────────────────
//
// Hovering `CgMaterial` in the in-game script editor quotes the declaration its author wrote, with its
// javadoc, instead of a form reassembled from the binding. `SourceArchives.ResourceArchive` reads them
// straight off the classloader -- one `getResourceAsStream`, because the JVM already indexed this jar's
// central directory when it opened it.
//
// OUR OWN NAMESPACE, not CrystalGUI's. This library is used by mods that have no CrystalGUI in the pack
// at all, and a jar shipping an `assets/crystalgui/` directory to such a game is claiming a namespace it
// does not own. CrystalGUI DISCOVERS these by scanning the classpath -- nothing registers a namespace,
// so any mod can do this -- and the paths beneath are package paths, so two of them cannot collide.
//
// Loose entries rather than a nested zip: `ZipFile` cannot open an archive inside another and
// `ZipInputStream` is sequential, so a nested one would mean decompressing entries until the wanted file
// turned up, on every hover.
tasks.jar {
    from(sourceSets.main.get().allJava) { into("assets/crystalgraphics/sources") }
}
