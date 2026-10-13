package com.panfan.hivelineage.v2.lexical;

import com.panfan.hivelineage.v2.dialect.DialectProfile;
import java.util.Objects;

public final class LexicalOptions {
    private final DialectProfile dialectProfile;
    private final boolean preserveTrivia;

    public LexicalOptions(DialectProfile dialectProfile, boolean preserveTrivia) {
        this.dialectProfile = Objects.requireNonNull(dialectProfile, "dialectProfile");
        this.preserveTrivia = preserveTrivia;
    }

    public DialectProfile getDialectProfile() {
        return dialectProfile;
    }

    public boolean isPreserveTrivia() {
        return preserveTrivia;
    }
}
