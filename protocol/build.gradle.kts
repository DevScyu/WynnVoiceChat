plugins {
    `java-library`
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

val nettyVersion: String by rootProject.extra { property("netty_version") as String }

dependencies {
    compileOnly("io.netty:netty-buffer:$nettyVersion")
    compileOnly("io.netty:netty-codec:$nettyVersion")
    testImplementation("io.netty:netty-codec:$nettyVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
