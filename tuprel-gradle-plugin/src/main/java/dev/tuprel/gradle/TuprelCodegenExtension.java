package dev.tuprel.gradle;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;

/** Inputs for the staged CLI integration. */
public abstract class TuprelCodegenExtension {
    /** The project-relative schema read by validate and generate. */
    public abstract RegularFileProperty getSchema();

    /** The libraries of a trusted Tuprel CLI installation. */
    public abstract ConfigurableFileCollection getCliClasspath();
}
