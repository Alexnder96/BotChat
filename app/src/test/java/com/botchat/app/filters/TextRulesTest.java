package com.botchat.app.filters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TextRulesTest {

    private static List<String> words(String... values) {
        return Arrays.asList(values);
    }

    @Test
    public void fold_ignoraMayusculasYTildesSinCambiarLaLongitud() {
        assertEquals("camion", TextRules.fold("Camión"));
        assertEquals("Camión".length(), TextRules.fold("Camión").length());
        assertEquals("ñandú".length(), TextRules.fold("ÑANDÚ").length());
    }

    @Test
    public void normalize_recortaEspaciosYNull() {
        assertEquals("hola", TextRules.normalize("  HoLa "));
        assertEquals("", TextRules.normalize(null));
    }

    @Test
    public void strip_quitaLaPalabraAlFinal() {
        assertEquals("hola solo por hoy", TextRules.strip("hola solo por hoy jubilate", words("jubilate")));
    }

    @Test
    public void strip_quitaLaPalabraEnElMedioSinDejarDobleEspacio() {
        assertEquals("hola gracias", TextRules.strip("hola jubilate gracias", words("jubilate")));
    }

    @Test
    public void strip_quitaLaPalabraAlPrincipio() {
        assertEquals("hola", TextRules.strip("jubilate hola", words("jubilate")));
    }

    @Test
    public void strip_noDejaEspacioAntesDelPunto() {
        assertEquals("hola.", TextRules.strip("hola jubilate.", words("jubilate")));
    }

    @Test
    public void strip_soloCuentaLaPalabraCompleta() {
        assertEquals("hoyo", TextRules.strip("hoyo", words("hoy")));
        assertEquals("hoyo y", TextRules.strip("hoyo y hoy", words("hoy")));
    }

    @Test
    public void strip_ignoraMayusculasYTildes() {
        assertEquals("Un rojo", TextRules.strip("Un CAMIÓN rojo", words("camion")));
    }

    @Test
    public void strip_quitaTodasLasApariciones() {
        assertEquals("hola gracias", TextRules.strip("jubilate hola jubilate gracias jubilate", words("jubilate")));
    }

    @Test
    public void strip_conVariasPalabras() {
        assertEquals("hola", TextRules.strip("uno hola dos", words("uno", "dos")));
    }

    @Test
    public void strip_sinPalabrasDevuelveElMismoTexto() {
        String text = "hola mundo";
        assertSame(text, TextRules.strip(text, Collections.<String>emptyList()));
    }

    @Test
    public void strip_quedaVacioSiEraSoloLaPalabra() {
        assertTrue(TextRules.strip("jubilate", words("jubilate")).trim().isEmpty());
    }

    @Test
    public void ranges_estanOrdenadosYNoSeSolapan() {
        List<int[]> ranges = TextRules.ranges("ab cd ab", words("ab", "cd"));
        int lastEnd = -1;
        for (int[] range : ranges) {
            assertTrue(range[0] >= lastEnd);
            assertTrue(range[1] > range[0]);
            lastEnd = range[1];
        }
    }
}
