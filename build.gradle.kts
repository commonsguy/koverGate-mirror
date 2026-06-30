import org.gradle.plugin.compatibility.compatibility
import org.jetbrains.dokka.gradle.tasks.DokkaGeneratePublicationTask
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode

plugins {
  alias(libs.plugins.detekt)
  alias(libs.plugins.detektifier)
  alias(libs.plugins.dokka)
  alias(libs.plugins.dokka.javadoc)
  `kotlin-dsl`
  kotlin("plugin.serialization")
  alias(libs.plugins.ktfmt.gradle)
  alias(libs.plugins.plugin.publish)
  alias(libs.plugins.sort.dependencies)
}

group = "com.commonsware.kovergate"

version = libs.versions.kovergate.get()

kotlin {
  jvmToolchain(17)

  explicitApi = ExplicitApiMode.Strict
}

java {
  withJavadocJar()
  withSourcesJar()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
  compilerOptions {
    languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
    apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
  }
}

repositories { mavenCentral() }

detekt { source.setFrom("src/functionalTest/kotlin", "src/main/kotlin", "src/test/kotlin") }

ktfmt {
  googleStyle()
  maxWidth.set(120)
}

sortDependencies {
  insertBlankLines = true
  check(true)
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
  testImplementation(libs.kotlinx.kover.gradle.plugin)
  testImplementation(libs.kotlinx.serialization.json)

  testRuntimeOnly(libs.junit.platform.launcher)
}

val functionalTest by
  tasks.registering(Test::class) {
    testClassesDirs = functionalTestSourceSet.output.classesDirs
    classpath = functionalTestSourceSet.runtimeClasspath
    useJUnitPlatform()
  }

tasks.check { dependsOn(functionalTest) }

tasks.check { dependsOn("detekt") }

tasks.named<Jar>("javadocJar") {
  val dokkaJavadoc = tasks.named<DokkaGeneratePublicationTask>("dokkaGeneratePublicationJavadoc")

  dependsOn(dokkaJavadoc)
  from(dokkaJavadoc.flatMap { it.outputDirectory })
}

gradlePlugin {
  website.set("https://codeberg.org/commonsguy/koverGate")
  vcsUrl.set("https://codeberg.org/commonsguy/koverGate")

  plugins {
    create("koverGate") {
      id = "com.commonsware.kovergate"
      implementationClass = "com.commonsware.kovergate.CoverageGatePlugin"
      displayName = "KoverGate Plugin"
      description = "A Gradle plugin that gates builds on Kover coverage thresholds and generates gap reports"
      tags.set(listOf("coverage", "kover", "quality", "testing"))

      compatibility { features { configurationCache = true } }
    }
  }
  // Registers functionalTest as a plugin test source set:
  // - injects gradleTestKit() dependency
  // - generates plugin-under-test-metadata.properties for withPluginClasspath()
  testSourceSets(functionalTestSourceSet)
}

publishing {
  publications.withType<MavenPublication> {
    pom {
      name.set("KoverGate Gradle Plugin")
      description.set("A Gradle plugin that gates builds on Kover coverage thresholds and generates gap reports.")
      url.set("https://codeberg.org/commonsguy/koverGate")

      licenses {
        license {
          name.set("The Apache Software License, Version 2.0")
          url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
        }
      }

      developers {
        developer {
          id.set("commonsguy")
          name.set("Mark Murphy")
          email.set("mmurphy@commonsware.com")
        }
      }

      scm { url.set("https://codeberg.org/commonsguy/koverGate") }
    }
  }
}

tasks.withType<Test> { useJUnitPlatform() }
