plugins {
  `java-gradle-plugin`
  `kotlin-dsl`
  kotlin("plugin.serialization")
  alias(libs.plugins.ktfmt.gradle)
  `maven-publish`
}

group = "com.commonsware"

version = "0.1.0"

kotlin { jvmToolchain(17) }

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
  kotlinOptions {
    languageVersion = "1.9"
    apiVersion = "1.9"
  }
}

repositories { mavenCentral() }

ktfmt {
  googleStyle()
  maxWidth.set(120)
}

// Functional test source set
val functionalTestSourceSet =
    sourceSets.create("functionalTest") {
      compileClasspath += sourceSets.main.get().output
      runtimeClasspath += output + sourceSets.main.get().output
    }

configurations["functionalTestImplementation"].extendsFrom(configurations["testImplementation"])

configurations["functionalTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

dependencies {
  add("functionalTestImplementation", libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.serialization.json)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlinx.serialization.json)
  testImplementation("org.jetbrains.kotlinx:kover-gradle-plugin:0.9.1")
  testRuntimeOnly(libs.junit.platform.launcher)
}

val functionalTest by
    tasks.registering(Test::class) {
      testClassesDirs = functionalTestSourceSet.output.classesDirs
      classpath = functionalTestSourceSet.runtimeClasspath
      useJUnitPlatform()
    }

tasks.check { dependsOn(functionalTest) }

gradlePlugin {
  plugins {
    create("koverGate") {
      id = "com.commonsware.kovergate"
      implementationClass = "com.commonsware.kovergate.CoverageGatePlugin"
    }
  }
  // Registers functionalTest as a plugin test source set:
  // - injects gradleTestKit() dependency
  // - generates plugin-under-test-metadata.properties for withPluginClasspath()
  testSourceSets(functionalTestSourceSet)
}

tasks.withType<Test> { useJUnitPlatform() }
