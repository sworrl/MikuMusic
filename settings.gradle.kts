pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "MikuPlayer"
include(":app")
include(":hardware-settings")
include(":mikuos-settings")
include(":mikuos-systemui")
include(":mikuos-launcher")
include(":fmradio")
