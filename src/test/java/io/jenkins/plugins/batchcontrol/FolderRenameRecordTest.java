package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A folder rename writes one RENAME for the folder and one MOVE per descendant job (issue #31, note 370,
 * gap X-13, retired drivers browser/b11.mjs #4, #5 and browser/audit/b11.mjs #11). Matrix row T-09-94.
 *
 * <p>Basis: SPEC item 9 (creation, change, deletion, rename and move are recorded whatever the path;
 * UI and API paths alike), docs/LIMITATIONS.md item 26 ("A folder rename produces one {@code MOVE} record
 * per descendant job, plus a {@code RENAME} for the folder itself ... every child's full name did change").
 * T-09-08 covers a job's rename and move only.
 *
 * <p>Written from docs/SPEC.md item 9, docs/LIMITATIONS.md item 26 and docs/TEST-MATRIX.md (T-09-08); the
 * retired drivers were read only to learn what each looked at. No src/main knowledge.
 */
@WithJenkins
public class FolderRenameRecordTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().grant(Jenkins.ADMINISTER).everywhere().to("admin"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-09-94 (SPEC 9, LIMITATIONS 26; #31 gap X-13): change control on. The administrator renames folder
     * {@code f1} (jobs {@code f1/a}, {@code f1/b}) to {@code g1} through {@code renameTo} and folder
     * {@code f2} (jobs {@code f2/c}, {@code f2/d}) to {@code g2} through core's {@code confirmRename} POST.
     * For each: exactly one new RENAME record names the old and the new folder name, by admin; exactly two
     * new MOVE records name the new full names of its jobs ({@code g1/a}, {@code g1/b}; {@code g2/c},
     * {@code g2/d}), one each, by admin. Guard: renaming the job {@code solo} to {@code solo-2} adds exactly
     * one RENAME and no MOVE.
     */
    @Test
    public void t_09_94_folderRenameRecordsTheFolderAndEachDescendant() throws Exception {
        List<ChangeRecord> before = all();
        FreeStyleProject solo = j.createFreeStyleProject("solo");
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            solo.renameTo("solo-2");
        }
        List<ChangeRecord> soloAdded = added(before);
        assertEquals(1, ofType(soloAdded, ChangeType.RENAME).size(), "guard: a job rename adds exactly one RENAME: " + describe(soloAdded));
        assertEquals(0, ofType(soloAdded, ChangeType.MOVE).size(), "guard: a job rename adds no MOVE: " + describe(soloAdded));

        Folder f1 = folderWith("f1", "a", "b");
        before = all();
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            f1.renameTo("g1");
        }
        assertFolderRenameRecorded(added(before), "f1", "g1", Set.of("g1/a", "g1/b"), "renameTo");

        Folder f2 = folderWith("f2", "c", "d");
        before = all();
        WebResponse answer = ApproverFormFixtures.post(j, "admin", f2.getUrl() + "confirmRename",
                List.of(new NameValuePair("newName", "g2")));
        assertTrue(answer.getStatusCode() < 400, "fixture: the administrator's confirmRename succeeds, got " + answer.getStatusCode());
        assertNotNull(j.jenkins.getItemByFullName("g2/c"), "fixture: f2 is now g2");
        assertFolderRenameRecorded(added(before), "f2", "g2", Set.of("g2/c", "g2/d"), "confirmRename");
    }

    // ---------------------------------------------------------------- helpers

    private Folder folderWith(String name, String... jobs) throws Exception {
        Folder folder = j.jenkins.createProject(Folder.class, name);
        for (String job : jobs) {
            folder.createProject(FreeStyleProject.class, job);
        }
        return folder;
    }

    private static void assertFolderRenameRecorded(List<ChangeRecord> added, String from, String to, Set<String> moved,
                                                   String path) {
        List<ChangeRecord> renames = ofType(added, ChangeType.RENAME);
        assertEquals(1, renames.size(), path + ": exactly one RENAME for the folder: " + describe(added));
        String renameText = renames.get(0).getTarget() + " " + renames.get(0).getDetail();
        assertTrue(renameText.contains(from) && renameText.contains(to), path + ": the RENAME names " + from + " and " + to + ": "
                + renameText);
        assertEquals("admin", renames.get(0).getUser(), path + ": the RENAME names admin");

        List<ChangeRecord> moves = ofType(added, ChangeType.MOVE);
        assertEquals(moved.size(), moves.size(), path + ": one MOVE per descendant job " + moved + ": " + describe(added));
        for (String fullName : moved) {
            List<ChangeRecord> forJob = moves.stream().filter(r -> (r.getTarget() + " " + r.getDetail()).contains(fullName))
                    .collect(Collectors.toList());
            assertEquals(1, forJob.size(), path + ": exactly one MOVE names " + fullName + ": " + describe(moves));
            assertEquals("admin", forJob.get(0).getUser(), path + ": the MOVE of " + fullName + " names admin");
        }
    }

    private static List<ChangeRecord> all() {
        return FileStore.get().listChangeRecords(YearMonth.now(BatchClock.clock()));
    }

    private static List<ChangeRecord> added(List<ChangeRecord> before) {
        Set<String> seen = before.stream().map(ChangeRecord::getId).collect(Collectors.toSet());
        return all().stream().filter(r -> !seen.contains(r.getId())).collect(Collectors.toList());
    }

    private static List<ChangeRecord> ofType(List<ChangeRecord> records, ChangeType type) {
        return records.stream().filter(r -> r.getType() == type).collect(Collectors.toList());
    }

    private static String describe(List<ChangeRecord> records) {
        return records.stream().map(r -> r.getType() + "(" + r.getTarget() + ": " + r.getDetail() + " by " + r.getUser() + ")")
                .collect(Collectors.joining(", ", "[", "]"));
    }
}
