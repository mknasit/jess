package de.upb.sse.jess.configuration;

import lombok.*;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class JessConfiguration {
    private boolean exitOnCompilationFail = false;
    private boolean exitOnParsingFail = false;
    private boolean looseSignatureMatching = true;
    private boolean keepAsteriskImports = true;
    private boolean failOnAmbiguity = true;
    private boolean disableStubbing = false;
    public enum StubberKind { JESS, SPOON }

    private StubberKind stubberKind = StubberKind.SPOON ;

    private String targetVersion = null;

    public JessConfiguration(boolean exitOnCompilationFail, boolean exitOnParsingFail, boolean looseSignatureMatching, boolean keepAsteriskImports, boolean failOnAmbiguity, boolean disableStubbing,String targetVersion) {
        this.exitOnCompilationFail = exitOnCompilationFail;
        this.exitOnParsingFail = exitOnParsingFail;
        this.looseSignatureMatching = looseSignatureMatching;
        this.keepAsteriskImports = keepAsteriskImports;
        this.failOnAmbiguity = failOnAmbiguity;
        this.disableStubbing = disableStubbing;
        this.targetVersion = targetVersion;

    }
    // Legacy 6-arg ctor kept for compatibility with old callers (e.g., Main)
    public JessConfiguration(boolean exitOnCompilationFail,
                             boolean exitOnParsingFail,
                             boolean looseSignatureMatching,
                             boolean keepAsteriskImports,
                             boolean failOnAmbiguity,
                             boolean disableStubbing) {
        // delegate to your 7-arg ctor; keep Spoon as default stubberKind via the field
        this(exitOnCompilationFail, exitOnParsingFail, looseSignatureMatching,
                keepAsteriskImports, failOnAmbiguity, disableStubbing, null); // targetVersion = null
    }


    public StubberKind getStubberKind() { return stubberKind; }
    public void setStubberKind(StubberKind kind) { this.stubberKind = kind; }

}
