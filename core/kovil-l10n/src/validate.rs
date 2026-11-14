use std::collections::BTreeSet;

use crate::glossary::{self, Policy};
use crate::profile;
use crate::script::{is_hangul, is_kana, is_latin, Script};
use crate::L10nError;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Severity {
    Warning,
    Error,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Issue {
    pub severity: Severity,
    pub code: &'static str,
    pub message: String,
}

fn issue(severity: Severity, code: &'static str, message: String) -> Issue {
    Issue { severity, code, message }
}

/// Checks a translation of `source` into `locale`. Errors mean the text must not ship.
pub fn validate(source: &str, output: &str, locale: &str, max_chars: Option<usize>) -> Result<Vec<Issue>, L10nError> {
    let p = profile::profile(locale)?;
    let mut out = Vec::new();
    check_placeholders(source, output, &mut out);
    check_apostrophes(output, &mut out);
    check_numbers(source, output, &mut out);
    check_script(output, p.script, &mut out);
    if p.script == Script::Latin {
        check_kept_terms(source, output, &mut out);
    }
    if let Some(max) = max_chars.filter(|m| output.chars().count() > *m) {
        out.push(issue(Severity::Warning, "too_long", format!("{} chars, limit {max}", output.chars().count())));
    }
    Ok(out)
}

/// MessageFormat argument indices: `{0}`, `{1,number}`.
pub fn placeholders(s: &str) -> BTreeSet<u32> {
    s.split('{')
        .skip(1)
        .filter_map(|part| {
            let end = part.find(['}', ','])?;
            part[..end].trim().parse().ok()
        })
        .collect()
}

fn check_placeholders(source: &str, output: &str, out: &mut Vec<Issue>) {
    let (a, b) = (placeholders(source), placeholders(output));
    if a != b {
        out.push(issue(Severity::Error, "placeholder_mismatch", format!("source {a:?}, translation {b:?}")));
    }
}

/// With arguments, a lone `'` starts a quoted section and later `{1}` ships literally.
fn check_apostrophes(output: &str, out: &mut Vec<Issue>) {
    if placeholders(output).is_empty() {
        return;
    }
    // A run of quotes is literal only in pairs: '' -> ', ''' -> ' + an opening quote that swallows {1}.
    let mut run = 0usize;
    let mut odd_run = false;
    for ch in output.chars().chain(std::iter::once('\0')) {
        if ch == '\'' {
            run += 1;
        } else {
            odd_run |= run % 2 == 1;
            run = 0;
        }
    }
    if odd_run {
        out.push(issue(
            Severity::Error,
            "messageformat_apostrophe",
            "single ' in a MessageFormat string; write '' or use ’".into(),
        ));
    }
}

/// Multiplier for East Asian large-number units written after a number (5000만, 1억, 1亿, 1億).
fn unit_multiplier(c: char) -> Option<u128> {
    match c {
        '만' | '万' => Some(10_000),
        '억' | '亿' | '億' => Some(100_000_000),
        _ => None,
    }
}

/// "1,500" -> 1500; with a unit, a single separator followed by 1-2 digits is a decimal point: "1.5" 억 -> 150,000,000.
fn scaled(raw: &str, mult: u128) -> Option<u128> {
    let seps = raw.matches([',', '.']).count();
    let frac_len = raw.rfind([',', '.']).map(|i| raw.len() - i - 1);
    match frac_len {
        Some(n @ 1..=2) if seps == 1 && mult > 1 => {
            let (int, frac) = raw.split_at(raw.len() - n - 1);
            let (int, frac): (u128, u128) = (int.parse().ok()?, frac[1..].parse().ok()?);
            Some(int * mult + frac * mult / 10u128.pow(n as u32))
        }
        _ => raw.replace([',', '.'], "").parse::<u128>().ok().map(|v| v * mult),
    }
}

/// Amounts in `s`, normalised: grouping removed and 만/억 units expanded, so "1억 5000만" == "150,000,000".
fn numbers(s: &str) -> Vec<String> {
    let chars: Vec<char> = s.chars().collect();
    // (value, had_unit, index after the unit)
    let mut found: Vec<(u128, Option<u128>, usize)> = Vec::new();
    let mut i = 0;
    while i < chars.len() {
        if !chars[i].is_ascii_digit() {
            i += 1;
            continue;
        }
        let start = i;
        while i < chars.len()
            && (chars[i].is_ascii_digit()
                || (matches!(chars[i], ',' | '.') && chars.get(i + 1).is_some_and(char::is_ascii_digit)))
        {
            i += 1;
        }
        let raw: String = chars[start..i].iter().collect();
        let mut j = i;
        while chars.get(j) == Some(&' ') {
            j += 1;
        }
        let mult = chars.get(j).copied().and_then(unit_multiplier);
        if mult.is_some() {
            i = j + 1;
        }
        // Compared in hundredths so a decimal point counts: "3.3" ≠ "33", while "1,000" == "1.000" (grouping).
        let Some(v) = scaled(&raw, mult.unwrap_or(1) * 100) else { continue };
        // "1억 5000만": a 만 amount right after an 억 amount is one number.
        let merge = matches!(found.last(), Some((_, Some(100_000_000), end))
            if mult == Some(10_000) && chars[*end..start].iter().all(|c| c.is_whitespace()));
        match found.last_mut() {
            Some(last) if merge => *last = (last.0 + v, Some(10_000), i),
            _ => found.push((v, mult, i)),
        }
    }
    found.into_iter().map(|(v, _, _)| v.to_string()).collect()
}

fn check_numbers(source: &str, output: &str, out: &mut Vec<Issue>) {
    // Placeholder indices are not amounts.
    let strip = |s: &str| {
        placeholders(s).iter().fold(s.to_owned(), |acc, n| acc.replace(&format!("{{{n}"), "{"))
    };
    let mut have = numbers(&strip(output));
    for n in numbers(&strip(source)) {
        match have.iter().position(|h| *h == n) {
            Some(i) => {
                have.swap_remove(i);
            }
            None => out.push(issue(Severity::Error, "number_changed", format!("{n} missing from translation"))),
        }
    }
}

fn check_script(output: &str, target: Script, out: &mut Vec<Issue>) {
    let letters: Vec<char> = output.chars().filter(|c| c.is_alphabetic()).collect();
    if letters.is_empty() {
        return;
    }
    let expected = letters.iter().filter(|c| target.contains(**c)).count();
    // Latin loanwords (OTP, ARC, app) are normal everywhere, so they never count against a non-Latin target.
    let foreign = letters.iter().filter(|c| !target.contains(**c) && !is_latin(**c)).count();
    if foreign > expected {
        out.push(issue(Severity::Error, "wrong_script", format!("mostly not {target:?} script: wrong language?")));
        return;
    }
    if target == Script::Han && letters.iter().any(|c| is_kana(*c)) {
        out.push(issue(Severity::Error, "wrong_script", "kana in Chinese text: looks Japanese".into()));
    }
    if expected == 0 {
        out.push(issue(Severity::Warning, "untranslated", format!("no {target:?} letters: left in English?")));
    }
    if target != Script::Hangul && without_bracketed(output).chars().any(is_hangul) {
        out.push(issue(Severity::Warning, "hangul_left", "Korean text left in translation".into()));
    }
}

/// Text outside (…) and （…）. A bracketed Korean term is a deliberate gloss ("迁入申报（전입신고）") that lets a
/// resident show office staff the exact word, so it is not "Korean left untranslated".
fn without_bracketed(s: &str) -> String {
    let mut depth = 0usize;
    let kept: String = s
        .chars()
        .filter(|c| match c {
            '(' | '（' => {
                depth += 1;
                false
            }
            ')' | '）' => {
                depth = depth.saturating_sub(1);
                false
            }
            _ => depth == 0,
        })
        .collect();
    // An unclosed bracket is not a gloss: check the whole text instead of hiding everything after it.
    if depth > 0 { s.to_owned() } else { kept }
}

fn check_kept_terms(source: &str, output: &str, out: &mut Vec<Issue>) {
    let lower = output.to_lowercase();
    for t in glossary::find(source).into_iter().filter(|t| t.policy == Policy::Keep) {
        if !lower.contains(&t.roman.to_lowercase()) {
            out.push(issue(Severity::Warning, "glossary_term_dropped", format!("{} should appear as \"{}\"", t.ko, t.roman)));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn codes(src: &str, out: &str, loc: &str) -> Vec<&'static str> {
        validate(src, out, loc, None).unwrap().into_iter().map(|i| i.code).collect()
    }

    #[test]
    fn clean_translation_passes() {
        assert!(codes("월세 {0}원", "Tiền thuê wolse {0} won", "vi").is_empty());
        assert!(codes("Rent of KRW {1}", "किराया KRW {1}", "ne").is_empty());
    }

    #[test]
    fn placeholder_drop_is_error() {
        assert!(codes("Hi {0}", "Xin chào", "vi").contains(&"placeholder_mismatch"));
    }

    #[test]
    fn lone_apostrophe_with_args_is_error() {
        assert!(codes("Rent {1}", "You're paying {1}", "en").contains(&"messageformat_apostrophe"));
        assert!(codes("Rent {1}", "You''re paying {1}", "en").is_empty());
        assert!(codes("Rent", "You're paying", "en").is_empty());
        // Three quotes = a literal quote plus an opening quote that swallows {1}.
        assert!(codes("Rent {1}", "Don'''t pay {1}", "en").contains(&"messageformat_apostrophe"));
        assert!(codes("Rent {1}", "Don''''t pay {1}", "en").is_empty());
    }

    #[test]
    fn changed_amount_is_error() {
        assert!(codes("보증금 1,000,000원", "deposit 100000 won", "en").contains(&"number_changed"));
        assert!(codes("보증금 1,000,000원", "deposit 1,000,000 won", "en").is_empty());
    }

    #[test]
    fn korean_units_are_expanded() {
        assert!(codes("보증금 1억 5000만원", "deposit 150,000,000 won", "en").is_empty());
        assert!(codes("월세 50만원", "wolse (monthly rent) 500,000 won", "en").is_empty());
        assert!(codes("1.5억", "150,000,000 won", "en").is_empty());
        assert!(codes("보증금 1억", "押金1亿韩元", "zh").is_empty());
        assert!(codes("보증금 1억", "deposit 100 million won", "en").contains(&"number_changed"));
        assert!(codes("3층 1,500", "floor 3, 1.500", "vi").is_empty());
        // A dropped decimal point changes the amount; a decimal comma does not.
        assert!(codes("3.3㎡", "33 m²", "en").contains(&"number_changed"));
        assert!(codes("3.3㎡", "3,3 м²", "ru").is_empty());
    }

    #[test]
    fn wrong_language_is_error() {
        assert!(codes("x", "これは日本語です", "zh").contains(&"wrong_script"));
        assert!(codes("x", "안녕하세요 반갑습니다", "th").contains(&"wrong_script"));
        assert!(codes("x", "Hello there", "si").contains(&"untranslated"));
        assert!(codes("x", "OTP कोड पठाइयो", "ne").is_empty());
    }

    #[test]
    fn bracketed_korean_gloss_is_allowed_but_bare_korean_warns() {
        assert!(!codes("전입신고", "请办理迁入申报（전입신고）。", "zh").contains(&"hangul_left"));
        assert!(!codes("전입신고", "jeonip-singo (전입신고) करनुहोस्", "ne").contains(&"hangul_left"));
        assert!(codes("전입신고", "请办理迁入申报 전입신고。", "zh").contains(&"hangul_left"));
        // An unclosed bracket must not hide the rest of the text.
        assert!(codes("x", "Xin vui lòng (xem 전입신고", "vi").contains(&"hangul_left"));
    }

    #[test]
    fn dropped_kept_term_warns() {
        assert!(codes("전세 계약", "lump-sum lease contract", "en").contains(&"glossary_term_dropped"));
    }

    #[test]
    fn length_limit_warns() {
        let i = validate("a", "abcdef", "en", Some(3)).unwrap();
        assert_eq!(i[0].code, "too_long");
    }
}
