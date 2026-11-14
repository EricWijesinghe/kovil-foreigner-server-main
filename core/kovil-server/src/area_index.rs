//! In-memory index of 행정동 boundaries (admin_dongs) for GPS -> area resolution without calling any map provider.
//!
//! ~3.6k polygons / ~330k vertices (≈5 MB). A coarse grid maps each cell to the dongs whose bounding box touches it;
//! a query checks those candidates' boxes, then ray-casts against outer rings minus holes.

use std::collections::HashMap;

use serde::Deserialize;

/// Grid cell size in degrees (~5.5 km north-south). Small enough that a cell holds a handful of dongs.
const CELL_DEG: f64 = 0.05;
/// Points this far outside every polygon still snap to the nearest dong (coastline, harbour, simplification gaps).
pub const NEAREST_FALLBACK_M: f64 = 3_000.0;
const EARTH_RADIUS_M: f64 = 6_371_000.0;

/// (lng, lat) — GeoJSON order.
pub type Ring = Vec<(f64, f64)>;
/// Outer ring first, then holes.
pub type Polygon = Vec<Ring>;

#[derive(Debug, Clone)]
pub struct Dong {
    pub code: String,
    pub region_code: String,
    pub name_ko: String,
    pub name_en: String,
    pub center: (f64, f64),
    /// (min_lng, min_lat, max_lng, max_lat)
    pub bbox: (f64, f64, f64, f64),
    pub polygons: Vec<Polygon>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Match {
    Inside,
    Nearest,
}

#[derive(Default)]
pub struct AreaIndex {
    dongs: Vec<Dong>,
    by_code: HashMap<String, usize>,
    grid: HashMap<(i32, i32), Vec<usize>>,
}

fn cell(lng: f64, lat: f64) -> (i32, i32) {
    ((lng / CELL_DEG).floor() as i32, (lat / CELL_DEG).floor() as i32)
}

#[derive(Deserialize)]
struct GeoJsonMultiPolygon {
    coordinates: Vec<Vec<Vec<[f64; 2]>>>,
}

/// Parses `ST_AsGeoJSON(boundary)` of a MULTIPOLYGON.
pub fn parse_multipolygon(geojson: &str) -> Result<Vec<Polygon>, serde_json::Error> {
    let g: GeoJsonMultiPolygon = serde_json::from_str(geojson)?;
    Ok(g.coordinates
        .into_iter()
        .map(|poly| poly.into_iter().map(|ring| ring.into_iter().map(|[x, y]| (x, y)).collect()).collect())
        .collect())
}

fn point_in_ring((x, y): (f64, f64), ring: &Ring) -> bool {
    let mut inside = false;
    for w in ring.windows(2) {
        let ((x1, y1), (x2, y2)) = (w[0], w[1]);
        if (y1 > y) != (y2 > y) && x < (x2 - x1) * (y - y1) / (y2 - y1) + x1 {
            inside = !inside;
        }
    }
    inside
}

pub fn point_in_polygon(p: (f64, f64), polygon: &Polygon) -> bool {
    match polygon.split_first() {
        Some((outer, holes)) => point_in_ring(p, outer) && !holes.iter().any(|h| point_in_ring(p, h)),
        None => false,
    }
}

/// Great-circle distance in metres between two (lng, lat) points.
pub fn haversine_m((lng1, lat1): (f64, f64), (lng2, lat2): (f64, f64)) -> f64 {
    let (p1, p2) = (lat1.to_radians(), lat2.to_radians());
    let dp = p2 - p1;
    let dl = (lng2 - lng1).to_radians();
    let a = (dp / 2.0).sin().powi(2) + p1.cos() * p2.cos() * (dl / 2.0).sin().powi(2);
    2.0 * EARTH_RADIUS_M * a.sqrt().asin()
}

impl AreaIndex {
    pub fn new(dongs: Vec<Dong>) -> Self {
        let mut grid: HashMap<(i32, i32), Vec<usize>> = HashMap::new();
        for (i, d) in dongs.iter().enumerate() {
            let (min_lng, min_lat, max_lng, max_lat) = d.bbox;
            let (cx0, cy0) = cell(min_lng, min_lat);
            let (cx1, cy1) = cell(max_lng, max_lat);
            for cx in cx0..=cx1 {
                for cy in cy0..=cy1 {
                    grid.entry((cx, cy)).or_default().push(i);
                }
            }
        }
        let by_code = dongs.iter().enumerate().map(|(i, d)| (d.code.clone(), i)).collect();
        Self { dongs, by_code, grid }
    }

    pub fn len(&self) -> usize {
        self.dongs.len()
    }

    pub fn is_empty(&self) -> bool {
        self.dongs.is_empty()
    }

