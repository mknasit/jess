package de.upb.sse.jess.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class PublicApi {

    public static final class MethodId {
        public final String binaryClassName; // e.g. "org/apache/commons/io/ByteBuffers"
        public final String name;            // e.g. "littleEndian" or "<init>" or "<clinit>"
        public final String jvmDescriptor;   // e.g. "([B)Ljava/nio/ByteBuffer;"

        public MethodId(String binaryClassName, String name, String jvmDescriptor) {
            this.binaryClassName = Objects.requireNonNull(binaryClassName, "binaryClassName");
            this.name = Objects.requireNonNull(name, "name");
            this.jvmDescriptor = Objects.requireNonNull(jvmDescriptor, "jvmDescriptor");
        }
    }

    public static final class Options {
        public final String depMode;           // "none" | "provided" | "fetched"
        public final String sliceMode;         // "method" | "class"
        public final int    timeoutSec;
        public final List<Path> extraClasspath; // jars + dirs for javac
        public final Path   workDir;           // required; JESS writes to workDir/classes

        public Options(String depMode, String sliceMode, int timeoutSec, List<Path> extraClasspath, Path workDir) {
            this.depMode = Objects.requireNonNull(depMode, "depMode");
            this.sliceMode = Objects.requireNonNull(sliceMode, "sliceMode");
            this.timeoutSec = timeoutSec;
            this.extraClasspath = List.copyOf(Objects.requireNonNull(extraClasspath, "extraClasspath"));
            this.workDir = Objects.requireNonNull(workDir, "workDir");
        }
    }

    public enum Status {
        OK,
        FAILED_PARSE,
        FAILED_RESOLVE,
        FAILED_COMPILE,
        MISSING_DEP,
        TIMEOUT,
        INTERNAL_ERROR,
        /** Compile returned success for some classes, but the target method (name+descriptor) wasn’t emitted with a Code body. */
        TARGET_METHOD_NOT_EMITTED
    }

    public static final class Result {
        public final Status status;

        /** Non-null when status==OK; may be non-null for some failures too. Points to workDir/classes. */
        public final Path   classesOutDir;

        /** Owner’s binary name (same as input). */
        public final String targetClass;

        /** Binary names (slash-separated, without ".class") for every file written under classesOutDir. */
        public final List<String> emittedClasses;

        /** If known, the relative ".class" file (under classesOutDir) that actually contains the target method. */
        public final String targetClassFile; // e.g. "org/apache/Foo$Bar.class" or null if not found

        /** True iff the target method (name+desc) is present in an emitted class with a Code body. */
        public final boolean targetHasCode;

        /** Whether JESS used stubs to satisfy missing deps. */
        public final boolean usedStubs;

        /** Echo of options.depMode or post-resolve mode string; null if unknown. */
        public final String  depsResolved;

        /** Elapsed time in ms. */
        public final long    elapsedMs;

        /** Human-readable detail for failures or special conditions. */
        public final String  notes;

        public Result(Status status,
                      Path classesOutDir,
                      String targetClass,
                      List<String> emittedClasses,
                      String targetClassFile,
                      boolean targetHasCode,
                      boolean usedStubs,
                      String depsResolved,
                      long elapsedMs,
                      String notes) {
            this.status = Objects.requireNonNull(status, "status");
            this.classesOutDir = classesOutDir;
            this.targetClass = targetClass;
            this.emittedClasses = List.copyOf(Objects.requireNonNull(emittedClasses, "emittedClasses"));
            this.targetClassFile = targetClassFile;
            this.targetHasCode = targetHasCode;
            this.usedStubs = usedStubs;
            this.depsResolved = depsResolved;
            this.elapsedMs = elapsedMs;
            this.notes = notes == null ? "" : notes;
        }
    }
}
