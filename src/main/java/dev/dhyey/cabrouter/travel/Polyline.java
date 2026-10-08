package dev.dhyey.cabrouter.travel;

import dev.dhyey.cabrouter.routing.GeoPoint;
import java.util.ArrayList;
import java.util.List;

/**
 * The standard Google polyline algorithm: each coordinate is scaled by 10^precision,
 * delta-encoded against the previous point, zig-zagged and written as 5-bit chunks.
 * OSRM's {@code polyline6} geometries use precision 6; Google's own use 5.
 */
public final class Polyline {

    private Polyline() {
    }

    public static List<GeoPoint> decode(String encoded, int precision) {
        double factor = Math.pow(10, precision);
        List<GeoPoint> out = new ArrayList<>();
        int index = 0;
        long lat = 0;
        long lng = 0;
        while (index < encoded.length()) {
            int[] next = new int[1];
            lat += readValue(encoded, index, next);
            index = next[0];
            if (index >= encoded.length()) {
                throw new IllegalArgumentException("truncated polyline");
            }
            lng += readValue(encoded, index, next);
            index = next[0];
            out.add(new GeoPoint(lat / factor, lng / factor));
        }
        return out;
    }

    public static String encode(List<GeoPoint> points, int precision) {
        double factor = Math.pow(10, precision);
        StringBuilder sb = new StringBuilder();
        long prevLat = 0;
        long prevLng = 0;
        for (GeoPoint p : points) {
            long lat = Math.round(p.lat() * factor);
            long lng = Math.round(p.lng() * factor);
            writeValue(sb, lat - prevLat);
            writeValue(sb, lng - prevLng);
            prevLat = lat;
            prevLng = lng;
        }
        return sb.toString();
    }

    private static long readValue(String s, int start, int[] next) {
        long result = 0;
        int shift = 0;
        int i = start;
        int b;
        do {
            if (i >= s.length()) {
                throw new IllegalArgumentException("truncated polyline");
            }
            b = s.charAt(i++) - 63;
            result |= (long) (b & 0x1f) << shift;
            shift += 5;
        } while (b >= 0x20);
        next[0] = i;
        return (result & 1) != 0 ? ~(result >> 1) : result >> 1;
    }

    private static void writeValue(StringBuilder sb, long value) {
        long v = value < 0 ? ~(value << 1) : value << 1;
        while (v >= 0x20) {
            sb.append((char) ((0x20 | (v & 0x1f)) + 63));
            v >>= 5;
        }
        sb.append((char) (v + 63));
    }
}
