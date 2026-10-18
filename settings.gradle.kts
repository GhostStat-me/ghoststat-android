// Only needed to open GhostStatSDK-Android on its own (to run its tests). In your app, include the
// `ghoststat` folder as a module instead — see README.md.
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "GhostStatSDK-Android"
include(":ghoststat")
