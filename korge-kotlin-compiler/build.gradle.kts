import com.github.jengelman.gradle.plugins.shadow.tasks.*
import org.jetbrains.kotlin.gradle.dsl.*

plugins {
    java
    kotlin("jvm") version "2.0.0"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
    //id("maven-publish")
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-build-tools-impl")
    implementation("org.jetbrains.kotlin:kotlin-build-tools-api")
    implementation("com.soywiz:korlibs-serialization-jvm:6.0.0-alpha6")
    implementation("com.soywiz:korlibs-dyn-jvm:6.0.0-alpha6")
    implementation("io.airlift:aircompressor:0.27")
    implementation("org.apache.commons:commons-compress:1.26.2")
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
        this.delete(File(System.getProperty("user.home"), ".korge/socket/compiler.socket"))
    }
    val shadowJarFast by creating(Jar::class) {
        manifest {
            attributes["Main-Class"] = mainClassFqname
        }
        with(jar.get())
        archiveClassifier.set("all-fast")
        //from(sourceSets["main"].output)
        entryCompression = ZipEntryCompression.STORED
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(configurations.runtimeClasspath.map { it.map { if (it.isDirectory) it else zipTree(it) } })
    }
    val install by creating(Copy::class) {
        dependsOn(shutdownDaemon, shadowJar)
        from("build/libs/korge-kotlin-compiler-all.jar")
        rename { "korge-kotlin-compiler.jar" }
        into(System.getProperty("user.home") + "/.korge/compiler")
    }
    val installFast by creating(Copy::class) {
        dependsOn(shutdownDaemon, shadowJarFast)
        from("build/libs/korge-kotlin-compiler-all-fast.jar")
        rename { "korge-kotlin-compiler.jar" }
        into(System.getProperty("user.home") + "/.korge/compiler")
    }
    //val customShadow by creating(ShadowJar::class) {
    //    archiveClassifier.set("all")
    //    mergeServiceFiles()
    //    //relocate("com.somepackage", "shadow.com.somepackage")
    //    this.entryCompression = ZipEntryCompression.STORED
    //}
}
