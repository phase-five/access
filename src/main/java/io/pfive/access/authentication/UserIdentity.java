// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.authentication;

import java.util.StringJoiner;

public class UserIdentity {

    /// The key used by the HTTP server to associate UserIdentity instances with HTTP requests,
    /// allowing downstream HTTP handlers to see the identity information.
    public static final String ATTRIBUTE = "user_identity";

    public final String name;
    public final String email;
    public final String organization;

    public UserIdentity(String name, String email, String organization) {
        this.name = name;
        this.email = email;
        this.organization = organization;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", UserIdentity.class.getSimpleName() + "[", "]")
                .add("name='" + name + "'")
                .add("email='" + email + "'")
                .add("organization='" + organization + "'")
                .toString();
    }
}
