rootProject.name = "build-logic"

// The single-jar build, shared with every consumer rather than copied into each — see
// ../../singlejar-logic/README.md.
includeBuild("../../singlejar-logic")
