plugins {
    application
    id("org.openjfx.javafxplugin")
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
    mainClass.set("com.vukotic.crane.ui.CraneRemoteApp")
}
