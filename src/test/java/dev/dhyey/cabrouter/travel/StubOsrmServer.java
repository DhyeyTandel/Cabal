package dev.dhyey.cabrouter.travel;

import com.sun.net.httpserver.HttpServer;
import dev.dhyey.cabrouter.routing.GeoPoint;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fake OSRM table endpoint. It answers with a deterministic, deliberately
 * asymmetric function of the coordinates, so tests can check every stitched entry.
 */
final class StubOsrmServer implements AutoCloseable {

    private final HttpServer server;
    private final int maxTableSize;
    final AtomicInteger requests = new AtomicInteger();
    /** Pairs (by latitude) the stub pretends it cannot route. */
    final List<double[]> unroutable = new ArrayList<>();

    StubOsrmServer(int maxTableSize) throws IOException {
        this.maxTableSize = maxTableSize;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/table/v1/driving/", exchange -> {
            requests.incrementAndGet();
            String body = respond(exchange.getRequestURI().getPath(), exchange.getRequestURI().getQuery());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Metres from a to b: 1000 per 0.01 degree of latitude, plus 300 when heading north. */
    static double metres(GeoPoint a, GeoPoint b) {
        if (a.equals(b)) {
            return 0;
        }
        return Math.abs(b.lat() - a.lat()) * 100_000 + Math.abs(b.lng() - a.lng()) * 100_000
                + (b.lat() > a.lat() ? 300 : 0);
    }

    /** Seconds: one second per 10 metres. */
    static double seconds(GeoPoint a, GeoPoint b) {
        return metres(a, b) / 10;
    }

    private String respond(String path, String query) {
        String[] coords = path.substring("/table/v1/driving/".length()).split(";");
        if (coords.length > maxTableSize) {
            return "{\"code\":\"TooBig\",\"message\":\"Too many table coordinates\"}";
        }
        List<GeoPoint> points = new ArrayList<>();
        for (String c : coords) {
            String[] ll = c.split(",");
            points.add(new GeoPoint(Double.parseDouble(ll[1]), Double.parseDouble(ll[0])));
        }
        List<Integer> sources = indices(query, "sources", points.size());
        List<Integer> destinations = indices(query, "destinations", points.size());

        StringBuilder durations = new StringBuilder("[");
        StringBuilder distances = new StringBuilder("[");
        for (int r = 0; r < sources.size(); r++) {
            durations.append(r == 0 ? "[" : ",[");
            distances.append(r == 0 ? "[" : ",[");
            for (int c = 0; c < destinations.size(); c++) {
                GeoPoint from = points.get(sources.get(r));
                GeoPoint to = points.get(destinations.get(c));
                boolean gap = unroutable.stream().anyMatch(u -> near(from.lat(), u[0]) && near(to.lat(), u[1]));
                durations.append(c == 0 ? "" : ",").append(gap ? "null" : fmt(seconds(from, to)));
                distances.append(c == 0 ? "" : ",").append(gap ? "null" : fmt(metres(from, to)));
            }
            durations.append("]");
            distances.append("]");
        }
        return "{\"code\":\"Ok\",\"durations\":" + durations + "],\"distances\":" + distances
                + "],\"sources\":[],\"destinations\":[]}";
    }

    private static boolean near(double x, double y) {
        return Math.abs(x - y) < 1e-6;
    }

    private static List<Integer> indices(String query, String name, int n) {
        List<Integer> out = new ArrayList<>();
        if (query != null) {
            for (String part : query.split("&")) {
                if (part.startsWith(name + "=")) {
                    for (String i : part.substring(name.length() + 1).split(";")) {
                        out.add(Integer.parseInt(i));
                    }
                    return out;
                }
            }
        }
        for (int i = 0; i < n; i++) {
            out.add(i);
        }
        return out;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
