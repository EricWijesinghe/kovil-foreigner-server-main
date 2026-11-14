use std::collections::BTreeMap;
use std::sync::OnceLock;

use serde::Deserialize;

use crate::L10nError;
use crate::script::Script;

/// Where the text appears. Decides how formal it must be: friendly is not always right.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Context {
    Legal,
    Transactional,
    Support,
    Community,
}

impl Context {
    pub const ALL: [Context; 4] = [Self::Legal, Self::Transactional, Self::Support, Self::Community];

    pub fn parse(s: &str) -> Result<Self, L10nError> {
        match s.to_ascii_lowercase().as_str() {
            "legal" => Ok(Self::Legal),
            "transactional" => Ok(Self::Transactional),
            "support" => Ok(Self::Support),
            "community" => Ok(Self::Community),
            _ => Err(L10nError::UnknownContext(s.to_owned())),
        }
    }
}

#[derive(Debug, Deserialize)]
pub struct Register {
    pub address: String,
    pub tone: String,
}

#[derive(Debug, Deserialize)]
pub struct LocaleProfile {
    pub name: String,
    pub native: String,
    pub script: Script,
    #[serde(default)]
    pub rtl: bool,
    pub notes: Vec<String>,
    pub register: BTreeMap<Context, Register>,
}

#[derive(Deserialize)]
struct LocalesFile {
    locales: BTreeMap<String, LocaleProfile>,
}

/// All locale profiles, keyed by code (`ko`, `zh-TW`, …). Data is embedded at compile time;
/// `tests::embedded_data_is_complete` guarantees it parses.
pub fn profiles() -> &'static BTreeMap<String, LocaleProfile> {
    static P: OnceLock<BTreeMap<String, LocaleProfile>> = OnceLock::new();
    P.get_or_init(|| {
        toml::from_str::<LocalesFile>(include_str!("../data/locales.toml"))
            .expect("embedded locales.toml is valid")
            .locales
    })
}

/// Accepts `zh-TW`, `zh_TW`; falls back from `vi-VN` to `vi`.
pub fn profile(code: &str) -> Result<&'static LocaleProfile, L10nError> {
    let code = code.replace('_', "-");
    let all = profiles();
    all.get(&code)
        .or_else(|| code.split('-').next().and_then(|base| all.get(base)))
        .ok_or(L10nError::UnknownLocale(code))
}

impl LocaleProfile {
    pub fn register(&self, ctx: Context) -> &Register {
        // Every context is present: checked by tests::embedded_data_is_complete.
        &self.register[&ctx]
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn embedded_data_is_complete() {
        let all = profiles();
        assert!(all.len() >= 20, "expected 20+ locales, got {}", all.len());
        for (code, p) in all {
            for ctx in Context::ALL {
                let r = p.register.get(&ctx).unwrap_or_else(|| panic!("{code} missing {ctx:?}"));
                assert!(!r.tone.is_empty() && !r.address.is_empty(), "{code} {ctx:?} empty");
            }
            assert!(!p.notes.is_empty(), "{code} has no notes");
        }
    }

    #[test]
    fn locale_lookup_normalises_and_falls_back() {
        assert_eq!(profile("zh_TW").unwrap().name, "Chinese (Traditional, Taiwan)");
        assert_eq!(profile("vi-VN").unwrap().name, "Vietnamese");
        assert!(profile("xx").is_err());
    }
}
