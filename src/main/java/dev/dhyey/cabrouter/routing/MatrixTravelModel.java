package dev.dhyey.cabrouter.routing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Travel model backed by precomputed matrices, typically fetched from a road router
 * for exactly the points one planning call will touch.
 *
 * <p>Road distances differ by direction (one-way streets, divided roads). The
 * optimiser needs a symmetric objective, so {@link #distanceKm} returns the mean of
 * the two directions. {@link #minutes} keeps the true directed time, so ETAs and ride
 * limits reflect the way the cab actually drives.
 */
public final class MatrixTravelModel implements TravelModel {

    private final Map<GeoPoint, Integer> index = new HashMap<>();
    private final double[][] symmetricKm;
    private final double[][] minutes;

    /**
     * @param points  the points, in matrix order; duplicates must be removed first
     * @param km      km[i][j] is the road distance from points[i] to points[j]
     * @param minutes minutes[i][j] is the drive time from points[i] to points[j]
     */
    public MatrixTravelModel(List<GeoPoint> points, double[][] km, double[][] minutes) {
        int n = points.size();
        if (km.length != n || minutes.length != n) {
            throw new IllegalArgumentException("matrix size does not match " + n + " points");
        }
        for (int i = 0; i < n; i++) {
            if (index.put(points.get(i), i) != null) {
                throw new IllegalArgumentException("duplicate point " + points.get(i));
            }
        }
        this.symmetricKm = new double[n][n];
        this.minutes = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                symmetricKm[i][j] = (km[i][j] + km[j][i]) / 2;
                this.minutes[i][j] = minutes[i][j];
            }
        }
    }

    @Override
    public double distanceKm(GeoPoint a, GeoPoint b) {
        return symmetricKm[indexOf(a)][indexOf(b)];
    }

    @Override
    public double minutes(GeoPoint a, GeoPoint b) {
        return minutes[indexOf(a)][indexOf(b)];
    }

    private int indexOf(GeoPoint p) {
        Integer i = index.get(p);
        if (i == null) {
            // Failing loudly beats silently guessing: it means the caller fetched the wrong points.
            throw new IllegalArgumentException("point " + p + " is not in the travel matrix");
        }
        return i;
    }
}
