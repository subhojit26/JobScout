package com.JobHunt.appJob.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC repository for the user-editable {@code profiles} table. Every profile belongs to one user,
 * and every read/write here is scoped by {@code userId} so users can only see their own profiles.
 * Lists are stored comma-separated.
 */
@Repository
public class ProfileRepository {

    private final JdbcClient jdbc;

    public ProfileRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public List<ProfileRow> findAll(long userId) {
        return jdbc.sql("SELECT * FROM profiles WHERE user_id = :u ORDER BY name")
                .param("u", userId).query(ProfileRepository::mapRow).list();
    }

    public List<ProfileRow> findAllEnabled(long userId) {
        return jdbc.sql("SELECT * FROM profiles WHERE user_id = :u AND enabled = 1 ORDER BY name")
                .param("u", userId).query(ProfileRepository::mapRow).list();
    }

    /** Enabled profiles of every user, grouped by user id (used by the shared pipeline run). */
    public Map<Long, List<ProfileRow>> findAllEnabledByUser() {
        Map<Long, List<ProfileRow>> byUser = new LinkedHashMap<>();
        jdbc.sql("SELECT * FROM profiles WHERE user_id IS NOT NULL AND enabled = 1 ORDER BY user_id, name")
                .query((rs, n) -> {
                    byUser.computeIfAbsent(rs.getLong("user_id"), k -> new java.util.ArrayList<>())
                            .add(mapRow(rs, n));
                    return null;
                }).list();
        return byUser;
    }

    public Optional<ProfileRow> findById(long userId, long id) {
        return jdbc.sql("SELECT * FROM profiles WHERE id = :id AND user_id = :u")
                .param("id", id).param("u", userId).query(ProfileRepository::mapRow).optional();
    }

    public boolean nameTaken(long userId, String name, Long exceptId) {
        Long n = jdbc.sql("""
                SELECT COUNT(*) FROM profiles
                WHERE user_id = :u AND lower(name) = lower(:name) AND (:except IS NULL OR id != :except)
                """)
                .param("u", userId).param("name", name).param("except", exceptId)
                .query(Long.class).single();
        return n != null && n > 0;
    }

    /** Inserts a new profile for the user and returns the generated id. */
    public long insert(long userId, ProfileRow p) {
        String now = Instant.now().toString();
        jdbc.sql("""
                INSERT INTO profiles (user_id, name, include_title, stack, exclude_title, companies,
                                      exp_min, exp_max, enabled, created_at, updated_at)
                VALUES (:u, :name, :include, :stack, :exclude, :companies, :expMin, :expMax, :enabled, :now, :now)
                """)
                .param("u", userId)
                .param("name", p.name())
                .param("include", join(p.includeTitle()))
                .param("stack", join(p.stack()))
                .param("exclude", join(p.excludeTitle()))
                .param("companies", join(p.companies()))
                .param("expMin", p.expMin())
                .param("expMax", p.expMax())
                .param("enabled", p.enabled() ? 1 : 0)
                .param("now", now)
                .update();
        Long id = jdbc.sql("SELECT id FROM profiles WHERE user_id = :u AND name = :name")
                .param("u", userId).param("name", p.name()).query(Long.class).single();
        return id == null ? -1 : id;
    }

    /** Updates one of the user's profiles. Returns false if it does not exist or is not theirs. */
    public boolean update(long userId, long id, ProfileRow p) {
        return jdbc.sql("""
                UPDATE profiles SET name = :name, include_title = :include, stack = :stack,
                       exclude_title = :exclude, companies = :companies, exp_min = :expMin,
                       exp_max = :expMax, enabled = :enabled, updated_at = :now
                 WHERE id = :id AND user_id = :u
                """)
                .param("name", p.name())
                .param("include", join(p.includeTitle()))
                .param("stack", join(p.stack()))
                .param("exclude", join(p.excludeTitle()))
                .param("companies", join(p.companies()))
                .param("expMin", p.expMin())
                .param("expMax", p.expMax())
                .param("enabled", p.enabled() ? 1 : 0)
                .param("now", Instant.now().toString())
                .param("id", id)
                .param("u", userId)
                .update() > 0;
    }

    public void delete(long userId, long id) {
        jdbc.sql("DELETE FROM profiles WHERE id = :id AND user_id = :u")
                .param("id", id).param("u", userId).update();
    }

    /**
     * Toggles the active flag on one of the user's profiles. A user can keep several profiles
     * active; only active profiles' jobs are shown and emailed.
     */
    public void toggleActive(long userId, long id) {
        jdbc.sql("""
                UPDATE profiles SET is_active = CASE WHEN is_active = 1 THEN 0 ELSE 1 END
                WHERE id = :id AND user_id = :u
                """)
                .param("id", id).param("u", userId).update();
    }

    public List<Long> activeProfileIds(long userId) {
        return jdbc.sql("SELECT id FROM profiles WHERE user_id = :u AND is_active = 1")
                .param("u", userId).query(Long.class).list();
    }

    public List<String> activeProfileNames(long userId) {
        return jdbc.sql("SELECT name FROM profiles WHERE user_id = :u AND is_active = 1 ORDER BY name")
                .param("u", userId).query(String.class).list();
    }

    /** If the user has profiles but none is active, activates the first enabled one. */
    public void ensureOneActive(long userId) {
        if (!activeProfileIds(userId).isEmpty()) {
            return;
        }
        jdbc.sql("""
                UPDATE profiles SET is_active = 1
                WHERE id = (SELECT id FROM profiles WHERE user_id = :u AND enabled = 1 ORDER BY name LIMIT 1)
                """)
                .param("u", userId).update();
    }

    /**
     * Role keywords of every user's active profiles, most common first. Used to drive the
     * search-based sources so each user's interests are actually searched for.
     */
    public List<String> activeRoleKeywords() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbc.sql("SELECT include_title FROM profiles WHERE user_id IS NOT NULL AND enabled = 1 AND is_active = 1")
                .query(String.class).list()
                .forEach(csv -> split(csv).forEach(k ->
                        counts.merge(k.toLowerCase(java.util.Locale.ROOT), 1, Integer::sum)));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
    }

    /** Hands profiles created before accounts existed (user_id NULL) to the given user. */
    public int claimLegacyProfiles(long userId) {
        return jdbc.sql("UPDATE profiles SET user_id = :u WHERE user_id IS NULL")
                .param("u", userId).update();
    }

    private static String join(List<String> list) {
        return (list == null || list.isEmpty()) ? null : String.join(",", list);
    }

    private static List<String> split(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    private static ProfileRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        Integer expMin = (Integer) rs.getObject("exp_min");
        Integer expMax = (Integer) rs.getObject("exp_max");
        return new ProfileRow(
                rs.getLong("id"),
                rs.getString("name"),
                split(rs.getString("include_title")),
                split(rs.getString("stack")),
                split(rs.getString("exclude_title")),
                split(rs.getString("companies")),
                expMin,
                expMax,
                rs.getInt("enabled") == 1
        );
    }
}
