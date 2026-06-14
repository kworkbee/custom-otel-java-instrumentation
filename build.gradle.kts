import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
  id("java")
  id("com.gradleup.shadow") version "9.4.2"
}

group = "com.tommy.instrumentation"
version = "1.0"

val versions = mapOf(
  "opentelemetrySdk" to "1.62.0",
  "opentelemetryJavaagent" to "2.28.1",
  "opentelemetryJavaagentAlpha" to "2.28.1-alpha"
)

repositories {
  mavenCentral()
  maven {
    name = "sonatype"
    url = uri("https://central.sonatype.com/repository/maven-snapshots/")
  }
}

dependencies {
  implementation(platform("io.opentelemetry:opentelemetry-bom:${versions["opentelemetrySdk"]}"))
  implementation(platform("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom:${versions["opentelemetryJavaagent"]}"))
  implementation(platform("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom-alpha:${versions["opentelemetryJavaagentAlpha"]}"))

  compileOnly("io.opentelemetry:opentelemetry-api")
  compileOnly("io.opentelemetry.instrumentation:opentelemetry-instrumentation-api")
  compileOnly("io.opentelemetry.instrumentation:opentelemetry-instrumentation-api-incubator")

  compileOnly("com.google.auto.service:auto-service:1.1.1")
  annotationProcessor("com.google.auto.service:auto-service:1.1.1")
}

tasks {
  compileJava {
    options.release.set(8)
  }

  assemble {
    dependsOn(named("shadowJar"))
  }
}
