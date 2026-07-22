import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    `maven-publish`
    signing
    // Build-time static null analysis. See the `errorprone`/`nullaway` dependency
    // block below for why this adds no runtime dependency.
    id("net.ltgt.errorprone") version "5.1.0"
}

group = "dev.getchat"
version = "1.0.0"

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

    // Error Prone + NullAway (JSpecify mode) turn the SDK's @NullMarked/@Nullable
    // annotations from unchecked documentation into a compile-time guarantee. The
    // errorprone Gradle plugin adds the required JDK compiler `--add-exports`/
    // `--add-opens` when running on JDK 16+, so the `options.release = 17`
    // arrangement keeps working without a Gradle toolchain.
    options.errorprone {
        // NullAway is the only check we hold the build to. It runs as an ERROR so a
        // broken null contract fails the build; every other Error Prone check stays
        // at its default severity (warnings do not fail — there is no -Werror).
        error("NullAway")
        // AnnotatedPackages covers dev.getchat.sdk AND dev.getchat.sdk.internal, so
        // both packages (already @NullMarked via package-info) are analysed.
        option("NullAway:AnnotatedPackages", "dev.getchat.sdk")
        // JSpecify mode: honour @NullMarked / org.jspecify.annotations.Nullable,
        // which is exactly how the SDK already annotates its nullability contract.
        option("NullAway:JSpecifyMode", "true")

        // Two non-nullability Error Prone style checks are turned off because they
        // conflict with deliberate, pre-existing conventions rather than flag bugs
        // (and this task is scoped to null analysis, not a style migration):
        //  - BooleanLiteral flags the boxed `Boolean.FALSE` constants the signing
        //    layer passes as Object-typed whitelist defaults; a primitive `false`
        //    would just autobox straight back, so the boxed form is intentional.
        //  - MissingSummary is stricter than this project's javadoc policy, which
        //    runs doclint with `-missing` on purpose (see the Javadoc task).
        disable("BooleanLiteral")
        disable("MissingSummary")
    }
}

tasks.withType<Javadoc>().configureEach {
    // The internal package is not part of the supported API — `module-info` does not
    // export it — so keep its classes out of the generated docs, where they would
    // otherwise render as if they were API (they are `public` only so the two public
    // entry points can reach them across the package boundary). Run javadoc on the
    // classpath and drop the internal sources; the compiled internal classes go on
    // the doc classpath so the public classes that import them still resolve.
    modularity.inferModulePath.set(false)
    exclude("dev/getchat/sdk/internal/**", "module-info.java")
    classpath += files(sourceSets["main"].output)
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        // Builder setters do not earn an @param/@return each; keep the rest of doclint on.
        addStringOption("Xdoclint:all,-missing", "-quiet")
    }
}

tasks.jar {
    manifest {
        attributes(
            // No Automatic-Module-Name: the jar ships a real `module-info` (module
            // `dev.getchat.sdk`, exporting only the public package), which is the
            // authoritative module descriptor and supersedes the manifest fallback.
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

    // Error Prone + NullAway are BUILD-TIME ONLY. They sit on the `errorprone`
    // configuration (fed to javac's annotation-processor path by the plugin), never
    // on `api`/`implementation`, so they add ZERO runtime dependencies and do not
    // appear in the published POM — the "one runtime dependency (Jackson)" policy is
    // untouched.
    // error_prone_core is pinned to 2.42.0 — the newest release still compiled to
    // Java 17 bytecode, so Error Prone runs on any JDK >= 17 (2.43.0+ ships Java 21
    // bytecode and would refuse to load on the JDK 17-20 this project supports).
    // NullAway 0.13.8 is the current release and runs on JDK 17+ (Java 17 bytecode).
    errorprone("com.google.errorprone:error_prone_core:2.42.0")
    errorprone("com.uber.nullaway:nullaway:0.13.8")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The test sources are same-package whitebox tests (they live in `dev.getchat.sdk`
// and `dev.getchat.sdk.internal` and reach package-private seams) and carry no
// test module-info. Compile and run them on the classpath so the main module's
// `exports`/encapsulation is not enforced against them — otherwise the tests that
// touch the non-exported `internal` package would not compile as a module.
tasks.compileTestJava {
    modularity.inferModulePath.set(false)

    // Error Prone / NullAway are disabled for the test sources. The tests are the
    // wire-contract spec and deliberately violate the null contract to assert it —
    // e.g. passing `null` where a required reference is mandatory to prove the NPE
    // is thrown (`sendTyping(..., null)`, constructor null-checks). NullAway would
    // (correctly) reject those call sites, so it is turned off here rather than by
    // weakening a single test. Null enforcement is a guarantee about the shipped
    // main sources; the tests only exercise it.
    options.errorprone {
        enabled.set(false)
    }
}

tasks.test {
    modularity.inferModulePath.set(false)
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
