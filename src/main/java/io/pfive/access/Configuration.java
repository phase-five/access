// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access;

import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;

/// Yes, this is global state and it's intentional.
/// Configuration options are readily available everywhere.
/// Will probably need to be instantiated non-globally if ever used in testing.
public abstract class Configuration {
    public static Properties properties = new Properties();
    public static final int HTTP_PORT;
    public static final int HTTP_SECURE_PORT;
    public static final boolean ENABLE_SNI;
    public static final boolean ENABLE_TLS_AUTH;
    public static final boolean BATCH_SMALL_AREA_DEBUG;

    // TODO check for unused keys (due to misspellings)
    static {
        try (FileReader reader = new FileReader("conf/conf.properties")) {
            properties.load(reader);
            HTTP_PORT = intVal("http-port");
            HTTP_SECURE_PORT = intVal("http-secure-port");
            ENABLE_SNI = boolVal("enable-sni");
            ENABLE_TLS_AUTH = boolVal("enable-tls-auth");
            BATCH_SMALL_AREA_DEBUG = boolVal("batch-small-area-debug");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String stringVal(String key) {
        String val = properties.getProperty(key);
        if (val == null) throw new RuntimeException("Missing configuration key: " + key);
        return val;
    }

    private static int intVal(String key) {
        String val = stringVal(key);
        try {
            return Integer.parseInt(stringVal(key));
        } catch (NumberFormatException e) {
            var message = String.format("Cannot parse value '%s' for configuration key '%s' as integer.", val, key);
            throw new RuntimeException(message, e);
        }
    }

    private static boolean boolVal(String key) {
        String val = stringVal(key);
        if (val.equalsIgnoreCase("true")) return true;
        if (val.equalsIgnoreCase("yes")) return true;
        if (val.equalsIgnoreCase("false")) return false;
        if (val.equalsIgnoreCase("no")) return false;
        var message = String.format("Boolean value '%s' for configuration key '%s' must be true/false/yes/no.", val, key);
        throw new RuntimeException(message);
    }


}
