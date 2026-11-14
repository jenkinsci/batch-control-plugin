package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.matrix.AxisList;
import hudson.matrix.MatrixProject;
import hudson.matrix.TextAxis;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.TopLevelItem;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.AuthorizationMatrixProperty;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlTextArea;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.NonInheritingStrategy;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import jenkins.branch.OrganizationFolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.grantRequestIds;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 8 (D-71), the screens: "The request screens show the item's kind with its icon; there
 * is no scope type selector." The frozen HTTP surface shared with ui-dev: the request form has no
 * {@code scopeType} field; the prefill URL is {@code batch-control/grants/new?scopeFullName=...&actions=...};
 * {@code POST batch-control/grants/checkScopeFullName} answers {@code <kind display name> '<full name>'}
 * for an item the caller may see and the same "No such item" error for a missing and an invisible
 * item; wherever a window or request scope is shown, an element carries
 * {@code data-batch-control-item-kind="<descriptor id>"} with the kind's display name; refusal
 * pages link to the prefill URL. Matrix rows T-08-118 .. T-08-122 and T-08-124 (note 260); the
 * job and folder entry points are T-UI-10/15, T-UI-98 and T-UI-112, the move refusal page T-SEC-72.
 * Note 262 adds the item-group notice on the approval page (T-08-138, D-71a) and the kind's icon
 * in the item check and the lists (T-08-139/140, spec-review-S6 M-1). Issue #8 adds the item
 * field's suggestions, {@code GET batch-control/grants/autoCompleteScopeFullName} (T-08-262 ..
 * T-08-265, T-08-299 .. T-08-303, note 373).
 *
 * <p>Users: g1 (RequestGrant), n1 (ViewHistory only: a Batch Control user without the permission
 * to request), a1 (the approver, no RequestGrant), p0 (no Batch Control permission), u3
 * (RequestGrant and a standing Item/Delete, the delete veto's audience). {@code secret} is a job
 * no one but the administrator may see (a non-inheriting authorization property).
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-71 and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 */
@WithJenkins
public class ItemScopeUiTest {

    static final String FREESTYLE_ID = "hudson.model.FreeStyleProject";
    static final String FOLDER_ID = "com.cloudbees.hudson.plugins.folder.Folder";
    static final String PIPELINE_ID = "org.jenkinsci.plugins.workflow.job.WorkflowJob";
    static final String CHECK = "batch-control/grants/checkScopeFullName";
    static final String AUTOCOMPLETE = "batch-control/grants/autoCompleteScopeFullName";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"g1", "n1", "a1", "p0", "u3"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("g1"));
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u3"));
        strategy.add(BatchControlPermissions.VIEW_HISTORY, PermissionEntry.user("n1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        strategy.add(Item.DELETE, PermissionEntry.user("u3"));
        j.jenkins.setAuthorizationStrategy(strategy);

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        j.createFreeStyleProject("batch-x");
        Folder team = j.jenkins.createProject(Folder.class, "team");
        team.createProject(FreeStyleProject.class, "inner");
        j.jenkins.createProject(WorkflowJob.class, "pipe-x");
        FreeStyleProject secret = j.createFreeStyleProject("secret");
        AuthorizationMatrixProperty hidden = new AuthorizationMatrixProperty(new HashMap<>(), new NonInheritingStrategy());
        hidden.add(Item.READ, PermissionEntry.user("admin"));
        secret.addProperty(hidden);
        assertFalse(can("g1", secret, Item.READ) || can("g1", secret, Item.DISCOVER), "fixture: g1 may not see the job secret");
    }

    /**
     * T-08-118 (D-71): the grant request form, blank, prefilled for a folder or a job, and the
     * grant dialog fragment carry no {@code scopeType} control and no link to the withdrawn scope
     * type help; guard: the form still asks for the item and the actions.
     */
    @Test
    public void t_08_118_requestFormHasNoScopeTypeControl() throws Exception {
        for (String path : new String[] {"batch-control/grants/new", "batch-control/grants/new?scopeFullName=team",
                "batch-control/grants/new?scopeFullName=batch-x", "batch-control/grants/dialog"}) {
            HtmlPage page = UsabilityFixtures.htmlPage(j, "g1", path);
            assertEquals(200, page.getWebResponse().getStatusCode(), "g1 must open " + path);
            List<HtmlForm> forms = UsabilityFixtures.formsEndingWith(page, "batch-control/grants/create");
            assertFalse(forms.isEmpty(), path + " must render the grant request form; forms: " + UsabilityFixtures.formActions(page));
            for (HtmlForm form : forms) {
                assertTrue(UsabilityFixtures.hasField(form, "scopeFullName") && UsabilityFixtures.hasField(form, "actions"),
                        "guard: the form on " + path + " still asks for the item and the actions");
            }
            assertTrue(page.getByXPath("//*[@name='scopeType' or @name='_.scopeType']").isEmpty(),
                    "D-71: " + path + " must carry no scope type control");
            assertFalse(page.getWebResponse().getContentAsString().contains("grant-scope-type"),
                    "D-71: " + path + " must not link the withdrawn scope type help");
        }
    }

    /**
     * T-08-119 (D-71): the prefill URL fills the form. {@code ?scopeFullName=team&actions=CREATE}
     * fills the folder and checks CREATE; {@code ?scopeFullName=batch-x&actions=DELETE} fills the
     * job and checks DELETE (not CREATE); {@code ?scopeFullName=team%2Finner&actions=CONFIGURE}
     * survives the encoded slash. Guard: {@code ?scopeFullName=batch-x} without {@code actions}
     * checks neither CREATE nor DELETE, so the checks above come from the parameter.
     */
    @Test
    public void t_08_119_prefillUrlFillsTheItemAndTheAction() throws Exception {
        HtmlPage create = UsabilityFixtures.htmlPage(j, "g1", "batch-control/grants/new?scopeFullName=team&actions=CREATE");
        assertEquals("team", scopeValue(create), "the prefill must fill the folder");
        assertTrue(actionChecked(create, "CREATE"), "actions=CREATE must check CREATE");

        HtmlPage delete = UsabilityFixtures.htmlPage(j, "g1", "batch-control/grants/new?scopeFullName=batch-x&actions=DELETE");
        assertEquals("batch-x", scopeValue(delete), "the prefill must fill the job");
        assertTrue(actionChecked(delete, "DELETE"), "actions=DELETE must check DELETE");
        assertFalse(actionChecked(delete, "CREATE"), "actions=DELETE must not check CREATE");

        HtmlPage nested = UsabilityFixtures.htmlPage(j, "g1", "batch-control/grants/new?scopeFullName=team%2Finner&actions=CONFIGURE");
        assertEquals("team/inner", scopeValue(nested), "the encoded slash must survive the prefill");
        assertTrue(actionChecked(nested, "CONFIGURE"), "actions=CONFIGURE must check CONFIGURE");

        HtmlPage plain = UsabilityFixtures.htmlPage(j, "g1", "batch-control/grants/new?scopeFullName=batch-x");
        assertEquals("batch-x", scopeValue(plain), "guard: the job is filled without actions too");
        assertFalse(actionChecked(plain, "DELETE"), "guard: without actions=DELETE, DELETE is not checked");
        assertFalse(actionChecked(plain, "CREATE"), "guard: without actions=CREATE, CREATE is not checked");
    }

    /**
     * T-08-120 (D-71): {@code POST grants/checkScopeFullName} answers an ok message naming the
     * kind's display name and the full name for items g1 may see (a Freestyle job, a folder, a
     * Pipeline, a job inside a folder), and the same "No such item" error for a missing item and
     * for {@code secret}, which g1 may not see: the two answers are identical once the queried
     * name is masked, so the endpoint is no existence oracle. Guard: the administrator, who may see
     * {@code secret}, gets its kind.
     */
    @Test
    public void t_08_120_checkNamesTheKindAndHidesInvisibleItems() throws Exception {
        assertCheck("g1", "batch-x", "ok", "Freestyle project 'batch-x'");
        assertCheck("g1", "team", "ok", "Folder 'team'");
        assertCheck("g1", "pipe-x", "ok", "Pipeline 'pipe-x'");
        assertCheck("g1", "team/inner", "ok", "Freestyle project 'team/inner'");

        WebResponse missing = check("g1", "no-such-item");
        WebResponse invisible = check("g1", "secret");
        for (WebResponse r : new WebResponse[] {missing, invisible}) {
            assertEquals(200, r.getStatusCode(), "a validation answer is HTTP 200: " + excerpt(r.getContentAsString()));
            assertEquals("error", kind(r), "a missing or invisible item must be an error: " + excerpt(r.getContentAsString()));
            assertTrue(text(r).contains("No such item"), "the error must say 'No such item': " + text(r));
        }
        assertEquals(text(missing).replace("no-such-item", "<name>"), text(invisible).replace("secret", "<name>"),
                "a missing and an invisible item must get the same answer");
        assertFalse(text(invisible).contains("Freestyle project"), "the invisible item's kind must not be disclosed: " + text(invisible));

        assertCheck("admin", "secret", "ok", "Freestyle project 'secret'");
    }

    /**
     * T-08-121 (D-71, CLAUDE.md: state-changing and validation endpoints are POST plus a permission
     * check): {@code GET grants/checkScopeFullName} is refused with 405; a POST by n1 (a Batch
     * Control user without the permission to request) and by a1 (approver only) answers 403, by p0
     * (no Batch Control permission) 404 (SPEC 2); none of them discloses a kind. Guard: g1's POST
     * answers the kind.
     */
    @Test
    public void t_08_121_checkRequiresPostAndThePermissionToRequest() throws Exception {
        WebResponse viaGet = ApproverFormFixtures.get(j, "g1", CHECK + "?value=batch-x&scopeFullName=batch-x");
        assertEquals(405, viaGet.getStatusCode(), "a GET of the check must be refused with 405: " + excerpt(viaGet.getContentAsString()));
        assertFalse(viaGet.getContentAsString().contains("Freestyle project"), "the refused GET must not disclose the kind");

        for (String[] caller : new String[][] {{"n1", "403"}, {"a1", "403"}, {"p0", "404"}}) {
            WebResponse r = check(caller[0], "batch-x");
            assertEquals(Integer.parseInt(caller[1]), r.getStatusCode(), caller[0] + "'s check must answer " + caller[1] + ": "
                    + excerpt(r.getContentAsString()));
            assertFalse(r.getContentAsString().contains("Freestyle project"), caller[0] + " must not learn the kind");
        }

        assertCheck("g1", "batch-x", "ok", "Freestyle project 'batch-x'");
    }

    /**
     * T-08-262 (issue #8, D-71): {@code GET grants/autoCompleteScopeFullName} suggests the full names
     * of the items g1 may read that contain what was typed, ignoring case, those that start with it
     * first: {@code team} offers {@code team} then {@code team/inner}; {@code INNER} offers
     * {@code team/inner}; {@code batch} offers {@code batch-x}. {@code secret}, which g1 may not see,
     * is never offered, not even for its exact name, and nothing is offered for an empty field or a
     * name nothing contains. Guard: the administrator, who may see {@code secret}, is offered it.
     */
    @Test
    public void t_08_262_autoCompleteOffersTheVisibleItemsThatContainWhatWasTyped() throws Exception {
        assertEquals(Arrays.asList("team", "team/inner"), suggestions("g1", "team"));
        assertEquals(Arrays.asList("team/inner"), suggestions("g1", "INNER"));
        assertEquals(Arrays.asList("batch-x"), suggestions("g1", "batch"));

        assertEquals(Arrays.<String>asList(), suggestions("g1", "secret"), "an item g1 may not see is not offered");
        assertEquals(Arrays.<String>asList(), suggestions("g1", "sec"), "nor by a part of its name");
        assertEquals(Arrays.<String>asList(), suggestions("g1", ""), "an empty field gets no suggestions");
        assertEquals(Arrays.<String>asList(), suggestions("g1", "no-such-item"), "a name nothing contains gets none");

        assertEquals(Arrays.asList("secret"), suggestions("admin", "secret"), "guard: the administrator may see secret");
    }

    /**
     * T-08-263 (issue #8): at most 20 names are offered, so a Jenkins with many items does not answer
     * a keystroke with its whole tree, and the list is in a stable order. 25 jobs {@code many-01} ..
     * {@code many-25} are offered as the first 20 of them for {@code many}. Guard: a more specific
     * {@code many-25} still finds the one beyond the first 20.
     */
    @Test
    public void t_08_263_autoCompleteOffersAtMostTwentyNames() throws Exception {
        List<String> expected = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            String name = String.format("many-%02d", i);
            j.createFreeStyleProject(name);
            if (i <= 20) {
                expected.add(name);
            }
        }
        assertEquals(expected, suggestions("g1", "many"));
        assertEquals(Arrays.asList("many-25"), suggestions("g1", "many-25"));
    }

    /**
     * T-08-264 (issue #8, as T-08-121 for the check): the suggestions need the permission to request
     * a window. n1 (Batch Control, no request permission) and a1 (approver only) get 403, p0 (no
     * Batch Control permission) 404 (SPEC 2), and none of them is told an item name. Guard: g1 is
     * offered {@code batch-x}.
     */
    @Test
    public void t_08_264_autoCompleteRequiresThePermissionToRequest() throws Exception {
        for (String[] caller : new String[][] {{"n1", "403"}, {"a1", "403"}, {"p0", "404"}}) {
            WebResponse r = ApproverFormFixtures.get(j, caller[0], AUTOCOMPLETE + "?value=batch");
            assertEquals(Integer.parseInt(caller[1]), r.getStatusCode(), caller[0] + "'s request for suggestions must answer "
                    + caller[1] + ": " + excerpt(r.getContentAsString()));
            assertFalse(r.getContentAsString().contains("batch-x"), caller[0] + " must not be told an item name");
        }
        assertEquals(Arrays.asList("batch-x"), suggestions("g1", "batch"));
    }

    /**
     * T-08-265 (issue #8): the item field of the request form, blank and prefilled, is wired to the
     * suggestions: it carries the URL of {@code grants/autoCompleteScopeFullName}. Guard: the field
     * keeps its name check ({@code checkScopeFullName}).
     */
    @Test
    public void t_08_265_requestFormFieldIsWiredToTheSuggestions() throws Exception {
        for (String path : new String[] {"batch-control/grants/new", "batch-control/grants/new?scopeFullName=team",
                "batch-control/grants/dialog"}) {
            HtmlPage page = UsabilityFixtures.htmlPage(j, "g1", path);
            DomElement field = page.getElementsByName("scopeFullName").get(0);
            String markup = field.asXml();
            assertTrue(markup.contains("autoCompleteScopeFullName"), path + ": the item field must be wired to the suggestions: " + markup);
            assertTrue(markup.contains("checkScopeFullName"), path + ": guard: the item field keeps its name check: " + markup);
        }
    }

    /**
     * T-08-299 (issue #8, SPEC 8 "names that start with the typed text first and then names that
     * contain it, ignoring case"): with the jobs {@code x-job} and {@code XL-report} added, typing
     * {@code x} offers exactly four names: the two that start with it ({@code XL-report} in the other
     * case) before {@code batch-x} and {@code pipe-x}, which only contain it. Alphabetical order gets
     * this wrong under either case rule (case-sensitive: {@code XL-report, batch-x, pipe-x, x-job};
     * ignoring case: {@code batch-x, pipe-x, x-job, XL-report}). Typing {@code X} gives the same groups.
     * The order inside each group is not pinned (SPEC fixes none). {@code xl} offers {@code XL-report}.
     */
    @Test
    public void t_08_299_namesStartingWithTheTextComeBeforeNamesContainingIt() throws Exception {
        j.createFreeStyleProject("x-job");
        j.createFreeStyleProject("XL-report");
        Set<String> starting = Set.of("x-job", "XL-report");
        Set<String> containing = Set.of("batch-x", "pipe-x");

        for (String typed : new String[] {"x", "X"}) {
            List<String> offered = suggestions("g1", typed);
            assertEquals(4, offered.size(), "'" + typed + "' must offer the four readable names that contain it: " + offered);
            assertEquals(starting, new HashSet<>(offered.subList(0, 2)), "'" + typed + "': the names that start with the typed"
                    + " text, in any case, must come first: " + offered);
            assertEquals(containing, new HashSet<>(offered.subList(2, 4)), "'" + typed + "': the names that only contain the"
                    + " typed text must come after them: " + offered);
        }
        assertEquals(List.of("XL-report"), suggestions("g1", "xl"), "a name starting with the typed text in another case is offered");
    }

    /**
     * T-08-300 (issue #8, SPEC 8 "names that start with the typed text first ..., at most 20"): the
     * 20 names offered are the first 20 of that order, so a name that starts with the typed text is
     * not left out for names that only contain it. 25 jobs {@code a-zz-01} .. {@code a-zz-25} contain
     * {@code zz}; {@code zz-top}, created last, starts with it and sorts after them: typing {@code zz}
     * offers exactly 20 names, {@code zz-top} first and 19 {@code a-zz-*} jobs after it. Guard:
     * {@code zz-t} offers {@code zz-top} alone.
     */
    @Test
    public void t_08_300_theTwentyNamesKeepTheNamesThatStartWithTheText() throws Exception {
        for (int i = 1; i <= 25; i++) {
            j.createFreeStyleProject(String.format("a-zz-%02d", i));
        }
        j.createFreeStyleProject("zz-top");

        List<String> offered = suggestions("g1", "zz");
        assertEquals(20, offered.size(), "at most 20 names, and 26 match: " + offered);
        assertEquals("zz-top", offered.get(0), "the name that starts with the typed text must be offered first, not cut by the"
                + " limit: " + offered);
        for (String name : offered.subList(1, 20)) {
            assertTrue(name.startsWith("a-zz-"), "the other 19 must be names that contain the typed text: " + offered);
        }
        assertEquals(List.of("zz-top"), suggestions("g1", "zz-t"), "guard: zz-top exists and g1 may read it");
    }

    /**
     * T-08-301 (issue #8, SPEC 8 "jobs and folders the requester may read (Item/Read)", SPEC 2): an
     * item g1 may only discover is not offered. The job {@code vis-discover} and the folder
     * {@code vis-folder} each carry a non-inheriting authorization property that gives the
     * administrator Item/Read and g1 only Item/Discover (premise: g1 discovers both and reads
     * neither); {@code vis-read} inherits g1's Item/Read. g1 typing {@code vis} is offered
     * {@code vis-read} only, and the exact names of the other two offer nothing. Guard: the
     * administrator is offered all three.
     */
    @Test
    public void t_08_301_anItemTheRequesterMayOnlyDiscoverIsNotOffered() throws Exception {
        FreeStyleProject readable = j.createFreeStyleProject("vis-read");
        FreeStyleProject job = j.createFreeStyleProject("vis-discover");
        AuthorizationMatrixProperty onJob = new AuthorizationMatrixProperty(new HashMap<>(), new NonInheritingStrategy());
        onJob.add(Item.READ, PermissionEntry.user("admin"));
        onJob.add(Item.DISCOVER, PermissionEntry.user("g1"));
        job.addProperty(onJob);
        Folder folder = j.jenkins.createProject(Folder.class, "vis-folder");
        com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty onFolder =
                new com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty(new HashMap<Permission, Set<String>>());
        onFolder.setInheritanceStrategy(new NonInheritingStrategy());
        onFolder.add(Item.READ, PermissionEntry.user("admin"));
        onFolder.add(Item.DISCOVER, PermissionEntry.user("g1"));
        folder.addProperty(onFolder);
        for (Item item : new Item[] {job, folder}) {
            assertTrue(can("g1", item, Item.DISCOVER), "premise: g1 may discover " + item.getFullName());
            assertFalse(can("g1", item, Item.READ), "premise: g1 may not read " + item.getFullName());
        }
        assertTrue(can("g1", readable, Item.READ), "premise: g1 may read vis-read");

        assertEquals(List.of("vis-read"), suggestions("g1", "vis"), "only the item g1 may read is offered");
        assertEquals(List.of(), suggestions("g1", "vis-discover"), "a job g1 may only discover is not offered, not even by its name");
        assertEquals(List.of(), suggestions("g1", "vis-folder"), "a folder g1 may only discover is not offered, not even by its name");
        assertEquals(Set.of("vis-read", "vis-discover", "vis-folder"), new HashSet<>(suggestions("admin", "vis")),
                "guard: the administrator, who may read all three, is offered them");
    }

    /**
     * T-08-302 (issue #8, SPEC 8 "Sub-items of a job (D-71a) are not offered"): the configurations
     * of the multi-configuration project {@code mx} (axis X=a,b) are not offered, although g1 may
     * read them (premise: {@code mx/X=a} and {@code mx/X=b} exist, are not top-level items and are
     * readable by g1). g1 typing {@code mx} is offered {@code mx} alone (guard: the project itself
     * is offered); {@code X=} and {@code mx/} offer nothing, to the administrator neither. Maven
     * modules, the other kind of sub-item, are not covered: the Maven plugin is not a test
     * dependency (note 260).
     */
    @Test
    public void t_08_302_configurationsOfAMultiConfigurationProjectAreNotOffered() throws Exception {
        MatrixProject mx = j.jenkins.createProject(MatrixProject.class, "mx");
        mx.setAxes(new AxisList(new TextAxis("X", "a", "b")));
        for (String name : new String[] {"mx/X=a", "mx/X=b"}) {
            Item configuration = j.jenkins.getItemByFullName(name);
            assertNotNull(configuration, "fixture: mx must have the configuration " + name);
            assertFalse(configuration instanceof TopLevelItem, "fixture: " + name + " is a sub-item of the job");
            assertTrue(can("g1", configuration, Item.READ), "premise: g1 may read " + name + ", so only the sub-item rule keeps it out");
        }

        assertEquals(List.of("mx"), suggestions("g1", "mx"), "the project is offered, its configurations are not");
        assertEquals(List.of(), suggestions("g1", "X="), "a configuration must not be offered");
        assertEquals(List.of(), suggestions("g1", "mx/"), "nothing below the job may be offered");
        assertEquals(List.of(), suggestions("admin", "X="), "a configuration is not offered to the administrator either");
    }

    /**
     * T-08-303 (issue #8, SPEC 8 "The suggestion request (GET) ... changes nothing and writes no
     * record"; CLAUDE.md: no state change through GET; LIMITATIONS 29): with g1's pending CONFIGURE
     * request on {@code batch-x} stored and after g1 has opened the grants page once, a round of
     * suggestion requests leaves every file under {@code $JENKINS_HOME/batch-control/}
     * byte-identical, the month's change records and the grant requests unchanged. The round: g1
     * asks for {@code batch}, {@code secret}, an empty value, {@code no-such-item}, and once with the
     * fields of the request form ({@code scopeFullName}, {@code actions}, {@code durationMinutes},
     * {@code reason}, {@code approvers}); n1, a1 and p0 ask for {@code batch} and are refused. Run
     * with change control on, then off (run control on, as T-08-260), where a window request is
     * refused with a GRANT_REQUEST_BLOCKED record. Guards: on, g1's real {@code POST grants/create}
     * adds one grant request and changes the store; off, the same POST adds one
     * GRANT_REQUEST_BLOCKED record and changes the store.
     */
    @Test
    public void t_08_303_suggestionRequestChangesNothingAndWritesNoRecord() throws Exception {
        String pending = request("batch-x", GrantAction.CONFIGURE).getId();
        HtmlPage warmUp = UsabilityFixtures.htmlPage(j, "g1", "batch-control/grants/");
        assertEquals(200, warmUp.getWebResponse().getStatusCode(), "fixture: g1 opens the grants page");

        Map<String, String> store = storeDigest();
        List<String> changes = changeRecords();
        Set<String> requests = grantRequestIds();
        assertTrue(requests.contains(pending) && !store.isEmpty(), "premise: the store holds g1's pending request");
        askForSuggestions(true);
        assertNothingChanged("change control on", store, changes, requests);

        String created = submitGrantOk(j, "g1", "pipe-x", List.of("CONFIGURE"), 30, "guard: a real request", null, "a1");
        assertTrue(grantRequestIds().contains(created), "guard: the real request is stored");
        assertFalse(store.equals(storeDigest()), "guard: a real request changes the store, so the comparison can see a change");

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(false);
        cfg.save();
        assertFalse(BatchControlGlobalConfiguration.get().isChangeControlEnabled(), "premise: change control is off");
        store = storeDigest();
        changes = changeRecords();
        requests = grantRequestIds();
        askForSuggestions(false);
        assertNothingChanged("change control off", store, changes, requests);

        int blocked = ApproverFormFixtures.records(ChangeType.GRANT_REQUEST_BLOCKED).size();
        WebResponse refused = submitGrant(j, "g1", "pipe-x", List.of("CONFIGURE"), 30, "guard: refused while off", null, "a1");
        assertTrue(refused.getStatusCode() >= 400, "guard: a window request while change control is off is refused: "
                + excerpt(refused.getContentAsString()));
        assertEquals(blocked + 1, ApproverFormFixtures.records(ChangeType.GRANT_REQUEST_BLOCKED).size(),
                "guard: the refused POST writes one GRANT_REQUEST_BLOCKED record, so the record check can see one");
        assertFalse(store.equals(storeDigest()), "guard: the refused POST's record changes the store");
    }

    /**
     * T-08-122 (D-71): the grants page's pending, active and ended lists and the grant request's
     * detail page show the item's kind: an element carrying
     * {@code data-batch-control-item-kind="<descriptor id>"} that shows the kind's display name, in
     * the row of the item's full name. Pending: {@code batch-x} (Freestyle project) and
     * {@code team} (Folder); active: {@code pipe-x} (Pipeline); ended (rejected):
     * {@code team/inner} (Freestyle project). The attribute differs per kind, so it is not a
     * constant.
     */
    @Test
    public void t_08_122_listsAndDetailPageShowTheItemKind() throws Exception {
        String pendingJob = request("batch-x", GrantAction.CONFIGURE).getId();
        request("team", GrantAction.CREATE);
        String active = request("pipe-x", GrantAction.CONFIGURE).getId();
        assertSuccess(decideGrant(j, "a1", active, "approve", "ok"), "fixture: a1 approves pipe-x");
        String ended = request("team/inner", GrantAction.CONFIGURE).getId();
        assertSuccess(decideGrant(j, "a1", ended, "reject", "not this week"), "fixture: a1 rejects team/inner");

        HtmlPage list = UsabilityFixtures.htmlPage(j, "g1", "batch-control/grants/");
        assertKindShown(list, "table[data-batch-control-list=pending]", FREESTYLE_ID, "Freestyle project", "batch-x");
        assertKindShown(list, "table[data-batch-control-list=pending]", FOLDER_ID, "Folder", "team");
        assertKindShown(list, "table[data-batch-control-list=active]", PIPELINE_ID, "Pipeline", "pipe-x");
        assertKindShown(list, "table[data-batch-control-list=ended]", FREESTYLE_ID, "Freestyle project", "team/inner");

        for (String viewer : new String[] {"g1", "a1"}) {
            HtmlPage detail = UsabilityFixtures.htmlPage(j, viewer, "batch-control/grants/" + pendingJob + "/");
            assertEquals(200, detail.getWebResponse().getStatusCode(), viewer + " must open the detail page");
            assertKindShown(detail, "#main-panel", FREESTYLE_ID, "Freestyle project", "batch-x");
        }
    }

    /**
     * T-08-124 (D-71): the delete veto's refusal for u3 (RequestGrant, standing Item/Delete, no
     * DELETE window) points to the prefill URL for the refused Delete:
     * {@code batch-control/grants/new?scopeFullName=veto-u3} (with {@code actions=DELETE} if it names
     * an action), never with a {@code scopeType}; following it as u3 opens the form filled with the
     * job. The job survives the refusal.
     */
    @Test
    public void t_08_124_deleteRefusalLinksThePrefillUrl() throws Exception {
        FreeStyleProject veto = j.createFreeStyleProject("veto-u3");
        JenkinsRule.WebClient u3 = UsabilityFixtures.clientNoJs(j, "u3");
        Page answer = u3.getPage(new WebRequest(u3.createCrumbedUrl(veto.getUrl() + "doDelete"), HttpMethod.POST));
        assertTrue(answer.getWebResponse().getStatusCode() >= 400, "fixture: the delete is vetoed without a DELETE window");
        assertNotNull(j.jenkins.getItemByFullName("veto-u3"), "fixture: the job survives");

        List<String> targets = prefillTargets(answer);
        assertFalse(targets.isEmpty(), "the refusal must point to the prefill URL grants/new: " + excerpt(UsabilityFixtures.text(answer)));
        assertTrue(targets.stream().anyMatch(t -> queryValue(t, "scopeFullName").equals("veto-u3")),
                "the prefill URL must be for the refused job veto-u3: " + targets);
        for (String target : targets) {
            assertFalse(target.contains("scopeType"), "D-71: no scopeType in " + target);
            String action = queryValue(target, "actions");
            assertTrue(action.isEmpty() || "DELETE".equals(action), "a named action must be the refused DELETE: " + target);
        }
        String first = targets.stream().filter(t -> queryValue(t, "scopeFullName").equals("veto-u3")).findFirst().orElseThrow();
        Page form = u3.getPage(new URL(first));
        assertEquals(200, form.getWebResponse().getStatusCode(), "u3 must open " + first);
        assertTrue(form instanceof HtmlPage, "the prefill URL must render a page");
        assertEquals("veto-u3", scopeValue((HtmlPage) form), "the form must be filled with the job");
    }

    /**
     * T-08-138 (SPEC 8 line 170, D-71a ruling 5, security-34 S-34-02): the approval page of a
     * CONFIGURE request whose item is an item group states that the group's settings apply to the
     * items inside it, and for a multibranch project or an organization folder that reconfiguring it
     * can create or delete its generated items. g1's pending CONFIGURE requests on the folder
     * {@code team}, the multibranch project {@code mb} and the organization folder {@code org}: the
     * designated approver a1's detail page of each carries an element
     * {@code [data-batch-control-notice="group-configure"]} whose text speaks of the items inside;
     * for {@code mb} and {@code org} it also speaks of creating and deleting. Since note 264 (D-71a
     * ruling 2) every one of the three also says that a Configure window does not allow renaming
     * the item and names the administrator who can. Guard: the detail page of g1's CONFIGURE
     * request on the job {@code batch-x} carries no such element (the page itself opens and names
     * the job). The wording is not pinned beyond those words.
     */
    @Test
    public void t_08_138_configureRequestOnAnItemGroupWarnsTheApprover() throws Exception {
        j.jenkins.createProject(WorkflowMultiBranchProject.class, "mb");
        j.jenkins.createProject(OrganizationFolder.class, "org");
        String onFolder = request("team", GrantAction.CONFIGURE).getId();
        String onMultibranch = request("mb", GrantAction.CONFIGURE).getId();
        String onOrganization = request("org", GrantAction.CONFIGURE).getId();
        String onJob = request("batch-x", GrantAction.CONFIGURE).getId();

        for (String[] c : new String[][] {{onFolder, "team", "no"}, {onMultibranch, "mb", "yes"}, {onOrganization, "org", "yes"}}) {
            HtmlPage detail = UsabilityFixtures.htmlPage(j, "a1", "batch-control/grants/" + c[0] + "/");
            assertEquals(200, detail.getWebResponse().getStatusCode(), "a1 must open the request on " + c[1]);
            DomNode notice = detail.querySelector(GROUP_NOTICE);
            assertNotNull(notice, "D-71a: the approval page of a CONFIGURE request on the item group " + c[1] + " must carry "
                    + GROUP_NOTICE + ": " + excerpt(detail.asNormalizedText()));
            String text = notice.asNormalizedText();
            assertTrue(INSIDE.matcher(text).find(), "the notice on " + c[1] + " must say that the group's settings apply to the items"
                    + " inside it: " + text);
            if ("yes".equals(c[2])) {
                assertTrue(CREATE_WORD.matcher(text).find() && DELETE_WORD.matcher(text).find(), "the notice on the computed folder "
                        + c[1] + " must say that reconfiguring it can create or delete its generated items: " + text);
            }
            assertTrue(RENAME_REFUSED.matcher(text).find(), "D-71a ruling 2 (note 264): the notice on " + c[1] + " must say that a"
                    + " Configure window does not allow renaming it: " + text);
            assertTrue(text.toLowerCase(java.util.Locale.ROOT).contains("administrator"), "the notice on " + c[1] + " must say who can"
                    + " rename it (an administrator): " + text);
        }

        HtmlPage jobDetail = UsabilityFixtures.htmlPage(j, "a1", "batch-control/grants/" + onJob + "/");
        assertEquals(200, jobDetail.getWebResponse().getStatusCode(), "guard: a1 opens the request on the job");
        assertTrue(jobDetail.asNormalizedText().contains("batch-x"), "guard: the job request's page names the job");
        assertTrue(jobDetail.querySelectorAll(GROUP_NOTICE).isEmpty(), "a CONFIGURE request on a job carries no item-group notice");
    }

    /**
     * T-08-139 (SPEC 8 line 171 "The request screens show the item's kind with its icon",
     * spec-review-S6 M-1): the request form's item check answers, for the folder {@code team}, the
     * Freestyle job {@code batch-x} and the Pipeline {@code pipe-x}, an ok answer whose markup puts
     * an {@code svg} icon before the text, and whose text content (outside the icon) is exactly
     * {@code <kind display name> '<full name>'}. The full name stays escaped: for a job whose name
     * carries quotes and attribute-like text, the answer's text is exactly the kind and that name,
     * and no tag of the answer gains an attribute from it.
     */
    @Test
    public void t_08_139_itemCheckShowsTheKindIconBeforeTheText() throws Exception {
        assertIconThenText("team", "Folder 'team'");
        assertIconThenText("batch-x", "Freestyle project 'batch-x'");
        assertIconThenText("pipe-x", "Pipeline 'pipe-x'");

        j.createFreeStyleProject(HOSTILE);
        assertIconThenText(HOSTILE, "Freestyle project '" + HOSTILE + "'");
        JenkinsRule.WebClient wc = UsabilityFixtures.clientNoJs(j, "g1");
        WebRequest request = new WebRequest(wc.createCrumbedUrl(CHECK), HttpMethod.POST);
        request.setRequestParameters(List.of(new NameValuePair("value", HOSTILE), new NameValuePair("scopeFullName", HOSTILE)));
        Page parsed = wc.getPage(request);
        assertTrue(parsed instanceof HtmlPage, "fixture: the check answer parses as HTML, got " + parsed.getWebResponse().getContentType());
        for (org.htmlunit.html.HtmlElement e : ((HtmlPage) parsed).getHtmlElementDescendants()) {
            assertFalse(e.hasAttribute("onmouseover") || e.hasAttribute("data-injected"),
                    "the item name must not inject attributes, found on <" + e.getTagName() + ">: " + excerpt(e.asXml()));
        }
    }

    /**
     * T-08-140 (SPEC 8 line 171, spec-review-S6 M-1): on the grants page's pending, active and ended
     * lists and on the request's detail page, the element
     * {@code [data-batch-control-item-kind=<descriptor id>]} in the row of the item contains an
     * {@code svg} icon (the T-08-122 fixture: pending {@code batch-x} and {@code team}, active
     * {@code pipe-x}, ended {@code team/inner}).
     */
    @Test
    public void t_08_140_listsAndDetailPageShowTheKindIcon() throws Exception {
        String pendingJob = request("batch-x", GrantAction.CONFIGURE).getId();
        request("team", GrantAction.CREATE);
        String active = request("pipe-x", GrantAction.CONFIGURE).getId();
        assertSuccess(decideGrant(j, "a1", active, "approve", "ok"), "fixture: a1 approves pipe-x");
        String ended = request("team/inner", GrantAction.CONFIGURE).getId();
        assertSuccess(decideGrant(j, "a1", ended, "reject", "not this week"), "fixture: a1 rejects team/inner");

        HtmlPage list = UsabilityFixtures.htmlPage(j, "g1", "batch-control/grants/");
        assertKindIcon(list, "table[data-batch-control-list=pending]", FREESTYLE_ID, "batch-x");
        assertKindIcon(list, "table[data-batch-control-list=pending]", FOLDER_ID, "team");
        assertKindIcon(list, "table[data-batch-control-list=active]", PIPELINE_ID, "pipe-x");
        assertKindIcon(list, "table[data-batch-control-list=ended]", FREESTYLE_ID, "team/inner");

        HtmlPage detail = UsabilityFixtures.htmlPage(j, "a1", "batch-control/grants/" + pendingJob + "/");
        assertEquals(200, detail.getWebResponse().getStatusCode(), "a1 must open the detail page");
        assertKindIcon(detail, "#main-panel", FREESTYLE_ID, "batch-x");
    }

    // ---------------------------------------------------------------- helpers

    static final String GROUP_NOTICE = "[data-batch-control-notice=\"group-configure\"]";
    private static final Pattern INSIDE = Pattern.compile("(?i)\\b(?:inside|within|contain(?:s|ed)?|below|beneath|under)\\b");
    private static final Pattern CREATE_WORD = Pattern.compile("(?i)\\bcreat");
    private static final Pattern DELETE_WORD = Pattern.compile("(?i)\\bdelet");
    /** A sentence saying a rename is not allowed ("does not allow renaming it", "cannot rename it", ...). */
    private static final Pattern RENAME_REFUSED = Pattern.compile(
            "(?i)\\b(?:does not|doesn't|cannot|can't|will not|won't|may not|never)\\s+(?:allow\\s+|let\\s+\\w+\\s+)?renam");
    /**
     * Allowed by Jenkins' item name check, able to break out of an attribute if written unescaped (as MoveRefusalPageTest):
     * with a double quote where the file system can hold one in the item's directory name, with single quotes on NTFS
     * (TEST-MATRIX note 291).
     */
    static final String HOSTILE = PlatformFixtures.HOSTILE_ITEM_NAME;

    /**
     * The check answer for {@code name} is ok; its raw markup has an {@code <svg} element with no
     * visible text before it, and the visible text outside every svg equals {@code expectedText}.
     * Returns the raw answer.
     */
    private String assertIconThenText(String name, String expectedText) throws Exception {
        WebResponse r = check("g1", name);
        String raw = r.getContentAsString();
        assertEquals(200, r.getStatusCode(), "the check of " + name + " must answer 200: " + excerpt(raw));
        assertEquals("ok", kind(r), "the check of " + name + " must be ok: " + excerpt(raw));
        int svg = raw.indexOf("<svg");
        assertTrue(svg >= 0, "M-1: the check answer for " + name + " must carry the kind's icon (an svg): " + excerpt(raw));
        assertEquals("", visible(raw.substring(0, svg)), "M-1: the icon must come before the text in the answer for " + name
                + ": " + excerpt(raw));
        String outsideIcons = raw.replaceAll("(?s)<svg\\b.*?</svg>", " ").replaceAll("<svg\\b[^>]*/>", " ");
        assertEquals(expectedText, visible(outsideIcons), "the text of the answer for " + name + " must stay exactly "
                + expectedText + ": " + excerpt(raw));
        return raw;
    }

    /** Visible text of a markup fragment: tags removed, entities decoded, whitespace collapsed. */
    private static String visible(String html) {
        String s = html.replaceAll("<[^>]*>", " ")
                .replace("&#039;", "'").replace("&#39;", "'").replace("&apos;", "'").replace("&quot;", "\"").replace("&#34;", "\"")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        return s.replaceAll("\\s+", " ").trim();
    }

    /** Some {@code [data-batch-control-item-kind=<id>]} element in the row naming {@code fullName} contains an svg. */
    private static void assertKindIcon(HtmlPage page, String containerSelector, String id, String fullName) {
        DomNode container = page.querySelector(containerSelector);
        assertNotNull(container, "the page must carry " + containerSelector + ": " + excerpt(page.asNormalizedText()));
        List<String> seen = new ArrayList<>();
        for (DomNode n : container.querySelectorAll("[data-batch-control-item-kind=\"" + id + "\"]")) {
            DomElement e = (DomElement) n;
            DomNode row = e;
            while (row != null && !(row instanceof DomElement d && "tr".equals(d.getTagName()))) {
                row = row.getParentNode();
            }
            String rowText = (row == null ? container : row).asNormalizedText();
            boolean icon = !e.getElementsByTagName("svg").isEmpty();
            seen.add(icon + " | " + rowText);
            if (icon && rowText.contains(fullName)) {
                return;
            }
        }
        throw new AssertionError(containerSelector + ": the kind element (data-batch-control-item-kind=\"" + id + "\") next to "
                + fullName + " must contain an svg icon (M-1); elements with that kind (has svg | row): " + seen);
    }

    private static GrantRequest request(String fullName, GrantAction... actions) {
        try (ACLContext ignored = ACL.as2(User.getById("g1", true).impersonate2())) {
            return GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, fullName),
                    Arrays.asList(actions), 30, "work on " + fullName, "a1");
        }
    }

    /** {@code POST grants/checkScopeFullName} with the queried name as {@code value} and {@code scopeFullName}. */
    private WebResponse check(String userId, String name) throws Exception {
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("value", name));
        params.add(new NameValuePair("scopeFullName", name));
        return ApproverFormFixtures.post(j, userId, CHECK, params);
    }

    private void assertCheck(String userId, String name, String expectedKind, String expectedText) throws Exception {
        WebResponse r = check(userId, name);
        assertEquals(200, r.getStatusCode(), userId + "'s check of " + name + " must answer 200: " + excerpt(r.getContentAsString()));
        assertEquals(expectedKind, kind(r), userId + "'s check of " + name + ": " + excerpt(r.getContentAsString()));
        assertTrue(text(r).contains(expectedText), userId + "'s check of " + name + " must say " + expectedText + ", was: " + text(r));
    }

    /** The names {@code GET grants/autoCompleteScopeFullName} offers {@code userId} for {@code typed}, in order. */
    private List<String> suggestions(String userId, String typed) throws Exception {
        WebResponse r = ApproverFormFixtures.get(j, userId, AUTOCOMPLETE + "?value="
                + java.net.URLEncoder.encode(typed, java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(200, r.getStatusCode(), userId + "'s suggestions for '" + typed + "': " + excerpt(r.getContentAsString()));
        List<String> names = new ArrayList<>();
        Matcher m = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]*)\"").matcher(r.getContentAsString());
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    /**
     * T-08-303's round of suggestion requests: g1's (200 with change control on, below 500 with it
     * off) and the refused n1, a1 (403) and p0 (404; with change control off only below 500).
     */
    private void askForSuggestions(boolean changeControlOn) throws Exception {
        String formFields = "?value=batch-x&scopeFullName=batch-x&actions=CONFIGURE&durationMinutes=30"
                + "&reason=sneaked+through+a+GET&approvers=a1";
        for (String query : new String[] {"?value=batch", "?value=secret", "?value=", "?value=no-such-item", formFields}) {
            WebResponse r = ApproverFormFixtures.get(j, "g1", AUTOCOMPLETE + query);
            int code = r.getStatusCode();
            assertTrue(changeControlOn ? code == 200 : code < 500, "g1's suggestion request " + query + " (change control "
                    + (changeControlOn ? "on" : "off") + ") answered " + code + ": " + excerpt(r.getContentAsString()));
        }
        for (String[] caller : new String[][] {{"n1", "403"}, {"a1", "403"}, {"p0", "404"}}) {
            WebResponse r = ApproverFormFixtures.get(j, caller[0], AUTOCOMPLETE + "?value=batch");
            int code = r.getStatusCode();
            assertTrue(changeControlOn ? code == Integer.parseInt(caller[1]) : code < 500, caller[0] + "'s suggestion request"
                    + " (change control " + (changeControlOn ? "on" : "off") + ") answered " + code + ": "
                    + excerpt(r.getContentAsString()));
        }
    }

    /** No file of the store, no change record of the month and no grant request differs from before. */
    private void assertNothingChanged(String when, Map<String, String> store, List<String> changes, Set<String> requests)
            throws Exception {
        List<String> newRecords = new ArrayList<>(changeRecords());
        for (String rec : changes) {
            newRecords.remove(rec);
        }
        assertEquals(changes, changeRecords(), when + ": a suggestion request must write no record; new records: " + newRecords);
        assertEquals(requests, grantRequestIds(), when + ": a suggestion request must store no grant request");
        Map<String, String> now = storeDigest();
        Set<String> differing = new java.util.TreeSet<>();
        for (String path : now.keySet()) {
            if (!now.get(path).equals(store.get(path))) {
                differing.add(path);
            }
        }
        for (String path : store.keySet()) {
            if (!now.containsKey(path)) {
                differing.add(path + " (deleted)");
            }
        }
        assertTrue(differing.isEmpty(), when + ": a suggestion request must change nothing in the store; changed: " + differing);
    }

    /** Every file under {@code $JENKINS_HOME/batch-control/} (ARCHITECTURE 5): relative path to the SHA-256 of its bytes. */
    private Map<String, String> storeDigest() throws Exception {
        Path root = j.jenkins.getRootDir().toPath().resolve("batch-control");
        Map<String, String> out = new TreeMap<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        for (Path file : files) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            out.put(root.relativize(file).toString().replace('\\', '/'), HexFormat.of().formatHex(digest));
        }
        return out;
    }

    /** The change records of the current month, each as id, type, user and target. */
    private static List<String> changeRecords() {
        List<String> out = new ArrayList<>();
        for (ChangeRecord rec : FileStore.get().listChangeRecords(YearMonth.now(BatchClock.clock()))) {
            out.add(rec.getId() + " " + rec.getType() + " user=" + rec.getUser() + " target=" + rec.getTarget());
        }
        return out;
    }

    /** The FormValidation kind of a validation answer ({@code <div class=ok|warning|error>}), or "". */
    private static String kind(WebResponse r) {
        Matcher m = Pattern.compile("class=[\"']?(ok|warning|error)\\b").matcher(r.getContentAsString());
        return m.find() ? m.group(1) : "";
    }

    /** The visible text of a validation answer: tags removed, entities decoded, whitespace collapsed. */
    private static String text(WebResponse r) {
        String s = r.getContentAsString().replaceAll("<[^>]*>", " ")
                .replace("&#039;", "'").replace("&#39;", "'").replace("&apos;", "'").replace("&quot;", "\"")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        return s.replaceAll("\\s+", " ").trim();
    }

    private static boolean can(String user, Item item, hudson.security.Permission p) {
        return item.getACL().hasPermission2(User.getById(user, true).impersonate2(), p);
    }

    /** The scopeFullName control's value (input or textarea). */
    static String scopeValue(HtmlPage page) {
        for (DomElement e : page.getElementsByName("scopeFullName")) {
            if (e instanceof HtmlInput i) {
                return i.getValue();
            }
            if (e instanceof HtmlTextArea t) {
                return t.getText();
            }
        }
        throw new AssertionError("the page must carry a scopeFullName field: " + excerpt(page.asNormalizedText()));
    }

    /** Whether the {@code actions} checkbox with {@code value} is checked; the checkbox must exist. */
    static boolean actionChecked(HtmlPage page, String value) {
        for (DomElement e : page.getElementsByName("actions")) {
            if (e instanceof HtmlCheckBoxInput box && value.equals(box.getValue())) {
                return box.isChecked();
            }
        }
        throw new AssertionError("the form must offer the action " + value + " as a checkbox: " + excerpt(page.asNormalizedText()));
    }

    /**
     * Inside {@code containerSelector}, some element carrying {@code data-batch-control-item-kind=<id>}
     * shows {@code displayName} (as text, or as its title / tooltip / aria-label) and sits in the
     * table row (or, outside a table, the container) that names {@code fullName}.
     */
    private static void assertKindShown(HtmlPage page, String containerSelector, String id, String displayName, String fullName) {
        DomNode container = page.querySelector(containerSelector);
        assertNotNull(container, "the page must carry " + containerSelector + ": " + excerpt(page.asNormalizedText()));
        List<String> seen = new ArrayList<>();
        for (DomNode n : container.querySelectorAll("[data-batch-control-item-kind=\"" + id + "\"]")) {
            DomElement e = (DomElement) n;
            DomNode row = e;
            while (row != null && !(row instanceof DomElement d && "tr".equals(d.getTagName()))) {
                row = row.getParentNode();
            }
            String rowText = (row == null ? container : row).asNormalizedText();
            seen.add(e.asNormalizedText() + " | " + rowText);
            boolean shows = e.asNormalizedText().contains(displayName);
            for (String attr : new String[] {"title", "tooltip", "data-html-tooltip", "aria-label"}) {
                shows |= e.getAttribute(attr).contains(displayName);
            }
            if (shows && rowText.contains(fullName)) {
                return;
            }
        }
        throw new AssertionError(containerSelector + " must show the kind " + displayName + " (data-batch-control-item-kind=\"" + id
                + "\") next to " + fullName + "; elements with that kind: " + seen + "; text: " + excerpt(container.asNormalizedText()));
    }

    /** Every reference to {@code batch-control/grants/new?...} in the answer: anchor hrefs (resolved) and URLs in the text. */
    private List<String> prefillTargets(Page answer) throws Exception {
        List<String> out = new ArrayList<>();
        if (answer instanceof HtmlPage html) {
            for (HtmlAnchor a : html.getAnchors()) {
                String href = a.getHrefAttribute();
                if (href != null && href.contains("batch-control/grants/new?")) {
                    out.add(html.getFullyQualifiedUrl(href).toExternalForm());
                }
            }
        }
        Matcher m = Pattern.compile("[^\\s\"'<>]*batch-control/grants/new\\?[^\\s\"'<>]*")
                .matcher(answer.getWebResponse().getContentAsString().replace("&amp;", "&"));
        while (m.find()) {
            String found = m.group().replaceFirst("[.,;:!)\\]]+$", ""); // a URL quoted in a sentence
            String absolute = found.startsWith("http") ? found : new URL(j.getURL(), found.replaceFirst("^.*?batch-control/", "batch-control/")).toExternalForm();
            if (!out.contains(absolute)) {
                out.add(absolute);
            }
        }
        return out;
    }

    private static String queryValue(String url, String name) {
        int q = url.indexOf('?');
        if (q < 0) {
            return "";
        }
        for (String pair : url.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (key.equals(name)) {
                return java.net.URLDecoder.decode(eq < 0 ? "" : pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return "";
    }
}
