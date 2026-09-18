plugins {
    kotlin("jvm") version "2.3.0"
    application
}

kotlin {
    jvmToolchain(21)
}

val nettyVersion: String by rootProject.extra { property("netty_version") as String }
val exposedVersion = "1.5.0"

dependencies {
    implementation(project(":protocol"))
    implementation("io.netty:netty-codec:$nettyVersion")
    implementation("io.netty:netty-transport:$nettyVersion")
    implementation("io.netty:netty-handler:$nettyVersion")
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("io.micrometer:micrometer-registry-prometheus:1.17.1")
    implementation("org.slf4j:slf4j-api:2.0.17")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "wynnvoice.server.MainKt"
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest.attributes("Implementation-Version" to project.version)
}
