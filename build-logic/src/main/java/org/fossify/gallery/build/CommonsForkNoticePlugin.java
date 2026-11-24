package org.fossify.gallery.build;

import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import com.android.build.api.instrumentation.FramesComputationMode;
import com.android.build.api.instrumentation.InstrumentationScope;
import kotlin.Unit;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

/** Keeps Commons instrumentation in the app's plugin classloader, away from buildSrc. */
public class CommonsForkNoticePlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPlugins().withId("com.android.application", plugin -> {
            ApplicationAndroidComponentsExtension components = project.getExtensions()
                    .getByType(ApplicationAndroidComponentsExtension.class);
            components.onVariants(components.selector().all(), variant -> {
                variant.getInstrumentation().transformClassesWith(
                        CommonsForkNoticeVisitorFactory.class, InstrumentationScope.ALL,
                        parameters -> Unit.INSTANCE);
                variant.getInstrumentation().setAsmFramesComputationMode(
                        FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS);
            });
        });
    }
}
