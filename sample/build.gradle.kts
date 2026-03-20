plugins {
  kotlin("jvm")
  id("org.jetbrains.kotlinx.kover") version "0.9.1"
  id("com.commonsware.kovergate") version "0.1.0"
}

repositories {
  mavenCentral()
}

dependencies {
  testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
  useJUnitPlatform()
}
