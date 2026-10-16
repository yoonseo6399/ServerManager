plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}
group = "local.servermanager"
version = file("VERSION").readText().trim()
repositories {
    mavenCentral()
    maven(url = "https://maven.lavalink.dev/releases")
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("com.yubico:webauthn-server-core:2.10.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation(kotlin("reflect"))
    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("org.tomlj:tomlj:1.1.1")
    implementation("net.dv8tion:JDA:6.7.0") { exclude(module = "opus-java") }
    implementation("dev.arbjerg:lavaplayer:2.2.7")
    implementation("dev.lavalink.youtube:v2:1.18.2")
    implementation("moe.kyokobot.libdave:adapter-jda:0.1.2")
    implementation("moe.kyokobot.libdave:impl-jni:0.1.2")
    runtimeOnly("moe.kyokobot.libdave:natives-linux-x86-64:0.1.2")
    runtimeOnly("moe.kyokobot.libdave:natives-linux-aarch64:0.1.2")
    runtimeOnly("moe.kyokobot.libdave:natives-darwin:0.1.2")
    runtimeOnly("moe.kyokobot.libdave:natives-win-x86-64:0.1.2")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(kotlin("test"))
    testImplementation("org.springframework.security:spring-security-test")
}
tasks.test {
    dependsOn(tasks.bootJar) // Packaged-host/runner fixtures must use this source, including on a clean CI checkout.
    useJUnitPlatform()
    // Tests must never inherit the live profile, even when launched from its working directory.
    environment("SERVER_MANAGER_DATA", layout.buildDirectory.dir("test-profile").get().asFile.absolutePath)
    environment("SERVER_MANAGER_HOME", layout.buildDirectory.dir("test-desktop-home").get().asFile.absolutePath)
}

tasks.processResources { inputs.file("VERSION"); doLast { destinationDir.resolve("app-version.txt").writeText(project.version.toString()) } }

val sableBridgeBuild by tasks.registering(JavaExec::class) {
    inputs.files(fileTree("sable-bridge/src"),file("sable-bridge/build.gradle"),file("sable-bridge/settings.gradle"),file("sable-bridge/LICENSE"))
    outputs.files("sable-bridge/build/libs/servermanager-sable-bridge.jar","sable-bridge/build/libs/isolated-sable-probe.jar")
    mainClass.set("org.gradle.wrapper.GradleWrapperMain")
    classpath=files("gradle/wrapper/gradle-wrapper.jar")
    args("-p",file("sable-bridge").absolutePath,"jar","probeJar","--no-daemon")
}
tasks.processResources {
    dependsOn(sableBridgeBuild)
    from("sable-bridge/build/libs/servermanager-sable-bridge.jar") { into("sable") }
}
