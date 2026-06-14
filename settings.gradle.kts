pluginManagement {
  repositories {
    gradlePluginPortal()
    maven {
      name = "sonatype"
      url = uri("https://central.sonatype.com/repository/maven-snapshots/")
    }
  }
}

rootProject.name = "custom-otel-java-instrumentation"
