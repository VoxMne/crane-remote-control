plugins {
    application
    id("org.openjfx.javafxplugin")
    id("org.beryx.runtime") version "1.13.1"
}

javafx {
    version = "21.0.7"
    modules("javafx.controls")
}

dependencies {
    implementation(project(":crane-core"))
    implementation(project(":crane-sim"))
    implementation(project(":crane-driver-serial"))
}

application {
    // Launcher (not the Application subclass) so the packaged classpath image starts.
    mainClass.set("com.vukotic.crane.ui.Launcher")
}

// Stamp the version into the jar so the HMI can show it in its title bar â€”
// a screenshot then always says which build it came from.
tasks.jar {
    manifest {
        attributes("Implementation-Title" to "Crane Remote Control",
                "Implementation-Version" to project.version)
    }
}

// Forward dev/diagnostic properties from the Gradle JVM to the app JVM, e.g.
//   gradlew :crane-ui:run "-Dcrane.devSnapshotDir=<dir>" "-Dprism.verbose=true"
tasks.named<JavaExec>("run") {
    listOf("crane.devSnapshotDir", "crane.devStress", "prism.verbose", "prism.order")
            .forEach { key -> System.getProperty(key)?.let { systemProperty(key, it) } }
}

// Self-contained Windows packages via jpackage:
//   `gradlew :crane-ui:jpackageImage` -> build/jpackage/CraneRemoteControl/ (portable folder)
//   `gradlew :crane-ui:jpackage`      -> build/jpackage/*.msi installer
// The MSI needs WiX on the PATH; this repo carries it in tools/wix314 (gitignored):
//   $env:Path = "$pwd\tools\wix314;$env:Path"; .\gradlew.bat :crane-ui:jpackage
runtime {
    options.set(listOf("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages"))
    modules.set(listOf(
            "java.base", "java.desktop", "java.logging", "java.naming",
            "java.sql", "java.xml", "jdk.unsupported"))
    jpackage {
        imageName = "CraneRemoteControl"
        installerName = "CraneRemoteControl"
        // jpackage rejects a leading 0 in MSI versions, so the package version
        // stays 1.x even while the project is 0.x.
        appVersion = "3.4.0"
        installerType = "msi"
        installerOptions = listOf(
                "--vendor", "Vukotic",
                "--win-menu", "--win-shortcut", "--win-per-user-install")
        imageOptions = listOf("--vendor", "Vukotic")
    }
}
