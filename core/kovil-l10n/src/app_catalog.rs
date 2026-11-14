//! Lints the mobile app's JSON UI catalogs (`src/i18n/catalogs/<lang>.json`, `en.json` = source).
//!
//! On top of the generic translation checks in [`validate`] this enforces what the app relies on:
//! identical keys, identical `{name}` placeholders, no English left behind, researched terms not
//! replaced by the words machine translation tends to pick, and labels short enough for their slot.

use std::collections::{BTreeMap, BTreeSet};
use std::io;
use std::path::Path;

use crate::catalog::Finding;
use crate::profile;
use crate::script::Script;
use crate::terms::{self, Confidence};
use crate::validate::{Issue, Severity, validate};

const SOURCE: &str = "en";
/// Bottom tab labels share one row on a 360 dp phone.
const TAB_MAX: usize = 14;
/// Button and chip labels must fit one line of a full-width button.
const BUTTON_MAX: usize = 32;
/// Brand and code words that are the same in every language.
const UNIVERSAL: &[&str] = &["KOVIL", "OK", "Wi-Fi", "SMS"];

pub type Catalog = BTreeMap<String, String>;

/// `{name}` placeholders as the app's `translate()` fills them.
pub fn named_placeholders(s: &str) -> BTreeSet<&str> {
    s.split('{')
        .skip(1)
        .filter_map(|part| {
            let name = &part[..part.find('}')?];
            (!name.is_empty() && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')).then_some(name)
        })
        .collect()
}

fn issue(severity: Severity, code: &'static str, message: impl Into<String>) -> Issue {
    Issue { severity, code, message: message.into() }
}

fn max_len(key: &str) -> Option<usize> {
    if key.starts_with("tab.") {
        Some(TAB_MAX)
    } else if key.ends_with(".button") || key.ends_with(".retry") || key.ends_with(".cta") {
        Some(BUTTON_MAX)
    } else {
        None
    }
}

/// Same text as English is only fine for brand/code words or very short tokens.
fn untranslated(src: &str, out: &str, script: Script) -> Option<Issue> {
    if out != src || UNIVERSAL.contains(&src.trim()) {
        return None;
    }
    let letters = src.chars().filter(|c| c.is_alphabetic()).count();
    let words = src.split_whitespace().count();
    match (script, words, letters) {
        (_, _, 0..=3) => None,
        (Script::Latin, 1, _) => Some(issue(Severity::Warning, "same_as_english", "identical to English: intended?")),
        _ => Some(issue(Severity::Error, "untranslated", format!("still English: \"{src}\""))),
    }
}

/// Researched "avoid" words (medium/high confidence). Matching is whole-word for Latin/Cyrillic scripts,
/// substring for scripts written without spaces.
fn avoided_terms(locale: &str, out: &str, script: Script) -> Vec<Issue> {
    let lower = out.to_lowercase();
    let spaced = matches!(script, Script::Latin | Script::Cyrillic);
    terms::for_locale(locale)
        .iter()
        .filter(|t| t.confidence >= Confidence::Medium)
        .flat_map(|t| t.avoid.iter().map(move |a| (t, a.to_lowercase())))
        .filter(|(t, a)| !a.is_empty() && !lower.contains(&t.use_word.to_lowercase()) && contains_term(&lower, a, spaced))
        .map(|(t, a)| {
            let severity = if t.confidence == Confidence::High { Severity::Error } else { Severity::Warning };
            issue(severity, "avoid_term", format!("\"{a}\" for {} ({}): use \"{}\"", t.concept, t.ko, t.use_word))
        })
        .collect()
}

fn contains_term(text: &str, term: &str, whole_word: bool) -> bool {
    if !whole_word {
        return text.contains(term);
    }
    text.match_indices(term).any(|(i, _)| {
        let before = text[..i].chars().next_back();
        let after = text[i + term.len()..].chars().next();
        !before.is_some_and(char::is_alphanumeric) && !after.is_some_and(char::is_alphanumeric)
    })
}

/// Checks one translated catalog against the source. Pure, so it is unit-tested without files.
pub fn lint_catalog(locale: &str, source: &Catalog, target: &Catalog) -> Vec<(Option<String>, Issue)> {
    let mut out: Vec<(Option<String>, Issue)> = Vec::new();
    let script = match profile::profile(locale) {
        Ok(p) => p.script,
        Err(e) => return vec![(None, issue(Severity::Error, "unknown_locale", e.to_string()))],
    };
    for key in target.keys().filter(|k| !source.contains_key(*k)) {
        out.push((Some(key.clone()), issue(Severity::Error, "extra_key", "not in en.json")));
    }
    for (key, src) in source {
        let k = Some(key.clone());
        let Some(text) = target.get(key) else {
            out.push((k, issue(Severity::Error, "missing_key", "not translated")));
            continue;
        };
        if text.trim().is_empty() {
            out.push((k, issue(Severity::Error, "empty", "empty value")));
            continue;
        }
        let (a, b) = (named_placeholders(src), named_placeholders(text));
        if a != b {
            out.push((k.clone(), issue(Severity::Error, "placeholder_mismatch", format!("en {a:?}, {locale} {b:?}"))));
        }
        if let Some(i) = untranslated(src, text, script) {
            out.push((k.clone(), i));
        }
        // Generic checks (script, numbers, kept Korean terms). Their MessageFormat placeholder check is moot here,
        // and a brand word has no letters of the target script by design.
        if UNIVERSAL.contains(&text.trim()) {
            continue;
        }
        if let Ok(issues) = validate(src, text, locale, max_len(key)) {
            out.extend(issues.into_iter().filter(|i| i.code != "placeholder_mismatch").map(|i| (k.clone(), i)));
        }
        out.extend(avoided_terms(locale, text, script).into_iter().map(|i| (k.clone(), i)));
    }
    out
}

fn read(path: &Path) -> io::Result<Catalog> {
    let text = std::fs::read_to_string(path)?;
    serde_json::from_str(&text).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("{}: {e}", path.display())))
}

