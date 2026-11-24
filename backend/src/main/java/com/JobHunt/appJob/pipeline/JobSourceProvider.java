package com.JobHunt.appJob.pipeline;

import com.JobHunt.appJob.config.AppProperties;
import com.JobHunt.appJob.http.HttpJobFetcher;
import com.JobHunt.appJob.source.AdzunaJobSource;
import com.JobHunt.appJob.source.AshbyJobSource;
import com.JobHunt.appJob.source.GreenhouseJobSource;
import com.JobHunt.appJob.source.HimalayasJobSource;
import com.JobHunt.appJob.source.JobSource;
import com.JobHunt.appJob.source.LeverJobSource;
import com.JobHunt.appJob.source.RemotiveJobSource;
import com.JobHunt.appJob.source.SmartRecruitersJobSource;
import com.JobHunt.appJob.store.CompanyRepository;
import com.JobHunt.appJob.store.ProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds the live {@link JobSource} list for each run.
 *
 * <ul>
 *   <li><b>Company boards</b> (Greenhouse, Lever, Ashby, SmartRecruiters): one source per company
 *       in the DB-backed {@code companies} roster, which discovery keeps growing.</li>
 *   <li><b>Search sources</b> (Adzuna, Himalayas): one source per search query. Queries come from
 *       the role keywords of users' active profiles (most common first), topped up with the
 *       configured defaults, capped at {@code jobwatch.sources.max-queries}.</li>
 *   <li><b>Remotive</b>: one request for its whole software category.</li>
 * </ul>
 */
@Component
public class JobSourceProvider {

    private static final Logger log = LoggerFactory.getLogger(JobSourceProvider.class);

    private final CompanyRepository companyRepository;
    private final ProfileRepository profileRepository;
    private final HttpJobFetcher fetcher;
    private final AppProperties props;
    private final String adzunaAppId;
    private final String adzunaAppKey;

    public JobSourceProvider(CompanyRepository companyRepository,
                             ProfileRepository profileRepository,
                             HttpJobFetcher fetcher,
                             AppProperties props,
                             @Value("${adzuna.app-id:}") String adzunaAppId,
                             @Value("${adzuna.app-key:}") String adzunaAppKey) {
        this.companyRepository = companyRepository;
        this.profileRepository = profileRepository;
        this.fetcher = fetcher;
        this.props = props;
        this.adzunaAppId = adzunaAppId;
        this.adzunaAppKey = adzunaAppKey;
    }

    /** Assembles the current source list from the discovered roster, profiles and config. */
    public List<JobSource> currentSources() {
        List<JobSource> sources = new ArrayList<>();
        AppProperties.SourcesConfig cfg = props.getSources();

        if (cfg.getGreenhouse().isEnabled()) {
            for (String slug : companyRepository.findEnabledSlugs(GreenhouseJobSource.SOURCE)) {
                sources.add(new GreenhouseJobSource(slug, fetcher));
            }
        }
        if (cfg.getLever().isEnabled()) {
            for (String slug : companyRepository.findEnabledSlugs(LeverJobSource.SOURCE)) {
                sources.add(new LeverJobSource(slug, slug, fetcher));
            }
        }
        if (cfg.getAshby().isEnabled()) {
            for (String slug : companyRepository.findEnabledSlugs(AshbyJobSource.SOURCE)) {
                sources.add(new AshbyJobSource(slug, slug, fetcher));
            }
        }
        if (cfg.getSmartrecruiters().isEnabled()) {
            for (String slug : companyRepository.findEnabledSlugs(SmartRecruitersJobSource.SOURCE)) {
                sources.add(new SmartRecruitersJobSource(slug, fetcher));
            }
        }

        List<String> queries = searchQueries(profileRepository.activeRoleKeywords(),
                cfg.getAdzuna().getQueries(), cfg.getMaxQueries());

        AppProperties.AdzunaConfig adzuna = cfg.getAdzuna();
        if (adzuna.isEnabled()) {
            if (adzunaAppId == null || adzunaAppId.isBlank()
                    || adzunaAppKey == null || adzunaAppKey.isBlank()) {
                log.warn("Adzuna enabled but ADZUNA_APP_ID/ADZUNA_APP_KEY not set — skipping Adzuna");
            } else {
                for (String query : queries) {
                    sources.add(new AdzunaJobSource(
                            adzuna.getCountry(), query, adzunaAppId, adzunaAppKey, fetcher));
                }
            }
        }
        if (cfg.getHimalayas().isEnabled()) {
            for (String query : queries) {
                sources.add(new HimalayasJobSource(query, fetcher));
            }
        }
        if (cfg.getRemotive().isEnabled()) {
            sources.add(new RemotiveJobSource(fetcher));
        }

        log.info("Resolved {} source(s); search queries {}: {}", sources.size(), queries,
                sources.stream().map(JobSource::name).distinct().toList());
        return sources;
    }

    /**
     * Profile keywords first (already ordered by popularity), then configured defaults, skipping
     * duplicates and keywords a default already covers ("java" is covered by "java developer").
     */
    static List<String> searchQueries(List<String> profileKeywords, List<String> defaults, int max) {
        Set<String> out = new LinkedHashSet<>();
        List<String> defs = defaults == null ? List.of()
                : defaults.stream().map(d -> d.trim().toLowerCase(Locale.ROOT)).filter(d -> !d.isEmpty()).toList();
        for (String k : profileKeywords == null ? List.<String>of() : profileKeywords) {
            String kw = k.trim().toLowerCase(Locale.ROOT);
            if (kw.length() < 2) {
                continue;
            }
            String covering = defs.stream().filter(d -> (" " + d + " ").contains(" " + kw + " "))
                    .findFirst().orElse(null);
            out.add(covering != null ? covering : kw);
        }
        out.addAll(defs);
        return out.stream().limit(Math.max(1, max)).toList();
    }
}
