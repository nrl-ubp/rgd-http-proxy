package com.ubp.rgd.proxy.services.wdx1;

public final class NameFolds {

    private NameFolds() {}

    /**
     * Apply single-letter folds for special Latin letters and ligatures,
     * then remove any remaining non A–Z/a–z characters.
     * Intended to run AFTER:
     *  - Optional ICU transliteration (Any-Latin)
     *  - Unicode NFD and removal of combining marks (\p{M})
     * Examples enforced by this method:
     *  - Æ/æ -> A/a
     *  - Œ/œ -> O/o
     *  - ß -> s, ẞ -> S
     *  - Ł/ł -> L/l
     *  - Þ/þ -> T/t
     *  - ð -> d
     *  - Ø/ø -> O/o
     *  - ı (dotless i) -> i
     * Additionally, it removes any characters outside [A-Za-z].
     */
    public static String foldToSingleBaseLettersAndStripNonAsciiLetters(String input) {
        if (input == null || input.isEmpty()) return "";

        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            char mapped = mapSingleBase(ch);
            if (isAsciiLetter(mapped)) {
                sb.append(mapped);
            }
            // else drop
        }
        return sb.toString();
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    /**
     * Map special letters/ligatures to a single ASCII base letter.
     * Characters already in ASCII A–Z/a–z are returned unchanged.
     * Unknown or unsupported characters map to '\0' (caller drops them).
     */
    private static char mapSingleBase(char ch) {
        // Fast path: ASCII letters unchanged
        if (isAsciiLetter(ch)) return ch;

        switch (ch) {
            // AE ligature -> A/a
            case '\u00C6' -> {
                return 'A'; // Æ
            }
            case '\u00E6' -> {
                return 'a'; // æ
            }

            // OE ligature -> O/o
            case '\u0152' -> {
                return 'O'; // Œ
            }
            case '\u0153' -> {
                return 'o'; // œ
            }

            // Eszett -> S/s (single-letter fold per your policy)
            case '\u1E9E' -> {
                return 'S'; // ẞ
            }
            case '\u00DF' -> {
                return 's'; // ß
            }

            // Polish L with stroke -> L/l
            case '\u0141' -> {
                return 'L'; // Ł
            }
            case '\u0142' -> {
                return 'l'; // ł
            }

            // Thorn -> T/t
            case '\u00DE' -> {
                return 'T'; // Þ
            }
            case '\u00FE' -> {
                return 't'; // þ
            }

            // Eth -> d
            case '\u00F0' -> {
                return 'd'; // ð
            }
            case '\u00D0' -> {
                return 'D'; // Ð (uppercase eth, if encountered)
            }

            // O with stroke -> O/o
            case '\u00D8' -> {
                return 'O'; // Ø
            }
            case '\u00F8' -> {
                return 'o'; // ø
            }

            // Dotless i -> i
            case '\u0131' -> {
                return 'i'; // ı
            }

            // Icelandic/Scandinavian variants sometimes appear after partial normalization
            // (kept here for completeness; most diacritics are removed earlier):
            // ﬀ -> f (if any legacy ligature sneaks through)
            // ﬁ
            // ﬂ
            // ﬃ
            // ﬄ
            // ﬅ
            case '\uFB00', '\uFB01', '\uFB02', '\uFB03', '\uFB04', '\uFB05', '\uFB06' -> {
                // Because policy is single-letter fold only and not digraphs, map to base 'f' or 's' rough approximation:
                // However, these are Latin presentation forms; ideally they'd be decomposed earlier by NFKC.
                // We'll drop them rather than guess multi-letters. Return '\0' to drop.
                return '\0'; // ﬆ
                // Because policy is single-letter fold only and not digraphs, map to base 'f' or 's' rough approximation:
                // However, these are Latin presentation forms; ideally they'd be decomposed earlier by NFKC.
                // We'll drop them rather than guess multi-letters. Return '\0' to drop.
            }
            default -> {
                // Many other letters with diacritics should have had their marks removed earlier.
                // If any non-ASCII remains here, we drop it to enforce [A-Za-z] only.
                return '\0';
            }
        }
    }
}

