package com.JobHunt.appJob.pipeline;

import com.JobHunt.appJob.store.ProfileRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Supplies the active role profiles for each pipeline run from the DB-backed {@code profiles}
 * table (Phase 10 increment), so edits made in the dashboard take effect on the next run without
 * a restart.
 */
@Component
public class ProfileProvider {

    private final ProfileRepository profileRepository;

    public ProfileProvider(ProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    /** Stack keywords across every user's enabled profiles, used for job extraction. */
    public List<String> allStackKeywords() {
        return profileRepository.findAllEnabledByUser().values().stream()
                .flatMap(List::stream)
                .flatMap(r -> r.stack().stream())
                .map(String::toLowerCase)
                .distinct()
                .toList();
    }
}

