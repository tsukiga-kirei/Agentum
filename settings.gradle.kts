pluginManagement {
    repositories {
        maven { url = java.net.URI("https://maven.aliyun.com/repository/public") }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = java.net.URI("https://maven.aliyun.com/repository/public") }
        mavenCentral()
    }
}

rootProject.name = "agentum"

include("apps:api")
include("workers:document-worker")

