/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.react.utils

import com.facebook.react.utils.PropertyUtils.DEFAULT_INTERNAL_PUBLISHING_GROUP
import com.facebook.react.utils.PropertyUtils.INTERNAL_PUBLISHING_GROUP
import com.facebook.react.utils.PropertyUtils.INTERNAL_REACT_NATIVE_MAVEN_LOCAL_REPO
import com.facebook.react.utils.PropertyUtils.INTERNAL_USE_HERMES_NIGHTLY
import com.facebook.react.utils.PropertyUtils.INTERNAL_VERSION_NAME
import java.io.File
import java.net.URI
import java.util.*
import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.MavenArtifactRepository

internal object DependencyUtils {

    /**
     * This method takes care of configuring the repositories{} block for both the app and all the 3rd
     * party libraries which are auto-linked.
     */
    fun configureRepositories(project: Project, reactNativeDir: File) {
        println("configureRepositories: $reactNativeDir")
        project.rootProject.allprojects { eachProject ->
            with(eachProject) {
                if (hasProperty(INTERNAL_REACT_NATIVE_MAVEN_LOCAL_REPO)) {
                    val mavenLocalRepoPath =
                        property(INTERNAL_REACT_NATIVE_MAVEN_LOCAL_REPO) as String
                    mavenRepoFromURI(File(mavenLocalRepoPath).toURI())
                }
                // We add the snapshot for users on nightlies.
                mavenRepoFromUrl("https://oss.sonatype.org/content/repositories/snapshots/")
                repositories.mavenCentral { repo ->
                    // We don't want to fetch JSC from Maven Central as there are older versions there.
                    repo.content { it.excludeModule("org.webkit", "android-jsc") }
                }
                // Android JSC is installed from npm
                mavenRepoFromURI(File(reactNativeDir, "../jsc-android/dist").toURI())
                repositories.google()
                mavenRepoFromUrl("https://www.jitpack.io")
            }
        }
    }

    /**
     * This method takes care of configuring the resolution strategy for both the app and all the 3rd
     * party libraries which are auto-linked. Specifically it takes care of:
     * - Forcing the react-android/hermes-android/flipper-integration version to the one specified in
     *   the package.json
     * - Substituting `react-native` with `react-android` and `hermes-engine` with `hermes-android`.
     */

    /**
     * 配置依赖
     * 1.替换依赖
     * 2.强制使用指定版本
     *
     * @param project Project
     * @param versionString String
     * @param groupString String
     */
    fun configureDependencies(
        project: Project,
        versionString: String,
        groupString: String = DEFAULT_INTERNAL_PUBLISHING_GROUP
    ) {
        println("configureDependencies: $versionString, $groupString")
        if (versionString.isBlank()) return
        //遍历所有项目
        project.rootProject.allprojects { eachProject ->
            println("eachProject: ${eachProject.name}")
            //遍历项目的所有配置
            eachProject.configurations.all { configuration ->
                /**
                 * configuration.resolutionStrategy 用于配置解析策略，一般用于配置依赖替换和强制使用指定版本
                 */
                configuration.resolutionStrategy.dependencySubstitution {
                    //获取依赖替换列表
                    getDependencySubstitutions(
                        versionString,
                        groupString
                    ).forEach { (module, dest, reason) ->
                        //将指定的依赖替换为目标依赖
                        it.substitute(it.module(module)).using(it.module(dest)).because(reason)
                    }
                }
                //强制使用指定版本
                configuration.resolutionStrategy.force(
                    "${groupString}:react-android:${versionString}",
                    "${groupString}:flipper-integration:${versionString}",
                )

                //如果用户没有选择使用夜间版本进行本地开发，则强制使用hermes-android指定版本
                if (!(eachProject.findProperty(INTERNAL_USE_HERMES_NIGHTLY) as? String).toBoolean()) {
                    // Contributors only: The hermes-engine version is forced only if the user has
                    // not opted into using nightlies for local development.
                    configuration.resolutionStrategy.force("${groupString}:hermes-android:${versionString}")
                }
            }
        }
    }

    /**
     * 生成依赖替换列表
     * @param versionString String
     * @param groupString String
     * @return List<Triple<String, String, String>>
     */
    internal fun getDependencySubstitutions(
        versionString: String,
        groupString: String = DEFAULT_INTERNAL_PUBLISHING_GROUP
    ): List<Triple<String, String, String>> {
        /**
         * 生成依赖替换列表
         * first:原始依赖
         * second:替换后的依赖
         * third:原因
         */
        val dependencySubstitution = mutableListOf<Triple<String, String, String>>()
        // react-native替换为react-android
        dependencySubstitution.add(
            Triple(
                "com.facebook.react:react-native",
                "${groupString}:react-android:${versionString}",
                "The react-native artifact was deprecated in favor of react-android due to https://github.com/facebook/react-native/issues/35210."
            )
        )
        // hermes-engine替换为hermes-android
        dependencySubstitution.add(
            Triple(
                "com.facebook.react:hermes-engine",
                "${groupString}:hermes-android:${versionString}",
                "The hermes-engine artifact was deprecated in favor of hermes-android due to https://github.com/facebook/react-native/issues/35210."
            )
        )
        // 如果 groupString 不是默认值 com.facebook.react，则修改react-android和hermes-android的Maven group
        if (groupString != DEFAULT_INTERNAL_PUBLISHING_GROUP) {
            dependencySubstitution.add(
                Triple(
                    "com.facebook.react:react-android",
                    "${groupString}:react-android:${versionString}",
                    "The react-android dependency was modified to use the correct Maven group."
                )
            )
            dependencySubstitution.add(
                Triple(
                    "com.facebook.react:hermes-android",
                    "${groupString}:hermes-android:${versionString}",
                    "The hermes-android dependency was modified to use the correct Maven group."
                )
            )
        }

//        println("dependencySubstitution: $dependencySubstitution")
        return dependencySubstitution
    }

    /**
     * 读取版本和group字符串
     * @param propertiesFile File
     * @return Pair<String, String>
     */
    fun readVersionAndGroupStrings(propertiesFile: File): Pair<String, String> {
        println("readVersionAndGroupStrings: $propertiesFile")
        val reactAndroidProperties = Properties()
        propertiesFile.inputStream().use { reactAndroidProperties.load(it) }
        val versionStringFromFile = reactAndroidProperties[INTERNAL_VERSION_NAME] as? String ?: ""
        // If on a nightly, we need to fetch the -SNAPSHOT artifact from Sonatype.
        val versionString =
            if (versionStringFromFile.startsWith("0.0.0") || "-nightly-" in versionStringFromFile) {
                "$versionStringFromFile-SNAPSHOT"
            } else {
                versionStringFromFile
            }
        // Returns Maven group for repos using different group for Maven artifacts
        val groupString =
            reactAndroidProperties[INTERNAL_PUBLISHING_GROUP] as? String
                ?: DEFAULT_INTERNAL_PUBLISHING_GROUP
        return Pair(versionString, groupString)
    }

    fun Project.mavenRepoFromUrl(url: String): MavenArtifactRepository =
        project.repositories.maven { it.url = URI.create(url) }

    fun Project.mavenRepoFromURI(uri: URI): MavenArtifactRepository =
        project.repositories.maven { it.url = uri }
}
