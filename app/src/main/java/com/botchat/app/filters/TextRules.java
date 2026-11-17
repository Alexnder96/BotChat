package com.botchat.app.filters;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reglas de texto de los filtros, sin nada de Android (se pueden probar con tests unitarios):
 * comparar sin mayúsculas ni tildes y quitar palabras completas de un texto.
 */
public final class TextRules {

    private TextRules() {
    }

    /** Minúsculas y sin tildes, para comparar sin fijarse en cómo está escrito. */
    public static String normalize(String value) {
        return value == null ? "" : fold(value).trim().replaceAll(" {2,}", " ");
    }

    /**
     * Minúsculas y sin tildes, letra por letra: el resultado mide EXACTAMENTE lo mismo que el original,
     * así una posición en el texto plegado es la misma posición en el texto real.
     */
    public static String fold(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isBlank(c)) {
                out.append(' ');
            } else if (c < 128) {
                out.append(Character.toLowerCase(c));
            } else {
                String decomposed = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
                out.append(Character.toLowerCase(decomposed.isEmpty() ? c : decomposed.charAt(0)));
            }
        }
        return out.toString();
    }

    /**
     * Dónde están, dentro de text, las palabras a quitar (ya normalizadas): lista de [inicio, fin) ordenada
     * y sin solaparse. Solo cuenta la palabra completa ("hoy" no se quita de "hoyo"). Incluye los espacios
     * sobrantes que quedarían al borrar la palabra.
     */
    public static List<int[]> ranges(String text, List<String> normWords) {
        List<int[]> result = new ArrayList<>();
        if (normWords == null || normWords.isEmpty() || text == null || text.isEmpty()) {
            return result;
        }
        String folded = fold(text);
        List<int[]> found = new ArrayList<>();
        for (String word : normWords) {
            if (word.isEmpty()) {
                continue;
            }
            Matcher m = Pattern.compile(toRegex(word)).matcher(folded);
            while (m.find()) {
                if (isWholeWord(folded, m.start(), m.end(), word)) {
                    found.add(new int[]{m.start(), m.end()});
                }
            }
        }
        // Se amplía cada hallazgo para no dejar dobles espacios ni un espacio antes del punto.
        for (int[] range : found) {
            int start = range[0];
            int end = range[1];
            boolean afterSpaceOrStart = start == 0 || text.charAt(start - 1) == ' ' || text.charAt(start - 1) == '\n';
            if (afterSpaceOrStart) {
                while (end < text.length() && text.charAt(end) == ' ') {
                    end++;
                }
            }
            boolean atEnd = end >= text.length() || text.charAt(end) == '\n' || ",.;:!?)".indexOf(text.charAt(end)) >= 0;
            if (atEnd) {
                while (start > 0 && text.charAt(start - 1) == ' ') {
                    start--;
                }
            }
            range[0] = start;
            range[1] = end;
        }
        found.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        for (int[] range : found) {
            int[] last = result.isEmpty() ? null : result.get(result.size() - 1);
            if (last != null && range[0] <= last[1]) {
                last[1] = Math.max(last[1], range[1]);
            } else {
                result.add(new int[]{range[0], range[1]});
            }
        }
        return result;
    }

    /** El texto sin las palabras (ya normalizadas) indicadas. */
    public static String strip(String text, List<String> normWords) {
        List<int[]> ranges = ranges(text, normWords);
        if (ranges.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text);
        for (int i = ranges.size() - 1; i >= 0; i--) {
            out.delete(ranges.get(i)[0], ranges.get(i)[1]);
        }
        return out.toString();
    }

    /** Espacio de cualquier tipo: normal, tabulador, salto de línea o espacio de no separación (nbsp). */
    private static boolean isBlank(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /** Una frase con espacios coincide aunque en el mensaje haya varios espacios o saltos de línea entre palabras. */
    private static String toRegex(String word) {
        StringBuilder regex = new StringBuilder();
        for (String part : word.split(" +")) {
            if (part.isEmpty()) {
                continue;
            }
            if (regex.length() > 0) {
                regex.append(" +");
            }
            regex.append(Pattern.quote(part));
        }
        return regex.toString();
    }

    private static boolean isWholeWord(String folded, int start, int end, String word) {
        if (Character.isLetterOrDigit(word.charAt(0)) && start > 0
                && Character.isLetterOrDigit(folded.charAt(start - 1))) {
            return false;
        }
        return !(Character.isLetterOrDigit(word.charAt(word.length() - 1)) && end < folded.length()
                && Character.isLetterOrDigit(folded.charAt(end)));
    }
}