package com.botchat.app.filters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.drinkless.tdlib.TdApi;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class FilterRulesTest {

    private static final FilterRules RULES = FilterRules.of(
            Arrays.asList("jubilate"),
            Arrays.asList("logo", "5071069394380721250_121.jpg"));

    private static TdApi.FormattedText formatted(String text) {
        TdApi.FormattedText f = new TdApi.FormattedText();
        f.text = text;
        f.entities = new TdApi.TextEntity[0];
        return f;
    }

    private static TdApi.Message message(TdApi.MessageContent content, boolean outgoing) {
        TdApi.Message m = new TdApi.Message();
        m.content = content;
        m.isOutgoing = outgoing;
        return m;
    }

    private static TdApi.Message text(String text, boolean outgoing) {
        TdApi.MessageText content = new TdApi.MessageText();
        content.text = formatted(text);
        return message(content, outgoing);
    }

    private static TdApi.Message photo(String caption, boolean outgoing) {
        TdApi.MessagePhoto content = new TdApi.MessagePhoto();
        content.caption = formatted(caption);
        return message(content, outgoing);
    }

    private static TdApi.Message document(String fileName, String caption) {
        TdApi.MessageDocument content = new TdApi.MessageDocument();
        content.document = new TdApi.Document();
        content.document.fileName = fileName;
        content.caption = formatted(caption);
        return message(content, false);
    }

    // --- texto -------------------------------------------------------------------------------

    @Test
    public void shouldHide_textoQueEraSoloLaPalabraSeOculta() {
        assertTrue(RULES.shouldHide(text("jubilate", false)));
        assertTrue(RULES.shouldHide(text("  JUBILATE ", false)));
    }

    @Test
    public void shouldHide_textoConMasContenidoNoSeOculta() {
        assertFalse(RULES.shouldHide(text("hola jubilate", false)));
        assertFalse(RULES.shouldHide(text("hola", false)));
    }

    @Test
    public void shouldHide_tusPropiosMensajesNuncaSeOcultan() {
        assertFalse(RULES.shouldHide(text("jubilate", true)));
    }

    @Test
    public void shouldHide_lasFotosNoSeOcultanEnteras() {
        assertFalse(RULES.shouldHide(photo("mi logo", false)));
    }

    @Test
    public void shouldHide_conReglasVaciasNoOcultaNada() {
        assertFalse(FilterRules.EMPTY.shouldHide(text("jubilate", false)));
    }

    // --- fotos y archivos ------------------------------------------------------------------------

    @Test
    public void mediaMatches_fotoConPieQueContieneElNombre() {
        assertTrue(RULES.mediaMatches(photo("mi LOGO aquí", false)));
        assertFalse(RULES.mediaMatches(photo("hola", false)));
    }

    @Test
    public void mediaMatches_documentoPorNombreDeArchivo() {
        assertTrue(RULES.mediaMatches(document("logo_empresa.png", "")));
        assertFalse(RULES.mediaMatches(document("otro.pdf", "nada")));
    }

    @Test
    public void mediaMatches_ignoraTildesYMayusculas() {
        FilterRules rules = FilterRules.of(Collections.<String>emptyList(), Arrays.asList("cámara"));
        assertTrue(rules.mediaMatches(document("CAMARA.jpg", "")));
    }

    @Test
    public void mediaMatches_tusPropiosMensajesNoSeFiltran() {
        assertFalse(RULES.mediaMatches(photo("logo", true)));
    }

    @Test
    public void mediaMatches_stickersYTextoNoSeFiltran() {
        assertFalse(RULES.mediaMatches(message(new TdApi.MessageSticker(), false)));
        assertFalse(RULES.mediaMatches(text("logo", false)));
    }

    @Test
    public void mediaMatches_sinListaDeImagenesNoOcultaNada() {
        FilterRules onlyWords = FilterRules.of(Arrays.asList("jubilate"), Collections.<String>emptyList());
        assertFalse(onlyWords.mediaMatches(photo("mi logo", false)));
    }

    @Test
    public void matchesPhotoFile_valeParaTodosLosTamanosDeLaMismaFoto() {
        assertTrue(RULES.matchesPhotoFile("/data/x/5071069394380721250_121.jpg"));
        assertTrue(RULES.matchesPhotoFile("/data/x/5071069394380721250_320.jpg"));
        assertFalse(RULES.matchesPhotoFile("/data/x/999999999999_121.jpg"));
        assertFalse(RULES.matchesPhotoFile(null));
    }

    @Test
    public void matchesPhotoFile_nombreCortoCoincidePorContenido() {
        assertTrue(RULES.matchesPhotoFile("/data/x/mi_logo_grande.jpg"));
    }

    // --- pie de foto -------------------------------------------------------------------------------

    @Test
    public void captionOf_devuelveElPieDeFotoYDocumento() {
        assertEquals("mi pie", FilterRules.captionOf(photo("mi pie", false).content).text);
        assertEquals("otro", FilterRules.captionOf(document("a.pdf", "otro").content).text);
    }

    @Test
    public void captionOf_sinPieDevuelveNull() {
        assertNull(FilterRules.captionOf(text("x", false).content));
        assertNull(FilterRules.captionOf(null));
    }

    // --- listas ------------------------------------------------------------------------------------------

    @Test
    public void of_descartaVaciosYNormaliza() {
        FilterRules rules = FilterRules.of(Arrays.asList("  ", "Hoy", ""), Arrays.asList("LOGO", " "));
        assertTrue(rules.hasWords());
        assertTrue(rules.hasImages());
        assertEquals("hola", rules.strip("hola hoy"));
        assertNotNull(rules.ranges("hoy"));
    }

    @Test
    public void empty_noTieneNada() {
        assertFalse(FilterRules.EMPTY.hasWords());
        assertFalse(FilterRules.EMPTY.hasImages());
    }
}
