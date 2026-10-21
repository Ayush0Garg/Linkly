package com.ayush.shortener.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/** Keeps the backend's idea of the app's pages in step with the frontend's router. */
class SpaRoutesTest {

    private static final Path APP_TSX = Path.of("..", "frontend", "src", "App.tsx");

    @Test
    void everyTopLevelPageIsListedAsATakenCodeSegment() {
        Set<String> firstSegments = new HashSet<>();
        for (String page : SpaRoutes.PAGES) {
            String[] parts = page.substring(1).split("/");
            if (!parts[0].isEmpty()) {
                firstSegments.add(parts[0]);
            }
        }
        assertThat(SpaRoutes.TOP_LEVEL_SEGMENTS).containsExactlyInAnyOrderElementsOf(firstSegments);
    }

    @Test
    void theRoutesInTheReactRouterMatchTheOnesTheServerKnows() throws IOException {
        assumeThat(Files.exists(APP_TSX)).as("frontend sources are not next to the backend (e.g. inside a Docker build)").isTrue();
        String source = Files.readString(APP_TSX);

        Set<String> fromRouter = new HashSet<>();
        Matcher paths = Pattern.compile("path=\"([^\"]+)\"").matcher(source);
        while (paths.find()) {
            String path = paths.group(1);
            if (!path.equals("*")) {
                fromRouter.add("/" + path.replaceFirst("^/", "").replaceAll(":(\\w+)", "{$1}"));
            }
        }
        if (source.contains("<Route index")) {
            fromRouter.add("/");
        }

        assertThat(fromRouter).as("pages in frontend/src/App.tsx (add new ones to SpaRoutes, SpaController and the tests)")
                .containsExactlyInAnyOrderElementsOf(SpaRoutes.PAGES);
    }
}
