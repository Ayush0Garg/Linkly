package com.ayush.shortener.link;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import javax.imageio.ImageIO;

import com.ayush.shortener.auth.JwtService;
import com.ayush.shortener.user.User;
import com.ayush.shortener.user.UserRepository;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
class QrApiIT {

    @Autowired MockMvc mvc;
    @Autowired LinkService links;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;

    long userId;
    Cookie owner;
    Link link;

    long newUser() {
        return users.save(new User("sub-" + System.nanoTime(), "q" + System.nanoTime() + "@example.com", "Q", null, Instant.now())).getId();
    }

    @BeforeEach
    void setUp() {
        userId = newUser();
        owner = new Cookie("access_token", jwt.issueAccessToken(userId));
        link = links.create(userId, new CreateLinkCommand("https://example.com/landing", null, null));
    }

    static String decode(byte[] png) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        var source = new RGBLuminanceSource(image.getWidth(), image.getHeight(), pixels);
        return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
    }

    @Test
    void ownerGetsAPngThatScansToTheShortUrl() throws Exception {
        MvcResult result = mvc.perform(get("/api/links/" + link.getId() + "/qr").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andReturn();
        byte[] body = result.getResponse().getContentAsByteArray();

        assertThat(body[0] & 0xFF).isEqualTo(0x89); // PNG signature
        assertThat(new String(body, 1, 3)).isEqualTo("PNG");
        assertThat(decode(body)).isEqualTo("http://localhost:8080/" + link.getCode());
    }

    @Test
    void aCustomAliasLinkScansToItsAliasUrl() throws Exception {
        Link custom = links.create(userId, new CreateLinkCommand("https://example.com/x", "qr-" + System.nanoTime(), null));
        byte[] body = mvc.perform(get("/api/links/" + custom.getId() + "/qr").cookie(owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(decode(body)).isEqualTo("http://localhost:8080/" + custom.getCode());
    }

    @Test
    void svgComesBackAsVectorMarkup() throws Exception {
        MvcResult result = mvc.perform(get("/api/links/" + link.getId() + "/qr?format=svg&size=300").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/svg+xml"))
                .andReturn();
        String svg = result.getResponse().getContentAsString();
        assertThat(svg).startsWith("<svg").contains("width=\"300\"").contains("viewBox=");
    }

    @Test
    void formatIsCaseInsensitive() throws Exception {
        mvc.perform(get("/api/links/" + link.getId() + "/qr?format=SVG").cookie(owner)).andExpect(status().isOk());
        mvc.perform(get("/api/links/" + link.getId() + "/qr?format=Png").cookie(owner)).andExpect(status().isOk());
    }

    @Test
    void theFileNameIsTheCodeAndTheBrowserMayCacheItPrivately() throws Exception {
        mvc.perform(get("/api/links/" + link.getId() + "/qr").cookie(owner))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"" + link.getCode() + ".png\""))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("private"), org.hamcrest.Matchers.containsString("max-age=3600"))));
        mvc.perform(get("/api/links/" + link.getId() + "/qr?format=svg").cookie(owner))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"" + link.getCode() + ".svg\""));
    }

    @Test
    void sizeChangesThePngButNeverTheContent() throws Exception {
        byte[] small = mvc.perform(get("/api/links/" + link.getId() + "/qr?size=128").cookie(owner)).andReturn().getResponse().getContentAsByteArray();
        byte[] large = mvc.perform(get("/api/links/" + link.getId() + "/qr?size=2048").cookie(owner)).andReturn().getResponse().getContentAsByteArray();
        assertThat(ImageIO.read(new ByteArrayInputStream(large)).getWidth()).isGreaterThan(ImageIO.read(new ByteArrayInputStream(small)).getWidth());
        assertThat(decode(small)).isEqualTo(decode(large));
    }

    @Test
    void invalidRequestsAre400() throws Exception {
        for (String query : new String[] {"?format=gif", "?format=pdf", "?size=63", "?size=2049", "?size=0", "?size=-5", "?size=abc"}) {
            mvc.perform(get("/api/links/" + link.getId() + "/qr" + query).cookie(owner)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void anEmptyParameterMeansTheDefault() throws Exception {
        mvc.perform(get("/api/links/" + link.getId() + "/qr?format=&size=").cookie(owner))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "image/png"));
    }

    @Test
    void youCannotGetSomeoneElsesCodeAndNothingLeaksAboutWhetherItExists() throws Exception {
        Cookie stranger = new Cookie("access_token", jwt.issueAccessToken(newUser()));
        mvc.perform(get("/api/links/" + link.getId() + "/qr").cookie(stranger)).andExpect(status().isNotFound());
        mvc.perform(get("/api/links/" + (link.getId() + 99_999) + "/qr").cookie(owner)).andExpect(status().isNotFound());
    }

    @Test
    void signInIsRequired() throws Exception {
        mvc.perform(get("/api/links/" + link.getId() + "/qr")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/links/" + link.getId() + "/qr").cookie(new Cookie("access_token", "garbage"))).andExpect(status().isUnauthorized());
    }
}
