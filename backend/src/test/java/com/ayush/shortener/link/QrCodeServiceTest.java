package com.ayush.shortener.link;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** The strongest check available: draw the code, then read it back with a real QR decoder. */
class QrCodeServiceTest {

    private final QrCodeService qr = new QrCodeService();

    private static String decode(LuminanceSource source) throws Exception {
        return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
    }

    private static String decodePng(byte[] png) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        return decode(new RGBLuminanceSource(image.getWidth(), image.getHeight(), pixels));
    }

    /** Rebuilds the module grid from the SVG path, paints it at 8px per module and decodes that. */
    private static String decodeSvg(String svg) throws Exception {
        Matcher box = Pattern.compile("viewBox=\"0 0 (\\d+) \\d+\"").matcher(svg);
        assertThat(box.find()).isTrue();
        int n = Integer.parseInt(box.group(1));
        int scale = 8;
        int[] pixels = new int[n * scale * n * scale];
        java.util.Arrays.fill(pixels, 0xFFFFFFFF);
        Matcher run = Pattern.compile("M(\\d+) (\\d+)h(\\d+)v1h-\\d+z").matcher(svg);
        while (run.find()) {
            int x0 = Integer.parseInt(run.group(1));
            int y0 = Integer.parseInt(run.group(2));
            int len = Integer.parseInt(run.group(3));
            for (int y = y0 * scale; y < (y0 + 1) * scale; y++) {
                for (int x = x0 * scale; x < (x0 + len) * scale; x++) {
                    pixels[y * n * scale + x] = 0xFF000000;
                }
            }
        }
        return decode(new RGBLuminanceSource(n * scale, n * scale, pixels));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:8080/linkly-KZrNBm",
            "https://sho.rt/show-hn",
            "https://sho.rt/a",
            "https://example.com/some/path?with=query&and=more#fragment",
            "https://sho.rt/linkly-AbCdEf1234567890",
    })
    void thePngDecodesBackToTheExactUrl(String url) throws Exception {
        assertThat(decodePng(qr.png(url, 512))).isEqualTo(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8080/linkly-KZrNBm", "https://sho.rt/show-hn", "https://example.com/p?q=1&r=2"})
    void theSvgDecodesBackToTheExactUrl(String url) throws Exception {
        assertThat(decodeSvg(qr.svg(url, 256))).isEqualTo(url);
    }

    @Test
    void pngSizeIsAWholeMultipleOfTheModuleCountThatFitsTheTarget() throws Exception {
        String url = "http://localhost:8080/linkly-KZrNBm";
        int modules = qr.matrix(url).getWidth();
        for (int target : new int[] {64, 300, 512, 1024, 2048}) {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(qr.png(url, target)));
            assertThat(image.getWidth()).isEqualTo(image.getHeight());
            assertThat(image.getWidth() % modules).as("whole pixels per module at %d", target).isZero();
            assertThat(image.getWidth()).isLessThanOrEqualTo(Math.max(target, modules));
            assertThat(image.getWidth()).isGreaterThan(target - modules);
        }
    }

    @Test
    void aTargetSmallerThanTheCodeStillGivesAReadableOnePixelPerModuleCode() throws Exception {
        String url = "http://localhost:8080/linkly-KZrNBm";
        byte[] png = qr.png(url, 10);
        assertThat(ImageIO.read(new ByteArrayInputStream(png)).getWidth()).isEqualTo(qr.matrix(url).getWidth());
    }

    @Test
    void keepsTheQuietZoneWhiteBecauseScannersNeedIt() throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(qr.png("https://sho.rt/abc", 512)));
        int scale = image.getWidth() / qr.matrix("https://sho.rt/abc").getWidth();
        int margin = 4 * scale;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                boolean inMargin = x < margin || y < margin || x >= image.getWidth() - margin || y >= image.getHeight() - margin;
                if (inMargin) {
                    assertThat(image.getRGB(x, y) & 0xFFFFFF).isEqualTo(0xFFFFFF);
                }
            }
        }
    }

    @Test
    void theSvgIsWellFormedScalableAndCarriesItsDisplaySize() throws Exception {
        String svg = qr.svg("http://localhost:8080/linkly-KZrNBm", 300);
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var root = factory.newDocumentBuilder().parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
        assertThat(root.getTagName()).isEqualTo("svg");
        assertThat(root.getAttribute("width")).isEqualTo("300");
        assertThat(root.getAttribute("viewBox")).matches("0 0 \\d+ \\d+");
        assertThat(svg).contains("fill=\"#fff\"").contains("fill=\"#000\"").doesNotContain("NaN").doesNotContain("script");
    }

    @Test
    void sameInputGivesIdenticalOutput() {
        assertThat(qr.png("https://sho.rt/x", 400)).isEqualTo(qr.png("https://sho.rt/x", 400));
        assertThat(qr.svg("https://sho.rt/x", 400)).isEqualTo(qr.svg("https://sho.rt/x", 400));
    }

    @Test
    void differentLinksGiveDifferentCodes() {
        assertThat(qr.svg("https://sho.rt/one", 200)).isNotEqualTo(qr.svg("https://sho.rt/two", 200));
    }
}
