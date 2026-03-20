pluginManagement {
  repositories {
    mavenLocal()
    gradlePluginPortal()
    mavenCentral()
  }
  resolutionStrategy {
    eachPlugin {
      if (requested.id.id.startsWith("org.jetbrains.kotlin.")) {
        useVersion(embeddedKotlinVersion)
      }
    }
  }
}

rootProject.name = "kovergate"

include(":sample")
