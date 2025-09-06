// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.authentication;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.random.RandomGenerator;

/// A token included by a user in requests to show that they are authorized and authenticated.
/// A token needs to be representable in a way that can be included in HTTP headers but also as URL
/// query parameters, because some client libraries allow easily setting query parameters but not
/// HTTP headers in requests. There is also the Unicode problem (ASCII vs. UTF8 vs. String's
/// internal UTF16 characters). Rather than encoding random integers or bytes into a text token
/// we just generate the characters directly. Elsewhere (constructing UserHashRecords) we export
/// and import hash codes and salt using Java's stock Base64 encoder. We may want to use such an
/// approach here for uniformity.
///
/// ## URL-encoded Base64:
/// - https://base64.guru/standards/base64url
/// - https://developer.mozilla.org/en-US/docs/Glossary/Base64
///
/// Token instances be used as a key (they define semantic hashCode and equals).
public class Token {

    // "SecureRandom objects are safe for use by multiple concurrent threads." - Javadoc
    public static final RandomGenerator randomGenerator = new SecureRandom();

    private static final int N_CHARS = 32;

    private final char[] chars;

    public Token () {
        chars = new char[N_CHARS];
        for (int i = 0; i < N_CHARS; i++) {
            chars[i] = generateOneCharUrlBase64();
        }
    }

    private Token(char[] chars) {
        if (chars == null || chars.length != N_CHARS) {
            throw new IllegalArgumentException(
                String.format("Expected %d chars, received %s", N_CHARS, chars == null ? 0 : chars.length)
            );
        }
        for (char c : chars) {
            if (!isValidUrlBase64(c)) {
                throw new IllegalArgumentException("Character is not valid in URL Base64: " + c);
            }
        }
        this.chars = chars;
    }

    private char generateOneCharUrlBase64 () {
        char c = (char) randomGenerator.nextInt(0, 64);
        if (c < 26) {
            c += 'A';
        } else if (c < 52) {
            c -= 26;
            c += 'a';
        } else if (c < 62) {
            c -= 52;
            c += '0';
        } else if (c == 62) {
            c = '-';
        } else {
            c = '_';
        }
        return c;
    }

    private boolean isValidUrlBase64 (char c) {
        if (c >= '0' && c <= '9') return true;
        if (c >= 'A' && c <= 'Z') return true;
        if (c >= 'a' && c <= 'z') return true;
        if (c == '_' || c == '-') return true;
        return false;
    }

    public String asString () {
        return String.valueOf(chars);
    }

    public static Token fromString (String string) {
        char[] chars = string.toCharArray();
        return new Token(chars);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Token token = (Token) o;
        return Arrays.equals(chars, token.chars);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(chars);
    }

    public static String shortRandomHostname() {
        final int N = 16;
        char[] chars = new char[N];
        for (int i = 0; i < N; i++) {
            chars[i] = (char) (randomGenerator.nextInt(0, 26) + 'a');
        }
        chars[3] = '-';
        chars[7] = '-';
        chars[11] = '-';
        return new String(chars);
    }

}
