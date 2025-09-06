// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.http.handler;

import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.transit.TransportNetwork;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import io.pfive.access.authentication.UserIdentity;
import io.pfive.access.http.routing.RoutingDataCache;
import io.pfive.access.util.JettyUtil;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;

import static com.conveyal.r5.streets.VertexStore.floatingDegreesToFixed;
import static io.pfive.access.util.JettyUtil.CACHE_CONTROL_NO_STORE;
import static org.eclipse.jetty.http.HttpStatus.OK_200;

/// Allows fetching a representation of all street edges and nodes in a limited area. Intended for a
/// street editing UI. The vector tile layers contain similar information, but they do not
/// sufficiently preserve the topological details, node and intersection identity, traversal
/// permissions, and so on needed when editing streets. Expects to be situated at URL path
/// /streets/[networkId]/
public class StreetEdgeHandler extends Handler.Abstract {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    record RequestBody (String networkId, double west, double east, double south, double north) {
        public Envelope toFixedEnvelope () {
            return new Envelope(
                  floatingDegreesToFixed(west),
                  floatingDegreesToFixed(east),
                  floatingDegreesToFixed(south),
                  floatingDegreesToFixed(north)
            );
        }
    }

    @Override
    public boolean handle (Request request, Response response, Callback callback) throws Exception {
        if (!request.getMethod().equals("POST")) {
            return JettyUtil.respondClientError("Method must be POST.", response, callback);
        }
        final UserIdentity user = JettyUtil.extractUserIdentity(request);
        RequestBody body = JettyUtil.objectFromRequestBody(request, RequestBody.class);
        final TransportNetwork network = RoutingDataCache.getNetwork(body.networkId, user);
        final StreetLayer streets = network.streetLayer;

        JsonFactory factory = new JsonFactory();
        // Unfortunately BAOS is going to result in multiple copies of the data. There must be a better way.
        // Maybe ByteBufferOutputStream?
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JsonGenerator generator = factory.createGenerator(baos);
        generator.writeStartObject();

        // I think this index contains only the forward edge in each pair (not the pair index numbers).
        // Really we want to edit and update edge pairs, not edges. So work in terms of pairs.
        // This is yet another copy just because our collection and buffer objects insist on making copies.

        int[] edgeIds = streets.spatialIndex.query(body.toFixedEnvelope()).toArray();
        EdgeStore.Edge edge = streets.edgeStore.getCursor();
        generator.writeArrayFieldStart("pairs");
        for (int eid : edgeIds) {
            edge.seek(eid);
            int pairId = eid / 2;
            generator.writeStartObject();
            generator.writeNumberField("id", pairId);
            generator.writeNumberField("from", edge.getFromVertex());
            generator.writeNumberField("to", edge.getToVertex());
            LineString ls = edge.getGeometry();
            CoordinateSequence cs = ls.getCoordinateSequence();
            generator.writeArrayFieldStart("geom");
            for (int i = 0; i < cs.size(); i++) {
                generator.writeStartArray();
                generator.writeNumber(cs.getOrdinate(i, 0));
                generator.writeNumber(cs.getOrdinate(i, 1));
                generator.writeEndArray(); // End individual coordinate pair.
            }
            generator.writeEndArray(); // End array of points.
            generator.writeEndObject(); // End object for a single edge pair.
        }
        // End array of edge pairs, end outer object, and flush buffer.
        generator.writeEndArray();
        generator.writeEndObject();
        generator.close();

        // Vertices don't have much information except whether they have traffic signals.
        // Even if we use that info some day, it's not present on most vertices. So we could have a separate
        // object or array containing only those vertices that have any flags.

        if (baos.size() == 0) {
            return JettyUtil.respondServerError("No street edge JSON generated.", response, callback);
        }
        response.setStatus(OK_200);
        response.getHeaders().add(HttpHeader.CONTENT_TYPE, MimeTypes.Type.APPLICATION_JSON_UTF_8.asString());
        response.getHeaders().add(HttpHeader.CACHE_CONTROL, CACHE_CONTROL_NO_STORE);
        // When logging timing info, only this final write call seems to take much time at all.
        // Maybe due to gzip compression?
        response.write(true, ByteBuffer.wrap(baos.toByteArray()), callback);
        return true;
    }


}