/// Lints every `<lang>.json` in `dir` against `en.json`.
pub fn lint_dir(dir: &Path) -> io::Result<Vec<Finding>> {
    let source = read(&dir.join(format!("{SOURCE}.json")))?;
    let mut locales: Vec<String> = std::fs::read_dir(dir)?
        .filter_map(Result::ok)
        .filter_map(|e| e.file_name().to_str()?.strip_suffix(".json").map(str::to_owned))
        .filter(|l| l != SOURCE)
        .collect();
    locales.sort();
    let mut found = Vec::new();
    for locale in locales {
        let target = read(&dir.join(format!("{locale}.json")))?;
        let file = format!("{locale}.json");
        found.extend(lint_catalog(&locale, &source, &target).into_iter().map(|(key, issue)| Finding { file: file.clone(), key, issue }));
    }
    Ok(found)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cat(pairs: &[(&str, &str)]) -> Catalog {
        pairs.iter().map(|(k, v)| ((*k).to_owned(), (*v).to_owned())).collect()
    }

    fn codes(locale: &str, src: &[(&str, &str)], tgt: &[(&str, &str)]) -> Vec<&'static str> {
        lint_catalog(locale, &cat(src), &cat(tgt)).into_iter().map(|(_, i)| i.code).collect()
    }

    #[test]
    fn named_placeholders_are_parsed() {
        assert_eq!(named_placeholders("{count} homes in {area}"), BTreeSet::from(["area", "count"]));
        assert!(named_placeholders("no {braces here} or {}").is_empty());
    }

    #[test]
    fn clean_catalog_passes() {
        let src = [("home.title", "Find your home in Korea"), ("campus.count", "{count} universities")];
        let vi = [("home.title", "Tìm nhà ở Hàn Quốc"), ("campus.count", "{count} trường đại học")];
        assert!(codes("vi", &src, &vi).is_empty(), "{:?}", codes("vi", &src, &vi));
    }

    #[test]
    fn missing_extra_empty_and_placeholders_are_errors() {
        let src = [("a.b", "{count} homes"), ("c.d", "Search homes")];
        let tgt = [("a.b", "nhà"), ("c.d", " "), ("z.z", "thừa")];
        let c = codes("vi", &src, &tgt);
        for code in ["placeholder_mismatch", "empty", "extra_key"] {
            assert!(c.contains(&code), "{code} in {c:?}");
        }
        assert!(codes("vi", &src, &[("a.b", "{count} nhà")]).contains(&"missing_key"));
    }

    #[test]
    fn english_left_behind_is_caught() {
        let src = [("home.subtitle", "Rooms and apartments for foreign residents")];
        assert!(codes("ne", &src, &src).contains(&"untranslated"));
        assert!(codes("vi", &src, &src).contains(&"untranslated"));
        // Brand words and single Latin words are allowed or only warned.
        assert!(codes("ne", &[("brand", "KOVIL")], &[("brand", "KOVIL")]).is_empty());
        assert_eq!(codes("id", &[("x.y", "Internet")], &[("x.y", "Internet")]), vec!["same_as_english"]);
    }

    #[test]
    fn tab_labels_must_be_short() {
        let src = [("tab.home", "Home")];
        assert!(codes("vi", &src, &[("tab.home", "Trang chủ của bạn ở đây")]).contains(&"too_long"));
        assert!(codes("vi", &src, &[("tab.home", "Trang chủ")]).is_empty());
    }

    #[test]
    fn wrong_script_is_caught() {
        let src = [("home.title", "Find your home in Korea")];
        assert!(codes("zh", &src, &[("home.title", "韓国で家を探す、これは日本語です")]).contains(&"wrong_script"));
    }

    #[test]
    fn whole_word_matching_does_not_hit_longer_words() {
        assert!(contains_term("quốc gia của bạn", "quốc gia", true));
        assert!(!contains_term("quốc giao", "quốc gia", true));
        assert!(contains_term("国家", "国家", false));
    }

    #[test]
    fn real_catalog_dir_parses() {
        // Runs against the app checkout when it sits next to the server repo (local dev); skipped in CI.
        let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../kovil-foreigner-expo-main/src/i18n/catalogs");
        if dir.is_dir() {
            assert!(lint_dir(&dir).is_ok());
        }
    }
}
