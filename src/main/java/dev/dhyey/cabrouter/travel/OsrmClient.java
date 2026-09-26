package dev.dhyey.cabrouter.travel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.dhyey.cabrouter.routing.GeoPoint;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls the OSRM table service, which returns road distance and free-flow drive time
 * between every pair of points.
 *
 * <p>OSRM servers cap the number of coordinates per table request (100 on the public
 * demo server). Larger shifts are split into blocks: for each pair of blocks, one
 * request carries both blocks and asks for block A as sources and block B as
 * destinations, and the results are stitched into the full matrix.
 */
public class OsrmClient {

    private final RestClient rest;
    private final String baseUrl;
    private final int maxTableSize;

    public OsrmClient(String baseUrl, int maxTableSize, Duration timeout) {
        if (maxTableSize < 2) {
            throw new IllegalArgumentException("max table size must be at least 2");
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        this.rest = RestClient.builder().requestFactory(factory).build();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.maxTableSize = maxTableSize;
    }

    /**
     * Distances in metres and durations in seconds. An entry is null when OSRM cannot
     * route between the pair (for example, a point snapped onto an island).
     */
    public record Matrices(Double[][] metres, Double[][] seconds) {
    }

    public Matrices table(List<GeoPoint> points) {
        int n = points.size();
        Double[][] metres = new Double[n][n];
        Double[][] seconds = new Double[n][n];
        if (n <= maxTableSize) {
            fill(points, range(0, n), range(0, n), metres, seconds);
            return new Matrices(metres, seconds);
        }
        int block = maxTableSize / 2;
        for (int a = 0; a < n; a += block) {
            for (int b = 0; b < n; b += block) {
                fill(points, range(a, Math.min(n, a + block)), range(b, Math.min(n, b + block)), metres, seconds);
            }
        }
        return new Matrices(metres, seconds);
    }

    /** One request: travel from every point in {@code from} to every point in {@code to}. */
    private void fill(List<GeoPoint> points, List<Integer> from, List<Integer> to,
                      Double[][] metres, Double[][] seconds) {
        List<Integer> coords = new ArrayList<>(from);
        for (int j : to) {
            if (!coords.contains(j)) {
                coords.add(j);
            }
        }
        StringJoiner path = new StringJoiner(";");
        for (int i : coords) {
            GeoPoint p = points.get(i);
            path.add(String.format(Locale.ROOT, "%.6f,%.6f", p.lng(), p.lat())); // OSRM wants lng,lat
        }
        StringJoiner sources = new StringJoiner(";");
        from.forEach(i -> sources.add(String.valueOf(coords.indexOf(i))));
        StringJoiner destinations = new StringJoiner(";");
        to.forEach(j -> destinations.add(String.valueOf(coords.indexOf(j))));

        URI uri = URI.create(baseUrl + "/table/v1/driving/" + path
                + "?annotations=duration,distance&sources=" + sources + "&destinations=" + destinations);
        TableResponse body;
        try {
            // Ask for gzip only: the public OSRM server labels gzip bodies as "deflate" when
            // deflate is also offered, and decoding them as deflate fails.
            body = rest.get().uri(uri).header(HttpHeaders.ACCEPT_ENCODING, "gzip")
                    .retrieve().body(TableResponse.class);
        } catch (RestClientException e) {
            // Not e.getMessage(): it contains the request URL, which holds home coordinates.
            Throwable cause = e.getMostSpecificCause();
            throw new OsrmException("OSRM table request failed: " + cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : " " + cause.getMessage()), e);
        }
        if (body == null || !"Ok".equals(body.code()) || body.durations() == null || body.distances() == null) {
            throw new OsrmException("OSRM returned " + (body == null ? "no body" : body.code() + " " + body.message()));
        }
        for (int r = 0; r < from.size(); r++) {
            for (int c = 0; c < to.size(); c++) {
                metres[from.get(r)][to.get(c)] = body.distances().get(r).get(c);
                seconds[from.get(r)][to.get(c)] = body.durations().get(r).get(c);
            }
        }
    }

    private static List<Integer> range(int from, int to) {
        List<Integer> out = new ArrayList<>(to - from);
        for (int i = from; i < to; i++) {
            out.add(i);
        }
        return out;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TableResponse(String code, String message, List<List<Double>> durations, List<List<Double>> distances) {
    }
}
