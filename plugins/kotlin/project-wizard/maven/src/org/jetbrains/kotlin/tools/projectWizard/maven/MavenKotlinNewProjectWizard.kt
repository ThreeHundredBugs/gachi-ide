// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.kotlin.tools.projectWizard.maven

import com.intellij.ide.projectWizard.NewProjectWizardCollector.Base.logAddSampleCodeChanged
import com.intellij.ide.projectWizard.NewProjectWizardCollector.Base.logAddSampleCodeFinished
import com.intellij.ide.projectWizard.NewProjectWizardCollector.Base.logAddSampleOnboardingTipsChanged
import com.intellij.ide.projectWizard.NewProjectWizardCollector.Base.logAddSampleOnboardingTipsFinished
import com.intellij.ide.projectWizard.NewProjectWizardConstants.BuildSystem.MAVEN
import com.intellij.ide.projectWizard.generators.AssetsNewProjectWizardStep
import com.intellij.ide.projectWizard.generators.AssetsOnboardingTips.proposeToGenerateOnboardingTipsByDefault
import com.intellij.ide.starters.local.StandardAssetsProvider
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardStep.Companion.ADD_SAMPLE_CODE_PROPERTY_NAME
import com.intellij.openapi.externalSystem.model.ExternalSystemDataKeys
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsManagerImpl
import com.intellij.openapi.observable.util.bindBooleanStorage
import com.intellij.openapi.project.Project
import com.intellij.ui.UIBundle
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.whenStateChangedFromUi
import com.intellij.util.io.createDirectories
import org.jetbrains.idea.maven.wizards.MavenNewProjectWizardStep
import org.jetbrains.kotlin.tools.projectWizard.BuildSystemKotlinNewProjectWizard
import org.jetbrains.kotlin.tools.projectWizard.BuildSystemKotlinNewProjectWizard.Companion.DEFAULT_KOTLIN_VERSION
import org.jetbrains.kotlin.tools.projectWizard.BuildSystemKotlinNewProjectWizardData
import org.jetbrains.kotlin.tools.projectWizard.BuildSystemKotlinNewProjectWizardData.Companion.SRC_MAIN_KOTLIN_PATH
import org.jetbrains.kotlin.tools.projectWizard.BuildSystemKotlinNewProjectWizardData.Companion.SRC_MAIN_RESOURCES_PATH
import org.jetbrains.kotlin.tools.projectWizard.BuildSystemKotlinNewProjectWizardData.Companion.SRC_TEST_KOTLIN_PATH
import org.jetbrains.kotlin.tools.projectWizard.BuildSystemKotlinNewProjectWizardData.Companion.SRC_TEST_RESOURCES_PATH
import org.jetbrains.kotlin.tools.projectWizard.KotlinNewProjectWizard
import org.jetbrains.kotlin.tools.projectWizard.KotlinNewProjectWizard.Companion.getKotlinWizardVersion
import org.jetbrains.kotlin.tools.projectWizard.addMultiPlatformLink
import org.jetbrains.kotlin.tools.projectWizard.wizard.NewProjectWizardModuleBuilder
import org.jetbrains.kotlin.tools.projectWizard.wizard.prepareKotlinSampleOnboardingTips
import org.jetbrains.kotlin.tools.projectWizard.wizard.withKotlinSampleCode
import java.nio.file.Path

internal class MavenKotlinNewProjectWizard : BuildSystemKotlinNewProjectWizard {

    override val name = MAVEN

    override val ordinal = 100

    override fun createStep(parent: KotlinNewProjectWizard.Step): NewProjectWizardStep =
        Step(parent)
            .nextStep(::AssetsStep)

