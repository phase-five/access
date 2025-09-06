// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.http.handler;

import io.pfive.access.authentication.AuthMechanism;
import io.pfive.access.authentication.Token;
import io.pfive.access.authentication.UserIdentity;
import io.pfive.access.http.exception.AuthException;
import io.pfive.access.util.JettyUtil;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Fields.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;

/// Examines an incoming request and extracts a bearer token that identifies an authenticated user.
/// This token may be provided in three ways: header, cookie, or query parameter. If no token is
/// present, the handler will respond with a 401 unauthorized status. If a token is present but
/// doesn't correspond to a known authenticated user, an exception is thrown.
public class TokenExtractHandler extends Handler.Wrapper {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthMechanism authMechanism;

    public TokenExtractHandler (AuthMechanism authMechanism, Handler handler) {
        super(handler);
        this.authMechanism = authMechanism;
    }

    @Override
    public boolean handle (Request request, Response response, Callback callback) throws Exception {
        // First, check Authorization header.
        String authString = request.getHeaders().get(HttpHeader.AUTHORIZATION);

        // Second, check token cookie.
        if (authString == null) {
            for (HttpCookie cookie : Request.getCookies(request)) {
                if ("token".equals(cookie.getName())) {
                    authString = cookie.getValue();
                }
            }
        }

        // Third, check token query parameter.
        if (authString == null) {
            Fields fields = Request.extractQueryParameters(request);
            Field authField = fields.get("auth");
            if (authField == null) {
                // TODO redirect to login page
                return JettyUtil.respondUnauthorized("Please supply auth header or query parameter.", response, callback);
            }
            authString = authField.getValue();
        }

        // Validate and return.
        if (authString == null || authString.isEmpty()) {
            return JettyUtil.respondUnauthorized("Auth header or query parameter was empty.", response, callback);
        }
        authString = authString.trim();
        if (authString.startsWith(BEARER_PREFIX)) {
            authString = authString.substring(BEARER_PREFIX.length()).trim();
        }
        Token token = Token.fromString(authString);
        UserIdentity userIdentity = authMechanism.userIdentityForToken(token).getOrThrow(AuthException::new);
        request.setAttribute(UserIdentity.ATTRIBUTE, userIdentity);
        return super.handle(request, response, callback);
    }

}
