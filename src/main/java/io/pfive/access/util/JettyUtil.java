// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.util;

import com.conveyal.r5.analyst.WebMercatorExtents;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import io.pfive.access.authentication.UserIdentity;
import io.pfive.access.http.exception.AuthException;
import io.pfive.access.http.exception.InvalidRequestException;
import io.pfive.access.http.model.ApiModel;
import io.pfive.access.importer.Wgs84Bounds;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.locationtech.jts.geom.Envelope;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;

import static org.eclipse.jetty.http.HttpStatus.OK_200;

/// Static utility methods for working with Jetty handlers. Some of this probably exists in Jetty
/// already and I just haven't found/explored it. Response.writeError() for example.
public abstract class JettyUtil {

    // Create Object mapper adding module to handle Guava collection types like Multimaps.
    public static final ObjectMapper objectMapper = new ObjectMapper().registerModule(new GuavaModule());

    // Example of making a writer for one specific type. These are threadsafe.
    public static final ObjectWriter fileListWriter = objectMapper.writerFor(ApiModel.FileList.class);

    // We have three ways to respond with an error:
    // return respondUnauthorized("User identity not found.", response, callback);
    // if (userIdentity.isErr()) throw new AuthException(userIdentity.errorMessage());
    // return err("Identity not found", AUTHORIZATION); ret.get();

    public static boolean respondBadRequest (String message, Response response, Callback callback) {
        return respond(HttpStatus.BAD_REQUEST_400, message, response, callback);
    }

    public static boolean respondServerError (String message, Response response, Callback callback) {
        return respond(HttpStatus.INTERNAL_SERVER_ERROR_500, message, response, callback);
    }

    public static boolean respondUnauthorized (String message, Response response, Callback callback) {
        // TODO REDIRECT TO LOGIN PAGE
        return respond(HttpStatus.UNAUTHORIZED_401, message, response, callback);
    }

    // TODO merge respond and respondText; merge respondClientError and respondBadRequest

    public static boolean respond (int code, String message, Response response, Callback callback) {
        response.setStatus(code);
        response.write(true, byteWrap(message), callback);
        return true;
    }

    public static ByteBuffer byteWrap(String string) {
        return ByteBuffer.wrap(string.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean respondClientError (String message, Response response, Callback callback) {
        return respondText(400, message, response, callback);
    }

    public static boolean respondText (int code, String message, Response response, Callback callback) {
        response.setStatus(code);
        response.getHeaders().add(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_PLAIN_UTF_8.asString());
        response.write(true, ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)), callback);
        return true;
    }

    // Maybe this should return Ret<UserIdentity>. But then consider other cases like extracting JSON objects
    // from request bodies. These are so specific to HTTP handler situations, would they ever do anything else?
    public static UserIdentity extractUserIdentity (Request request) {
        UserIdentity userIdentity = (UserIdentity) request.getAttribute(UserIdentity.ATTRIBUTE);
        if (userIdentity == null) {
            throw new AuthException("Expected user identity to be present.");
        }
        return userIdentity;
    }