    class Step(parent: KotlinNewProjectWizard.Step) :
        MavenNewProjectWizardStep<KotlinNewProjectWizard.Step>(parent),
        BuildSystemKotlinNewProjectWizardData by parent,
        MavenKotlinNewProjectWizardData
    {

        init {
            data.putUserData(MavenKotlinNewProjectWizardData.KEY, this)
        }

        override val addSampleCodeProperty = propertyGraph.property(true)
            .bindBooleanStorage(ADD_SAMPLE_CODE_PROPERTY_NAME)

        override var addSampleCode by addSampleCodeProperty

        override val generateOnboardingTipsProperty = propertyGraph.property(proposeToGenerateOnboardingTipsByDefault())
            .bindBooleanStorage(NewProjectWizardStep.GENERATE_ONBOARDING_TIPS_NAME)

        override var generateOnboardingTips by generateOnboardingTipsProperty

        private fun setupSampleCodeUI(builder: Panel) {
            builder.row {
                checkBox(UIBundle.message("label.project.wizard.new.project.add.sample.code"))
                    .bindSelected(addSampleCodeProperty)
                    .whenStateChangedFromUi { logAddSampleCodeChanged(it) }
                    .onApply { logAddSampleCodeFinished(addSampleCode) }
            }
        }

        private fun setupSampleCodeWithOnBoardingTipsUI(builder: Panel) {
            builder.indent {
                row {
                    checkBox(UIBundle.message("label.project.wizard.new.project.generate.onboarding.tips"))
                        .bindSelected(generateOnboardingTipsProperty)
                        .whenStateChangedFromUi { logAddSampleOnboardingTipsChanged(it) }
                        .onApply { logAddSampleOnboardingTipsFinished(generateOnboardingTips) }
                }
            }.enabledIf(addSampleCodeProperty)
        }

        override fun setupSettingsUI(builder: Panel) {
            setupJavaSdkUI(builder)
            setupParentsUI(builder)
            setupSampleCodeUI(builder)
            setupSampleCodeWithOnBoardingTipsUI(builder)
            if (context.isCreatingNewProject) {
                addMultiPlatformLink(builder)
            }
        }

        override fun setupAdvancedSettingsUI(builder: Panel) {
            setupGroupIdUI(builder)
            setupArtifactIdUI(builder)
        }

        private fun findKotlinVersionToUse(newProjectWizardModuleBuilder: NewProjectWizardModuleBuilder) {
            kotlinPluginWizardVersion = getKotlinWizardVersion(newProjectWizardModuleBuilder).version.text
        }

        private fun initializeProjectValues() {
            findKotlinVersionToUse(NewProjectWizardModuleBuilder())
        }

        private var kotlinPluginWizardVersion: String = DEFAULT_KOTLIN_VERSION

        override fun setupProject(project: Project) {
            initializeProjectValues()

            ExternalProjectsManagerImpl.setupCreatedProject(project)
            project.putUserData(ExternalSystemDataKeys.NEWLY_CREATED_PROJECT, true)

            val moduleBuilder = MavenKotlinModuleBuilder("$path/$name")
            if (addSampleCode) {
                moduleBuilder.filesToOpen.add("$SRC_MAIN_KOTLIN_PATH/Main.kt")
            }

            linkMavenProject(
                project,
                moduleBuilder
            ) { builder ->
                builder.kotlinPluginWizardVersion = kotlinPluginWizardVersion
            }
        }
    }

    private class AssetsStep(private val parent: Step) : AssetsNewProjectWizardStep(parent) {
        override fun setupAssets(project: Project) {
            if (context.isCreatingNewProject) {
                addAssets(StandardAssetsProvider().getMavenIgnoreAssets())
            }
            createKotlinContentRoots()
            if (parent.addSampleCode) {
                if (parent.generateOnboardingTips) {
                    prepareKotlinSampleOnboardingTips(project)
                }
                withKotlinSampleCode(SRC_MAIN_KOTLIN_PATH, parent.groupId, parent.generateOnboardingTips, shouldOpenFile = false)
            }
        }

        private fun createKotlinContentRoots() {
            val directories = listOf(
                "$outputDirectory/$SRC_MAIN_KOTLIN_PATH",
                "$outputDirectory/$SRC_MAIN_RESOURCES_PATH",
                "$outputDirectory/$SRC_TEST_KOTLIN_PATH",
                "$outputDirectory/$SRC_TEST_RESOURCES_PATH",
            )
            directories.forEach {
                Path.of(it).createDirectories()
            }
        }
    }
}
