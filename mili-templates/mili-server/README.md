# Mili 生态构建模板（paperweight-patcher）

这是基于 paperweight-patcher 的 **Mili 下游 fork 构建模板**，用于快速创建基于 Mili（Folia/Luminol 体系）的服务端 fork，并享受 Mili 定制版 paperweight 带来的下载镜像、自动重试与并发调优能力。

## 目录结构

```
mili-server-template/
├── settings.gradle.kts      # 插件仓库与项目名
├── build.gradle.kts         # patcher 配置：上游仓库 + 补丁集定义
├── gradle.properties        # 上游版本、镜像、重试、并发等可调参数
├── mili-server/             # 应用补丁后的服务端工作区（自动生成）
└── patches/mili-server/     # 本 fork 的补丁（自动生成/由 rebuildPatches 更新）
```

## 前置要求

- JDK 25（Gradle 会自动通过 foojay resolver 拉取对应工具链）
- Git（补丁的应用与重建均基于 git）
- paperweight 2.0.0-SNAPSHOT（Mili 定制版）

若使用本地修改版 paperweight，先在其仓库执行：

```bash
./gradlew publishToMavenLocal
```

并在 `settings.gradle.kts` 中取消 `mavenLocal()` 的注释。

## 快速开始

```bash
# 1. 应用上游补丁，生成服务端工作区
./gradlew applyAllPatches

# 2. 在 mili-server/ 中进行你的修改（git 提交）

# 3. 将修改重新导出为补丁
./gradlew rebuildPatches
```

## 常用 Gradle 任务

| 任务 | 说明 |
|------|------|
| `applyAllPatches` | 检出上游 Mili 仓库并应用全部补丁 |
| `rebuildPatches` | 根据工作区 git 提交重建补丁文件 |
| `cleanCache` | 清理 `.gradle/caches/paperweight` 构建缓存 |

## 网络调优（Mili 定制特性）

以下 Gradle 属性均可在 `gradle.properties` 中配置：

- `paperweight.download.mirrors` — 下载镜像规则，格式为 `原始URL前缀=镜像URL前缀`，多条规则用 `,` 分隔，命中即生效。例如启用 BMCLAPI 加速 Mojang 下载：

  ```properties
  paperweight.download.mirrors=https://piston-meta.mojang.com=https://bmclapi2.bangbang93.com,https://piston-data.mojang.com=https://bmclapi2.bangbang93.com
  ```

- `paperweight.download.retries` — 下载失败自动重试次数（指数退避，默认 2；I/O 错误、HTTP 429/5xx、哈希校验失败会重试，404 等永久错误会快速失败）。
- `paperweight.download.maxConnections` / `paperweight.download.maxConnectionsPerRoute` — HTTP 连接池大小（默认 24 / 8）。使用单一镜像源时建议调大单路由并发。
- `paperweight.mojangManifestUrl` — 直接覆盖 Mojang 版本清单 URL。

## 上游配置

在 `gradle.properties` 中可覆盖上游仓库与分支：

```properties
mili.repo=https://github.com/xucy10/Mili.git
mili.ref=master
```

如需调整补丁集（例如上游目录名变化），修改 `build.gradle.kts` 中的 `patchDir("miliServer")` 配置块。
