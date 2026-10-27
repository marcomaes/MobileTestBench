import com.google.protobuf.gradle.id
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
    id("com.google.protobuf") version "0.10.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

val grpcVersion = providers.gradleProperty("grpcVersion").get()
val protobufVersion = providers.gradleProperty("protobufVersion").get()

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Stops Kotlin from generating bridge methods for the Java default methods of
        // ToolWindowFactory (isApplicable, manage, ...). Without it the Plugin Verifier
        // reports experimental, deprecated and scheduled-for-removal API use that our
        // source never calls.
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
}

// Change notes for the Marketplace: the "## 0.1.0" section of CHANGELOG.md as an HTML list.
fun changeNotesFromChangelog(): String {
    val lines = file("CHANGELOG.md").readLines()
    val start = lines.indexOfFirst { it.trim() == "## $version" }
    if (start < 0) {
        throw GradleException("CHANGELOG.md has no '## $version' section. Add one before building the plugin.")
    }
    val body = lines.drop(start + 1).takeWhile { !it.startsWith("## ") }
    fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val items = body.map { it.trim() }.filter { it.startsWith("- ") }
        .joinToString("") { "<li>" + esc(it.removePrefix("- ")) + "</li>" }
    if (items.isEmpty()) {
        throw GradleException("The '## $version' section of CHANGELOG.md has no '- ' bullets.")
    }
    return "<ul>$items</ul>"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // gRPC client for the Android emulator's EmulatorController service.
    // OkHttp transport keeps the plugin small compared to Netty.
    implementation("io.grpc:grpc-okhttp:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    // Needed for the @Generated annotation in grpc-java stubs.
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")

    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion").get())
        testFramework(TestFrameworkType.Platform)
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
            }
        }
    }
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
        changeNotes = provider { changeNotesFromChangelog() }
    }

    // Plugin Verifier: ./gradlew verifyPlugin (downloads the recommended IDEs, takes minutes).
    pluginVerification {
        ides {
            recommended()
        }
    }

    // Signing and publishing read environment variables only. Nothing is stored in files.
    // Without them signPlugin is skipped and publishPlugin fails (nothing is uploaded).
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks {
    test {
        useJUnit()
    }

    // The license and the third-party notices (BSD and MIT texts) travel with the plugin: MobileTestBench/LICENSE and
    // MobileTestBench/NOTICE next to lib/ in the distribution zip, and inside the plugin jar under META-INF.
    prepareSandbox {
        from(layout.projectDirectory.files("LICENSE", "NOTICE")) {
            into(pluginName)
        }
    }
    jar {
        from(layout.projectDirectory.files("LICENSE", "NOTICE")) {
            into("META-INF")
        }
    }
}
