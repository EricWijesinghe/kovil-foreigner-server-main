use std::path::Path;
use std::process::ExitCode;

use kovil_l10n::{Context, Issue, Severity, app_catalog, catalog, profile, prompt, validate};

const USAGE: &str = "usage:
  kovil-l10n locales
  kovil-l10n prompt <locale> <legal|transactional|support|community> <source text>
  kovil-l10n check  <locale> <source text> <translation> [max_chars]
  kovil-l10n lint   <messages dir>
  kovil-l10n app-lint <app catalogs dir>   (mobile app src/i18n/catalogs, en.json = source)";

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let a: Vec<&str> = args.iter().map(String::as_str).collect();
    let result: Result<bool, String> = match a.as_slice() {
        ["locales"] => {
            for (code, p) in profile::profiles() {
                println!("{code:6} {:30} {:20} {:?}", p.name, p.native, p.script);
            }
            Ok(true)
        }
        ["prompt", loc, ctx, text @ ..] if !text.is_empty() => Context::parse(ctx)
            .and_then(|c| prompt::system_prompt(loc, c, &text.join(" ")))
            .map(|s| {
                println!("{s}");
                true
            })
            .map_err(|e| e.to_string()),
        ["check", loc, src, out, rest @ ..] if rest.len() <= 1 => {
            let max = match rest.first().map(|m| m.parse::<usize>()) {
                Some(Err(_)) => return usage_error(),
                Some(Ok(m)) => Some(m),
                None => None,
            };
            validate::validate(src, out, loc, max)
                .map(|issues| report(issues.iter().map(|i| (String::new(), i))))
                .map_err(|e| e.to_string())
        }
        ["lint", dir] => catalog::lint_dir(Path::new(dir))
            .map(|found| {
                report(found.iter().map(|f| (format!("{} {}: ", f.file, f.key.as_deref().unwrap_or("-")), &f.issue)))
            })
            .map_err(|e| format!("{dir}: {e}")),
        ["app-lint", dir] => app_catalog::lint_dir(Path::new(dir))
            .map(|found| {
                report(found.iter().map(|f| (format!("{} {}: ", f.file, f.key.as_deref().unwrap_or("-")), &f.issue)))
            })
            .map_err(|e| format!("{dir}: {e}")),
        _ => return usage_error(),
    };
    match result {
        Ok(true) => ExitCode::SUCCESS,
        Ok(false) => ExitCode::FAILURE,
        Err(e) => {
            eprintln!("{e}");
            ExitCode::from(2)
        }
    }
}

fn usage_error() -> ExitCode {
    eprintln!("{USAGE}");
    ExitCode::from(2)
}

/// Prints issues; returns false if any is an error.
fn report<'a>(items: impl Iterator<Item = (String, &'a Issue)>) -> bool {
    let (mut errors, mut warnings) = (0, 0);
    for (prefix, i) in items {
        match i.severity {
            Severity::Error => errors += 1,
            Severity::Warning => warnings += 1,
        }
        println!("{:?} [{}] {prefix}{}", i.severity, i.code, i.message);
    }
    println!("{errors} error(s), {warnings} warning(s)");
    errors == 0
}
