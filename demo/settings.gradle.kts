pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "IntyVoiceCallPartnerDemo"

include(":app")
include(":inty_voice_call")
project(":inty_voice_call").projectDir = file("../inty_voice_call")
