//! Lints Spring `messages/*.properties` bundles: `errors.properties` (base = en), `errors_ko.properties`, ...

use std::collections::{BTreeMap, BTreeSet};
use std::io;
use std::path::Path;

use crate::validate::{Issue, Severity, validate};

#[derive(Debug)]
pub struct Finding {
    pub file: String,
    pub key: Option<String>,
    pub issue: Issue,
}

const BASE_LOCALE: &str = "en";

/// Parses `.properties` text (UTF-8): comments, `=`/`:` separators, line continuations, `\uXXXX`.
pub fn parse_properties(text: &str) -> Vec<(String, String)> {
    let mut logical = Vec::new();
    let mut cur = String::new();
    for raw in text.lines() {
        let line = raw.trim_start();
        if cur.is_empty() && (line.is_empty() || line.starts_with('#') || line.starts_with('!')) {
            continue;
        }
        let trailing = line.chars().rev().take_while(|c| *c == '\\').count();
        if trailing % 2 == 1 {
            cur.push_str(&line[..line.len() - 1]);
        } else {
            cur.push_str(line);
            logical.push(std::mem::take(&mut cur));
        }
    }
    if !cur.is_empty() {
        logical.push(cur);
    }
    logical.iter().map(|l| split_entry(l)).collect()
}

fn split_entry(line: &str) -> (String, String) {
    let mut escaped = false;
    for (i, c) in line.char_indices() {
        match c {
            '\\' if !escaped => escaped = true,
            '=' | ':' | ' ' | '\t' if !escaped => {
                let rest = line[i..].trim_start_matches([' ', '\t']);
                let rest = rest.strip_prefix(['=', ':']).unwrap_or(rest).trim_start();
                return (unescape(&line[..i]), unescape(rest));
            }
            _ => escaped = false,
        }
    }
    (unescape(line), String::new())
}

fn unescape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut it = s.chars();
    while let Some(c) = it.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        match it.next() {
            Some('n') => out.push('\n'),
            Some('t') => out.push('\t'),
            Some('r') => out.push('\r'),
            Some('u') => {
                let hex: String = it.by_ref().take(4).collect();
                match u32::from_str_radix(&hex, 16).ok().and_then(char::from_u32) {
                    Some(ch) => out.push(ch),
                    None => {
                        out.push_str("\\u");
                        out.push_str(&hex);
                    }
                }
            }
            Some(other) => out.push(other),
            None => {}
        }
    }
    out
}

type Entries = Vec<(String, String)>;
type Bundles = BTreeMap<String, BTreeMap<String, Entries>>;

fn load(dir: &Path) -> io::Result<Bundles> {
    let mut bundles = Bundles::new();
    for entry in std::fs::read_dir(dir)? {
        let path = entry?.path();
        let name = path.file_name().and_then(|n| n.to_str()).unwrap_or_default();
        let Some(stem) = name.strip_suffix(".properties") else {
            continue;
        };
        let (bundle, locale) = match stem.split_once('_') {
            Some((b, l)) => (b.to_owned(), l.replace('_', "-")),
            None => (stem.to_owned(), BASE_LOCALE.to_owned()),
        };
        let entries = parse_properties(&std::fs::read_to_string(&path)?);
        bundles.entry(bundle).or_default().insert(locale, entries);
    }
    Ok(bundles)
}

fn file_name(bundle: &str, locale: &str) -> String {
    if locale == BASE_LOCALE {
        format!("{bundle}.properties")
    } else {
        format!("{bundle}_{}.properties", locale.replace('-', "_"))
    }
}

fn warn(code: &'static str, message: impl Into<String>) -> Issue {
    Issue { severity: Severity::Warning, code, message: message.into() }
}

/// Lints every bundle in `dir`. Errors mean a string will render wrong; warnings mean English fallback or drift.
pub fn lint_dir(dir: &Path) -> io::Result<Vec<Finding>> {
    let bundles = load(dir)?;
    let all_locales: BTreeSet<&String> = bundles.values().flat_map(|b| b.keys()).collect();
    let mut out = Vec::new();
    for (bundle, locales) in &bundles {
        let mut push = |loc: &str, key: Option<&str>, issue: Issue| {
            out.push(Finding { file: file_name(bundle, loc), key: key.map(str::to_owned), issue });
        };
        for loc in all_locales.iter().filter(|l| !locales.contains_key(**l)) {
            push(loc, None, warn("missing_locale_file", format!("no {loc} file: users fall back to English")));
        }
        let Some(base) = locales.get(BASE_LOCALE) else {
            let issue = Issue { severity: Severity::Error, code: "missing_base", message: "no base (English) file".into() };
            push(BASE_LOCALE, None, issue);
            continue;
        };
        let base_map: BTreeMap<&str, &str> = base.iter().map(|(k, v)| (k.as_str(), v.as_str())).collect();
        for (loc, entries) in locales {
            lint_locale(&base_map, loc, entries, &mut push);
        }
    }
    Ok(out)
}

fn lint_locale(
    base: &BTreeMap<&str, &str>,
    loc: &str,
    entries: &Entries,
    push: &mut impl FnMut(&str, Option<&str>, Issue),
) {
    let map: BTreeMap<&str, &str> = entries.iter().map(|(k, v)| (k.as_str(), v.as_str())).collect();
    for (key, src) in base {
        let Some(val) = map.get(key) else {
            push(loc, Some(key), warn("missing_key", "missing: falls back to English"));
            continue;
        };
        match validate(src, val, loc, None) {
            Ok(issues) => issues.into_iter().for_each(|i| push(loc, Some(key), i)),
            Err(e) => return push(loc, None, warn("unknown_locale", e.to_string())),
        }
    }
    for key in map.keys().filter(|k| !base.contains_key(*k)) {
        push(loc, Some(key), warn("extra_key", "not in base file"));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_comments_continuations_escapes() {
        let p = parse_properties("# c\n! c\na=1\nb : two \\\n  lines\nc=\\u00e9\\n\nd\n");
        let want: Vec<(String, String)> = [("a", "1"), ("b", "two lines"), ("c", "é\n"), ("d", "")]
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect();
        assert_eq!(p, want);
    }

    #[test]
    fn utf8_values_survive() {
        assert_eq!(parse_properties("k=보증금 {0}원")[0].1, "보증금 {0}원");
    }
}
