use serde::Deserialize;

/// Writing system a locale must be written in. Used to catch output in the wrong language.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Script {
    Latin,
    Hangul,
    Han,
    /// Kana + kanji.
    Japanese,
    Cyrillic,
    Thai,
    Devanagari,
    Bengali,
    Sinhala,
    Khmer,
    Myanmar,
    Arabic,
}

pub fn is_hangul(c: char) -> bool {
    matches!(c, '\u{AC00}'..='\u{D7A3}' | '\u{1100}'..='\u{11FF}' | '\u{3130}'..='\u{318F}')
}

pub fn is_kana(c: char) -> bool {
    matches!(c, '\u{3040}'..='\u{30FF}')
}

fn is_han(c: char) -> bool {
    matches!(c, '\u{4E00}'..='\u{9FFF}' | '\u{3400}'..='\u{4DBF}')
}

pub fn is_latin(c: char) -> bool {
    c.is_ascii_alphabetic() || matches!(c, '\u{00C0}'..='\u{024F}' | '\u{1E00}'..='\u{1EFF}')
}

impl Script {
    pub fn contains(self, c: char) -> bool {
        match self {
            Self::Latin => is_latin(c),
            Self::Hangul => is_hangul(c),
            Self::Han => is_han(c),
            Self::Japanese => is_kana(c) || is_han(c),
            Self::Cyrillic => matches!(c, '\u{0400}'..='\u{04FF}'),
            Self::Thai => matches!(c, '\u{0E00}'..='\u{0E7F}'),
            Self::Devanagari => matches!(c, '\u{0900}'..='\u{097F}'),
            Self::Bengali => matches!(c, '\u{0980}'..='\u{09FF}'),
            Self::Sinhala => matches!(c, '\u{0D80}'..='\u{0DFF}'),
            Self::Khmer => matches!(c, '\u{1780}'..='\u{17FF}'),
            Self::Myanmar => matches!(c, '\u{1000}'..='\u{109F}'),
            Self::Arabic => {
                matches!(c, '\u{0600}'..='\u{06FF}' | '\u{0750}'..='\u{077F}' | '\u{FB50}'..='\u{FDFF}' | '\u{FE70}'..='\u{FEFF}')
            }
        }
    }
}
