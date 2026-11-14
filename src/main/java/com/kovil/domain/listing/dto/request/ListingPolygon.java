package com.kovil.domain.listing.dto.request;

import com.kovil.domain.listing.exception.ListingException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.kovil.domain.listing.exception.ListingErrorCode.LISTING_POLYGON_INVALID;

public record ListingPolygon(List<Vertex> vertices) {

    public static final int MIN_VERTICES = 3;
    public static final int MAX_VERTICES = 30;

    private static final Pattern NUMBER = Pattern.compile("-?\\d{1,3}(\\.\\d{1,15})?");
    private static final int MAX_LENGTH = 2_000;
    private static final int MAX_RAW_POINTS = MAX_VERTICES * 2;
    private static final BigDecimal LAT_LIMIT = BigDecimal.valueOf(90);
    private static final BigDecimal LNG_LIMIT = BigDecimal.valueOf(180);

    public record Vertex(BigDecimal lat, BigDecimal lng) {}

    public ListingPolygon {
        vertices = List.copyOf(vertices);
        if (vertices.size() < MIN_VERTICES || vertices.size() > MAX_VERTICES) {
            throw invalid();
        }

        for (Vertex vertex : vertices) {
            if (vertex.lat().abs().compareTo(LAT_LIMIT) > 0 || vertex.lng().abs().compareTo(LNG_LIMIT) > 0) {
                throw invalid();
            }
        }

        if (hasRepeatedNeighbor(vertices) || twiceArea(vertices).signum() == 0 || selfIntersects(vertices)) {
            throw invalid();
        }
    }

    public static ListingPolygon parseOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.isBlank() || raw.length() > MAX_LENGTH) {
            throw invalid();
        }
        String[] points = raw.trim().split(",", -1);
        if (points.length > MAX_RAW_POINTS) {
            throw invalid();
        }

        List<Vertex> vertices = new ArrayList<>();
        for (String point : points) {
            String[] latLng = point.split(":", -1);
            if (latLng.length != 2) {
                throw invalid();
            }
            Vertex vertex = new Vertex(numberOf(latLng[0]), numberOf(latLng[1]));
            if (vertices.isEmpty() || !same(vertices.get(vertices.size() - 1), vertex)) {
                vertices.add(vertex);
            }
        }
        if (vertices.size() > 1 && same(vertices.get(0), vertices.get(vertices.size() - 1))) {
            vertices.remove(vertices.size() - 1);
        }
        return new ListingPolygon(vertices);
    }

    public boolean contains(BigDecimal lat, BigDecimal lng) {
        Vertex point = new Vertex(lat, lng);
        boolean inside = false;

        for (int i = 0, j = vertices.size() - 1; i < vertices.size(); j = i++) {
            Vertex a = vertices.get(i);
            Vertex b = vertices.get(j);
            if (withinBox(a, b, point) && orientation(a, b, point) == 0) {
                return true;
            }
            if ((a.lat().compareTo(lat) > 0) != (b.lat().compareTo(lat) > 0)) {
                BigDecimal lhs = lng.subtract(a.lng()).multiply(b.lat().subtract(a.lat()));
                BigDecimal rhs = b.lng().subtract(a.lng()).multiply(lat.subtract(a.lat()));
                int cmp = lhs.compareTo(rhs);
                if (b.lat().compareTo(a.lat()) > 0 ? cmp < 0 : cmp > 0) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    public String toParam() {
        return vertices.stream()
                .map(vertex -> vertex.lat().toPlainString() + ":" + vertex.lng().toPlainString())
                .collect(Collectors.joining(","));
    }

    public BigDecimal minLat() {
        return vertices.stream().map(Vertex::lat).min(BigDecimal::compareTo).orElseThrow();
    }

    public BigDecimal maxLat() {
        return vertices.stream().map(Vertex::lat).max(BigDecimal::compareTo).orElseThrow();
    }

    public BigDecimal minLng() {
        return vertices.stream().map(Vertex::lng).min(BigDecimal::compareTo).orElseThrow();
    }

    public BigDecimal maxLng() {
        return vertices.stream().map(Vertex::lng).max(BigDecimal::compareTo).orElseThrow();
    }

    private static BigDecimal numberOf(String raw) {
        String s = raw.trim();
        if (!NUMBER.matcher(s).matches()) {
            throw invalid();
        }
        return new BigDecimal(s);
    }

    private static boolean hasRepeatedNeighbor(List<Vertex> vertices) {
        for (int i = 0; i < vertices.size(); i++) {
            if (same(vertices.get(i), vertices.get((i + 1) % vertices.size()))) {
                return true;
            }
        }
        return false;
    }

    private static BigDecimal twiceArea(List<Vertex> vertices) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < vertices.size(); i++) {
            Vertex a  =vertices.get(i);
            Vertex b = vertices.get((i + 1) % vertices.size());

            sum = sum.add(a.lng().multiply(b.lat()).subtract(b.lng().multiply(a.lat())));
        }
        return sum;
    }

    private static boolean selfIntersects(List<Vertex> vertices) {
        int verticesSize = vertices.size();

        for (int i = 0; i < verticesSize; i++) {
            Vertex a = vertices.get(i);
            Vertex b = vertices.get((i + 1) % verticesSize);

            for (int j = i + 1; j < verticesSize; j++) {
                Vertex c = vertices.get(j);
                Vertex d = vertices.get((j + 1) % verticesSize);

                boolean crossed;
                if (j == i + 1) {
                    crossed = foldsBack(a, b, d);
                } else if (i == 0 && j == verticesSize - 1) {
                    crossed = foldsBack(c, a, b);
                } else {
                    crossed = intersects(a, b, c, d);
                }
                if (crossed) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean foldsBack(Vertex end1, Vertex shared, Vertex end2) {
        BigDecimal dot = end1.lng().subtract(shared.lng()).multiply(end2.lng().subtract(shared.lng()))
                .add(end1.lat().subtract(shared.lat()).multiply(end2.lat().subtract(shared.lat())));
        return orientation(end1, shared, end2) == 0 && dot.signum() > 0;
    }

    private static boolean intersects(Vertex a, Vertex b, Vertex c, Vertex d) {
        int d1 = orientation(c, d, a);
        int d2 = orientation(c, d, b);
        int d3 = orientation(a, b, c);
        int d4 = orientation(a, b, d);
        if (d1 * d2 < 0 && d3 * d4 < 0) {
            return true;
        }
        return (d1 == 0 && withinBox(c, d, a)) || (d2 == 0 && withinBox(c, d, b))
                || (d3 == 0 && withinBox(a, b, c)) || (d4 == 0 && withinBox(a, b, d));
    }

    private static int orientation(Vertex a, Vertex b, Vertex p) {
        return b.lng().subtract(a.lng()).multiply(p.lat().subtract(a.lat()))
                .subtract(b.lat().subtract(a.lat()).multiply(p.lng().subtract(a.lng())))
                .signum();
    }

    private static boolean withinBox(Vertex a, Vertex b, Vertex p) {
        return p.lat().compareTo(a.lat().min(b.lat())) >= 0 && p.lat().compareTo(a.lat().max(b.lat())) <= 0
                && p.lng().compareTo(a.lng().min(b.lng())) >= 0 && p.lng().compareTo(a.lng().max(b.lng())) <= 0;
    }


    private static boolean same(Vertex a, Vertex b) {
        return a.lat().compareTo(b.lat()) == 0 && a.lng().compareTo(b.lng()) == 0;
    }

    private static ListingException invalid() {
        return new ListingException(LISTING_POLYGON_INVALID);
    }
}
