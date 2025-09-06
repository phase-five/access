// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access;

import io.pfive.access.authentication.AuthMechanism;
import io.pfive.access.background.BackgroundItemTracker;
import io.pfive.access.batch.BatchJobManager;
import io.pfive.access.batch.BatchJobProcessor;
import io.pfive.access.http.handler.BatchResultsRasterHandler;
import io.pfive.access.http.handler.DownloadHandler;
import io.pfive.access.http.handler.EventSourceHandler;
import io.pfive.access.http.handler.ExceptionHandler;
import io.pfive.access.http.handler.StoredFileHandler;
import io.pfive.access.http.handler.GeoPackageImportHandler;
import io.pfive.access.http.handler.GridPngHandler;
import io.pfive.access.http.handler.GtfsHandler;
import io.pfive.access.http.handler.NetworkBuildHandler;
import io.pfive.access.http.handler.OneToManyHandler;
import io.pfive.access.http.handler.StreetEdgeHandler;
import io.pfive.access.http.handler.TokenExtractHandler;
import io.pfive.access.http.handler.TokenGenerateHandler;
import io.pfive.access.http.handler.UploadHandler;
import io.pfive.access.http.handler.VectorTileHandler;
import io.pfive.access.sparse.EgressHandler;
import io.pfive.access.util.SystemStatus;
import nl.altindag.ssl.SSLFactory;
import nl.altindag.ssl.jetty.util.JettySslUtils;
import nl.altindag.ssl.pem.util.PemUtils;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.handler.SecuredRedirectHandler;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static io.pfive.access.util.JettyUtil.memString;
import static io.pfive.access.util.JettyUtil.msecSleep;

/// This main method starts a Jetty HTTP server that serves both an API and the web UI files.
/// The HTTP/1.1 spec recommends (but does not mandate) a limit of two connections per server
/// or proxy. In Chrome, the default value is 6 connections per domain. SSEvents eats up one
/// of these connections, and this also limits the number of concurrent file uploads.
/// The jetty-http3-server connector seems to have a completely different programming API.
/// HTTP/2 (h2) can multiplex streams. It has a limit on the number of streams but it is high
/// at around 250. For now we are not supporting HTTP3, but HTTP2 is straightforward to set
/// up using the high-level server API described at:
/// https://jetty.org/docs/jetty/12/programming-guide/server/http.html#connector-protocol-http2
public class Main {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    // The resource handler or the wrapping context handler do not appear to tolerate symbolic
    // links or relative navigation with dots in the paths. See AllowSymLinkAliasChecker.
    private static final String PUBLIC_FILES_PATH = "static/public";
    private static final String PRIVATE_FILES_PATH = "static/private";

