plugins {
  kotlin("jvm")
  id("org.jetbrains.kotlinx.kover") version "0.9.8"
  id("com.commonsware.kovergate") version "0.2.0"
}

repositories {
  mavenCentral()
}

dependencies {
  testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.4")
}

tasks.test {
  useJUnitPlatform()
}
