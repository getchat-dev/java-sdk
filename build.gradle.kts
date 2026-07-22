plugins {
    `java-library`
    `maven-publish`
    signing
}

group = "dev.getchat"
version = "0.3.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    withSourcesJar()
    withJavadocJar()
}

// `release` rather than a toolchain so the build works on any JDK >= 17 the
// developer already has, without Gradle needing to provision an exact JDK.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        // Builder setters do not earn an @param/@return each; keep the rest of doclint on.
        addStringOption("Xdoclint:all,-missing", "-quiet")
    }
}

tasks.jar {
    manifest {
        attributes(
            // A stable automatic module name pins the SDK's JPMS identity even
            // though it ships as a plain classpath jar with no module-info: some
            // `internal/` classes are public out of necessity, so without this the
            // module system would derive an unstable name from the jar filename.
            // Fixing it at `dev.getchat.sdk` locks the JPMS boundary in place.
            "Automatic-Module-Name" to "dev.getchat.sdk",
            "Implementation-Title" to "getchat-java-sdk",
            "Implementation-Version" to project.version,
        )
    }
}

// Byte-for-byte reproducible archives: drop embedded file timestamps and sort
// entries, so the same source always yields the same jar — needed for build
// caching and independent artifact verification (Maven Central reproducibility).
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

dependencies {
    // Jackson is the one runtime dependency: Java has no built-in JSON. It is
    // declared with `implementation`, not `api`, because JSON responses surface as
    // the SDK's own JsonValue wrapper, so Jackson is not part of the public
    // contract and does not leak onto consumers' compile classpath.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    // JSpecify: an annotation-only artifact (@NullMarked / @Nullable) that
    // documents the SDK's nullability contract. `api`, not `implementation`,
    // because the annotations sit on public signatures and consumers want them
    // on their compile classpath for IDE and static null analysis. It ships no
    // executable code and adds no runtime behaviour, so the "one runtime
    // dependency (Jackson)" policy holds in spirit — this is a contract, not a
    // code library.
    api("org.jspecify:jspecify:1.0.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "getchat-java-sdk"
            // Maven Central requires name, description, url, licenses, developers
            // and scm — a release is rejected without them.
            pom {
                name.set("GetChat Java SDK")
                description.set("Server-side Java SDK for GetChat: signed embed URLs and REST API client.")
                url.set("https://getchat.dev")
                inceptionYear.set("2026")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }

                developers {
                    developer {
                        id.set("getchat")
                        name.set("GetChat")
                        url.set("https://getchat.dev")
                    }
                }

                scm {
                    url.set("https://github.com/getchat-dev/java-sdk")
                    connection.set("scm:git:https://github.com/getchat-dev/java-sdk.git")
                    developerConnection.set("scm:git:ssh://git@github.com/getchat-dev/java-sdk.git")
                }

                issueManagement {
                    system.set("GitHub")
                    url.set("https://github.com/getchat-dev/java-sdk/issues")
                }
            }
        }
    }
}

signing {
    // Sign the Maven publication, but only when signing keys are actually present.
    // Why conditional: Maven Central rejects unsigned releases, yet almost every
    // build has no keys — a fresh clone running `./gradlew build`, the test suite
    // in CI, `publishToMavenLocal` for a consuming app — and all of those must
    // stay green. So we read an in-memory ASCII-armored key + passphrase from
    // gradle properties or the environment (`signingKey`/`signingPassword`, or
    // `SIGNING_KEY`/`SIGNING_PASSWORD`); when they are present we require signing
    // and wire the key in, and when they are absent signing is not required and
    // the `sign*` tasks skip themselves instead of failing the build.
    val signingKey = (findProperty("signingKey") as String?) ?: System.getenv("SIGNING_KEY")
    val signingPassword = (findProperty("signingPassword") as String?) ?: System.getenv("SIGNING_PASSWORD")
    isRequired = signingKey != null
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
    sign(publishing.publications["maven"])
}
