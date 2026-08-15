import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import java.util.zip.ZipFile

plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.112-stable")

    implementation("org.yaml:snakeyaml:2.2")

    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.xerial:sqlite-jdbc:3.53.2.1")
    implementation("com.mysql:mysql-connector-j:26.7.0")
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("org.flywaydb:flyway-core:13.3.0")
    implementation("org.flywaydb:flyway-mysql:13.3.0")
    implementation("org.flywaydb:flyway-database-postgresql:13.3.0")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("net.kyori:adventure-text-minimessage:5.2.0")
    testImplementation("net.kyori:adventure-text-serializer-plain:5.2.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mysql")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks {
    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("26.2")
        jvmArgs("-Xms2G", "-Xmx2G")
        standardInput = System.`in`
    }

    processResources {
        val props = mapOf("version" to project.version.toString())
        inputs.properties(props)
        filteringCharset = "UTF-8"

        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    test {
        useJUnitPlatform()
    }

    jar {
        enabled = false
    }

    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()

        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        filesMatching("META-INF/services/**") {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }

        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")

        relocate("org.yaml.snakeyaml", "net.epicpunishments.lib.snakeyaml")
    }

    val verifyReleaseArtifact by registering {
        group = "verification"
        description = "Verifies that the distributable plugin JAR contains its runtime dependencies and resources."
        dependsOn(shadowJar)
        val releaseJar = shadowJar.flatMap { it.archiveFile }
        inputs.file(releaseJar)

        doLast {
            val requiredEntries = setOf(
                "plugin.yml",
                "config.yml",
                "messages.yml",
                "net/epicpunishments/bootstrap/EpicPunishments.class",
                "db/migration/sqlite/V1__initial_schema.sql",
                "com/zaxxer/hikari/HikariDataSource.class",
                "org/sqlite/JDBC.class",
                "com/mysql/cj/jdbc/Driver.class",
                "org/postgresql/Driver.class",
                "org/flywaydb/core/Flyway.class",
                "net/epicpunishments/lib/snakeyaml/Yaml.class"
            )
            ZipFile(releaseJar.get().asFile).use { archive ->
                val entries = archive.entries().asSequence().map { it.name }.toSet()
                val missing = requiredEntries - entries
                check(missing.isEmpty()) {
                    "Release JAR is missing required entries: ${missing.sorted().joinToString()}"
                }
                check("org/yaml/snakeyaml/Yaml.class" !in entries) {
                    "Release JAR contains the unrelocated SnakeYAML implementation"
                }
            }
        }
    }

    assemble {
        dependsOn(shadowJar)
    }

    check {
        dependsOn(verifyReleaseArtifact)
    }

    withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}
