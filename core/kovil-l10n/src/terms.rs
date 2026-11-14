//! Researched per-language terminology (data/terms/<lang>.toml): the word residents in Korea actually use for each
//! KOVIL concept, and the misleading alternatives machine translation tends to produce.

use std::collections::BTreeMap;
use std::sync::OnceLock;

use serde::Deserialize;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Confidence {
    Low,
    Medium,
    High,
}

#[derive(Debug, Deserialize)]
pub struct Term {
    pub concept: String,
    pub ko: String,
    #[serde(rename = "use")]
    pub use_word: String,
    #[serde(default)]
    pub avoid: Vec<String>,
    pub why: String,
    #[serde(default)]
    pub sources: Vec<String>,
    pub confidence: Confidence,
}

#[derive(Deserialize)]
struct TermsFile {
    term: Vec<Term>,
}

// One entry per researched language. Adding a language = add its file here (a test checks they all parse).
const FILES: &[(&str, &str)] = &[
    ("bn", include_str!("../data/terms/bn.toml")),
    ("fil", include_str!("../data/terms/fil.toml")),
    ("hi", include_str!("../data/terms/hi.toml")),
    ("id", include_str!("../data/terms/id.toml")),
    ("ja", include_str!("../data/terms/ja.toml")),
    ("kk", include_str!("../data/terms/kk.toml")),
    ("km", include_str!("../data/terms/km.toml")),
    ("ko", include_str!("../data/terms/ko.toml")),
    ("ky", include_str!("../data/terms/ky.toml")),
    ("mn", include_str!("../data/terms/mn.toml")),
    ("my", include_str!("../data/terms/my.toml")),
    ("ne", include_str!("../data/terms/ne.toml")),
    ("ru", include_str!("../data/terms/ru.toml")),
    ("si", include_str!("../data/terms/si.toml")),
    ("th", include_str!("../data/terms/th.toml")),
    ("ur", include_str!("../data/terms/ur.toml")),
    ("uz", include_str!("../data/terms/uz.toml")),
    ("vi", include_str!("../data/terms/vi.toml")),
    ("zh", include_str!("../data/terms/zh.toml")),
];

fn all() -> &'static BTreeMap<&'static str, Vec<Term>> {
    static T: OnceLock<BTreeMap<&'static str, Vec<Term>>> = OnceLock::new();
    T.get_or_init(|| {
        FILES
            .iter()
            .map(|(code, text)| {
                // Embedded data; tests::every_file_parses guarantees this cannot fail at runtime.
                let parsed: TermsFile = toml::from_str(text).unwrap_or_else(|e| panic!("data/terms/{code}.toml: {e}"));
                (*code, parsed.term)
            })
            .collect()
    })
}

/// Terms for a language code (`vi`, `zh-CN` -> `zh`). Empty when no research exists for it.
pub fn for_locale(code: &str) -> &'static [Term] {
    let base = code.split(['-', '_']).next().unwrap_or(code);
    all().get(base).map(Vec::as_slice).unwrap_or(&[])
}

/// Terms trustworthy enough to enforce in prompts. Low-confidence rows wait for native review.
pub fn enforced(code: &str) -> impl Iterator<Item = &'static Term> {
    for_locale(code).iter().filter(|t| t.confidence >= Confidence::Medium && !t.use_word.trim().is_empty())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_file_parses_with_all_concepts() {
        let concepts: Vec<&str> = for_locale("vi").iter().map(|t| t.concept.as_str()).collect();
        assert!(concepts.len() >= 30, "vi has {} concepts", concepts.len());
        for (code, _) in FILES {
            let terms = for_locale(code);
            assert_eq!(terms.len(), concepts.len(), "{code} concept count differs from vi");
            assert!(terms.iter().any(|t| t.concept == "nationality"), "{code} lacks nationality");
        }
    }

    #[test]
    fn nationality_is_never_plain_country() {
        // The owner's core complaint: MT renders 국적 as "country". These words mean "country" in each language.
        let country = [("hi", "देश"), ("zh", "国家"), ("vi", "quốc gia"), ("id", "negara asal"), ("ko", "국가")];
        for (code, bad) in country {
            let t = for_locale(code).iter().find(|t| t.concept == "nationality").unwrap();
            assert_ne!(t.use_word.to_lowercase(), bad, "{code}");
        }
    }

    #[test]
    fn enforced_excludes_low_confidence_and_handles_region_suffix() {
        assert!(enforced("zh-CN").all(|t| t.confidence >= Confidence::Medium));
        assert!(for_locale("xx").is_empty());
    }
}
