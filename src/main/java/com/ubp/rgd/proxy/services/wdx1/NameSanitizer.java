package com.ubp.rgd.proxy.services.wdx1;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

// If you can add ICU4J, this improves non-Latin transliteration:
import com.ibm.icu.text.Transliterator;

public class NameSanitizer {

    // Multi-word and single-word prefixes to remove when they occur at the start as separate words
    // These are case-insensitive and must match token boundaries.
    private static final List<String> PREFIXES = Arrays.asList(
            "am", "auf", "auf dem", "aus der", "d", "da", "de", "de l’", "del", "de la", "de le",
            "di", "do", "dos", "du", "im", "la", "le", "mac", "mc", "mhac", "mhíc", "mhic giolla", "mic",
            "ni", "ní", "níc", "o", "ó", "ua", "ui", "uí", "van", "van de", "van den", "van der",
            "vom", "von", "von dem", "von den", "von der"
    );

    // Build a normalized, lowercase prefix set for fast match
    // We’ll match from the longest (multi-word) prefixes first.
    private static final List<String> SORTED_PREFIXES;

    // Regex to remove punctuation-like characters (apostrophes, hyphens, punctuation, spaces)
    private static final Pattern PUNCT_OR_SPACE = Pattern.compile("[\\p{Punct}\\p{IsWhite_Space}\\u00A0\\u2011\\u2010\\u2012\\u2013\\u2014\\u02b9]+");

    static {
        // Normalize prefixes by removing diacritics and lowercasing, keeping spaces to match tokens
        List<String> norm = new ArrayList<>();
        for (String p : PREFIXES) {
            norm.add(stripDiacritics(p).toLowerCase(Locale.ROOT).trim());
        }
        // Sort by length descending so multi-word prefixes match before single-word
        norm.sort((a, b) -> Integer.compare(b.length(), a.length()));
        SORTED_PREFIXES = Collections.unmodifiableList(norm);
    }

    private static final Transliterator ANY_TO_LATIN_ASCII =
            Transliterator.getInstance("Any-Latin; NFD; [:Nonspacing Mark:] Remove; NFC");

    /**
     * Public API: sanitize a full name per the described rules.
     * - Remove listed surname prefixes when they appear as separate leading tokens.
     * - Transliterate to Latin if non-Latin (ICU4J optional).
     * - Remove diacritics.
     * - Remove apostrophes, hyphens, punctuation, and spaces.
     * - Preserve A–Z/a–z letters otherwise.
     */
    public static String sanitizeName(String name) {
        if (name == null) return "";
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return "";

        // Step 1: If non-Latin present and no Latin characters, transliterate to Latin (optional with ICU4J).
        String latinized = transliterateNonLatinIfNeeded(trimmed);

        // Step 2: Remove only the listed surname prefixes at the beginning when they are separate tokens.
        String withoutPrefixes = removeListedLeadingPrefixes(latinized);

        // Step 3: Remove diacritics (accents) from the remaining characters.
        String noDiacritics = stripDiacritics(withoutPrefixes);

        // Step 4 : Remove folded letter (ligatures)
        String folded = NameFolds.foldToSingleBaseLettersAndStripNonAsciiLetters(noDiacritics);

        // Step 5: Remove apostrophes, hyphens, punctuation, and spaces.
        String squashed = PUNCT_OR_SPACE.matcher(folded).replaceAll("");

        // Final: Return uppercase or lowercase as needed. The spec doesn’t force case;
        // keep original casing of letters minus diacritics and punctuation.
        // If a normalized casing is desired, uncomment one of the below:
        squashed = squashed.toUpperCase(Locale.ROOT);
        // squashed = squashed.toLowerCase(Locale.ROOT);

        return squashed;
    }

