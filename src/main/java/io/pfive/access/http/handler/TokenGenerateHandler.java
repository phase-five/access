// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.http.handler;


import io.pfive.access.authentication.AuthMechanism;
import io.pfive.access.authentication.Token;
import io.pfive.access.http.exception.AuthException;
import io.pfive.access.http.exception.InvalidRequestException;
import io.pfive.access.util.JettyUtil;
import io.pfive.access.util.Ret;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class TokenGenerateHandler extends Handler.Wrapper {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final String BASIC_PREFIX = "Basic ";
    public static final String TOKEN_COOKIE_NAME = "token";
    private static final int TOKEN_DURATION_SECONDS = 60 * 60 * 24 * 2;

    private final AuthMechanism authMechanism;

    public TokenGenerateHandler (AuthMechanism authMechanism, Handler wrapped) {
        super(wrapped);
        this.authMechanism = authMechanism;
    }

    @Override
    public boolean handle (Request request, Response response, Callback callback) throws Exception {
        // Only handle requests to "/token", everything else passes through to the authenticated endpoints.
        if (!request.getHttpURI().getPath().equals("/token")) {
            return super.handle(request, response, callback);
        }
        // Read the username and password out of the body, which are less likely to be recorded or cached somewhere
        // than query parameters. TODO Content.Source.asByteBuffer(request); but only the async methods have size limits.
        // byte[] bytes = Streams.readAllLimited(Request.asInputStream(request), 16);
        // LOG.info(Strings.fromByteArray(bytes));
        String[] parts = fromBase64(extractHeader(request, HttpHeader.AUTHORIZATION, BASIC_PREFIX)
                .getOrThrow(InvalidRequestException::new)).split(":");
        if (parts.length != 2) {
            // This requires a "return false" but probably is cleaner and could be wrapped.
            // It produces HTML output so may be less suitable for a machine-readable API.
            // Response.writeError(request, response, callback, HttpStatus.UNAUTHORIZED_401, "Header value should be username:password.");
            throw new AuthException("Header value should be username:password.");
        }
        // TODO sanitize to printable 7-bit
        // TODO read from body not header, or at least allow this?
        String email = parts[0];
        String password = parts[1];
        Token token = authMechanism.authenticateUser(email, password).getOrThrow(AuthException::new);
        HttpCookie tokenCookie = HttpCookie.build(TOKEN_COOKIE_NAME, token.asString())
                .httpOnly(true)
                .secure(true)
                .sameSite(HttpCookie.SameSite.STRICT)
                .maxAge(TOKEN_DURATION_SECONDS)
                .build();
        Response.addCookie(response, tokenCookie);
        return JettyUtil.respondText(HttpStatus.OK_200, token.asString(), response, callback);
    }

    /// At first glance this method is only used in the context of processing HTTP requests, so no
    /// there would be no need to use Ret (Optional/Either return type) and we could just
    /// immediately throw an HttpServerException. But consider the case where you want to examine
    /// several headers in turn. We might want to fall back on different headers or different
    /// behaviors if one is missing. Checking the headers.contains() in the caller breaks the
    /// encapsulation of error checking within this method.
    /// TODO reuse for both TokenExtractHandler and TokenGenerateHandler
    public Ret<String> extractHeader (Request request, HttpHeader header, String prefix) {
        // HttpFields return value may be empty but is never null.
        HttpFields headers = request.getHeaders();
        String value = headers.get(header);
        if (value == null) {
            return Ret.err("Header not present: " + header.asString());
        }
        value = value.trim();
        if (!value.startsWith(prefix)) { // Should this be case-insensitive?
            // Hmm maybe these should not be "ok" vs "err" but "some" vs "none" with optional message.
            return Ret.err("Prefix not present in header value: " + prefix);
        }
        return Ret.ok(value.substring(BASIC_PREFIX.length()));
    }

    /// This method is threadsafe.
    /// "Instances of Base64.Encoder class are safe for use by multiple concurrent threads."
    /// "Instances of Base64.Decoder class are safe for use by multiple concurrent threads."
    /// (from Javadoc on those classes)
    private static String fromBase64 (String base64) {
        if (base64 == null) throw new IllegalArgumentException();
        return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
    }

}
