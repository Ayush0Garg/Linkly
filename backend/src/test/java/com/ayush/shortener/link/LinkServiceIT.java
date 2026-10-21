package com.ayush.shortener.link;

import java.time.Duration;
import java.time.Instant;

import com.ayush.shortener.common.ApiException;
import com.ayush.shortener.user.User;
import com.ayush.shortener.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("embedded")
class LinkServiceIT {

    @Autowired LinkService service;
    @Autowired LinkRepository links;
    @Autowired UserRepository users;

    long userId;
    long otherUserId;

    @BeforeEach
    void createUsers() {
        String suffix = String.valueOf(System.nanoTime());
        userId = users.save(new User("sub-a-" + suffix, "a" + suffix + "@example.com", "A", null, Instant.now())).getId();
        otherUserId = users.save(new User("sub-b-" + suffix, "b" + suffix + "@example.com", "B", null, Instant.now())).getId();
    }

    @Test
    void createsGeneratedCodeAndResolvesIt() {
        Link link = service.create(userId, new CreateLinkCommand("https://example.com/page", null, null));

        assertThat(link.getCode()).startsWith("linkly-").hasSizeGreaterThanOrEqualTo(13);
        assertThat(link.isCustom()).isFalse();
        ResolvedLink resolved = service.resolve(link.getCode());
        assertThat(resolved.longUrl()).isEqualTo("https://example.com/page");
        assertThat(resolved.linkId()).isEqualTo(link.getId());
    }

    @Test
    void generatedCodesAreDistinct() {
        Link a = service.create(userId, new CreateLinkCommand("https://example.com/a", null, null));
        Link b = service.create(userId, new CreateLinkCommand("https://example.com/a", null, null));
        assertThat(a.getCode()).isNotEqualTo(b.getCode());
    }

    @Test
    void customAliasWorksAndCannotBeTakenTwice() {
        String alias = "my-link-" + System.nanoTime();
        Link link = service.create(userId, new CreateLinkCommand("https://example.com", alias, null));
        assertThat(link.getCode()).isEqualTo(alias);
        assertThat(link.isCustom()).isTrue();

        assertThatThrownBy(() -> service.create(otherUserId, new CreateLinkCommand("https://example.org", alias, null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status().value()).isEqualTo(409);
    }

    @Test
    void generatedCodeSkipsACodeThatIsAlreadyTaken() {
        // Aliases can't take our prefix, so write the clashing row directly: the code the NEXT id will produce.
        CodeGenerator generator = new CodeGenerator(properties());
        long taken = links.nextId();
        String squatted = generator.encode(taken + 1);
        links.save(new Link(taken, squatted, "https://example.com/squat", userId, true, null, Instant.now()));

        Link generated = service.create(otherUserId, new CreateLinkCommand("https://example.com/gen", null, null));

        assertThat(generated.getCode()).isNotEqualTo(squatted).isEqualTo(generator.encode(taken + 2));
        assertThat(service.resolve(squatted).longUrl()).isEqualTo("https://example.com/squat");
    }

    @Test
    void customAliasesCannotUseTheGeneratedPrefix() {
        for (String alias : new String[] {"linkly-abc123", "LINKLY-abc123", "Linkly-x"}) {
            assertThatThrownBy(() -> service.create(userId, new CreateLinkCommand("https://example.com", alias, null)))
                    .as(alias).isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).status().value()).isEqualTo(409);
        }
        // A look-alike without the dash is fine.
        assertThat(service.create(userId, new CreateLinkCommand("https://example.com", "linkly" + System.nanoTime(), null)))
                .isNotNull();
    }

    @Test
    void storesAndServesTheMaximumLengthUrl() {
        String url = "https://example.com/" + "a".repeat(UrlValidator.MAX_LENGTH - "https://example.com/".length());
        Link link = service.create(userId, new CreateLinkCommand(url, null, null));
        assertThat(service.resolve(link.getCode()).longUrl()).hasSize(UrlValidator.MAX_LENGTH).isEqualTo(url);
    }

    @Test
    void rejectsBadAliasesAndReservedWords() {
        for (String alias : new String[] {"ab", "has space", "bad/slash", "x".repeat(33)}) {
            assertThatThrownBy(() -> service.create(userId, new CreateLinkCommand("https://example.com", alias, null)))
                    .as(alias).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> service.create(userId, new CreateLinkCommand("https://example.com", "Admin", null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsExpiryInThePast() {
        Instant past = Instant.now().minus(Duration.ofMinutes(1));
        assertThatThrownBy(() -> service.create(userId, new CreateLinkCommand("https://example.com", null, past)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void unknownCodeIs404() {
        assertThatThrownBy(() -> service.resolve("does-not-exist"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status().value()).isEqualTo(404);
    }

    @Test
    void expiredLinkIs410() {
        // Create valid, then age it by writing an already-past expiry straight through the repository.
        Link expired = links.save(new Link(links.nextId(), "exp-" + System.nanoTime(), "https://example.com", userId,
                true, Instant.now().minusSeconds(5), Instant.now().minusSeconds(60)));
        assertThatThrownBy(() -> service.resolve(expired.getCode()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status().value()).isEqualTo(410);
    }

    @Test
    void deactivateByOwnerMakesLinkGone() {
        Link link = service.create(userId, new CreateLinkCommand("https://example.com", null, null));
        service.deactivate(userId, link.getId());
        assertThatThrownBy(() -> service.resolve(link.getCode()))
                .extracting(e -> ((ApiException) e).status().value()).isEqualTo(410);
    }

    @Test
    void deactivateByAnotherUserLooksLikeNotFoundAndChangesNothing() {
        Link link = service.create(userId, new CreateLinkCommand("https://example.com", null, null));
        assertThatThrownBy(() -> service.deactivate(otherUserId, link.getId()))
                .extracting(e -> ((ApiException) e).status().value()).isEqualTo(404);
        assertThat(service.resolve(link.getCode())).isNotNull();
    }

    @Test
    void listsOnlyOwnLinksNewestFirst() {
        Link first = service.create(userId, new CreateLinkCommand("https://example.com/1", null, null));
        service.create(otherUserId, new CreateLinkCommand("https://example.com/other", null, null));
        Link second = service.create(userId, new CreateLinkCommand("https://example.com/2", null, null));

        var page = service.listForUser(userId, PageRequest.of(0, 10,
                org.springframework.data.domain.Sort.by("createdAt").descending().and(
                        org.springframework.data.domain.Sort.by("id").descending())));

        assertThat(page.getContent()).extracting(Link::getId).containsExactly(second.getId(), first.getId());
    }

    private com.ayush.shortener.config.AppProperties properties() {
        return new com.ayush.shortener.config.AppProperties("http://localhost:8080",
                new com.ayush.shortener.config.AppProperties.Code(
                        "k3G7QAe51FCsPW92uEOyq4B6jtDnRMdVXcHLzmgYZNpoIlw8TShxbaifvJU0Kr", 6, "linkly-"), null, null);
    }
}
