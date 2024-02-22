/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.react

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * 该插件应用于`android/build.gradle`文件。
 * 该插件的作用是确保app项目在库项目之前被配置，以便在库项目被配置时可以使用app项目的配置
 *
 * @constructor
 */
class ReactRootProjectPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.subprojects {
            // As the :app project (i.e. ReactPlugin) configures both namespaces and JVM toolchains
            // for libraries, its evaluation must happen before the libraries' evaluation.
            // Eventually the configuration of namespace/JVM toolchain can be moved inside this plugin.
            println("ReactRootProjectPlugin apply: ${project.name}")
            if (it.path != ":app") {
                it.evaluationDependsOn(":app")
            }
        }
    }
}

