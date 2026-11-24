package com.JobHunt.appJob.source;

import com.JobHunt.appJob.model.Job;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Parsing tests for SmartRecruiters, Himalayas and Remotive against saved real responses. */
class NewSourcesTest {

    private static String fixture(String name) throws IOException {
        try (InputStream in = NewSourcesTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void smartRecruitersParsesIndiaPostings() throws IOException {
        SmartRecruitersJobSource.Page page = new SmartRecruitersJobSource().parse(fixture("smartrecruiters-freshworks-in.json"));
        assertFalse(page.jobs().isEmpty());
        assertTrue(page.totalFound() >= page.jobs().size());
        for (Job j : page.jobs()) {
            assertEquals("smartrecruiters", j.source());
            assertEquals("Freshworks", j.company());
            assertNotNull(j.title());
            assertNotNull(j.postedAt(), "releasedDate parsed");
            assertTrue(j.location().contains("India"), j.location());
            assertFalse(j.location().contains(", ,"), "empty location parts removed");
            assertTrue(j.applyUrl().startsWith("https://jobs.smartrecruiters.com/Freshworks/"));
        }
    }

    @Test
    void smartRecruitersBuildsPagedIndiaUrl() {
        var src = new SmartRecruitersJobSource("swiggy", null);
        assertEquals("https://api.smartrecruiters.com/v1/companies/swiggy/postings?country=in&limit=100&offset=200",
                src.pageUrl(200));
    }

    @Test
    void cleansSmartRecruitersLocations() {
        assertEquals("Bengaluru, India", SmartRecruitersJobSource.cleanLocation("Bengaluru, , India", null, null));
        assertEquals("Pune, India", SmartRecruitersJobSource.cleanLocation(null, "Pune", "in"));
        assertEquals("India", SmartRecruitersJobSource.cleanLocation(null, null, "IN"));
    }

    @Test
    void himalayasKeepsOnlyJobsOpenToIndia() throws IOException {
        List<Job> jobs = new HimalayasJobSource().parse(fixture("himalayas-java.json"));
        assertFalse(jobs.isEmpty(), "fixture has worldwide postings");
        for (Job j : jobs) {
            assertTrue(j.remote());
            assertTrue(j.location().equals("Remote (Worldwide)") || j.location().equals("Remote (India)"),
                    j.location());
            assertNotNull(j.applyUrl());
            assertNotNull(j.postedAt());
        }
        // The fixture also contains EU/US-only postings, which must be dropped.
        assertTrue(fixture("himalayas-java.json").contains("\"United States\""));
        assertTrue(jobs.stream().noneMatch(j -> j.location().contains("United States")));
    }

    @Test
    void himalayasSearchUrlEncodesQuery() {
        assertEquals("https://himalayas.app/jobs/api/search?q=spring+boot",
                new HimalayasJobSource("spring boot", null).searchUrl());
    }

    @Test
    void remotiveKeepsWorldwideAndDropsUsOnly() throws IOException {
        String json = fixture("remotive-java.json");
        List<Job> jobs = new RemotiveJobSource().parse(json);
        assertTrue(json.contains("\"candidate_required_location\": \"USA\"")
                || json.contains("\"candidate_required_location\":\"USA\""));
        for (Job j : jobs) {
            assertTrue(j.remote());
            String where = j.location().toLowerCase();
            assertTrue(where.matches(".*(worldwide|anywhere|global|india|apac|asia).*"), where);
            assertTrue(j.applyUrl().startsWith("https://remotive.com/"), "links back to Remotive");
        }
    }
}
