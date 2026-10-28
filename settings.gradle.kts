pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven ("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://jitpack.io")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()


    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 国内镜像放在 dl.google.com 之前：本机直连 Google 仓库会 READ TIMEOUT，
        // 而 Gradle 在第一个仓库超时后会直接让整次解析失败，不会继续落到后面的仓库。
        // 阿里云 public 代理了 Maven Central 上的 Google 发布物（如 google-genai-kotlin）。
        //
        // 但阿里云对 Maven Central 是「半同步」的：新版本常常 pom/.module 已经到位、jar 还是 404
        // （ADK-Kotlin 1.4.0 实测：google-adk-kotlin-processor / core-jvm / litertlm-jvm 的 jar
        // 在镜像上 404，Central 上 200；1.3.1 则是完整的）。
        // Gradle 的规则是：模块元数据在哪个仓库命中，就**只**在那个仓库找它的 artifact，
        // 不会带着同一个模块继续往后面的 mavenCentral() 落，所以报错里只列阿里云一个 URL，
        // 开代理也不会被触发（404 是正常应答，不是网络失败）。
        // 这里用组过滤把 com.google.adk 单独钉到 Central，其余依赖维持原有镜像顺序。
        maven("https://repo1.maven.org/maven2") {
            name = "centralForAdkKotlin"
            content { includeGroupByRegex("com\\.google\\.adk.*") }
        }
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://jitpack.io")
    }
}

rootProject.name = "NanoAI"
include(":app")
 