    public static void main (String[] args) throws Exception {
        // Debugging configuration and deployment can be easier when we know the current directory.
        Path currentDir = FileSystems.getDefault().getPath("").toAbsolutePath();
        LOG.info("Current working directory is: {}", currentDir.toString());

        // Load the TLS certificate and key from plain text files as created by OpenSSL or shipped by certificate
        // authorities. Avoids using the bizarrely complex Java "enterprise application server" keystore system.
        // TODO potentially write smaller util class inspired by JettySslUtils and SSLFactory.
        SSLFactory sslFactory = SSLFactory.builder()
                .withIdentityMaterial(PemUtils.loadIdentityMaterial(
                    Paths.get("conf/tls.crt"),
                    Paths.get("conf/tls.key")
                ))
                .withDefaultTrustMaterial()
                .build();
        SslContextFactory.Server sslContextFactory = JettySslUtils.forServer(sslFactory);

        Server server = new Server();
        // TODO if (Configuration.ENABLE_TLS_AUTH) { }
        {
            HttpConfiguration httpConf = new HttpConfiguration();
            // Tolerate use of certificates with no "common name" for testing purposes.
            SecureRequestCustomizer secureRequestCustomizer = new SecureRequestCustomizer();
            secureRequestCustomizer.setSniHostCheck(Configuration.ENABLE_SNI);
            secureRequestCustomizer.setSniRequired(Configuration.ENABLE_SNI);
            httpConf.addCustomizer(secureRequestCustomizer);
            // The securePort is used by the SecuredRedirectHandler in creating redirects.
            httpConf.setSecurePort(Configuration.HTTP_SECURE_PORT);

            // Create separate ConnectionFactories for HTTP/1.1 and HTTP/2 (h2 not h2c cleartext).
            HttpConnectionFactory http1 = new HttpConnectionFactory(httpConf);
            HTTP2ServerConnectionFactory http2 = new HTTP2ServerConnectionFactory(httpConf);

            // ALPN negotiates HTTP/2 over TLS. Set default protocol in case there is no negotiation.
            ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
            alpn.setDefaultProtocol(http1.getProtocol());

            // TLS encrypted connector. All traffic should pass through here.
            // Distributed computation nodes may eventually use cleartext HTTP2 over a local network.
            SslConnectionFactory tls = new SslConnectionFactory(sslContextFactory, alpn.getProtocol());
            ServerConnector secureConnector = new ServerConnector(server, tls, alpn, http2, http1);
            secureConnector.setPort(Configuration.HTTP_SECURE_PORT);
            server.addConnector(secureConnector);

            // Unencrypted connector should only intercept and redirect requests to secure TLS.
            ServerConnector insecureConnector = new ServerConnector(server, http1);
            insecureConnector.setPort(Configuration.HTTP_PORT);
            server.addConnector(insecureConnector);
        }

        // Instantiate components shared between multiple HTTP handlers.
        AuthMechanism authMechanism = new AuthMechanism();
        BackgroundItemTracker background = new BackgroundItemTracker();
        BatchJobManager batchJobManager = new BatchJobManager(background);

        // Combine several handlers, each one on a different URL path. Passing them in the constructor of the
        // ContextHandlerCollection rather than adding them later creates a non-dynamic (unmodifiable) collection.
        ContextHandlerCollection contexts = new ContextHandlerCollection(
            new ContextHandler(createPrivateResourceHandler(), "/static"),
            new ContextHandler(new UploadHandler(background), "/upload"),
            new ContextHandler(new NetworkBuildHandler(background), "/netbuild"),
            new ContextHandler(new GeoPackageImportHandler(background), "/rasterize"),
            new ContextHandler(new EgressHandler(background), "/egress"),
            new ContextHandler(new StoredFileHandler(), "/files"),
            new ContextHandler(new GtfsHandler(), "/gtfs"),
            new ContextHandler(new GridPngHandler(), "/grid/png"),
            // Batch results handler and file download handler are extremely similar to grid PNG endpoint.
            // These could potentially be instances of AuxFileHandler with different name suffixes.
            // But the results suffixes need to be dynamically generated or supplied from query params.
            new ContextHandler(new BatchResultsRasterHandler(), "/results"),
            new ContextHandler(new DownloadHandler(), "/download"),
            new ContextHandler(new OneToManyHandler(batchJobManager), "/otm"),
            new ContextHandler(new VectorTileHandler(), "/mvt"),
            new ContextHandler(new StreetEdgeHandler(), "/streets"),
            new ContextHandler(new EventSourceHandler(), "/events")
        );

        // Wrap the context handlers in a mechanism that extracts bearer tokens and looks up corresponding users.
        Handler tokenExtractHandler = new TokenExtractHandler(authMechanism, contexts);

        // Note this could probably be combined with tokenExtractHandler to ensure only token creation is outside auth.
        Handler tokenGenerateHandler = new TokenGenerateHandler(authMechanism, tokenExtractHandler);

        // Serve static files that do not require authentication, mainly the login page.
        Handler publicResourceHandler = createPublicResourceHandler(tokenGenerateHandler);

        // Wrap the authentication handlers in an exception handler to generate custom error pages.
        // Note that all the authentication and exception handling could be combined into one handler.
        Handler exceptionHandler = new ExceptionHandler(publicResourceHandler);

        // Wrap everything in a gzip handler to compress responses when requested by the client.
        // The gzip handler is the top level so all static and dynamic content handlers will be subject to compression.
        // This can be tested by adding -H "Accept-Encoding: gzip" to a curl or ab command.
        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.setMinGzipSize(1024);
        gzipHandler.addIncludedMethods("POST");
        gzipHandler.setHandler(exceptionHandler);

        // Redirect all HTTP requests to HTTPS. Eventually we may want to just turn off insecure HTTP entirely.
        SecuredRedirectHandler securedRedirectHandler = new SecuredRedirectHandler(gzipHandler);
        server.setHandler(securedRedirectHandler);
        // server.setHandler(gzipHandler);
        server.start();

        // Send system status updates, which also serve as an SSE heartbeat to prevent disconnect.
        // TODO Use a ScheduledExecutor. Replace busy-wait with periodic executor task.
        // TODO Include distributed RPC nodes, CPU count, and load.
        new Thread(() -> {
            while (true) {
                SystemStatus status = new SystemStatus();
                EventSourceHandler.sendEventToAllUsers("system", shortStatusString(status));
                if (msecSleep(5000)) return;
            }
        }).start();

        // Consume and compute batch job tasks.
        // TODO launch this and RPC server, then join all threads (RPC server and web server).
        new Thread(new BatchJobProcessor(batchJobManager)).start();

        server.join();
    }

