import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.tasks.testing.logging.*
import org.jetbrains.kotlin.gradle.dsl.*

plugins {
    java
    kotlin("jvm") version "2.0.0"
    application
    id("com.github.gmazzo.buildconfig") version "5.3.5"
}

var projectVersion = System.getenv("FORCED_VERSION")
    ?.replaceFirst(Regex("^refs/tags/"), "")
    ?.replaceFirst(Regex("^v"), "")
    ?.replaceFirst(Regex("^w"), "")
    ?.replaceFirst(Regex("^z"), "")
    ?: rootProject.file("korge").takeIf { it.exists() }?.readText()?.let { Regex("INSTALLER_VERSION=(.*)").find(it)?.groupValues?.get(1) }
    ?: "0.0.1-SNAPSHOT"
//?: project.findProperty("version")

version = projectVersion

//println(version)

if (System.getenv("FORCED_VERSION") != null) {
    println("FORCED_VERSION=$version")
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-build-tools-impl")
    implementation("org.jetbrains.kotlin:kotlin-build-tools-api")
    implementation("com.soywiz:korlibs-serialization-jvm:6.0.0-alpha6")
    implementation("com.soywiz:korlibs-dyn-jvm:6.0.0-alpha6")
    implementation("io.airlift:aircompressor:0.27")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("io.methvin:directory-watcher:0.18.0")
    implementation("org.slf4j:slf4j-simple:1.6.1")
    //api("org.jetbrains.kotlin:kotlin-compiler-embeddable")
    //api("org.jetbrains.kotlin:kotlin-compiler-client-embeddable")
    //api("org.jetbrains.kotlin:kotlin-daemon-embeddable")
    //api("org.jetbrains.kotlin:kotlin-gradle-plugin")
    testImplementation(kotlin("test"))

}

repositories {
    mavenLocal()
    mavenCentral()
}

val jversion = JvmTarget.JVM_21

//println(jversion.target)

java {
    setSourceCompatibility(jversion.target)
    setTargetCompatibility(jversion.target)
}

tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class).all {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        suppressWarnings = true
    }
}

val mainClassFqname = "korlibs.korge.kotlincompiler.KorgeKotlinCompilerCLI"

application {
    mainClass.set(mainClassFqname)
}

tasks {
    val shutdownDaemon by creating(Delete::class) {
        this.delete(File(System.getProperty("user.home"), ".korge/socket/compiler-${version}.socket"))
        doFirst {
            exec {
                workingDir = rootProject.rootDir
                if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                    commandLine("cmd", "/c", "korge.bat", "stop")
                } else {
                    commandLine("sh", "./korge", "stop")
                }
            }
        }
    }
    val fatJar by creating(Jar::class) {
        dependsOn(jar)
        manifest {
            attributes["Main-Class"] = mainClassFqname
        }
        with(jar.get())
        archiveClassifier.set("all")
        this.archiveVersion.set("")
        //from(sourceSets["main"].output)
        entryCompression = ZipEntryCompression.STORED
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(configurations.runtimeClasspath.map { it.map { if (it.isDirectory) it else zipTree(it) } })
    }
    val createJarPack by creating(Exec::class) {
        dependsOn(fatJar)
        workingDir(file("build/libs"))
        commandLine("tar", "-cJf", "korge-kotlin-compiler-all.tar.xz", "korge-kotlin-compiler-all.jar")
        //inputs.file("build/libs/korge-kotlin-compiler-all.tar")
        //outputs.file("build/libs/korge-kotlin-compiler-all.tar.xz")
    }
    val install by creating(Copy::class) {
        group = "install"
        dependsOn(shutdownDaemon, fatJar)
        from("build/libs/korge-kotlin-compiler-all.jar")
        rename { "korge-kotlin-compiler-all.$version.jar" }
        into(System.getProperty("user.home") + "/.korge/compiler")
    }
}

tasks.withType(org.gradle.api.tasks.testing.AbstractTestTask::class) {
    testLogging {
        events = mutableSetOf(
            TestLogEvent.SKIPPED,
            TestLogEvent.FAILED,
            TestLogEvent.STANDARD_OUT, TestLogEvent.STANDARD_ERROR
        )
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
        showStackTraces = true
    }
}

buildConfig {
    packageName("korlibs.korge.kotlincompiler")
    buildConfigField("String", "KORGE_COMPILER_VERSION", "\"$projectVersion\"")
    buildConfigField("String", "LATEST_KORGE_VERSION", "\"6.0.0-alpha5\"")
}
