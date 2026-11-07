package dev.tuprel.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;

/** Adds Tuprel validation and code generation to Java projects. */
public final class TuprelCodegenPlugin implements Plugin<Project> {
    @Override public void apply(Project project) {
        TuprelCodegenExtension config = project.getExtensions().create("tuprelCodegen", TuprelCodegenExtension.class);
        Directory root = project.getLayout().getProjectDirectory();
        config.getSchema().convention(root.file("tuprel/schema.tuprel"));
        project.getPluginManager().withPlugin("java", applied -> {
            TaskProvider<TuprelCliTask.Validate> validate = project.getTasks().register(
                    "tuprelValidate", TuprelCliTask.Validate.class, task -> configure(project, config, task));
            TaskProvider<TuprelCliTask.Generate> generate = project.getTasks().register(
                    "tuprelGenerate", TuprelCliTask.Generate.class, task -> {
                        configure(project, config, task);
                        task.getGeneratedSources().set(root.dir("build/generated/sources/tuprel/main"));
                        task.getGeneratedSources().disallowChanges();
                    });
            project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets().named("main", main ->
                    main.getJava().srcDir(generate.flatMap(TuprelCliTask.Generate::getGeneratedSources)));
            project.getTasks().named("check").configure(task -> task.dependsOn(validate));
        });
    }

    private static void configure(Project project, TuprelCodegenExtension config, TuprelCliTask task) {
        task.setGroup("tuprel");
        task.getSchema().set(config.getSchema());
        task.getCliClasspath().from(config.getCliClasspath());
        task.getProjectDirectory().set(project.getLayout().getProjectDirectory());
        JavaToolchainService toolchains = project.getExtensions().getByType(JavaToolchainService.class);
        task.getLauncher().set(toolchains.launcherFor(spec -> spec.getLanguageVersion().set(JavaLanguageVersion.of(21))));
    }
}
