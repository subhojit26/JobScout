package com.JobHunt.appJob.notify;

import com.JobHunt.appJob.auth.User;
import com.JobHunt.appJob.auth.UserRepository;
import com.JobHunt.appJob.filter.JobMatch;
import com.JobHunt.appJob.store.JobRepository;
import com.JobHunt.appJob.store.ProfileRepository;
import com.JobHunt.appJob.store.StoredJob;
import com.JobHunt.appJob.store.UserJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Sends each user their own digest: their fresh, not-yet-emailed matches for their active
 * profiles, to their account email. Matches are marked notified only after that user's email
 * was sent, so a failed send is retried on the next run.
 */
@Service
public class DigestNotificationService {

    private static final Logger log = LoggerFactory.getLogger(DigestNotificationService.class);
    private static final int DIGEST_LIMIT = 50;

    private final UserRepository users;
    private final UserJobRepository userJobs;
    private final JobRepository jobRepository;
    private final ProfileRepository profileRepository;
    private final DigestBuilder digestBuilder;
    private final EmailSender emailSender;

    public DigestNotificationService(UserRepository users,
                                     UserJobRepository userJobs,
                                     JobRepository jobRepository,
                                     ProfileRepository profileRepository,
                                     DigestBuilder digestBuilder,
                                     EmailSender emailSender) {
        this.users = users;
        this.userJobs = userJobs;
        this.jobRepository = jobRepository;
        this.profileRepository = profileRepository;
        this.digestBuilder = digestBuilder;
        this.emailSender = emailSender;
    }

    /**
     * Sends the pending digest to every user who has the daily email turned on.
     *
     * @return true if every needed email was sent (or none was needed), false if any send failed
     */
    public boolean sendPendingDigests(String sourceHealthLine) {
        boolean allOk = true;
        for (User user : users.findDigestRecipients()) {
            allOk &= sendDigest(user, sourceHealthLine);
        }
        return allOk;
    }

    /** Sends one user's digest. Returns true if sent or nothing was pending. */
    public boolean sendDigest(User user, String sourceHealthLine) {
        List<String> active = profileRepository.activeProfileNames(user.id());
        List<UserJobRepository.PendingMatch> pending =
                userJobs.findPending(user.id(), active, JobRepository.freshnessCutoff(), DIGEST_LIMIT);
        if (pending.isEmpty()) {
            log.info("User {}: nothing new to email", user.id());
            return true;
        }

        List<JobMatch> matches = new ArrayList<>();
        List<Long> jobIds = new ArrayList<>();
        for (UserJobRepository.PendingMatch p : pending) {
            Optional<StoredJob> job = jobRepository.findById(p.jobId());
            if (job.isPresent()) {
                matches.add(new JobMatch(job.get().toJob(), p.score() == null ? 0 : p.score(),
                        List.of(), p.profile()));
                jobIds.add(p.jobId());
            }
        }

        DigestEmail digest = digestBuilder.build(matches, sourceHealthLine);
        if (digest == null) {
            return true;
        }
        if (!emailSender.send(digest, user.email())) {
            log.error("User {}: digest send failed; {} match(es) stay pending", user.id(), jobIds.size());
            return false;
        }
        userJobs.markNotified(user.id(), jobIds, Instant.now());
        log.info("User {}: emailed {} match(es)", user.id(), jobIds.size());
        return true;
    }
}
