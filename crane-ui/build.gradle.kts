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
}

application {
    // Launcher (not the Application subclass) so the packaged classpath image starts.
    mainClass.set("com.vukotic.crane.ui.Launcher")
}

// Self-contained Windows package: jlink'ed JRE + app jars via jpackage.
// `gradlew :crane-ui:jpackageImage` -> build/jpackage/CraneRemoteControl/CraneRemoteControl.exe
// (an .msi installer additionally requires the WiX Toolset; app-image needs nothing)
runtime {
    options.set(listOf("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages"))
    modules.set(listOf(
            "java.base", "java.desktop", "java.logging", "java.naming",
            "java.sql", "java.xml", "jdk.unsupported"))
    jpackage {
        imageName = "CraneRemoteControl"
        skipInstaller = true
        imageOptions = listOf("--vendor", "Vukotic")
    }
}