    // Remove the specified prefixes if they appear at the start as stand-alone tokens.
    private static String removeListedLeadingPrefixes(String input) {
        // We match using a normalized, lowercase view for comparison,
        // but we remove from the original string based on token indices.
        String normLower = stripDiacritics(input).toLowerCase(Locale.ROOT);

        // Tokenize by whitespace to determine leading token sequences
        // Keep track of original positions.
        List<Token> tokens = tokenizeWithPositions(input);
        if (tokens.isEmpty()) return input;

        // Build a normalized view of tokens (strip diacritics + lowercase)
        List<String> normTokens = new ArrayList<>(tokens.size());
        for (Token t : tokens) {
            String s = stripDiacritics(t.text).toLowerCase(Locale.ROOT);
            normTokens.add(s);
        }

        int consumedTokens = 0;

        // Try to match the longest prefixes first
        for (String prefix : SORTED_PREFIXES) {
            // Split the normalized prefix into tokens
            String[] pTokens = prefix.split("\\s+");
            if (pTokens.length == 0) continue;
            if (pTokens.length > normTokens.size()) continue;

            // Check if the beginning tokens match the prefix tokens
            boolean allMatch = true;
            for (int i = 0; i < pTokens.length; i++) {
                // Requirement: prefix must be separate tokens. We compare token to token.
                if (!normTokens.get(i).equals(pTokens[i])) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                consumedTokens = Math.max(consumedTokens, pTokens.length);
                // Don’t break; there might be a longer prefix also matching? We’ve already sorted by length desc,
                // but to support multiple sequential prefixes (rare), we can continue checking:
                // For strict single-prefix removal, uncomment the break.
                // break outer;
            }
        }

        if (consumedTokens == 0) {
            return input;
        }

        // Remove the consumed leading tokens in the original string using character positions
        int startIndex = tokens.getFirst().start;
        int endIndex = tokens.get(consumedTokens - 1).end;

        // Remove everything from startIndex to endIndex and any immediate whitespace following
        StringBuilder sb = new StringBuilder();
        sb.append(input, 0, startIndex);

        // Skip the matched prefix and any spaces immediately after
        int i = endIndex;
        while (i < input.length() && Character.isWhitespace(input.charAt(i))) {
            i++;
        }
        sb.append(input.substring(i));
        return sb.toString().trim();
    }

    private static List<Token> tokenizeWithPositions(String s) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            // skip spaces
            while (i < n && Character.isWhitespace(s.charAt(i))) i++;
            if (i >= n) break;
            int start = i;
            while (i < n && !Character.isWhitespace(s.charAt(i))) i++;
            int end = i;
            tokens.add(new Token(s.substring(start, end), start, end));
        }
        return tokens;
    }

    private record Token(String text, int start, int end) {
    }

    // Remove diacritics using Unicode normalization.
    private static String stripDiacritics(String input) {
        if (input == null) return "";
        String norm = Normalizer.normalize(input, Normalizer.Form.NFD);
        // Remove combining marks
        return norm.replaceAll("\\p{M}+", "");
    }

    // Attempt non-Latin transliteration:
    // - If ICU4J is available, transliterate to Latin.
    // - Otherwise, if the string contains no ASCII letters, strip non-ASCII as a fallback.
    private static String transliterateNonLatinIfNeeded(String input) {
        if (containsAsciiLetter(input)) {
            return input;
        }

        try {
            return ANY_TO_LATIN_ASCII.transliterate(input);
        } catch (Exception ex) {
            // fallback implementation if transliterate fails.
        }

        // Fallback: attempt a simple mapping by removing non-ASCII after decomposition.
        // This isn’t a true transliteration but prevents returning empty for scripts with no latin form.
        String decomposed = Normalizer.normalize(input, Normalizer.Form.NFD);
        String stripped = decomposed.replaceAll("\\p{M}+", "");
        // Remove non-ASCII characters
        String asciiOnly = stripped.replaceAll("[^\\p{ASCII}]", "");
        // If this yields empty, return original to avoid data loss
        return asciiOnly.isEmpty() ? input : asciiOnly;
    }

    private static boolean containsAsciiLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) return true;
        }
        return false;
    }

    // Example main for quick tests
    public static void main(String[] args) {
        String[] tests = {
                "van der Waals",         // remove "van der" -> "Waals" -> "Waals" -> "Waals" -> "Waals" -> "Waals"
                "Von    Neumann",        // remove "von" -> "Neumann" -> "Neumann"
                "de la Cruz",            // -> "Cruz"
                "Mc Donald",             // remove "mc"? No, because attached prefixes rule says only listed prefixes as separate tokens
                // here "Mc" is separate token though; requirement says standalone prefixes in the list ARE removed.
                // If you want to keep 'Mc' only when attached, merge tokens in input; see note below.
                "O'Brian",               // do not remove 'O' as prefix because it's attached with apostrophe; then strip apostrophe -> "OBrian"
                "Ó Conchobhair",         // remove "Ó" -> "Conchobhair" -> strip diacritics -> "Conchobhair" -> remove space -> "Conchobhair"
                "Николай Гоголь",        // transliterate (ICU recommended). Fallback may reduce to empty or partial
                "Αριστοτέλης",           // transliterate (ICU recommended)
                "van-den Berg",          // hyphenated prefix not separate token -> not removed, then hyphen removed -> "vandenBerg" -> "vandenBerg"
                "mhic giolla Bríghde"    // remove "mhic giolla" -> "Brighde" -> "Brighde"
        };
        for (String t : tests) {
            System.out.println(t + " -> " + sanitizeName(t));
        }
    }
}

