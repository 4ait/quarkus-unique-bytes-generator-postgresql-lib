import org.jreleaser.model.Active

group = "ru.code4a"
version = file("version").readText().trim()
description =
  "PostgreSQL-backed unique bytes generator for Quarkus applications. " +
    "Generates 12-255 byte values by combining database-reserved 64-bit prefixes " +
    "with locally produced counters and format-preserving obfuscation."

plugins {
  val kotlinVersion = "2.3.10"

  kotlin("jvm") version kotlinVersion
  kotlin("plugin.serialization") version kotlinVersion
  kotlin("plugin.allopen") version kotlinVersion
  kotlin("plugin.noarg") version kotlinVersion

  id("org.kordamp.gradle.jandex") version "1.1.0"

  `java-library`
  `maven-publish`
  id("org.jreleaser") version "1.12.0"
}

java {
  withJavadocJar()
  withSourcesJar()
}

publishing {
  publications {
    create<MavenPublication>("mavenJava") {
      artifactId = "quarkus-unique-bytes-generator-postgresql"

      from(components["java"])

      pom {
        name = "Quarkus Unique Bytes Generator PostgreSQL Library"
        description = project.description.toString()
        url = "https://github.com/4ait/quarkus-unique-bytes-generator-postgresql-lib"
        inceptionYear = "2025"
        licenses {
          license {
            name = "The Apache License, Version 2.0"
            url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
          }
        }
        developers {
          developer {
            id = "tikara"
            name = "Evgeniy Simonenko"
            email = "tiikara93@gmail.com"
            organization.set("4A LLC")
            roles.set(
              listOf(
                "Software Developer",
                "Head of Development"
              )
            )
          }
        }
        organization {
          name = "4A LLC"
          url = "https://4ait.ru"
        }
        scm {
          connection = "scm:git:https://github.com/4ait/quarkus-unique-bytes-generator-postgresql-lib.git"
          developerConnection = "scm:git:git@github.com:4ait/quarkus-unique-bytes-generator-postgresql-lib.git"
          url = "https://github.com/4ait/quarkus-unique-bytes-generator-postgresql-lib"
        }
      }
    }
  }
  repositories {
    maven {
      url =
        layout.buildDirectory
          .dir("staging-deploy")
          .get()
          .asFile
          .toURI()
    }
  }
}

repositories {
  mavenCentral()
}

allOpen {
  annotation("jakarta.enterprise.context.ApplicationScoped")
}

tasks.withType<Test> {
  useJUnitPlatform()
  dependsOn(tasks["jandex"])
}

val quarkusVersion: String by project

dependencies {
  implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:${quarkusVersion}"))

  implementation("io.quarkus:quarkus-arc")
  implementation("io.quarkus:quarkus-hibernate-orm")
  implementation("io.quarkus:quarkus-virtual-threads")

  implementation("ru.code4a:quarkus-crypto:1.0.1")
  implementation("ru.code4a:quarkus-transactional-rw-lib:1.0.0")

  implementation("com.google.crypto.tink:tink:1.20.0")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.10.0")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.10.0")

  testImplementation(kotlin("test"))
  testImplementation("org.mockito:mockito-core:5.12.0")
}

tasks.named("compileTestKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask::class.java) {
  compilerOptions {
    freeCompilerArgs.add("-Xdebug")
  }
}

jreleaser {
  project {
    copyright.set("4A LLC")
  }
  gitRootSearch.set(true)
  signing {
    active.set(Active.ALWAYS)
    armored.set(true)
  }
  release {
    github {
      overwrite.set(true)
      branch.set("master")
    }
  }
  deploy {
    maven {
      mavenCentral {
        create("maven-central") {
          active.set(Active.ALWAYS)
          url.set("https://central.sonatype.com/api/v1/publisher")
          stagingRepositories.add("build/staging-deploy")
          retryDelay.set(30)
        }
      }
    }
  }
}

kotlin {
  compilerOptions {
    freeCompilerArgs.add("-Xemit-jvm-type-annotations")
  }
}

tasks.withType<GenerateModuleMetadata>().configureEach {
  // The value 'enforced-platform' is provided in the validation
  // error message you got
  suppressedValidationErrors.add("enforced-platform")
}
