use std::sync::OnceLock;

use serde::Deserialize;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Policy {
    /// Romanize and gloss: foreigners hear this Korean word in real life.
    Keep,
    /// Use the natural local equivalent.
    Translate,
}

#[derive(Debug, Deserialize)]
pub struct Term {
    pub ko: String,
    pub roman: String,
    pub meaning: String,
    pub policy: Policy,
}

#[derive(Deserialize)]
struct GlossaryFile {
    term: Vec<Term>,
}

/// Terms sorted longest-first so 반전세 is matched before 전세.
pub fn terms() -> &'static [Term] {
    static T: OnceLock<Vec<Term>> = OnceLock::new();
    T.get_or_init(|| {
        let mut t = toml::from_str::<GlossaryFile>(include_str!("../data/glossary.toml"))
            .expect("embedded glossary.toml is valid")
            .term;
        t.sort_by_key(|x| std::cmp::Reverse(x.ko.chars().count()));
        t
    })
}

/// Terms that appear in `source`. A term inside a longer matched term is not reported again.
pub fn find(source: &str) -> Vec<&'static Term> {
    let mut rest = source.to_owned();
    terms()
        .iter()
        .filter(|t| {
            let hit = rest.contains(&t.ko);
            if hit {
                rest = rest.replace(&t.ko, " ");
            }
            hit
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn longer_term_wins() {
        let hits: Vec<_> = find("반전세 원룸, 관리비 별도").iter().map(|t| t.ko.as_str()).collect();
        assert!(hits.contains(&"반전세") && hits.contains(&"원룸") && hits.contains(&"관리비"));
        assert!(!hits.contains(&"전세"));
    }

    #[test]
    fn no_hits_in_plain_text() {
        assert!(find("hello world").is_empty());
    }
}
