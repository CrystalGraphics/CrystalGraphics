plugins { `java-library` }

group = "com.crystalgraphics"
version = rootProject.version.toString()

java {
    // Use Java 8 toolchain to match gtnhconvention's compilation environment.
    // Without this, Gradle uses the daemon JVM (JDK 25), on which Lombok 1.18.32 fails.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.projectlombok:lombok:1.18.32")
    annotationProcessor("org.projectlombok:lombok:1.18.32")
    implementation("org.apache.logging.log4j:log4j-api:2.20.0")
}

tasks.withType<JavaCompile> { options.encoding = "UTF-8" }
