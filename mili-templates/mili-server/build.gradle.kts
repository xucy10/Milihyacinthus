import io.papermc.paperweight.patcher.extension.PaperweightPatcherExtension

plugins {
    id("io.papermc.paperweight.patcher") version "2.0.0-SNAPSHOT"
}

// Mili 生态下游 fork 构建模板
//
// 目录约定（与 Paper / Folia / Luminol 一致）：
//   mili-server/            应用补丁后的服务端源码工作区（首次 applyAllPatches 时生成）
//   patches/mili-server/    本 fork 的服务端补丁（*.patch）
//
// 常用命令：
//   ./gradlew applyAllPatches        # 拉取上游并应用全部补丁
//   ./gradlew rebuildPatches         # 将修改重新导出为补丁
//   ./gradlew cleanCache             # 清理 paperweight 缓存

configure<PaperweightPatcherExtension> {
    upstreams.register("mili") {
        // TODO: 若 Mili 仓库地址或默认分支有变化，请修改以下两行，
        //       或在 gradle.properties 中通过 mili.repo / mili.ref 覆盖。
        repo = providers.gradleProperty("mili.repo").getOrElse("https://github.com/xucy10/Mili.git")
        ref = providers.gradleProperty("mili.ref").getOrElse("master")

        // 服务端补丁集：上游 paper-server 目录 -> 本项目的 mili-server 目录
        patchDir("miliServer") {
            upstreamPath = "paper-server"
            excludes = listOf("src/minecraft/java", "src/minecraft/resources")
            outputDir = rootProject.layout.projectDirectory.dir("mili-server")
            patchesDir = rootProject.layout.projectDirectory.dir("patches/mili-server")
        }

        // 可选：单文件补丁（例如 .editorconfig、devbundle 配置）
        // patchFile {
        //     path = "build-data/mili.devbundle"
        //     patchFile = rootProject.layout.projectDirectory.file("patches/build-data/mili.devbundle.patch")
        //     outputFile = rootProject.layout.projectDirectory.file("mili-server/build_data/devbundle")
        // }
    }
}
