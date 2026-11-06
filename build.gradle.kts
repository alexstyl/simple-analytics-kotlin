plugins {
    id("com.composables.kpm") version "0.6.0"

    kotlin("multiplatform") version "2.4.0"
}

kotlin {
    jvmToolchain(17)
    jvm()

    sourceSets {
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.junit.jupiter:junit-jupiter:5.14.4")
            runtimeOnly("org.junit.platform:junit-platform-launcher")
        }
    }
}

tasks.named<Test>("jvmTest") { useJUnitPlatform() }
