package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 9 ("a change made while the user holds an active grant is linked by {@code grantId};
 * without one it stays {@code grantId=null} and is queried as a change without a grant") read with
 * item 12 (every record set has a CSV export): {@code changes.csv} carries the link. Matrix row
 * T-09-93 (note 367).
 *
 * <p>This is the server-side half of the retired e2e script {@code e2e/scripts/rest-grant-configure.sh}
 * (issue #31, T-E2E-03/06): "the change made under the grant is linked to the grant id in
 * changes.csv". The link itself is asserted on the stored record by T-09-03 and T-09-09; the save
 * refused after the window ended is T-08-03. No test read the link from the export an auditor
 * receives.
 *
 * <p>The export's columns are read by their header names ({@code type}, {@code target},
 * {@code user}, {@code grantId}), not by position. Time is moved with the plugin clock (matrix note 1).
 *
 * <p>Written from docs/SPEC.md items 8, 9 and 12 and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class ChangeRecordGrantExportTest {

    private static final Instant T0 = Instant.parse("2026-09-20T00:00:00Z");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-09-93 (SPEC 9 grantId, SPEC 12 CSV): u1 holds a 15-minute CONFIGURE window on {@code gc-x} and
     * saves it through {@code config.xml} (200); the administrator, who holds no window, saves it
     * back (200). In {@code changes.csv} the CONFIGURE row by u1 for {@code gc-x} carries the window's
     * id in its {@code grantId} cell, and the administrator's CONFIGURE row has an empty
     * {@code grantId} cell. After the clock passes the window's end, u1's next save answers 403
     * (premise) and the export still holds exactly those two CONFIGURE rows for {@code gc-x}.
     */
    @Test
    public void t_09_93_changesCsvLinksAChangeToTheWindowItWasMadeUnder() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("gc-x");
        Grant window = window("u1", "gc-x", 15);
        assertEquals(T0.plus(Duration.ofMinutes(15)), window.getExpiresAt(), "fixture: the window ends 15 minutes after T0");

        assertEquals(200, postConfigXml("u1", job, "changed under the window"), "premise: u1 saves gc-x inside the window");
        assertEquals(200, postConfigXml("admin", job, "restored by the administrator"), "premise: the administrator saves gc-x");

        List<List<String>> rows = configureRows("gc-x");
        assertEquals(2, rows.size(), "changes.csv must export one CONFIGURE row per save of gc-x: " + rows);
        assertEquals(1, rows.stream().filter(r -> "u1".equals(r.get(1)) && window.getId().equals(r.get(2))).count(),
                "SPEC 9: u1's CONFIGURE row must carry the window id " + window.getId() + " in its grantId cell: " + rows);
        assertEquals(1, rows.stream().filter(r -> "admin".equals(r.get(1)) && r.get(2).isEmpty()).count(),
                "SPEC 9: the administrator's CONFIGURE row, made without a window, must have an empty grantId cell: " + rows);

        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(16)), ZoneOffset.UTC));
        assertEquals(403, postConfigXml("u1", job, "attempted after the window ended"), "premise: the ended window confers nothing");
        assertEquals(rows, configureRows("gc-x"), "the refused save after the window ended must add no CONFIGURE row");
    }

    // ---------------------------------------------------------------- helpers

    private Grant window(String user, String job, int minutes) {
        GrantRequest request;
        try (ACLContext ignored = as(user)) {
            request = GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, job),
                    Arrays.asList(GrantAction.CONFIGURE), minutes, "fix the cron expression of " + job, "a1");
        }
        Grant grant;
        try (ACLContext ignored = as("a1")) {
            grant = GrantRequestService.get().approve(request.getId(), "ok");
        }
        assertNotNull(grant, "fixture: the window on " + job + " must have been issued");
        return grant;
    }

    private int postConfigXml(String userId, FreeStyleProject job, String newDescription) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
        String xml = job.getConfigFile().asString();
        String changed = xml.contains("<description>")
                ? xml.replaceFirst("<description>[^<]*</description>", "<description>" + newDescription + "</description>")
                : xml.replaceFirst("<project>", "<project><description>" + newDescription + "</description>");
        WebRequest request = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "config.xml"), HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(changed);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    /** The CONFIGURE rows of {@code changes.csv} naming {@code target}, each as [target, user, grantId]. */
    private List<List<String>> configureRows(String target) throws Exception {
        WebResponse response = ApproverFormFixtures.get(j, "admin", "batch-control/history/changes.csv");
        assertEquals(200, response.getStatusCode(), "the administrator must download changes.csv");
        String body = response.getContentAsString();
        if (!body.isEmpty() && body.charAt(0) == '﻿') {
            body = body.substring(1);
        }
        String[] lines = body.split("\r?\n");
        List<String> header = parse(lines[0]);
        int type = header.indexOf("type");
        int tgt = header.indexOf("target");
        int user = header.indexOf("user");
        int grantId = header.indexOf("grantId");
        assertTrue(type >= 0 && tgt >= 0 && user >= 0 && grantId >= 0, "changes.csv must name the columns type, target, user and grantId: "
                + header);
        List<List<String>> out = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            List<String> cells = parse(lines[i]);
            if (cells.size() > grantId && "CONFIGURE".equals(cells.get(type)) && target.equals(cells.get(tgt))) {
                out.add(Arrays.asList(cells.get(tgt), cells.get(user), cells.get(grantId)));
            }
        }
        return out.stream().sorted((a, b) -> String.join(",", a).compareTo(String.join(",", b))).collect(Collectors.toList());
    }

    /** One CSV line (RFC 4180 quoting) as its cells. */
    private static List<String> parse(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }
}
