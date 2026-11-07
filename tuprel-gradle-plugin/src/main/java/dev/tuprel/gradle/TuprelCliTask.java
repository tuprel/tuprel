package dev.tuprel.gradle;

import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.process.ExecOperations;
import org.gradle.work.DisableCachingByDefault;

/** Shared inputs for the CLI-backed Tuprel tasks. */
public abstract class TuprelCliTask extends DefaultTask {
    private final ExecOperations exec;
    private final RegularFileProperty schema;
    private final ConfigurableFileCollection cliClasspath;
    private final Property<JavaLauncher> launcher;
    private final DirectoryProperty projectDirectory;

    TuprelCliTask(ObjectFactory objects, ExecOperations exec) {
        this.exec = exec;
        schema = objects.fileProperty();
        cliClasspath = objects.fileCollection();
        launcher = objects.property(JavaLauncher.class);
        projectDirectory = objects.directoryProperty();
    }

    @InputFile @PathSensitive(PathSensitivity.RELATIVE)
    public final RegularFileProperty getSchema() { return schema; }

    @Classpath
    public final ConfigurableFileCollection getCliClasspath() { return cliClasspath; }

    @Nested
    public final Property<JavaLauncher> getLauncher() { return launcher; }

    @Internal
    public final DirectoryProperty getProjectDirectory() { return projectDirectory; }

    final void runCli(String command) {
        if (cliClasspath.isEmpty()) {
            throw new GradleException("Set tuprelCodegen.cliClasspath to the lib directory of a trusted Tuprel CLI installation.");
        }
        exec.javaexec(spec -> {
            spec.setExecutable(launcher.get().getExecutablePath().getAsFile().getAbsolutePath());
            spec.workingDir(projectDirectory.get().getAsFile());
            spec.classpath(cliClasspath);
            spec.getMainClass().set("dev.tuprel.cli.TuprelCli");
            spec.args(command, "--schema", schema.get().getAsFile().getAbsolutePath());
        });
    }

    /** Validates the configured schema. */
    @DisableCachingByDefault(because = "Validation writes no outputs")
    public static class Validate extends TuprelCliTask {
        @Inject public Validate(ObjectFactory objects, ExecOperations exec) { super(objects, exec); }

        @TaskAction public void validate() { runCli("validate"); }
    }

    /** Generates and tracks the CLI-owned Java sources. */
    @DisableCachingByDefault(because = "A cache restore would bypass CLI file ownership checks")
    public static class Generate extends TuprelCliTask {
        private final DirectoryProperty generatedSources;

        @Inject public Generate(ObjectFactory objects, ExecOperations exec) {
            super(objects, exec);
            generatedSources = objects.directoryProperty();
        }

        @OutputDirectory
        public final DirectoryProperty getGeneratedSources() { return generatedSources; }

        @TaskAction public void generate() { runCli("generate"); }
    }
}
