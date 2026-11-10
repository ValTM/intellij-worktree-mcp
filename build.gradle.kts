plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "local.worktree"
version = "1.0.2"

val webstormPath = providers.gradleProperty("webstormPath")
    .orElse("${System.getProperty("user.home")}/Applications/WebStorm.app")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local(webstormPath)
        bundledPlugin("com.intellij.mcpServer")
        bundledPlugin("Git4Idea")
        bundledModule("intellij.platform.vcs")
        pluginVerifier()
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    testImplementation(kotlin("test-junit"))
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

intellijPlatform {
    buildSearchableOptions = false
    sandboxContainer = layout.buildDirectory.dir("sandbox")
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262.10968.77"
            untilBuild = "263.*"
        }
    }
    pluginVerification {
        ides {
            local(file(webstormPath.get()))
        }
    }
}

tasks.named("verifyPluginSignature") {
    dependsOn("signPlugin")
}

tasks.jar {
    from("LICENSE") {
        into("META-INF")
    }
}

tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>().configureEach {
    doLast {
        val settings = sandboxConfigDirectory.file("options/mcpServer.xml").get().asFile
        if (!settings.exists()) {
            settings.parentFile.mkdirs()
            settings.writeText(
                """<application>
  <component name="McpServerSettings">
    <option name="enableMcpServer" value="true" />
    <option name="mcpServerPort" value="16432" />
  </component>
</application>
"""
            )
        }
    }
}
