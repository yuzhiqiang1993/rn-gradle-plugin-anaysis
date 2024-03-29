/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.react.utils

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.gradle.LibraryExtension
import com.facebook.react.ReactExtension
import com.facebook.react.utils.ProjectUtils.isHermesEnabled
import com.facebook.react.utils.ProjectUtils.isNewArchEnabled
import java.io.File
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.plugins.AppliedPlugin
import org.w3c.dom.Element

@Suppress("UnstableApiUsage")
internal object AgpConfiguratorUtils {

    /**
     * 确保在 Android 应用或库项目中启用buildConfig，并添加了两个自定义的布尔类型的构建配置字段，用于表示新架构是否启用以及是否启用了 Hermes 引擎。
     * 这些字段将在生成的 BuildConfig 类中作为静态字段提供。
     * @param project Project
     * @param extension ReactExtension
     */
    fun configureBuildConfigFieldsForApp(project: Project, extension: ReactExtension) {
        val action =
            Action<AppliedPlugin> {
                project.extensions.getByType(AndroidComponentsExtension::class.java)
                    .finalizeDsl { ext ->
                        ext.buildFeatures.buildConfig = true
                        ext.defaultConfig.buildConfigField(
                            "boolean",
                            "IS_NEW_ARCHITECTURE_ENABLED",
                            project.isNewArchEnabled(extension).toString()
                        )
                        ext.defaultConfig.buildConfigField(
                            "boolean", "IS_HERMES_ENABLED", project.isHermesEnabled.toString()
                        )
                    }
            }
        project.pluginManager.withPlugin("com.android.application", action)
        project.pluginManager.withPlugin("com.android.library", action)
    }

    /**
     * 配置构建配置字段
     * @param appProject Project
     */
    fun configureBuildConfigFieldsForLibraries(appProject: Project) {
        appProject.rootProject.allprojects { subproject ->
            subproject.pluginManager.withPlugin("com.android.library") {
                subproject.extensions.getByType(AndroidComponentsExtension::class.java)
                    .finalizeDsl { ext ->
                        ext.buildFeatures.buildConfig = true
                    }
            }
        }
    }

    fun configureDevPorts(project: Project) {
        val devServerPort =
            project.properties["reactNativeDevServerPort"]?.toString() ?: DEFAULT_DEV_SERVER_PORT
        val inspectorProxyPort =
            project.properties["reactNativeInspectorProxyPort"]?.toString() ?: devServerPort

        val action =
            Action<AppliedPlugin> {
                project.extensions.getByType(AndroidComponentsExtension::class.java)
                    .finalizeDsl { ext ->
                        ext.defaultConfig.resValue(
                            "integer",
                            "react_native_dev_server_port",
                            devServerPort
                        )
                        ext.defaultConfig.resValue(
                            "integer", "react_native_inspector_proxy_port", inspectorProxyPort
                        )
                    }
            }

        project.pluginManager.withPlugin("com.android.application", action)
        project.pluginManager.withPlugin("com.android.library", action)
    }

    fun configureNamespaceForLibraries(appProject: Project) {
        appProject.rootProject.allprojects { subproject ->
            subproject.pluginManager.withPlugin("com.android.library") {
                subproject.extensions.getByType(AndroidComponentsExtension::class.java)
                    .finalizeDsl { ext ->
                        if (ext.namespace == null) {
                            val android =
                                subproject.extensions.getByType(LibraryExtension::class.java)
                            val manifestFile = android.sourceSets.getByName("main").manifest.srcFile

                            manifestFile
                                .takeIf { it.exists() }
                                ?.let { file ->
                                    getPackageNameFromManifest(file)?.let { packageName ->
                                        ext.namespace = packageName
                                    }
                                }
                        }
                    }
            }
        }
    }
}

const val DEFAULT_DEV_SERVER_PORT = "8081"

fun getPackageNameFromManifest(manifest: File): String? {
    val factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance()
    val builder: DocumentBuilder = factory.newDocumentBuilder()

    try {
        val xmlDocument = builder.parse(manifest)

        val manifestElement = xmlDocument.getElementsByTagName("manifest").item(0) as? Element
        val packageName = manifestElement?.getAttribute("package")

        return if (packageName.isNullOrEmpty()) null else packageName
    } catch (e: Exception) {
        return null
    }
}
