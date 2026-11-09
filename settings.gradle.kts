pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "MyJournal"
include(":apps:server", ":apps:android", ":apps:desktop", ":shared:core", ":shared:domain", ":shared:data", ":shared:ui")
