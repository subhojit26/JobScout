package com.JobHunt.appJob.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How profile keywords and configured defaults become search queries. */
class SearchQueriesTest {

    private static final List<String> DEFAULTS = List.of("java developer", "golang developer", "react developer");

    @Test
    void profileKeywordsComeFirstAndDefaultsFillUp() {
        assertEquals(List.of("python", "django", "java developer", "golang developer", "react developer"),
                JobSourceProvider.searchQueries(List.of("Python", "django"), DEFAULTS, 8));
    }

    @Test
    void keywordCoveredByADefaultUsesTheDefault() {
        assertEquals(List.of("java developer", "golang developer", "react developer"),
                JobSourceProvider.searchQueries(List.of("java"), DEFAULTS, 8));
    }

    @Test
    void cappedAndSkipsTinyKeywords() {
        assertEquals(List.of("python", "django"),
                JobSourceProvider.searchQueries(List.of("python", "x", "django"), DEFAULTS, 2));
    }

    @Test
    void defaultsOnlyWhenNoProfiles() {
        assertEquals(DEFAULTS, JobSourceProvider.searchQueries(List.of(), DEFAULTS, 8));
    }
}
