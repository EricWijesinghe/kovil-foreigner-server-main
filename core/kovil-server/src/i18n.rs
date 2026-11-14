//! Error messages from the same `messages/errors*.properties` bundles the Java server uses.

use std::collections::HashMap;
use std::convert::Infallible;
use std::path::Path;

use axum::extract::FromRequestParts;
use axum::http::header::ACCEPT_LANGUAGE;
use axum::http::request::Parts;

use kovil_l10n::catalog::parse_properties;

/// Base bundle language (`errors.properties`), same as Spring with `fallback-to-system-locale=false`.
pub const BASE_LANG: &str = "en";
const BUNDLE: &str = "errors";

pub struct Messages {
    by_lang: HashMap<String, HashMap<String, String>>,
}

impl Messages {
    /// Loads `errors.properties` and every `errors_<lang>.properties` in `dir`.
    pub fn load(dir: &Path) -> std::io::Result<Self> {
        let mut by_lang = HashMap::new();
        for entry in std::fs::read_dir(dir)? {
            let path = entry?.path();
            let name = path.file_name().and_then(|n| n.to_str()).unwrap_or_default();
            let Some(stem) = name.strip_suffix(".properties") else { continue };
            let lang = match stem.strip_prefix(BUNDLE) {
                Some("") => BASE_LANG.to_owned(),
                Some(rest) => match rest.strip_prefix('_') {
                    Some(l) => l.to_ascii_lowercase(),
                    None => continue,
                },
                None => continue,
            };
            let text = std::fs::read_to_string(&path)?;
            by_lang.insert(lang, parse_properties(&text).into_iter().collect());
        }
        if !by_lang.contains_key(BASE_LANG) {
            return Err(std::io::Error::new(
                std::io::ErrorKind::NotFound,
                format!("{}/{BUNDLE}.properties missing", dir.display()),
            ));
        }
        Ok(Self { by_lang })
    }

    #[cfg(test)]
    pub fn from_pairs(pairs: &[(&str, &str, &str)]) -> Self {
        let mut by_lang: HashMap<String, HashMap<String, String>> = HashMap::new();
        for (lang, k, v) in pairs {
            by_lang.entry((*lang).to_owned()).or_default().insert((*k).to_owned(), (*v).to_owned());
        }
        Self { by_lang }
    }

    /// `lang` bundle, then base bundle, then the code's built-in default (Java `ErrorCode.getDefaultMessage()`).
    pub fn resolve(&self, code: &str, lang: &str, default: &str) -> String {
        [lang, BASE_LANG]
            .iter()
            .find_map(|l| self.by_lang.get(*l)?.get(code))
            .cloned()
            .unwrap_or_else(|| default.to_owned())
    }
}

/// Request language, the way Spring's `AcceptHeaderLocaleResolver` + `Locale.getLanguage()` picks it:
/// highest-q entry of `Accept-Language`, primary subtag, lowercase. No header -> `en`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Lang(pub String);

impl Lang {
    pub fn is_korean(&self) -> bool {
        self.0 == "ko"
    }

    pub fn parse(header: Option<&str>) -> Self {
        let best = header.and_then(|h| {
            let mut entries: Vec<(f32, &str)> = h
                .split(',')
                .filter_map(|part| {
                    let mut it = part.split(';');
                    let tag = it.next()?.trim();
                    let q = it
                        .find_map(|p| p.trim().strip_prefix("q="))
                        .map_or(Some(1.0), |q| q.trim().parse::<f32>().ok())?;
                    (!tag.is_empty() && tag != "*" && q > 0.0).then_some((q, tag))
                })
                .collect();
            // Stable sort keeps header order among equal q, like the servlet container.
            entries.sort_by(|a, b| b.0.total_cmp(&a.0));
            entries.first().map(|(_, tag)| tag.split(['-', '_']).next().unwrap_or(tag).to_ascii_lowercase())
        });
        Self(best.filter(|l| l.chars().all(|c| c.is_ascii_alphabetic())).unwrap_or_else(|| BASE_LANG.to_owned()))
    }
}

impl<S: Send + Sync> FromRequestParts<S> for Lang {
    type Rejection = Infallible;

    async fn from_request_parts(parts: &mut Parts, _: &S) -> Result<Self, Self::Rejection> {
        Ok(Self::parse(parts.headers.get(ACCEPT_LANGUAGE).and_then(|v| v.to_str().ok())))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn picks_highest_q_primary_subtag() {
        assert_eq!(Lang::parse(Some("vi-VN,vi;q=0.9,en;q=0.8")).0, "vi");
        assert_eq!(Lang::parse(Some("en;q=0.5, ko-KR")).0, "ko");
        assert_eq!(Lang::parse(Some("zh-TW")).0, "zh");
        assert_eq!(Lang::parse(Some("*")).0, "en");
        assert_eq!(Lang::parse(Some("")).0, "en");
        assert_eq!(Lang::parse(None).0, "en");
        assert_eq!(Lang::parse(Some("fr;q=0, ja")).0, "ja");
    }

    #[test]
    fn resolve_falls_back_lang_then_base_then_default() {
        let m = Messages::from_pairs(&[("en", "A", "en-a"), ("ko", "A", "ko-a"), ("en", "B", "en-b")]);
        assert_eq!(m.resolve("A", "ko", "d"), "ko-a");
        assert_eq!(m.resolve("B", "ko", "d"), "en-b");
        assert_eq!(m.resolve("A", "vi", "d"), "en-a");
        assert_eq!(m.resolve("C", "ko", "d"), "d");
    }

    #[test]
    fn loads_real_bundles() {
        let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../src/main/resources/messages");
        let m = Messages::load(&dir).unwrap();
        assert_eq!(m.resolve("NOT_FOUND", "ko", "x"), "요청하신 정보를 찾을 수 없어요.");
        assert_eq!(m.resolve("NOT_FOUND", "vi", "x"), "Không tìm thấy thông tin bạn yêu cầu.");
        // A language KOVIL has no bundle for falls back to English, never to the server's Korean.
        assert_eq!(m.resolve("NOT_FOUND", "fr", "x"), "The requested information could not be found.");
    }
}
