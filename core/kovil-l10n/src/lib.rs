//! KOVIL localization core.
//!
//! Translations sound stiff when the model gets no guidance on register, pronouns or local terms.
//! This crate supplies that guidance ([`prompt`]) and checks the result ([`validate`]) so a bad
//! translation is caught before it reaches a user.

pub mod app_catalog;
pub mod catalog;
pub mod glossary;
pub mod profile;
pub mod prompt;
pub mod script;
pub mod terms;
pub mod validate;

pub use profile::{Context, LocaleProfile};
pub use validate::{Issue, Severity};

#[derive(Debug, PartialEq, Eq)]
pub enum L10nError {
    UnknownLocale(String),
    UnknownContext(String),
}

impl std::fmt::Display for L10nError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::UnknownLocale(l) => write!(f, "unknown locale '{l}' (run `kovil-l10n locales`)"),
            Self::UnknownContext(c) => {
                write!(f, "unknown context '{c}' (legal|transactional|support|community)")
            }
        }
    }
}

impl std::error::Error for L10nError {}