    pub fn get(&self, code: &str) -> Option<&Dong> {
        self.by_code.get(code).map(|&i| &self.dongs[i])
    }

    pub fn in_region<'a>(&'a self, region_code: &'a str) -> impl Iterator<Item = &'a Dong> + 'a {
        self.dongs.iter().filter(move |d| d.region_code == region_code)
    }

    /// The dong containing (lng, lat); else the nearest dong centre within [`NEAREST_FALLBACK_M`].
    pub fn resolve(&self, lng: f64, lat: f64) -> Option<(&Dong, Match)> {
        let p = (lng, lat);
        let candidates = self.grid.get(&cell(lng, lat)).map(Vec::as_slice).unwrap_or(&[]);
        let inside = candidates.iter().map(|&i| &self.dongs[i]).find(|d| {
            let (min_lng, min_lat, max_lng, max_lat) = d.bbox;
            (min_lng..=max_lng).contains(&lng)
                && (min_lat..=max_lat).contains(&lat)
                && d.polygons.iter().any(|poly| point_in_polygon(p, poly))
        });
        if let Some(d) = inside {
            return Some((d, Match::Inside));
        }
        // 3,6k centres: a linear scan is microseconds and only runs for points outside every polygon.
        self.dongs
            .iter()
            .map(|d| (haversine_m(p, d.center), d))
            .filter(|(m, _)| *m <= NEAREST_FALLBACK_M)
            .min_by(|a, b| a.0.total_cmp(&b.0))
            .map(|(_, d)| (d, Match::Nearest))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn square(x0: f64, y0: f64, size: f64) -> Ring {
        vec![(x0, y0), (x0 + size, y0), (x0 + size, y0 + size), (x0, y0 + size), (x0, y0)]
    }

    fn dong(code: &str, polygons: Vec<Polygon>, center: (f64, f64)) -> Dong {
        let pts = polygons.iter().flat_map(|p| p[0].iter()).copied();
        let (mut a, mut b, mut c, mut d) = (f64::MAX, f64::MAX, f64::MIN, f64::MIN);
        for (x, y) in pts {
            a = a.min(x);
            b = b.min(y);
            c = c.max(x);
            d = d.max(y);
        }
        Dong {
            code: code.into(),
            region_code: "11110".into(),
            name_ko: code.into(),
            name_en: code.into(),
            center,
            bbox: (a, b, c, d),
            polygons,
        }
    }

    fn index() -> AreaIndex {
        // A: 127.00..127.01 with a hole; B: right next to it; C: a two-island dong far away.
        let a = dong("A", vec![vec![square(127.0, 37.5, 0.01), square(127.004, 37.504, 0.002)]], (127.001, 37.501));
        let b = dong("B", vec![vec![square(127.01, 37.5, 0.01)]], (127.015, 37.505));
        let c = dong("C", vec![vec![square(126.0, 33.0, 0.01)], vec![square(126.1, 33.1, 0.01)]], (126.005, 33.005));
        AreaIndex::new(vec![a, b, c])
    }

    #[test]
    fn inside_point_resolves_to_its_dong() {
        let idx = index();
        assert_eq!(idx.resolve(127.002, 37.502).map(|(d, m)| (d.code.as_str(), m)), Some(("A", Match::Inside)));
        assert_eq!(idx.resolve(127.015, 37.505).map(|(d, _)| d.code.as_str()), Some("B"));
    }

    #[test]
    fn hole_is_not_inside_and_falls_back_to_nearest_centre() {
        let idx = index();
        let (d, m) = idx.resolve(127.005, 37.505).unwrap();
        assert_eq!(m, Match::Nearest);
        // Nearest centre to the hole: A's centre is ~560 m away, B's ~880 m.
        assert_eq!(d.code, "A");
    }

    #[test]
    fn second_island_of_a_multipolygon_counts() {
        assert_eq!(index().resolve(126.105, 33.105).map(|(d, m)| (d.code.as_str(), m)), Some(("C", Match::Inside)));
    }

    #[test]
    fn far_away_point_is_none() {
        assert!(index().resolve(139.69, 35.68).is_none()); // Tokyo
        assert!(AreaIndex::default().resolve(127.0, 37.5).is_none());
    }

    #[test]
    fn parses_mysql_geojson_in_lng_lat_order() {
        let polys = parse_multipolygon(r#"{"type":"MultiPolygon","coordinates":[[[[126.97,37.56],[126.98,37.56],[126.98,37.57],[126.97,37.56]]]]}"#).unwrap();
        assert_eq!(polys[0][0][0], (126.97, 37.56));
    }

    #[test]
    fn haversine_is_sane() {
        let seoul_busan = haversine_m((126.978, 37.566), (129.075, 35.18));
        assert!((320_000.0..330_000.0).contains(&seoul_busan), "{seoul_busan}");
    }
}
