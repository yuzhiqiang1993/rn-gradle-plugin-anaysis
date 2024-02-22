/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.react

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.gradle.internal.tasks.factory.dependsOn
import com.facebook.react.internal.PrivateReactExtension
import com.facebook.react.tasks.GenerateCodegenArtifactsTask
import com.facebook.react.tasks.GenerateCodegenSchemaTask
import com.facebook.react.utils.AgpConfiguratorUtils.configureBuildConfigFieldsForApp
import com.facebook.react.utils.AgpConfiguratorUtils.configureBuildConfigFieldsForLibraries
import com.facebook.react.utils.AgpConfiguratorUtils.configureDevPorts
import com.facebook.react.utils.AgpConfiguratorUtils.configureNamespaceForLibraries
import com.facebook.react.utils.BackwardCompatUtils.configureBackwardCompatibilityReactMap
import com.facebook.react.utils.DependencyUtils.configureDependencies
import com.facebook.react.utils.DependencyUtils.configureRepositories
import com.facebook.react.utils.DependencyUtils.readVersionAndGroupStrings
import com.facebook.react.utils.JdkConfiguratorUtils.configureJavaToolChains
import com.facebook.react.utils.JsonUtils
import com.facebook.react.utils.NdkConfiguratorUtils.configureReactNativeNdk
import com.facebook.react.utils.ProjectUtils.needsCodegenFromPackageJson
import com.facebook.react.utils.findPackageJsonFile
import java.io.File
import kotlin.system.exitProcess
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.internal.jvm.Jvm

class ReactPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        println("ReactPlugin apply: ${project.name}")

        //检查JVM版本，不能低于17
        checkJvmVersion(project)
        //创建react配置
        val extension = project.extensions.create("react", ReactExtension::class.java, project)

        // We register a private extension on the rootProject so that project wide configs
        // like codegen config can be propagated from app project to libraries.
        /**
         * 在根项目创建一个私有的配置项 privateReact，如果已经存在则获取
         * 用于在app项目和库项目之间共享配置
         */
        val rootExtension =
            project.rootProject.extensions.findByType(PrivateReactExtension::class.java)
                ?: project.rootProject.extensions.create(
                    "privateReact", PrivateReactExtension::class.java, project
                )

        // App Only Configuration
        /**
         * 如果项目中使用了com.android.application插件，也就是app模块中会执行以下代码
         */
        project.pluginManager.withPlugin("com.android.application") {
            // We wire the root extension with the values coming from the app (either user populated or
            // defaults).

            /**
             * 下面代码实际上就是把用户自定义的配置赋值给rootExtension，就是把用户自定义的配置传递给上面创建好的一个私有配置项 privateReact
             */
            rootExtension.root.set(extension.root)
            rootExtension.reactNativeDir.set(extension.reactNativeDir)
            rootExtension.codegenDir.set(extension.codegenDir)
            rootExtension.nodeExecutableAndArgs.set(extension.nodeExecutableAndArgs)

            println("rootExtension root: ${rootExtension.root.get()}")
            println("rootExtension reactNativeDir: ${rootExtension.reactNativeDir.get()}")
            println("rootExtension codegenDir: ${rootExtension.codegenDir.get()}")
            println("rootExtension nodeExecutableAndArgs: ${rootExtension.nodeExecutableAndArgs.get()}")


            /**
             * 项目配置完成后，执行以下代码
             */
            project.afterEvaluate {
                val reactNativeDir = extension.reactNativeDir.get().asFile
                val propertiesFile = File(reactNativeDir, "ReactAndroid/gradle.properties")

                //获取版本号和groupName
                val versionAndGroupStrings = readVersionAndGroupStrings(propertiesFile)
                val versionString = versionAndGroupStrings.first
                val groupString = versionAndGroupStrings.second
                //配置依赖，主要是做了依赖替换和统一版本的逻辑
                configureDependencies(project, versionString, groupString)
                //配置仓库源
                configureRepositories(project, reactNativeDir)
            }

            //配置NDK
            configureReactNativeNdk(project, extension)
            //配置App的构建配置字段
            configureBuildConfigFieldsForApp(project, extension)
            //配置开发端口 默认8081
            configureDevPorts(project)
            //处理老版本配置兼容性
            configureBackwardCompatibilityReactMap(project)
            //配置Java工具链，确保项目中的 Java 和 Kotlin 代码使用 Java 17 版本
            configureJavaToolChains(project)

            //根据不同的构建类型配置不同的任务
            project.extensions.getByType(AndroidComponentsExtension::class.java).apply {
                onVariants(selector().all()) { variant ->
                    //配置react任务，用于执行react-native的打包操作
                    project.configureReactTasks(variant = variant, config = extension)
                }
            }
            /**
             * 配置react-native-codegen，用于生成所需代码，帮助我们避免编写重复代码的工具。
             */
            configureCodegen(project, extension, rootExtension, isLibrary = false)
        }

        // Library Only Configuration
        /**
         * 下面是library模块的配置
         */
        configureBuildConfigFieldsForLibraries(project)
        configureNamespaceForLibraries(project)
        project.pluginManager.withPlugin("com.android.library") {
            //配置react-native-codegen，用于生成所需代码
            configureCodegen(project, extension, rootExtension, isLibrary = true)
        }
    }

    private fun checkJvmVersion(project: Project) {
        val jvmVersion = Jvm.current()?.javaVersion?.majorVersion
        println("jvmVersion: $jvmVersion")
        if ((jvmVersion?.toIntOrNull() ?: 0) <= 16) {
            project.logger.error(
                """

      ********************************************************************************

      ERROR: requires JDK17 or higher.
      Incompatible major version detected: '$jvmVersion'

      ********************************************************************************

      """
                    .trimIndent()
            )
            exitProcess(1)
        }
    }

    /**
     * 用于配置react-native-codegen，生成代码
     */
    /** This function sets up `react-native-codegen` in our Gradle plugin. */
    @Suppress("UnstableApiUsage")
    private fun configureCodegen(
        project: Project,
        localExtension: ReactExtension,
        rootExtension: PrivateReactExtension,
        isLibrary: Boolean
    ) {
        // First, we set up the output dir for the codegen.
        //设置代码生成的输出目录
        val generatedSrcDir: Provider<Directory> =
            project.layout.buildDirectory.dir("generated/source/codegen")
        println("generatedSrcDir: ${generatedSrcDir.get().asFile.absolutePath}")


        // We specify the default value (convention) for jsRootDir.
        // It's the root folder for apps (so ../../ from the Gradle project)
        // and the package folder for library (so ../ from the Gradle project)
        if (isLibrary) {
            localExtension.jsRootDir.convention(project.layout.projectDirectory.dir("../"))
        } else {
            localExtension.jsRootDir.convention(localExtension.root)
        }

        println("localExtension.jsRootDir: ${localExtension.jsRootDir.get().asFile.absolutePath}")

        // We create the task to produce schema from JS files.
        //从JS文件生成schema
        val generateCodegenSchemaTask =
            project.tasks.register(
                "generateCodegenSchemaFromJavaScript", GenerateCodegenSchemaTask::class.java
            ) { it ->
                it.nodeExecutableAndArgs.set(rootExtension.nodeExecutableAndArgs)
                it.codegenDir.set(rootExtension.codegenDir)
                it.generatedSrcDir.set(generatedSrcDir)

                // We're reading the package.json at configuration time to properly feed
                // the `jsRootDir` @Input property of this task & the onlyIf. Therefore, the
                // parsePackageJson should be invoked inside this lambda.
                val packageJson = findPackageJsonFile(project, rootExtension.root)
                println("packageJson: $packageJson")
                val parsedPackageJson = packageJson?.let { JsonUtils.fromPackageJson(it) }
                println("parsedPackageJson: $parsedPackageJson")
                val jsSrcsDirInPackageJson = parsedPackageJson?.codegenConfig?.jsSrcsDir
                println("jsSrcsDirInPackageJson: $jsSrcsDirInPackageJson")
                if (jsSrcsDirInPackageJson != null) {
                    it.jsRootDir.set(File(packageJson.parentFile, jsSrcsDirInPackageJson))
                } else {
                    it.jsRootDir.set(localExtension.jsRootDir)
                }
                println("jsRootDir: ${it.jsRootDir.get().asFile.absolutePath}")
                val needsCodegenFromPackageJson =
                    project.needsCodegenFromPackageJson(rootExtension.root)
                println("isLibrary:${isLibrary},needsCodegenFromPackageJson: $needsCodegenFromPackageJson")
                it.onlyIf { isLibrary || needsCodegenFromPackageJson }
            }

        // We create the task to generate Java code from schema.
        //从schema生成Java代码
        val generateCodegenArtifactsTask =
            project.tasks.register(
                "generateCodegenArtifactsFromSchema", GenerateCodegenArtifactsTask::class.java
            ) {
                it.dependsOn(generateCodegenSchemaTask)
                it.reactNativeDir.set(rootExtension.reactNativeDir)
                it.nodeExecutableAndArgs.set(rootExtension.nodeExecutableAndArgs)
                it.generatedSrcDir.set(generatedSrcDir)
                it.packageJsonFile.set(findPackageJsonFile(project, rootExtension.root))
                println("packageJsonFile: ${it.packageJsonFile.get()}")
                it.codegenJavaPackageName.set(localExtension.codegenJavaPackageName)
                it.libraryName.set(localExtension.libraryName)


                // Please note that appNeedsCodegen is triggering a read of the package.json at
                // configuration time as we need to feed the onlyIf condition of this task.
                // Therefore, the appNeedsCodegen needs to be invoked inside this lambda.
                val needsCodegenFromPackageJson =
                    project.needsCodegenFromPackageJson(rootExtension.root)
                println("isLibrary:${isLibrary},needsCodegenFromPackageJson: $needsCodegenFromPackageJson")
                it.onlyIf { isLibrary || needsCodegenFromPackageJson }
            }

        // We update the android configuration to include the generated sources.
        // This equivalent to this DSL:
        //
        // android { sourceSets { main { java { srcDirs += "$generatedSrcDir/java" } } } }
        project.extensions.getByType(AndroidComponentsExtension::class.java).finalizeDsl { ext ->
            ext.sourceSets.getByName("main").java.srcDir(generatedSrcDir.get().dir("java").asFile)
        }

        // `preBuild` is one of the base tasks automatically registered by AGP.
        // This will invoke the codegen before compiling the entire project.
        //在编译整个项目之前调用codegen，用于生成Java代码，生成代码的逻辑在native_modules.gradle中generateCodegenArtifactsTask中
        project.tasks.named("preBuild", Task::class.java).dependsOn(generateCodegenArtifactsTask)
    }
}
