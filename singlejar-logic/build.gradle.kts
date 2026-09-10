plugins { `kotlin-dsl` }

// The coordinates a consumer substitutes against. A composite build matches an included build to a
// dependency by group and name, so these two lines are the whole contract: any project that says
//
//     pluginManagement { includeBuild("<path>/CrystalGraphics/singlejar-logic") }
//     dependencies { implementation("com.crystalgraphics.build:singlejar-logic") }
//
// gets these classes, wherever CrystalGraphics sits relative to it.
group = "com.crystalgraphics.build"
version = "1.0.0"

repositories {
    gradlePluginPortal()
}

dependencies {
    // Shadow and jvmDowngrader are the merge's own tools, and the shared tasks name their types.
    implementation("com.gradleup.shadow:shadow-gradle-plugin:9.2.2")
    implementation("xyz.wagyourtail.jvmdowngrader:gradle-plugin:1.3.5")
}
