// Generates dml/07_country_names_cldr.sql: country names in every app language from Unicode CLDR,
// the locale data shipped in the JDK (no network, no hand translation). Korean is skipped: dml/02 holds
// the curated Korean names and they win.
//
// Run from the repo root:  java dml/tools/GenCountryNames.java > dml/07_country_names_cldr.sql
// Country codes come from dml/02_countries.sql, so the two files cannot drift apart.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GenCountryNames {

    /** App languages (languages.code) except en (name_en is the fallback) and ko (curated in dml/02). */
    static final List<String> LANGUAGES = List.of(
            "vi", "zh", "ja", "ne", "th", "uz", "ru", "mn", "km", "id", "fil", "my", "si", "bn", "hi", "ur", "kk", "ky");

    private static final Pattern COUNTRY_ROW = Pattern.compile("^\\s+\\('([A-Z]{2})', '");

    static List<String> countryCodes(Path dml02) throws IOException {
        List<String> codes = new ArrayList<>();
        for (String line : Files.readAllLines(dml02, StandardCharsets.UTF_8)) {
            Matcher m = COUNTRY_ROW.matcher(line);
            if (m.find()) {
                codes.add(m.group(1));
            }
        }
        return codes;
    }

    /** CLDR name, or null when CLDR has no translation (it then echoes the code or the English name). */
    static String cldrName(String code, String lang) {
        Locale region = new Locale.Builder().setRegion(code).build();
        String name = region.getDisplayCountry(Locale.forLanguageTag(lang));
        boolean untranslated = name.isBlank() || name.equals(code)
                || (!lang.equals("en") && name.equals(region.getDisplayCountry(Locale.ENGLISH)) && !isLatinScript(lang));
        return untranslated ? null : name;
    }

    private static boolean isLatinScript(String lang) {
        return List.of("vi", "uz", "id", "fil").contains(lang);
    }

    static String sqlString(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    static String insertFor(String lang, List<String> codes) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- ── ").append(lang).append('\n');
        sb.append("INSERT INTO country_translations (country_id, language_code, name)\n");
        sb.append("SELECT c.id, '").append(lang).append("', t.name\n");
        sb.append("FROM countries c\n         JOIN (\n");
        boolean first = true;
        for (String code : codes) {
            String name = cldrName(code, lang);
            if (name == null) {
                continue;
            }
            sb.append(first ? "               SELECT " + sqlString(code) + " code, " + sqlString(name) + " name\n"
                            : "               UNION ALL SELECT " + sqlString(code) + ", " + sqlString(name) + "\n");
            first = false;
        }
        sb.append("              ) t ON t.code = c.code\n");
        sb.append("ON DUPLICATE KEY UPDATE name = VALUES(name);\n\n");
        return sb.toString();
    }

    private static void selfCheck() {
        // Known CLDR values; if these fail the JDK's locale data changed and the output must be re-reviewed.
        check(cldrName("NP", "ne"), "नेपाल");
        check(cldrName("VN", "zh"), "越南");
        check(cldrName("LK", "si"), "ශ්‍රී ලංකාව");
        check(cldrName("KR", "ru"), "Республика Корея");
        check(sqlString("Cote d'Ivoire"), "'Cote d''Ivoire'");
    }

    private static void check(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException("self-check failed: expected " + expected + " got " + actual);
        }
    }

    public static void main(String[] args) throws IOException {
        selfCheck();
        List<String> codes = countryCodes(Path.of("dml/02_countries.sql"));
        if (codes.size() != 249) {
            throw new IllegalStateException("expected 249 countries in dml/02, found " + codes.size());
        }
        StringBuilder out = new StringBuilder();
        out.append("""
                -- =============================================================================
                -- dml/07_country_names_cldr.sql — 국가명 번역 (앱 언어 전체, 한국어 제외)
                --
                -- 생성물이다. 손으로 고치지 말고 다시 생성한다:
                --   java dml/tools/GenCountryNames.java > dml/07_country_names_cldr.sql
                -- 출처: Unicode CLDR (JDK 내장 로케일 데이터). 국명 표기는 각 언어의 표준 표기를 따른다.
                -- 한국어는 dml/02 의 실무 표기가 정본이라 여기서 만들지 않는다. CLDR 에 번역이 없는 칸은
                -- 행을 만들지 않는다 — 앱은 name_en 으로 폴백한다(빈 이름은 오지 않는다).
                -- 선행: V39(언어 행 — FK). 멱등: (country_id, language_code) upsert.
                -- =============================================================================

                """);
        for (String lang : LANGUAGES) {
            out.append(insertFor(lang, codes));
        }
        System.out.print(out);
    }
}
