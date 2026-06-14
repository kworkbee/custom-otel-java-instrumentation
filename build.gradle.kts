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

  // Package OTel SDK and OTLP exporter into our standalone agent JAR
  implementation("io.opentelemetry:opentelemetry-api")
  implementation("io.opentelemetry:opentelemetry-sdk")
  implementation("io.opentelemetry:opentelemetry-sdk-metrics")
  implementation("io.opentelemetry:opentelemetry-exporter-otlp")
  implementation("io.opentelemetry:opentelemetry-exporter-sender-okhttp")

  // Include ByteBuddy inside the standalone agent
  implementation("net.bytebuddy:byte-buddy:1.15.11")

  // Include OTel Micrometer bridge tool
  implementation("io.opentelemetry.instrumentation:opentelemetry-micrometer-1.5:${versions["opentelemetryJavaagentAlpha"]}")

  // CompileOnly APIs for instrumentation and auto-service
  compileOnly("javax.servlet:javax.servlet-api:4.0.1")
  compileOnly("jakarta.servlet:jakarta.servlet-api:6.0.0")
  compileOnly("io.micrometer:micrometer-core:1.12.0")

  compileOnly("com.google.auto.service:auto-service:1.1.1")
  annotationProcessor("com.google.auto.service:auto-service:1.1.1")
}

tasks {
  compileJava {
    options.release.set(8)
  }

  shadowJar {
    archiveClassifier.set("all")
    manifest {
      attributes(
        "Premain-Class" to "com.tommy.instrumentation.DemoAgent",
        "Can-Redefine-Classes" to "true",
        "Can-Retransform-Classes" to "true"
      )
    }

    mergeServiceFiles()

    // Prevent conflicts with target app's internal OTel or ByteBuddy libraries
    relocate("net.bytebuddy", "com.tommy.instrumentation.shaded.bytebuddy")
    relocate("io.opentelemetry", "com.tommy.instrumentation.shaded.opentelemetry")
    relocate("okhttp3", "com.tommy.instrumentation.shaded.okhttp3")
    relocate("okio", "com.tommy.instrumentation.shaded.okio")
  }

  assemble {
    dependsOn(shadowJar)
  }
}
