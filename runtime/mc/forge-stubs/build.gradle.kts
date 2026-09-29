// Compile-only stand-ins for the Forge types a union bootstrapper names: the @Mod both eras scan for,
// and legacy FML's lifecycle events. Never shipped. @see runtime/mc/forge-bootstrap
plugins { `java-library` }

group = providers.gradleProperty("modGroup").orElse("com.crystalgraphics").get()
version = providers.gradleProperty("modVersion").orElse("1.0.0").get()
base { archivesName.set("crystalgraphics-forge-stubs") }

tasks.withType<JavaCompile>().configureEach { options.release.set(8) }
