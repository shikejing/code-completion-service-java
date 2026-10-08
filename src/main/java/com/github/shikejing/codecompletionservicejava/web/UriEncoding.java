package com.github.shikejing.codecompletionservicejava.web;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Path-safe encoding of document URIs for HTTP path variables. Encoded URIs
 * contain only {@code [A-Za-z0-9_-]} so characters like {@code /} and
 * {@code :} never collide with Tomcat's URL path rules.
 */
public final class UriEncoding {

    private UriEncoding() {}

    public static String encode(String uri) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(uri.getBytes(StandardCharsets.UTF_8));
    }

    public static String decode(String encoded) {
        try {
            return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notEncoded) {
            // tolerate raw URIs from direct callers
            return encoded;
        }
    }
}