    private static String shortStatusString (SystemStatus status) {
        return String.format("JVM %s of %s, %d%% CPU load, %s clients.",
              memString(status.jvmUsedMemory),
              memString(status.jvmMaxMemory),
              (int)(status.jvmLoad * 100),
              EventSourceHandler.eventSources.size()
        );
    }

    private static String longStatusString (SystemStatus status) {
        return String.format("[JVM %s / %s load %d%%] [OS %s / %s load %d%%] [CPU %.1f / %d] %d clients",
              memString(status.jvmUsedMemory), memString(status.jvmMaxMemory), (int)(status.jvmLoad * 100),
              memString(status.osUsedMemory), memString(status.osTotalMemory), (int)(status.osLoad * 100),
              status.usedCpuCores, status.totalCpuCores,
              EventSourceHandler.eventSources.size()
        );
    }

    /// Create a ResourceHandler instance which serves static files. This is used to serve the web
    /// UI, and could also be used to serve batch analysis results files if placed behind auth.
    /// From [Jetty docs](https://eclipse.dev/jetty/documentation/jetty-12/programming-guide/index.html#pg-server-http-handler-use-resource):
    /// > "The number of features supported and the efficiency in sending static content are on the
    /// > same level as those of common front-end servers used to serve static content such as Nginx
    /// > or Apache. Therefore, the traditional architecture where Nginx/Apache was the front-end
    /// > server used only to send static content and Jetty was the back end server used only to send
    /// > dynamic content is somehow obsolete as Jetty can perform efficiently both tasks."
    private static Handler createPrivateResourceHandler () {
        ResourceHandler handler = new ResourceHandler();
        handler.setBaseResource(ResourceFactory.of(handler).newResource(PRIVATE_FILES_PATH));
        handler.setDirAllowed(true); // Allow directory listings.
        handler.setWelcomeFiles(List.of("index.html"));
        handler.setAcceptRanges(true);
        return handler;
    }

    /// It's tempting to add a /login conditional in the auth handler that is only capable of serving a single
    /// unauthenticated page, but the logic in resourceHandler seems complex enough that I hesitate to bypass it.
    /// Maybe if we've got a Content.Sink method or load-file-to-bytebuffer method (fileChannel.map) to use.
    /// But we want to be able to serve up things like images that are not embedded in the html, handle preflight
    /// requests etc.
    private static Handler createPublicResourceHandler (Handler nextHandler) {
        ResourceHandler handler = new ResourceHandler(nextHandler);
        handler.setBaseResource(ResourceFactory.of(handler).newResource(PUBLIC_FILES_PATH));
        handler.setDirAllowed(false);
        handler.setWelcomeFiles(List.of("login.html"));
        return handler;
    }

}