    /// Respond with an object serialized as JSON, with the proper content type header and a 200 OK
    /// response code.
    public static boolean respondJson (Object object, Response response, Callback callback) {
        try {
            response.setStatus(OK_200);
            response.getHeaders().add(HttpHeader.CONTENT_TYPE, MimeTypes.Type.APPLICATION_JSON_UTF_8.asString());
            byte[] jsonBytes = objectMapper.writeValueAsBytes(object);
            response.write(true, ByteBuffer.wrap(jsonBytes, 0, jsonBytes.length), callback);
            return true;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /// This can throw an HTTP-specific exception directly (rather than a Ret) because it's clearly
    /// not meant to be reusable outside the context of an HTTP server, as evidenced by its Request
    /// parameter. Instead of reading a tree, could use JettyUtil.objectMapper.readValue but that
    /// doesn't allow final fields, doesn't allow validating or transforming inputs before
    /// construction, and is less explicit.
    public static ObjectNode jsonObjectFromRequestBody (Request request) {
        try {
            ByteBuffer bbuf = Content.Source.asByteBuffer(request);
            JsonNode jsonNode = JettyUtil.objectMapper.readTree(bbuf.array());
            if (jsonNode instanceof ObjectNode objectNode) {
                return objectNode;
            } else {
                throw new InvalidRequestException("Request body should be parseable as a JSON object.");
            }
        } catch (Exception e) {
            throw new RuntimeException("Unable to decode request body.", e);
        }
    }

    public static <T> T objectFromRequestBody (Request request, Class<T> type) {
        try {
            ByteBuffer bbuf = Content.Source.asByteBuffer(request);
            T ret = JettyUtil.objectMapper.readValue(bbuf.array(), type);
            return ret;
        } catch (Exception e) {
            throw new InvalidRequestException("Request body should be parseable as a JSON representation of  " + type);
        }
    }

    /// Envelope is a convenient parameter type for the base-case method called by the overloaded
    /// ones, because it performs some validation and has built in methods for min/max values.
    /// Unfortunately WebMercatorExtents returns a plain, un-referenced envelope so we can't
    /// validate it as WGS84.
    /// NOTE these days we have a ReferencedEnvelope in every place we call this method.
    public static String getBboxHeaderValue (Envelope env) {
        // Default String.format will use comma as decimal separator in some locales, so specify US.
        String bbox = String.format(Locale.US, "%f,%f,%f,%f", env.getMinX(), env.getMinY(), env.getMaxX(), env.getMaxY());
        return bbox;
    }

    public static String getBboxHeaderValue (WebMercatorExtents extents) {
        Envelope env = extents.toWgsEnvelope();
        return getBboxHeaderValue(env);
    }

    public static String getBboxHeaderValue (Wgs84Bounds wgsBounds) {
        ReferencedEnvelope env = wgsBounds.toReferencedEnvelope();
        return getBboxHeaderValue(env);
    }

    public static final String BBOX_HEADER_KEY = "x-geopng-bbox";
    public static final String MIME_TYPE_PNG = "image/png";
    public static final String MIME_TYPE_MAPBOX_VECTOR = "application/vnd.mapbox-vector-tile";
    // Treat all map tiles and other images as immutable and fresh for one week.
    public static final String CACHE_CONTROL_IMMUTABLE_VALUE = "private, max-age=604800, immutable";
    // For data dependent on request body, which cannot be cached based on URL.
    // 'Note that no-cache does not mean "don't cache". no-cache allows caches to store a response but requires them
    // to revalidate it before reuse. If the sense of "don't cache" that you want is actually "don't store",
    // then no-store is the directive to use.'
    public static final String CACHE_CONTROL_NO_STORE = "private, no-store";

    // Ideally we want the parameter type to be very clearly representing WGS84 bounds (like Wgs84Bounds).
    public static void addGeoPngHeaders (Response response, Wgs84Bounds wgsBounds) {
        response.setStatus(OK_200);
        response.getHeaders().add(HttpHeader.CONTENT_TYPE, MIME_TYPE_PNG);
        response.getHeaders().add(BBOX_HEADER_KEY, getBboxHeaderValue(wgsBounds));
        response.getHeaders().add(HttpHeader.CACHE_CONTROL, CACHE_CONTROL_IMMUTABLE_VALUE);
    }

    public static void addMapboxVectorHeaders (Response response) {
        response.setStatus(OK_200);
        response.getHeaders().add(HttpHeader.CONTENT_TYPE, MIME_TYPE_MAPBOX_VECTOR);
        response.getHeaders().add(HttpHeader.CACHE_CONTROL, CACHE_CONTROL_IMMUTABLE_VALUE);
    }

    /// Wraps String.format to produce a UTF-8 ByteBuffer instead of a String.
    /// Note that String.format() is often much less efficient than string concatenation.
    public static ByteBuffer wrapFormat (String format, Object... params) {
        String s = String.format(format, params);
        return wrapString(s);
    }

    /// Convert a String to a UTF-8 ByteBuffer, typically for writing to an HTTP response body.
    public static ByteBuffer wrapString (String string) {
        // Alternatively: StandardCharsets.UTF_8.encode(s); but implementation looks slightly more complex.
        return ByteBuffer.wrap(string.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] jsonBytes (Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsBytes(map);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Could not convert map to JSON.", e);
        }
    }

    static final int KILO = 1024;
    static final int MEGA = KILO * KILO;
    static final int GIGA = MEGA * KILO;

    public static String memString (double bytes) {
        if (bytes >= GIGA) return String.format("%.1f GiB", bytes / GIGA);
        if (bytes >= MEGA) return String.format("%.1f MiB", bytes / MEGA);
        if (bytes >= KILO) return String.format("%.1f kiB", bytes / KILO);
        return bytes + " bytes";
    }

    /// EnumSet.copyOf(), Enum.value() and split() have weird edge cases for empty strings, empty
    /// sets, nulls, etc. So rather than repeating some complicated stream idiom, just spell out the
    /// conversion logic clearly and handle those cases.
    public static <T extends Enum<T>> EnumSet<T> enumSetFromCommaSeparatedString (String items, Class<T> enumClass) {
        if (items == null || items.isBlank()) {
            return EnumSet.noneOf(enumClass);
        }
        EnumSet<T> ret = EnumSet.noneOf(enumClass);
        for (String e : items.split(",")) {
            if (!e.isBlank()) {
                ret.add(Enum.valueOf(enumClass, e));
            }
        }
        return ret;
    }

    /// This helps avoid verbose try/catch clutter due to Java checked exceptions.
    /// @return true if the thread was interrupted, which allows caller to cleanly exit.
    public static boolean msecSleep (int msec) {
        try {
            Thread.sleep(msec);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

}
