/*
 * SPDX-FileCopyrightText: 2025 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
rootProject.name = "NewPipe"

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
        maven(url = "https://repo.clojars.org")
    }
}
include(":app") // androidApp
include(":desktopApp")
include("shared")

includeBuild("../Tubular-Extractor-Revived") {
    dependencySubstitution {
        substitute(module("com.github.feuerswut:extractor"))
            .using(project(":extractor"))
    }
}
