package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.model.Approvers;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.Store;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Runtime authority on active grants (SPEC item 8). Keeps an in-memory cache of all grants,
 * backed by the file store ({@code grants/<id>.xml}), so the permission-check hot path
 * ({@link GrantAwareACL}) never touches disk.
 *
 * <p>Restart safety: {@link Jenkins} is a singleton, so there is exactly one live cache to keep
 * fresh; {@link #resetCacheOnStartup()} clears it unconditionally on every Jenkins startup, so
 * the very first query after a restart reloads straight from the files instead of serving
 * whatever happened to be in memory before. A grant that is still inside its window therefore
 * survives a restart and one whose window ended during the downtime is gone from the very first
 * check (SPEC item 8). Activity is always judged by {@link Grant#isActiveAt} against
 * {@link BatchClock} at check time — no timers.
 *
 * <p>S-15: the change-control switch gates whether a grant <em>confers</em> anything (that check is
 * in {@link GrantAwareACL}), and turning the switch off also closes the windows that are open at
 * that moment — see {@link #revokeAllActive()}.
 *
 * <p>D-74: a window names one item by its full name and follows that item. Item events keep the
 * names right ({@link WindowItemListener}): a rename or move updates the windows naming the item
 * ({@link #followItem}), a deletion ends them ({@link #endWindowsOf}). Renaming through a window is
 * refused (D-71c, {@link GrantAwareACL}), so only an administrator or a user with their own
 * permissions can move a window's name. S-39-02: no window or D-35c record is left on a name its
 * item no longer has: registration verifies the approved item is still at its name
 * ({@link #register}, D-71c (3)), and a window that cannot follow its item for certain ends instead
 * ({@link #followItem}). D-35c created-item records and, D-75 (2), the D-58a changed-under-grant
 * lists follow in memory first and their write is retried; an entry at a name another item has just
 * taken is dropped ({@link #relocateCreatedItem}, {@link #relocateChanged}).
 *
 * <p>D-74, a window's end is never lost: a window ends in memory first, so it confers nothing from
 * that moment, and its {@code GRANT_REVOKE} record is appended before its file is rewritten. If the
 * file cannot be written, the end is kept in {@link #unsavedEnds} and written again before every
 * later grant write, by every item event and by the periodic work ({@link #flushUnsavedEnds()});
 * every read of a grant file in this class applies it, so no other write can bring the window back.
 * If Jenkins stops before the end is written, the record stands in for it: when the cache is
 * loaded, a window whose file still says it is open but which has a {@code GRANT_REVOKE} record
 * is ended again ({@link #applyRecordedEnds}), so a restart cannot re-open it, also not on an item
 * created at its name in the meantime. S-39-03: that read is bounded by time, not by a record
 * count, and when it cannot be completed every open window ends (fail-closed).
 */
@Restricted(NoExternalUse.class)
public final class GrantService {

    private static final Logger LOGGER = Logger.getLogger(GrantService.class.getName());

    private static final GrantService INSTANCE = new GrantService();

    /**
     * How the {@code GRANT_REVOKE} record of a window that ended because of its item goes on after
     * the grant's description (D-74, {@link #endWindowsWhere}); its reason is always
     * {@link Grant#REVOKED_ITEM_DELETED}. {@link #reasonOf} reads it back.
     */
    private static final String ENDED = "ended: ";

    /** As {@link #ENDED} for a window closed by turning change control off ({@link #revokeAllActive()}). */
    private static final String REVOKED_SWITCH_OFF = "revoked: " + Grant.REVOKED_CHANGE_CONTROL_OFF;

    /**
     * As {@link #ENDED} for a window that could not follow its renamed or moved item (S-39-02); its
     * reason is always {@link Grant#REVOKED_ITEM_NOT_FOLLOWED}.
     */
    private static final String NOT_FOLLOWED = "not followed: ";

    /**
     * As {@link #ENDED} for a window ended at startup because the change records could not rule out
     * that it had ended (S-39-03); its reason is always {@link Grant#REVOKED_UNCONFIRMED}.
     */
    private static final String UNCONFIRMED = "revoked: " + Grant.REVOKED_UNCONFIRMED;

    /** How the detail of a window's {@code GRANT_REVOKE} record of {@link #endUnconfirmed} ends, after {@link #describe}. */
    private static final String UNCONFIRMED_DETAIL = UNCONFIRMED + ": the change records since it was granted could not"
            + " all be read at startup, so its end could not be ruled out";

    private final Store store = Store.get();

    /** All known grants (active or not); guarded by {@code this}. */
    private List<Grant> cache;

    /**
     * R3-04: the D-35c records of the grants that were neither revoked nor expired when the cache
     * last changed, by the full name of the item recorded as created; {@code null} while the cache
     * is not loaded. An immutable snapshot, replaced as a whole under this monitor after every change
     * of the cache ({@link #reindexCreating}) and read without it: the Item/Read, Item/Discover and
     * Item/Configure checks ({@link #findCreatingGrant}) never wait for this monitor (which is held
     * across store reads and writes) and never iterate the retained, ended grants. Whether a record
     * applies (active at the check time, the same user, the item directly inside the grant's scope
     * folder) is still decided at query time, exactly as before.
     */
    private volatile java.util.Map<String, List<CreatingRecord>> creatingIndex;

    /** R3-04: one D-35c record of {@link #creatingIndex}, with the grant's fields it is matched on. */
    private static final class CreatingRecord {
        private final Grant grant;
        private final String user;
        private final GrantScope scope;
        private final Instant grantedAt;
        private final Instant expiresAt;

        CreatingRecord(Grant grant) {
            this.grant = grant;
            this.user = grant.getUser();
            this.scope = grant.getScope();
            this.grantedAt = grant.getGrantedAt();
            this.expiresAt = grant.getExpiresAt();
        }

        /** As {@link Grant#isActiveAt} (a revoked grant is never indexed). */
        boolean isActiveAt(Instant at) {
            return !at.isBefore(grantedAt) && at.isBefore(expiresAt);
        }
    }

    /**
     * D-74: windows that have ended (in memory, and in a {@code GRANT_REVOKE} record when that could
     * be appended) but whose file still says they are open, by id; guarded by {@code this}. Cleared
     * with the cache at startup, where the records stand in for them.
     */
    private final java.util.Map<String, Grant> unsavedEnds = new java.util.LinkedHashMap<>();

    /**
     * S-39-02: D-35c created-items lists already in effect in the cache but not yet written to their
     * grant file, by grant id; guarded by {@code this}. Written again like {@link #unsavedEnds}, and
     * applied by every read of a grant file in this class, so no other write puts the old list back.
     */
    private final java.util.Map<String, List<String>> unsavedCreatedItems = new java.util.LinkedHashMap<>();

    /**
     * D-58a, D-75 (2): changed-items lists ("changed under a grant") already in effect in the cache
     * but not yet written to their grant file, by grant id; guarded by {@code this}. Handled exactly
     * like {@link #unsavedCreatedItems}: written again before every grant write, by every item event
     * and by the periodic work, and applied by every read of a grant file in this class.
     */
    private final java.util.Map<String, List<String>> unsavedChangedItems = new java.util.LinkedHashMap<>();

    /**
     * S-39-02: the items whose deletion event has been handled ({@link #endWindowsOf}); guarded by
     * {@code this}. Core reports a deletion before it frees the name, so a window registered after
     * that event but before the name is free would otherwise still find its item at its name. Weak
     * keys: an entry never keeps a deleted item in memory.
     */
    private final java.util.Map<Item, Boolean> deletedItems = new java.util.WeakHashMap<>();

    private GrantService() {
    }

    public static GrantService get() {
        return INSTANCE;
    }

    /**
     * Clears the in-memory cache. Jenkins is a singleton, so this is not about telling one
     * running instance apart from another — it simply makes sure the first query after a fresh
     * Jenkins startup (a real restart, or a test harness booting a new session) reloads from the
     * files instead of serving a cache built for whatever was on disk before.
     */
    @Initializer(after = InitMilestone.PLUGINS_STARTED)
    public static void resetCacheOnStartup() {
        INSTANCE.clearCache();
    }

    private synchronized void clearCache() {
        cache = null;
        creatingIndex = null;
        // Belongs to the Jenkins session that is gone (a test harness may start the next one, with
        // another home, in the same JVM); the GRANT_REVOKE records replace it (applyRecordedEnds).
        unsavedEnds.clear();
        unsavedCreatedItems.clear();
        unsavedChangedItems.clear();
        deletedItems.clear();
        markedRuns.clear();
        markedRunsLoaded = false;
    }

    /**
     * Drops grants that retention has deleted from the store. They ended before the
     * retention cut-off, so none of them can be active; this only keeps the cache from holding
     * them until the next restart.
     */
    public synchronized void forgetDeleted(java.util.Collection<String> grantIds) {
        if (cache != null && !grantIds.isEmpty()) {
            cache.removeIf(grant -> grantIds.contains(grant.getId()));
            reindexCreating();
        }
    }

    // ---------------------------------------------------------------- queries

    /*
     * A window confers something only on the item whose full name its scope names exactly (D-71);
     * item events keep that name right (D-74). The lookups take a copy of the matching grants under
     * this monitor, so nothing below runs while the monitor is held. The D-35c lookup, which every
     * Item/Read check makes, does not take the monitor at all (R3-04, creatingIndex).
     *
     * The lookups that take only a full name resolve the item currently at that name as the caller
     * sees it (no SYSTEM switch; an item the caller cannot read gets nothing). They are for callers
     * outside a permission check (tests, screens); the grant layer itself only uses the item forms.
     */

    /**
     * Whether {@code user} currently holds {@code permission} on the item named
     * {@code itemFullName} through an active grant. Only the three grantable item permissions
     * can ever match; any other permission returns {@code false} immediately.
     *
     * <p>The item currently at that name is resolved as the caller sees it; none, no grant. Whether
     * the action can apply to the item's kind is decided when the request is submitted and
     * approved, and again by the grant layer ({@code GrantAwareACL}) and {@link #findActiveDeleteGrant}.
     */
    public boolean hasActiveGrant(String user, String itemFullName, Permission permission) {
        GrantAction action = GrantAction.fromPermission(permission);
        return action != null && findActiveGrant(user, itemFullName, action) != null;
    }

    /**
     * The first active grant of {@code user} whose scope is the item {@code itemFullName} (an item
     * the caller can see must be at that name) and that includes {@code action}, or {@code null}.
     * A {@code null} action matches any action.
     *
     * <p>For DELETE this does not look at the item's kind; callers holding the item use
     * {@link #findActiveDeleteGrant}, which also requires the item to be a job (D-71).
     */
    @CheckForNull
    public Grant findActiveGrant(String user, String itemFullName, @CheckForNull GrantAction action) {
        return findActiveGrant(user, itemFullName, resolve(itemFullName), action);
    }

    /**
     * The first active grant of {@code user} naming exactly {@code item}'s full name and including
     * {@code action} ({@code null}: any action), or {@code null}.
     */
    @CheckForNull
    public Grant findActiveGrant(String user, @CheckForNull Item item, @CheckForNull GrantAction action) {
        return item == null ? null : findActiveGrant(user, item.getFullName(), item, action);
    }

    /**
     * As {@link #findActiveGrant(String, Item, GrantAction)} for a window naming
     * {@code itemFullName} rather than the item's current full name: its name before a rename or
     * move, whose window was the one in use (change records; item events update the window's name
     * only after those are written, {@link WindowItemListener}).
     */
    @CheckForNull
    public Grant findActiveGrant(String user, @CheckForNull String itemFullName, @CheckForNull Item item,
                                 @CheckForNull GrantAction action) {
        if (item == null) {
            return null;
        }
        List<Grant> found = named(user, itemFullName, action);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * D-71: the active DELETE window of {@code user} on {@code item}, or {@code null}. A window's
     * DELETE applies only to a job ({@link GrantScope#deleteAppliesTo}), so for any other item —
     * a folder, a multibranch project, an organization folder — this is {@code null} whatever
     * windows exist: deleting such a group would delete its children as SYSTEM.
     */
    @CheckForNull
    public Grant findActiveDeleteGrant(String user, @CheckForNull Item item) {
        return findActiveDeleteGrant(user, item, item == null ? null : item.getFullName());
    }

    /**
     * As {@link #findActiveDeleteGrant(String, Item)} for the item under the name
     * {@code itemFullName} (its name before a move, for example).
     */
    @CheckForNull
    public Grant findActiveDeleteGrant(String user, @CheckForNull Item item, @CheckForNull String itemFullName) {
        if (!GrantScope.deleteAppliesTo(item)) {
            return null;
        }
        return findActiveGrant(user, itemFullName, item, GrantAction.DELETE);
    }

    /**
     * Every active grant of {@code user} naming exactly {@code item}'s full name and including
     * {@code action} (D-40: the CREATE check has to see all of them, since each may carry a different
     * name restriction).
     *
     * <p>For {@link GrantAction#CREATE}, {@code item} is the item group the new item is created in
     * (Item/Create is checked on the group's ACL), so a CREATE window confers Create in its own
     * folder only, never in a nested folder or at the root (D-71).
     */
    public List<Grant> findActiveGrants(String user, @CheckForNull Item item, GrantAction action) {
        return findActiveGrants(user, item, item == null ? null : item.getFullName(), action);
    }

    private List<Grant> findActiveGrants(String user, @CheckForNull Item item, @CheckForNull String itemFullName,
                                         GrantAction action) {
        if (item == null || action == null) {
            return new ArrayList<>();
        }
        return named(user, itemFullName, action);
    }

    /**
     * D-40: the first active Create grant of {@code user} on the item group {@code group} (named
     * exactly) whose name restriction (if any) allows {@code itemName}, or
     * {@code null}. {@code null} for the Jenkins root, where no window exists (S-13).
     */
    @CheckForNull
    public Grant findActiveCreateGrant(String user, @CheckForNull ItemGroup<?> group, String itemName) {
        if (!(group instanceof Item)) {
            return null;
        }
        return firstAllowing(findActiveGrants(user, (Item) group, GrantAction.CREATE), itemName);
    }

    /** The first grant whose name restriction allows {@code itemName}; never under this monitor (S-03). */
    @CheckForNull
    private static Grant firstAllowing(List<Grant> grants, String itemName) {
        // Not synchronized: the list is a copy, and a user-supplied pattern is never matched while
        // this monitor is held, since every permission check passes through it (security-08 S-03).
        for (Grant grant : grants) {
            if (grant.allowsCreateName(itemName)) {
                return grant;
            }
        }
        return null;
    }

    /**
     * D-36: marks and returns every active grant whose window ends within {@code lead} and whose
     * GRANT_EXPIRING notification was not sent yet. The flag is persisted before the caller
     * dispatches, so a restart never resends.
     *
     * <p>Each window is claimed on its own: a grant file that cannot be read or written now is
     * logged and left unclaimed for the next run, and it never keeps the windows after it from being
     * claimed nor drops the claims already written, which are all returned (a claim written but not
     * returned would never be sent).
     */
    public synchronized List<Grant> claimExpiringNotifications(java.time.Duration lead) {
        Instant now = BatchClock.now();
        List<Grant> claimed = new ArrayList<>();
        for (Grant cached : new ArrayList<>(grants())) {
            if (!cached.isActiveAt(now) || cached.isExpiringNotified()
                    || now.isBefore(cached.getExpiresAt().minus(lead))) {
                continue;
            }
            try {
                Grant grant = load(cached.getId());
                if (grant == null || !grant.isActiveAt(now) || grant.isExpiringNotified()) {
                    continue;
                }
                grant.setExpiringNotified(true);
                save(grant);
                claimed.add(grant); // written: from here on it must reach the caller
                replaceInCache(grant);
            } catch (RuntimeException e) {
                LOGGER.log(java.util.logging.Level.WARNING, "Could not mark the GRANT_EXPIRING notification of grant "
                        + cached.getId() + " as sent; it is tried again on the next run", e);
            }
        }
        return claimed;
    }

    /**
     * D-35c: the active grant of {@code user} through whose Create {@code item} was created, or
     * {@code null}. While such a grant is active its holder also holds Item/Read and
     * Item/Configure on that item (see {@code GrantAwareACL}), so matrix-auth's creator listener
     * finds the permissions already held and writes no permanent entry; the permissions end with
     * the window.
     *
     * <p>The item must still lie directly inside the grant's scope folder, matched by parent, not by
     * name prefix (D-71). Item events keep the record's name right (D-74): a rename or move updates
     * it, a deletion drops it, and a new item arriving under a recorded name drops the record
     * ({@link #forgetStaleCreatedItem}), so the record never applies to an item someone else made.
     */
    @CheckForNull
    public Grant findCreatingGrant(String user, @CheckForNull Item item) {
        return item == null ? null : findCreatingGrant(user, item.getFullName(), item);
    }

    /**
     * As {@link #findCreatingGrant(String, Item)} for the record under the name
     * {@code itemFullName} (its name before a rename, whose record was the one in use); the parent
     * is {@code item}'s.
     */
    @CheckForNull
    public Grant findCreatingGrant(String user, @CheckForNull String itemFullName, @CheckForNull Item item) {
        if (item == null) {
            return null;
        }
        List<Grant> candidates = creatingCandidates(user, itemFullName);
        if (candidates.isEmpty()) {
            return null;
        }
        ItemGroup<?> parentGroup = item.getParent();
        Item parent = parentGroup instanceof Item ? (Item) parentGroup : null;
        if (parent == null) {
            return null; // no root-scope grant exists (S-13)
        }
        for (Grant grant : candidates) {
            if (grant.getScope().includes(parent.getFullName())) {
                return grant;
            }
        }
        return null;
    }

    /**
     * Active grants of {@code user} (under the realm's user id strategy) recording
     * {@code itemFullName} as created directly inside their scope (a copy), in cache order.
     *
     * <p>R3-04: read from {@link #creatingIndex} without this monitor, so a permission check never
     * waits for a grant write or a store read, and only the records of {@code itemFullName} are
     * looked at. Only before the cache is first loaded in a Jenkins session (it is loaded at startup,
     * when all items are loaded, {@code WindowItemListener#onLoaded}) does this load it, under the
     * monitor.
     */
    private List<Grant> creatingCandidates(String user, @CheckForNull String itemFullName) {
        List<Grant> found = new ArrayList<>();
        if (user == null || itemFullName == null) {
            return found;
        }
        java.util.Map<String, List<CreatingRecord>> index = creatingIndex;
        if (index == null) {
            index = loadCreatingIndex();
        }
        List<CreatingRecord> records = index.get(itemFullName);
        if (records == null) {
            return found;
        }
        Instant now = BatchClock.now();
        for (CreatingRecord record : records) {
            if (record.isActiveAt(now)
                    && Approvers.sameUser(user, record.user)
                    && record.scope.isParentOf(itemFullName)) {
                found.add(record.grant);
            }
        }
        return found;
    }

    /** {@link #creatingIndex}, loading the cache first (see {@link #creatingCandidates}). */
    private synchronized java.util.Map<String, List<CreatingRecord>> loadCreatingIndex() {
        grants();
        java.util.Map<String, List<CreatingRecord>> index = creatingIndex;
        return index != null ? index : java.util.Map.of(); // no Jenkins: nothing loaded, nothing confers
    }

    /**
     * R3-04: rebuilds {@link #creatingIndex} from the cache. Called under this monitor after every
     * change of the cache (load, add, replace, removal, and the one change of a cached grant in
     * place, {@link #rewriteCreatedItems}), before any store write that follows it, so a revocation
     * or a dropped record is in effect for the permission checks as soon as it is in the cache.
     * Revoked grants and grants already expired are left out: they never confer again.
     */
    private synchronized void reindexCreating() {
        if (cache == null) {
            creatingIndex = null;
            return;
        }
        Instant now = BatchClock.now();
        java.util.Map<String, List<CreatingRecord>> building = new java.util.HashMap<>();
        for (Grant grant : cache) {
            if (grant.getRevokedAt() != null || !now.isBefore(grant.getExpiresAt())
                    || grant.getScope() == null || grant.getUser() == null) {
                continue;
            }
            List<String> created = grant.getCreatedItems();
            if (created.isEmpty()) {
                continue;
            }
            CreatingRecord record = new CreatingRecord(grant);
            for (String name : created) {
                building.computeIfAbsent(name, key -> new ArrayList<>(1)).add(record);
            }
        }
        java.util.Map<String, List<CreatingRecord>> index = new java.util.HashMap<>();
        building.forEach((name, records) -> index.put(name, List.copyOf(records)));
        creatingIndex = java.util.Collections.unmodifiableMap(index);
    }

    /**
     * The active grant that gives {@code user} Item/Configure on {@code item}: a grant with the
     * CONFIGURE action naming the item, or a Create grant through which the user
     * created it (D-35c). {@code null} if neither exists. Used to name the grant a change or a
     * violation came from (D-35b, SPEC item 9).
     */
    @CheckForNull
    public Grant findConfigureGrant(String user, @CheckForNull Item item) {
        Grant grant = findActiveGrant(user, item, GrantAction.CONFIGURE);
        return grant != null ? grant : findCreatingGrant(user, item);
    }

    /**
     * Active grants of {@code user} whose scope is exactly {@code itemFullName} and that include
     * {@code action} ({@code null}: any), not yet matched against an item (a copy).
     */
    private synchronized List<Grant> named(String user, @CheckForNull String itemFullName,
                                           @CheckForNull GrantAction action) {
        List<Grant> found = new ArrayList<>();
        if (user == null || itemFullName == null) {
            return found;
        }
        Instant now = BatchClock.now();
        for (Grant grant : grants()) {
            if (grant.isActiveAt(now)
                    && Approvers.sameUser(user, grant.getUser())
                    && grant.getScope() != null
                    && grant.getScope().includes(itemFullName)
                    && (action == null || grant.getActions().contains(action))) {
                found.add(grant);
            }
        }
        return found;
    }

    /**
     * The item at {@code fullName} as the caller sees it, or {@code null} (none, not readable, or
     * Item/Discover only). Never called from inside a permission check (see above).
     */
    @CheckForNull
    private static Item resolve(@CheckForNull String fullName) {
        if (fullName == null || fullName.isEmpty() || Jenkins.getInstanceOrNull() == null) {
            return null;
        }
        try {
            return Jenkins.get().getItemByFullName(fullName);
        } catch (org.springframework.security.access.AccessDeniedException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- D-58a guarded items

    /**
     * D-58a: whether the item is guarded: covered by an active grant (its scope includes the item,
     * the scope lies below the item when the item is a folder, or the item was created through
     * it), or changed under a grant (active or ended) and not reviewed since. Answered from the
     * in-memory grant cache.
     */
    public synchronized boolean isGuardedItem(String itemFullName) {
        if (itemFullName == null || itemFullName.isEmpty()) {
            return false;
        }
        Instant now = BatchClock.now();
        for (Grant grant : grants()) {
            if (changedAtOrAbove(grant, itemFullName)) {
                return true;
            }
            if (grant.isActiveAt(now) && covers(grant, itemFullName)) {
                return true;
            }
        }
        return false;
    }

    /** D-58c: at most this many marked runs are listed per item. */
    static final int MAX_MARKED_RUNS = 50;

    /** At most this many REPLAY_UNDER_GRANT records are read to rebuild the index after a restart. */
    static final int MARKED_RUN_RECORDS = 5_000;

    /**
     * D-58c (S-30-05): job full name to the ids of its marked runs, newest first. Filled when a
     * marked run starts, and once per session from the REPLAY_UNDER_GRANT change records (bounded,
     * no build is loaded); never computed while a map lock is held.
     */
    private final java.util.Map<String, List<String>> markedRuns = new java.util.concurrent.ConcurrentHashMap<>();

    private volatile boolean markedRunsLoaded;

    /** The marked-run index follows a rename or move, and forgets a deleted item ({@code null}). */
    private void relocateMarkedRuns(String oldFullName, @CheckForNull String newFullName) {
        relocate(markedRuns, oldFullName, newFullName);
    }

    /**
     * Moves the entries of {@code index} at or below {@code oldFullName} to {@code newFullName}
     * (run ids included), or drops them when {@code newFullName} is {@code null}.
     */
    private static void relocate(java.util.Map<String, List<String>> index, String oldFullName,
                                 @CheckForNull String newFullName) {
        for (String job : new ArrayList<>(index.keySet())) {
            if (!job.equals(oldFullName) && !job.startsWith(oldFullName + "/")) {
                continue;
            }
            List<String> runs = index.remove(job);
            if (runs == null || newFullName == null) {
                continue;
            }
            String moved = newFullName + job.substring(oldFullName.length());
            List<String> renamed = new ArrayList<>();
            for (String runId : runs) {
                renamed.add(moved + runId.substring(job.length()));
            }
            index.merge(moved, renamed, GrantService::mergeRuns);
        }
    }

    /** {@code first}, then the runs of {@code second} it lacks, at most {@value #MAX_MARKED_RUNS}. */
    private static List<String> mergeRuns(List<String> first, List<String> second) {
        List<String> merged = new ArrayList<>(first);
        for (String runId : second) {
            if (merged.size() >= MAX_MARKED_RUNS) {
                break;
            }
            if (!merged.contains(runId)) {
                merged.add(runId);
            }
        }
        return merged;
    }

    /** D-58c: a marked run started (called by the run listener). */
    public void noteMarkedRun(String jobFullName, String runId) {
        markedRuns.compute(jobFullName, (job, runs) -> prepend(runs, runId));
    }

    private static List<String> prepend(List<String> runs, String runId) {
        List<String> updated = new ArrayList<>();
        updated.add(runId);
        if (runs != null) {
            for (String existing : runs) {
                if (updated.size() >= MAX_MARKED_RUNS) {
                    break;
                }
                if (!existing.equals(runId)) {
                    updated.add(existing);
                }
            }
        }
        return updated;
    }

    /**
     * D-58c: the ids ({@code job#number}) of the runs of the item (a job, or the jobs below a
     * folder) that were replayed under a grant, at most {@value #MAX_MARKED_RUNS}. Served from the
     * in-memory index; no build is loaded.
     */
    public List<String> markedRuns(hudson.model.Item item) {
        loadMarkedRunsOnce();
        List<String> out = new ArrayList<>();
        String fullName = item.getFullName();
        List<String> own = markedRuns.get(fullName);
        if (own != null) {
            out.addAll(own);
        }
        if (item instanceof hudson.model.ItemGroup) {
            for (java.util.Map.Entry<String, List<String>> e : markedRuns.entrySet()) {
                if (out.size() >= MAX_MARKED_RUNS) {
                    break;
                }
                if (e.getKey().startsWith(fullName + "/")) {
                    out.addAll(e.getValue());
                }
            }
        }
        return out.size() > MAX_MARKED_RUNS ? new ArrayList<>(out.subList(0, MAX_MARKED_RUNS)) : out;
    }

    /**
     * Rebuilds the index once per session from the REPLAY_UNDER_GRANT records of the retained months
     * (the record's target is the job, its detail starts with "Run #n"). Bounded by
     * {@value #MARKED_RUN_RECORDS} records; a failure leaves the index as it is.
     *
     * <p>#1.3 (S-31-03): a record names the job as it was called when the run started. The RENAME,
     * MOVE and DELETE records written since the oldest of those records (bounded the same way) are
     * applied in order, as the live index applied them, so after a restart the runs are listed
     * under the job's current name and a deleted job's runs are forgotten. What happened while
     * nothing was recorded (both switches off, D-13) cannot be followed.
     */
    private void loadMarkedRunsOnce() {
        if (markedRunsLoaded) {
            return;
        }
        synchronized (markedRuns) {
            if (markedRunsLoaded) {
                return;
            }
            markedRunsLoaded = true;
            try {
                List<ChangeRecord> marks = oldestFirst(store.pageChangeRecords(
                        store.listStoredMonths(), r -> r.getType() == ChangeType.REPLAY_UNDER_GRANT,
                        0, MARKED_RUN_RECORDS, MARKED_RUN_RECORDS * 20).getItems());
                if (marks.isEmpty()) {
                    return;
                }
                Instant since = marks.get(0).getAt();
                List<ChangeRecord> events = new ArrayList<>(marks);
                events.addAll(oldestFirst(store.pageChangeRecords(store.listStoredMonths(),
                        r -> isRelocation(r.getType()) && (since == null || (r.getAt() != null && !r.getAt().isBefore(since))),
                        0, MARKED_RUN_RECORDS, MARKED_RUN_RECORDS * 20).getItems()));
                // Oldest first; at the same instant a run's start comes before a rename or move,
                // which then moves it (a run started after a rename names the new name already).
                events.sort(java.util.Comparator.comparing(ChangeRecord::getAt,
                                java.util.Comparator.nullsFirst(java.util.Comparator.<Instant>naturalOrder()))
                        .thenComparing(r -> r.getType() == ChangeType.REPLAY_UNDER_GRANT ? 0 : 1));
                java.util.Map<String, List<String>> rebuilt = new java.util.HashMap<>();
                for (ChangeRecord record : events) {
                    if (record.getType() == ChangeType.REPLAY_UNDER_GRANT) {
                        String runId = markedRunId(record);
                        if (runId != null) {
                            rebuilt.compute(record.getTarget(), (job, runs) -> runs != null && runs.contains(runId)
                                    ? runs : prepend(runs, runId));
                        }
                    } else {
                        Relocation moved = relocation(record);
                        if (moved != null) {
                            relocate(rebuilt, moved.from(), moved.to());
                        }
                    }
                }
                // Runs noted in this session before the first read already carry their current name.
                rebuilt.forEach((job, runs) -> markedRuns.merge(job, runs, GrantService::mergeRuns));
            } catch (RuntimeException e) {
                LOGGER.log(java.util.logging.Level.WARNING, "Could not rebuild the index of runs replayed under a grant", e);
            }
        }
    }

    private static List<ChangeRecord> oldestFirst(List<ChangeRecord> newestFirst) {
        List<ChangeRecord> records = new ArrayList<>(newestFirst);
        java.util.Collections.reverse(records);
        return records;
    }

    private static boolean isRelocation(ChangeType type) {
        return type == ChangeType.RENAME || type == ChangeType.MOVE || type == ChangeType.DELETE;
    }

    /** The run id ({@code job#n}) a REPLAY_UNDER_GRANT record names, or {@code null}. */
    @CheckForNull
    private static String markedRunId(ChangeRecord record) {
        String detail = record.getDetail();
        if (record.getTarget() == null || detail == null || !detail.startsWith("Run #")) {
            return null;
        }
        int end = 5;
        while (end < detail.length() && Character.isDigit(detail.charAt(end))) {
            end++;
        }
        return end > 5 ? record.getTarget() + "#" + detail.substring(5, end) : null;
    }

    /** An item's full name before a rename or move, and after it ({@code null}: deleted). */
    private record Relocation(String from, @CheckForNull String to) {
    }

    /**
     * The old and new full names of a RENAME or MOVE record ({@code ItemChangeListener}'s texts,
     * read against the record's target, the new full name), or the target and {@code null} for a
     * DELETE record; {@code null} when the record does not say.
     */
    @CheckForNull
    private static Relocation relocation(ChangeRecord record) {
        String target = record.getTarget();
        String detail = record.getDetail();
        if (target == null || target.isEmpty()) {
            return null;
        }
        if (record.getType() == ChangeType.DELETE) {
            return new Relocation(target, null);
        }
        if (detail == null) {
            return null;
        }
        if (record.getType() == ChangeType.RENAME) {
            String prefix = "Renamed from '";
            String suffix = "' to '" + target.substring(target.lastIndexOf('/') + 1) + "'";
            if (!detail.startsWith(prefix) || !detail.endsWith(suffix) || detail.length() <= prefix.length() + suffix.length()) {
                return null;
            }
            String oldName = detail.substring(prefix.length(), detail.length() - suffix.length());
            String parent = GrantScope.parentOf(target);
            return new Relocation(parent.isEmpty() ? oldName : parent + "/" + oldName, target);
        }
        if (record.getType() == ChangeType.MOVE) {
            String prefix = "Moved from '";
            String to = "' to '" + target + "'";
            int at = detail.indexOf(to, prefix.length());
            int end = at + to.length();
            if (!detail.startsWith(prefix) || at <= prefix.length()
                    || (end != detail.length() && !detail.startsWith("; ", end))) {
                return null;
            }
            return new Relocation(detail.substring(prefix.length(), at), target);
        }
        return null;
    }

    /**
     * D-58b: whether the item, or an item above it, is in the "changed under a grant" state (any
     * grant, active or ended). Served from the in-memory grant cache, for page rendering.
     */
    public synchronized boolean isChangedUnderGrant(hudson.model.Item item) {
        if (item == null) {
            return false;
        }
        String fullName = item.getFullName();
        for (Grant grant : grants()) {
            if (changedAtOrAbove(grant, fullName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * D-58b (1): whether the grant lists the item, or an item above it, as changed under it (a
     * changed folder shapes what its children run: branch jobs of a multibranch project, jobs in a
     * folder).
     */
    private static boolean changedAtOrAbove(Grant grant, String itemFullName) {
        for (String changed : grant.getChangedItems()) {
            if (itemFullName.equals(changed) || itemFullName.startsWith(changed + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a grant's coverage (scope, a scope below a folder, or D-35c created) includes the item.
     *
     * <p>D-58b (1) guards every item below a scope item, unchanged by D-71: a window on a folder
     * confers nothing on its children, but the holder may change the folder's configuration (and
     * its authorization property, inherited below it), so what lies below it stays guarded.
     */
    private static boolean covers(Grant grant, String itemFullName) {
        String scope = grant.getScope() == null ? null : grant.getScope().getFullName();
        if (grant.getScope() != null && grant.getScope().includes(itemFullName)) {
            return true;
        }
        if (scope != null && !scope.isEmpty()
                // a folder's property is inherited below it, so a scope below the folder guards it
                && (scope.startsWith(itemFullName + "/")
                        // D-58b (1): everything below a scope item (branch jobs of a multibranch
                        // project, jobs in a folder)
                        || itemFullName.startsWith(scope + "/"))) {
            return true;
        }
        for (String created : grant.getCreatedItems()) {
            if (itemFullName.equals(created) || itemFullName.startsWith(created + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * D-58a: the id of a grant that makes the item guarded (one that lists it as changed, else an
     * active one covering it), or {@code null} when it is not guarded.
     */
    @CheckForNull
    public synchronized String guardingGrantId(String itemFullName) {
        if (itemFullName == null || itemFullName.isEmpty()) {
            return null;
        }
        Instant now = BatchClock.now();
        String covering = null;
        for (Grant grant : grants()) {
            if (changedAtOrAbove(grant, itemFullName)) {
                return grant.getId();
            }
            if (covering == null && grant.isActiveAt(now) && covers(grant, itemFullName)) {
                covering = grant.getId();
            }
        }
        return covering;
    }

    /** D-58a: the items in the "changed under a grant" state, sorted, at most {@code limit}. */
    public synchronized List<String> itemsChangedUnderGrant(int limit) {
        java.util.TreeSet<String> items = new java.util.TreeSet<>();
        for (Grant grant : grants()) {
            items.addAll(grant.getChangedItems());
        }
        List<String> out = new ArrayList<>();
        for (String item : items) {
            if (out.size() >= limit) {
                break;
            }
            out.add(item);
        }
        return out;
    }

    /**
     * D-58a: notes that {@code itemFullName} was changed under {@code grantId} (a save or creation
     * by the holder while their permission came only from the grant), persisting the grant. The mark
     * is in effect in memory at once and a failed write is retried ({@link #rewriteChangedItems});
     * it never fails the save.
     */
    public synchronized void markChanged(String grantId, String itemFullName) {
        if (grantId == null || itemFullName == null || itemFullName.isEmpty()) {
            return;
        }
        try {
            Grant cached = null;
            for (Grant grant : grants()) {
                if (grant.getId().equals(grantId)) {
                    cached = grant;
                    break;
                }
            }
            if (cached == null) {
                cached = load(grantId);
            }
            if (cached == null || cached.hasChanged(itemFullName)) {
                return; // no such grant, or already marked
            }
            rewriteChangedItems(cached, items -> {
                if (!items.contains(itemFullName)) {
                    items.add(itemFullName);
                }
                return items;
            }, "'" + itemFullName + "' marked as changed under it");
        } catch (RuntimeException e) {
            // S-28-06: a lost mark would let the item go unguarded once the window ends.
            LOGGER.log(java.util.logging.Level.SEVERE, "Could not mark '" + itemFullName
                    + "' as changed under grant " + grantId + "; an administrator must check it", e);
        }
    }

    /**
     * D-58b (3): the deliberate review. The item and everything below it leave the "changed under a
     * grant" state, and a {@code GUARD_REVIEWED} change record names the reviewer. The caller must
     * hold Item/Configure on the item natively (asked with every grant layer off) or
     * Overall/Administer; a user whose permission on the item comes from a grant cannot review it.
     * The entries leave the state in memory at once and a grant file that cannot be written is
     * written again later ({@link #removeChanged}), so the record says what the guard does.
     *
     * @throws org.springframework.security.access.AccessDeniedException (AccessDeniedException3)
     *         when the caller may not review the item
     */
    public void markReviewed(hudson.model.Item item) {
        org.springframework.security.core.Authentication auth = Jenkins.getAuthentication2();
        if (!mayReview(item, auth)) {
            throw new hudson.security.AccessDeniedException3(auth, hudson.model.Item.CONFIGURE);
        }
        String fullName = item.getFullName();
        // S-29-02: an entry below the item is cleared only if the reviewer may review that item too.
        java.util.Set<String> clear = new java.util.LinkedHashSet<>();
        java.util.Set<String> kept = new java.util.LinkedHashSet<>();
        for (String name : changedAtOrBelow(fullName)) {
            // S-30-03: found as SYSTEM (read-only lookup, ApprovalPolicy#itemForPolicy), so an item the
            // reviewer cannot read is not taken for a deleted one; the reviewer's own permission
            // on it is what decides below.
            hudson.model.Item below = name.equals(fullName) ? item
                    : io.jenkins.plugins.batchcontrol.policy.ApprovalPolicy.itemForPolicy(name);
            if (below == null || below == item || mayReview(below, auth)) {
                clear.add(name);
            } else {
                kept.add(name);
            }
        }
        int cleared = removeChanged(clear);
        // S-29-03: recorded only when something was cleared, and says what is still guarded.
        if (cleared > 0) {
            String still = isGuardedItem(fullName)
                    ? " The item is still guarded through an active permission window or a changed folder above it."
                    : "";
            String skipped = kept.isEmpty() ? "" : " Not cleared, because the reviewer may not review them: "
                    + String.join(", ", kept) + ".";
            appendReviewRecord(ChangeRecord.create(ChangeType.GUARD_REVIEWED, fullName, auth.getName(),
                    "Marked as reviewed by '" + auth.getName() + "': " + cleared + " changed-under-a-permission-window"
                            + " entr" + (cleared == 1 ? "y" : "ies") + " at or below this item cleared." + skipped + still));
            LOGGER.info(() -> "'" + fullName + "' marked as reviewed by '" + auth.getName() + "'");
        }
    }

    /**
     * R3-01: appends the {@code GUARD_REVIEWED} record of a review that is already applied. A record
     * that cannot be written is logged at SEVERE and does not fail the review (no HTTP 500): the
     * entries have left the state in memory and their grant files are written again later, as for
     * any other end ({@link #revokeOne}).
     */
    private void appendReviewRecord(ChangeRecord record) {
        try {
            store.appendChangeRecord(record);
        } catch (RuntimeException e) {
            LOGGER.log(java.util.logging.Level.SEVERE, "Could not record the review of '" + record.getTarget()
                    + "' by '" + record.getUser() + "'; the review is in effect all the same", e);
        }
    }

    /**
     * S-29-04: an administrator clears a listed entry whose item no longer resolves (a stale entry).
     * Checked here too: Overall/Administer.
     */
    public void clearStaleEntry(String fullName) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        int cleared = removeChanged(java.util.Set.of(fullName));
        if (cleared > 0) {
            String user = Jenkins.getAuthentication2().getName();
            appendReviewRecord(ChangeRecord.create(ChangeType.GUARD_REVIEWED, fullName, user,
                    "Marked as reviewed by '" + user + "': the entry named an item that no longer exists."));
        }
    }

    private static boolean mayReview(hudson.model.Item item, org.springframework.security.core.Authentication auth) {
        return GrantLayer.hasPermissionWithoutGrants(item, auth, hudson.model.Item.CONFIGURE)
                || GrantLayer.hasPermissionWithoutGrants(Jenkins.get(), auth, Jenkins.ADMINISTER);
    }

    /** The changed entries (any grant) equal to or below {@code fullName}. */
    private synchronized java.util.Set<String> changedAtOrBelow(String fullName) {
        java.util.Set<String> found = new java.util.TreeSet<>();
        for (Grant grant : grants()) {
            for (String name : grant.getChangedItems()) {
                if (name.equals(fullName) || name.startsWith(fullName + "/")) {
                    found.add(name);
                }
            }
        }
        return found;
    }

    /**
     * Removes the exact entries {@code names} from every grant; the number of entries removed.
     * D-75 (2), like every other changed-items write ({@link #rewriteChangedItems}): the entries
     * leave the cache at once, so the count is what the guard sees from now on, and a grant file
     * that cannot be written is written again later instead of keeping the entries.
     */
    private synchronized int removeChanged(java.util.Set<String> names) {
        if (names.isEmpty()) {
            return 0;
        }
        java.util.concurrent.atomic.AtomicInteger removed = new java.util.concurrent.atomic.AtomicInteger();
        for (Grant cached : new ArrayList<>(grants())) {
            if (cached.getChangedItems().stream().noneMatch(names::contains)) {
                continue;
            }
            rewriteChangedItems(cached, items -> {
                List<String> kept = new ArrayList<>();
                for (String name : items) {
                    if (names.contains(name)) {
                        removed.incrementAndGet();
                    } else {
                        kept.add(name);
                    }
                }
                return kept;
            }, "changed-under-grant entries cleared by a review: " + String.join(", ", names));
        }
        return removed.get();
    }

    /**
     * D-58a (S-27-03): an item was renamed or moved. The "changed under a grant" state follows it
     * and what is below it; and an item that was covered by an active grant under its old name,
     * but is not under its new name, is marked as changed under that grant, so it stays guarded. A
     * window naming the item or an item below it follows it (D-74, {@link #followItem}), so it
     * keeps covering the item and marks nothing.
     *
     * <p>D-75 (2), as for created-item records ({@link #relocateCreatedItem}): an entry already naming
     * {@code newFullName} (in any letter case) or an item below it, other than the moved item's own
     * entries, cannot be about the moved item, which only now took that name; it is dropped first, so
     * the moved item does not inherit another item's state. An entry whose own item is still at
     * exactly its name, next to the moved item, stays (DEF-E17-01, {@link #ownItemStillAt}). The
     * entries change in the cache first and their write is retried ({@link #rewriteChangedItems}).
     *
     * <p>T-GAP-320: core reports the location change of a folder and then of every item below it,
     * each with its own old and new name, and this drop runs again in each of those events. So the
     * entries of the items below a renamed or moved folder follow in their own item's event, exact
     * names first, as windows and created-item records do ({@link #entriesFollowingLater}); were
     * they moved here, the drop of the later event would take them for stale entries at a name the
     * item below has just taken. An entry below the item that no item below it carries (its item is
     * gone, so no event of its own comes) follows here.
     *
     * @param moved the renamed or moved item ({@code null} when unknown)
     */
    public synchronized void relocateChanged(@CheckForNull Item moved, String oldFullName, String newFullName) {
        if (oldFullName == null || oldFullName.isEmpty() || newFullName == null || newFullName.isEmpty()
                || oldFullName.equals(newFullName)) {
            return;
        }
        rewriteChangedWhere(name -> (sameName(name, newFullName) || startsWithFolder(name, newFullName))
                        && !name.equals(oldFullName) && !name.startsWith(oldFullName + "/")
                        && !ownItemStillAt(name, moved),
                name -> null, "dropped stale changed-under-grant entries at or below '" + newFullName
                        + "', a name another item has just taken");
        Instant now = BatchClock.now();
        List<String> carriers = new ArrayList<>();
        for (Grant grant : grants()) {
            String scope = grant.getScope() == null ? null : grant.getScope().getFullName();
            boolean follows = scope != null && (scope.equals(oldFullName) || scope.startsWith(oldFullName + "/"));
            if (grant.isActiveAt(now) && !follows && covers(grant, oldFullName) && !covers(grant, newFullName)) {
                carriers.add(grant.getId());
            }
            // S-30-04: guarded through a changed folder above it, and moved out of that folder.
            for (String changed : grant.getChangedItems()) {
                if (oldFullName.startsWith(changed + "/") && !newFullName.startsWith(changed + "/")) {
                    carriers.add(grant.getId());
                }
            }
        }
        java.util.Set<String> later = entriesFollowingLater(moved, oldFullName, newFullName);
        rewriteChangedWhere(name -> (name.equals(oldFullName) || name.startsWith(oldFullName + "/")) && !later.contains(name),
                name -> newFullName + name.substring(oldFullName.length()),
                "the changed-under-grant state follows '" + oldFullName + "' to '" + newFullName + "'");
        relocateMarkedRuns(oldFullName, newFullName);
        for (String grantId : carriers) {
            markChanged(grantId, newFullName);
        }
    }

    /**
     * T-GAP-320: the changed entries below {@code oldFullName} that follow in a later event of the
     * same rename or move, because an item strictly below {@code moved} is at, or above, the name the
     * entry takes ({@link #carriedBelow}): core reports that item's own location change after this
     * one, and the entry follows there. Empty when {@code moved} is not an item group (nothing
     * below it gets an event) or is unknown.
     */
    private synchronized java.util.Set<String> entriesFollowingLater(@CheckForNull Item moved, String oldFullName,
                                                                     String newFullName) {
        if (!(moved instanceof ItemGroup)) {
            return java.util.Set.of();
        }
        java.util.Set<String> later = new java.util.HashSet<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Grant grant : grants()) {
            for (String name : grant.getChangedItems()) {
                if (name.startsWith(oldFullName + "/") && seen.add(name)
                        && carriedBelow(moved, newFullName, newFullName + name.substring(oldFullName.length()))) {
                    later.add(name);
                }
            }
        }
        return later;
    }

    /**
     * Whether {@code target}, a name below {@code newFullName}, is at or below an item that lies
     * strictly below {@code moved} under exactly its full name. Looked up as SYSTEM and only
     * compared ({@link #itemAt}).
     */
    private static boolean carriedBelow(Item moved, String newFullName, String target) {
        for (int end = target.length(); end > newFullName.length(); end = target.lastIndexOf('/', end - 1)) {
            String candidate = target.substring(0, end);
            Item at = itemAt(candidate);
            if (at != null && at != moved && candidate.equals(at.getFullName()) && isAtOrBelow(at, moved)) {
                return true;
            }
        }
        return false;
    }

    /** D-58a: a deleted item (and what was below it) leaves the "changed under a grant" state. */
    public synchronized void forgetChanged(String fullName) {
        rewriteChanged(fullName, null, true);
        relocateMarkedRuns(fullName, null);
    }

    /**
     * Replaces (or removes, with a {@code null} replacement) {@code fullName}, and with
     * {@code descendants} what is below it, in the changed-items list of every grant, active or
     * ended ({@link #rewriteChangedItems}).
     */
    private void rewriteChanged(String fullName, @CheckForNull String replacement, boolean descendants) {
        if (fullName == null || fullName.isEmpty()) {
            return;
        }
        rewriteChangedWhere(item -> item.equals(fullName) || (descendants && item.startsWith(fullName + "/")),
                item -> replacement == null ? null : replacement + item.substring(fullName.length()),
                replacement == null ? "dropped the changed-under-grant state of '" + fullName + "'"
                        : "the changed-under-grant state follows '" + fullName + "' to '" + replacement + "'");
    }

    /**
     * In the changed-items list of every grant (active or ended: the state outlives the window,
     * D-58a), replaces each entry {@code affected} accepts with what {@code replacement} makes of it,
     * or drops it when that is {@code null} ({@link #rewriteChangedItems}).
     */
    private synchronized void rewriteChangedWhere(Predicate<String> affected,
                                                  java.util.function.UnaryOperator<String> replacement, String what) {
        for (Grant cached : new ArrayList<>(grants())) {
            if (cached.getChangedItems().stream().noneMatch(affected)) {
                continue;
            }
            rewriteChangedItems(cached, items -> {
                java.util.LinkedHashSet<String> updated = new java.util.LinkedHashSet<>();
                for (String item : items) {
                    String target = affected.test(item) ? replacement.apply(item) : item;
                    if (target != null) {
                        updated.add(target);
                    }
                }
                return new ArrayList<>(updated);
            }, what);
        }
    }

    /**
     * D-58a, D-75 (2): changes the changed-items list of {@code cached}'s grant to what {@code update}
     * makes of it, exactly as {@link #rewriteCreatedItems} does for created-item records: in the cache
     * first, so the guard follows the change at once, then in the grant file. A write that fails is
     * kept in {@link #unsavedChangedItems} and written again before every later grant write, by every
     * item event and by the periodic work ({@link #retryUnsavedWrites}); every read of the file in this
     * class applies it meanwhile, so no other write puts the old list back. A grant file that cannot
     * be read is changed from its copy in memory; a grant without a file only in memory.
     */
    private synchronized void rewriteChangedItems(Grant cached, java.util.function.UnaryOperator<List<String>> update,
                                                  String what) {
        Grant grant;
        try {
            grant = load(cached.getId());
            if (grant == null) {
                cached.setChangedItems(update.apply(cached.getChangedItems())); // nothing on disk to write
                return;
            }
        } catch (RuntimeException e) {
            LOGGER.log(java.util.logging.Level.WARNING, "Could not read grant " + cached.getId()
                    + "; changing its changed-under-grant state from its copy in memory", e);
            grant = cached;
        }
        List<String> updated = update.apply(grant.getChangedItems());
        grant.setChangedItems(updated);
        replaceInCache(grant);
        Grant changed = grant;
        try {
            save(changed);
            LOGGER.fine(() -> "Grant " + changed.getId() + ": " + what);
        } catch (RuntimeException e) {
            unsavedChangedItems.put(changed.getId(), new ArrayList<>(updated));
            LOGGER.log(java.util.logging.Level.SEVERE, "Could not save grant " + changed.getId() + " (" + what
                    + "); the change is in effect, and it is written again before every grant write and by the"
                    + " periodic work until that succeeds", e);
        }
    }

    /** Whether {@code name} lies below the item {@code folder}, compared as Jenkins looks names up (any letter case). */
    private static boolean startsWithFolder(String name, String folder) {
        return name.length() > folder.length() + 1 && name.charAt(folder.length()) == '/'
                && name.regionMatches(true, 0, folder, 0, folder.length());
    }

    /** Every grant that is active right now (not expired, not revoked). */
    public synchronized List<Grant> listActive() {
        Instant now = BatchClock.now();
        List<Grant> active = new ArrayList<>();
        for (Grant grant : grants()) {
            if (grant.isActiveAt(now)) {
                active.add(grant);
            }
        }
        return active;
    }

    // ---------------------------------------------------------------- mutations

    /**
     * Persists a freshly created grant and makes it effective immediately. Called only by
     * {@code policy.GrantRequestService} on approval, with the item the approval checked.
     *
     * <p>D-74: item events do not wait for the approval, so the item may have been renamed or moved
     * after it was checked and before this runs; the event then found no window to update. The
     * window is therefore written under the item's full name as it is now. From here on, every event
     * sees the window: item events are handled under this monitor too.
     *
     * <p>D-71c (3), S-39-02: the item may also have been deleted after it was checked, and another
     * item created, renamed or moved to its name; neither event found a window to end. So the item's
     * identity is verified here, under this monitor, before the window becomes effective: unless the
     * item at its full name is still the approved object and its deletion has not been reported, the
     * window ends at once, with {@link Grant#REVOKED_ITEM_DELETED} as the reason and a
     * {@code GRANT_REVOKE} record, like any other end ({@link #revokeOne}).
     *
     * <p>#184: the window's file is written before the window enters the cache. A write that fails
     * throws with nothing in effect ({@link #registerApproved} then has its request put back); once
     * the file is written the window exists, also for the next start, which loads the cache from
     * those files.
     *
     * @return {@code true} when the window is in effect; {@code false} when it ended at once because
     *         its item was deleted (the caller's APPROVED notice says so, owner decision 2026-10-06)
     */
    public synchronized boolean register(Grant grant, Item item) {
        Objects.requireNonNull(grant, "grant");
        if (!writeNew(grant, item)) {
            return false;
        }
        addToCache(grant);
        return true;
    }

    /**
     * The part of {@link #register} up to the window's file: follows the item's name as it is now,
     * verifies the item (D-71c (3)) and writes the file.
     *
     * @return {@code true} when the file is written; {@code false} when the window ended at once
     */
    private synchronized boolean writeNew(Grant grant, Item item) {
        Objects.requireNonNull(item, "item");
        String current = item.getFullName();
        if (grant.getScope() != null && !current.equals(grant.getScope().getFullName())) {
            grant.followItem(current);
        }
        if (!stillAtItsName(item)) {
            LOGGER.warning(() -> "Grant " + grant.getId() + " was approved on '" + current + "', but that item was"
                    + " deleted before the window could be registered; the window ends at once");
            revokeOne(grant, Jenkins.getAuthentication2().getName(), Grant.REVOKED_ITEM_DELETED,
                    ENDED + "its item '" + current + "' was deleted before the window was registered");
            return false;
        }
        save(grant);
        return true;
    }

    /** The rest of {@link #register}: puts the written window in the cache, where it is in effect. */
    private synchronized void addToCache(Grant grant) {
        List<Grant> grants = grants();
        grants.removeIf(existing -> existing.getId().equals(grant.getId()));
        grants.add(grant);
        reindexCreating();
    }

    /**
     * #39: the approval's registration. {@code approval} decides the request and returns the window
     * to register; it runs under this monitor, and the window is registered as {@link #register(Grant,
     * Item)} does before the monitor is released.
     *
     * <p>Why under this monitor: turning change control off writes the switch first and then calls
     * {@link #revokeAllActive()}, which holds this monitor while it ends every active window. A
     * switch check made inside {@code approval} is therefore atomic with the registration relative to
     * that revocation. Either the check runs first, so the window is in the cache when
     * {@code revokeAllActive} lists it and the switch-off ends it, or it runs after, sees the switch
     * off, and refuses before anything is decided or registered. No window can be registered after a
     * switch-off has returned.
     *
     * <p>#184, all or nothing: {@code approval} writes the request APPROVED before the window's file
     * is written, never after, because a window file whose request still says PENDING would confer
     * the permissions while the request could be approved again, for a second window. Every
     * exception thrown before the window's file is written (by {@code approval} itself, or a grant
     * file that cannot be written) goes to {@code undo} under this monitor, then propagates;
     * {@code undo} puts back what {@code approval} stored, so nothing that runs under this monitor
     * finds the request decided without its window. Once that file is written the window exists and
     * nothing is undone. The gap left: Jenkins stopping between the two writes leaves the request
     * APPROVED with no window, which confers nothing (fail-closed); its requester files a new request.
     *
     * <p>Lock order, which this method keeps acyclic: {@code policy.GrantRequestService}'s lock, then
     * this monitor, then the store. The switch-off path takes the configuration's monitor and then
     * this monitor. Nothing that runs under this monitor (including {@code approval} and {@code undo})
     * takes the configuration's monitor or {@code GrantRequestService}'s lock: the switch is a
     * volatile read.
     *
     * @param approval decides the request (re-checks the switch, persists the decision) and returns
     *                 the window to register; an exception it throws registers nothing
     * @param undo     puts back what {@code approval} stored; given the exception that ended the
     *                 registration before the window's file was written, which propagates after it
     * @return as {@link #register(Grant, Item)}
     */
    public synchronized boolean registerApproved(Item item, java.util.function.Supplier<Grant> approval,
                                                 java.util.function.Consumer<RuntimeException> undo) {
        Objects.requireNonNull(item, "item");
        Grant grant;
        try {
            grant = Objects.requireNonNull(approval.get(), "grant");
            if (!writeNew(grant, item)) {
                return false;
            }
        } catch (RuntimeException e) {
            undo.accept(e);
            throw e;
        }
        addToCache(grant);
        return true;
    }

    /**
     * S-39-02: whether {@code item} is still the item at its full name: its deletion has not been
     * reported ({@link #endWindowsOf}), and the item Jenkins finds at that name is the same object.
     */
    private synchronized boolean stillAtItsName(Item item) {
        if (deletedItems.containsKey(item)) {
            return false;
        }
        return itemAt(item.getFullName()) == item;
    }

    /**
     * The item at {@code fullName}, whoever may see it, or {@code null}. Looked up as SYSTEM through
     * {@link io.jenkins.plugins.batchcontrol.policy.ApprovalPolicy#itemForPolicy}, whose javadoc gives
     * the reason for that ACL.SYSTEM2 switch: an item the acting user cannot read must not be taken
     * for a free name. The permission checks are complete before this is reached: the approver's
     * ({@code ApprovalPolicy.checkDecision}, the item check) for {@link #register}, core's own check
     * of the rename or move for {@link #followItem}, {@link #relocateCreatedItem} and
     * {@link #relocateChanged}. The item found
     * is only compared by identity, never returned to a caller or acted on.
     */
    @CheckForNull
    private static Item itemAt(String fullName) {
        // ACL.SYSTEM2 switch (in itemForPolicy): the permission checks are complete (see javadoc).
        return io.jenkins.plugins.batchcontrol.policy.ApprovalPolicy.itemForPolicy(fullName);
    }

    /** Whether an active grant matches {@code test}. */
    private synchronized boolean anyActive(Predicate<Grant> test) {
        Instant now = BatchClock.now();
        for (Grant grant : grants()) {
            if (grant.isActiveAt(now) && test.test(grant)) {
                return true;
            }
        }
        return false;
    }

    /**
     * S-39-02: whether an item other than {@code moved} is at {@code fullName}: when a rename or move
     * is handled, its old name may already belong to another item (renames whose events interleave),
     * and a window or a record naming that name can then no longer be told apart.
     */
    private static boolean otherItemAt(String fullName, @CheckForNull Item moved) {
        Item at = itemAt(fullName);
        return at != null && at != moved;
    }

    /**
     * Whether two full names may be one name for Jenkins: item names are usually looked up without
     * regard to letter case (core keeps the items of {@code Jenkins} in a case-insensitive map, and a
     * folder's are too until it is loaded again), so a record under another spelling of a name may be
     * about the item now at that name (S-39-02). A folder loaded from disk (after a restart or a
     * reload) looks its children up by exact name, though, and may then hold two items whose names
     * differ only in letter case; so before a window ends, or a record is dropped, because of a name
     * matched here, {@link #ownItemStillAt} asks whether its own item is still there (DEF-E17-01).
     */
    private static boolean sameName(@CheckForNull String a, @CheckForNull String b) {
        return a != null && b != null && String.CASE_INSENSITIVE_ORDER.compare(a, b) == 0;
    }

    /**
     * DEF-E17-01: whether the item a window or record names, {@code fullName}, is still there under
     * exactly that name, as an item other than {@code arrived} and the items below it (the item just
     * created, renamed or moved to another spelling of that name). Then the window or record is about
     * that item, not about the one that arrived, and keeps applying to it: next to it in a folder that
     * looks its children up by exact name ({@link #sameName}). Otherwise (no item at that exact name,
     * or the item found there is the one that arrived, which is what a case-insensitive lookup
     * answers for another spelling) its own item is gone, and S-39-02 ends or drops it so it never
     * reaches the new item. Looked up as SYSTEM, and only compared ({@link #itemAt}).
     */
    private static boolean ownItemStillAt(String fullName, @CheckForNull Item arrived) {
        Item at = itemAt(fullName);
        return at != null && fullName.equals(at.getFullName()) && !isAtOrBelow(at, arrived);
    }

    /** Whether {@code item} is {@code top} or lies below it. */
    private static boolean isAtOrBelow(Item item, @CheckForNull Item top) {
        if (top == null) {
            return false;
        }
        Item current = item;
        while (current != null) {
            if (current == top) {
                return true;
            }
            ItemGroup<?> parent = current.getParent();
            current = parent instanceof Item ? (Item) parent : null;
        }
        return false;
    }

    /**
     * D-35c: notes that {@code user} created {@code item} through the Create of an active grant,
     * persisting the grant. Called by {@code listener.CreatedItemGrantListener} only when the
     * creation was not possible without the grant. The Create window is the one on the item's
     * parent folder whose name restriction admits the item's name (D-40, chosen outside the
     * monitor, S-03).
     *
     * @return the grant that now records the item, or {@code null} when no active Create grant of
     *         {@code user} covers the item
     */
    @CheckForNull
    public Grant recordCreatedItem(String user, Item item) {
        Grant active = findActiveCreateGrant(user, item.getParent(), item.getName());
        if (active == null) {
            return null;
        }
        return recordCreatedItemIn(active.getId(), user, item.getFullName());
    }

    @CheckForNull
    private synchronized Grant recordCreatedItemIn(String grantId, String user, String itemFullName) {
        Grant grant = load(grantId);
        if (grant == null || !grant.isActiveAt(BatchClock.now()) || !Approvers.sameUser(user, grant.getUser())
                || !grant.getScope().isParentOf(itemFullName) || !grant.getActions().contains(GrantAction.CREATE)) {
            return null;
        }
        List<String> items = grant.getCreatedItems();
        if (!items.contains(itemFullName)) {
            items.add(itemFullName);
        }
        grant.setCreatedItems(items);
        // D-58a: an item created through the grant alone is changed under it.
        List<String> changed = grant.getChangedItems();
        if (!changed.contains(itemFullName)) {
            changed.add(itemFullName);
        }
        grant.setChangedItems(changed);
        save(grant);
        replaceInCache(grant);
        return grant;
    }

    /**
     * D-35c: the item {@code moved}, recorded as created through an active grant, was renamed or
     * moved from {@code oldFullName} to {@code newFullName}; the record follows it. Whether it still
     * confers anything is decided by the parent check at query time, so moving the item out of the
     * scope folder ends the permission. Core reports the change for every item below a renamed or
     * moved folder too, so each record follows in its own item's event (exact names, as windows do).
     *
     * <p>S-39-02, as for windows ({@link #followItem}): a record already naming {@code newFullName}
     * (in any letter case, other than the moved item's own old name) cannot be about the moved item
     * and is dropped first, unless its own item is still at exactly its name, next to the moved item
     * (DEF-E17-01, {@link #ownItemStillAt}); and when another item is at {@code oldFullName} again by
     * the time the event is handled, the records naming it are dropped instead of moved. Records
     * change in the cache first and their write is retried ({@link #rewriteCreatedItems}).
     */
    public synchronized void relocateCreatedItem(@CheckForNull Item moved, String oldFullName, String newFullName) {
        if (oldFullName == null || oldFullName.isEmpty() || newFullName == null || newFullName.isEmpty()
                || oldFullName.equals(newFullName)) {
            return;
        }
        rewriteCreatedItemsWhere(name -> sameName(name, newFullName) && !name.equals(oldFullName)
                        && !ownItemStillAt(name, moved),
                name -> null, "dropped the stale created-item record of '" + newFullName + "', now another item's name");
        boolean ambiguous = anyActive(grant -> grant.getCreatedItems().contains(oldFullName))
                && otherItemAt(oldFullName, moved);
        rewriteCreatedItemsWhere(oldFullName::equals, name -> ambiguous ? null : newFullName,
                ambiguous ? "dropped the created-item record of '" + oldFullName + "': another item already has that"
                        + " name again" : "the created-item record follows '" + oldFullName + "' to '" + newFullName + "'");
    }

    /**
     * D-35c: an item recorded as created through an active grant was deleted. The record (and any
     * record of an item below it) is dropped, so an item created later under the same name by
     * someone else confers nothing.
     */
    public synchronized void forgetCreatedItem(String fullName) {
        if (fullName == null || fullName.isEmpty()) {
            return;
        }
        rewriteCreatedItemsWhere(name -> name.equals(fullName) || name.startsWith(fullName + "/"), name -> null,
                "dropped the created-item record of the deleted '" + fullName + "'");
    }

    /**
     * D-74: the item {@code moved} was renamed or moved from {@code oldFullName} to
     * {@code newFullName}; every active window naming exactly {@code oldFullName} now names
     * {@code newFullName}. Core reports the location change of a folder and then of every item below
     * it, each with its own old and new name, so the windows on the items inside a renamed or moved
     * folder follow too, one event each. Ended windows keep the name they had: they record what was
     * approved.
     *
     * <p>S-39-02, so that no window is left on a name its item no longer has, and none reaches an
     * item nobody approved:
     * <ul>
     *   <li>an active window already naming {@code newFullName} (in any letter case, other than the
     *       moved item's own old name) cannot be about the moved item, which only now took that
     *       name; it ends first, as on the creation of a new item at its name (its own item vanished
     *       without an event, or moved away and its event has not been handled yet), unless its own
     *       item is still at exactly its name, next to the moved item (DEF-E17-01,
     *       {@link #ownItemStillAt});</li>
     *   <li>when another item is already at {@code oldFullName} again by the time the event is
     *       handled (renames whose events interleave), a window naming it can no longer be told to
     *       be about the moved item rather than that other item; it ends instead of following;</li>
     *   <li>a window whose file cannot be written with its new name ends ({@link #revokeOne}: in
     *       memory at once, durably through its {@code GRANT_REVOKE} record and the retried write)
     *       instead of staying on the old name, where an item renamed or moved there later would
     *       find it.</li>
     * </ul>
     * Each of these ends has {@link Grant#REVOKED_ITEM_NOT_FOLLOWED} as its reason. They are
     * fail-closed: the holder requests the window again.
     */
    public synchronized void followItem(@CheckForNull Item moved, String oldFullName, String newFullName) {
        if (oldFullName == null || oldFullName.isEmpty() || newFullName == null || newFullName.isEmpty()
                || oldFullName.equals(newFullName)) {
            return;
        }
        retryUnsavedWrites();
        String caller = Jenkins.getAuthentication2().getName();
        endWindowsWhere(scope -> sameName(scope, newFullName) && !scope.equals(oldFullName)
                        && !ownItemStillAt(scope, moved),
                Grant.REVOKED_ITEM_NOT_FOLLOWED, NOT_FOLLOWED + "'" + newFullName + "' is now the name of another item",
                caller);
        if (anyActive(grant -> grant.getScope() != null && grant.getScope().includes(oldFullName))
                && otherItemAt(oldFullName, moved)) {
            endWindowsWhere(oldFullName::equals, Grant.REVOKED_ITEM_NOT_FOLLOWED, NOT_FOLLOWED + "'" + oldFullName
                    + "' was renamed or moved to '" + newFullName + "', but another item already has the name '"
                    + oldFullName + "' again", caller);
            return;
        }
        Instant now = BatchClock.now();
        for (Grant cached : new ArrayList<>(grants())) {
            if (!cached.isActiveAt(now) || cached.getScope() == null || !cached.getScope().includes(oldFullName)) {
                continue;
            }
            try {
                Grant grant = load(cached.getId());
                if (grant == null) {
                    dropFromCache(cached.getId()); // no file: confers nothing
                    continue;
                }
                if (grant.getRevokedAt() != null) {
                    replaceInCache(grant); // ended behind the cache's back: it keeps its name
                    continue;
                }
                grant.followItem(newFullName);
                save(grant);
                replaceInCache(grant);
                LOGGER.info(() -> "Grant " + grant.getId() + " follows its item from '" + oldFullName + "' to '"
                        + newFullName + "'");
            } catch (RuntimeException e) {
                LOGGER.log(java.util.logging.Level.SEVERE, "Could not update grant " + cached.getId() + " to its item's"
                        + " new name '" + newFullName + "'; the window ends, so it never applies to another item at '"
                        + oldFullName + "'", e);
                revokeOne(cached, caller, Grant.REVOKED_ITEM_NOT_FOLLOWED, NOT_FOLLOWED
                        + "its file could not be updated to its item's new name '" + newFullName + "'");
            }
        }
    }

    /**
     * D-74: the item {@code fullName} was deleted; every active window naming it, or an item below
     * it, ends: it is revoked by {@code user}, with {@link Grant#REVOKED_ITEM_DELETED} as the reason
     * and a {@code GRANT_REVOKE} record, so it keeps its history. Core reports the deletion before it
     * frees the name, so no item can be created under that name first.
     *
     * <p>S-39-02: the item is remembered as deleted, so a window approved on it whose registration
     * runs after this event, while core has not freed the name yet, still ends ({@link #register}).
     *
     * @param user the user who deleted the item, also when core deleted it as SYSTEM because it lay
     *             below a folder that user deleted (SPEC 6: the history names who did what;
     *             {@link WindowItemListener})
     */
    public synchronized void endWindowsOf(Item item, String user) {
        deletedItems.put(item, Boolean.TRUE);
        String fullName = item.getFullName();
        if (fullName == null || fullName.isEmpty()) {
            return;
        }
        endWindowsWhere(scope -> scope.equals(fullName) || scope.startsWith(fullName + "/"),
                Grant.REVOKED_ITEM_DELETED, ENDED + "'" + fullName + "' was deleted", user);
    }

    /**
     * D-74: a new item was created (or copied) under {@code fullName}. An active window naming that
     * name cannot be about the new item: its own item disappeared without a deletion event (deleted
     * on disk, then reloaded), or was deleted while the window was being approved. It ends, as for
     * a deletion, so it never applies to the new item. The name is compared as Jenkins looks names
     * up, without regard to letter case (S-39-02): a window under another spelling of the name could
     * otherwise reach the new item through a later rename that only changes the case. A window whose
     * own item is still at exactly its name, next to the new item in a folder that looks its children
     * up by exact name, is about that item and stays (DEF-E17-01, {@link #ownItemStillAt}).
     */
    public synchronized void endWindowsOnNewItem(Item created) {
        String fullName = created.getFullName();
        if (fullName == null || fullName.isEmpty()) {
            return;
        }
        endWindowsWhere(scope -> sameName(scope, fullName) && !ownItemStillAt(scope, created),
                Grant.REVOKED_ITEM_DELETED, ENDED + "'" + fullName + "' is now the name of a new item",
                Jenkins.getAuthentication2().getName());
    }

    /**
     * D-74: after all items are loaded at startup, ends every active window whose item no longer
     * exists ({@code exists} is false for its full name): it was deleted while Jenkins was down, or
     * Jenkins stopped between deleting it and handling the deletion.
     */
    public synchronized void endWindowsOfMissingItems(Predicate<String> exists) {
        endWindowsWhere(scope -> !exists.test(scope), Grant.REVOKED_ITEM_DELETED, ENDED + "its item no longer exists",
                Jenkins.getAuthentication2().getName());
    }

    /**
     * Revokes, by {@code caller}, the active windows (with a non-empty scope full name) whose scope
     * {@code affected} accepts, with {@code reason} and, after the grant's description, {@code detail}
     * in the {@code GRANT_REVOKE} record ({@link #reasonOf} reads the reason back from it). A window
     * whose file cannot be read is ended from its cached copy; either way a failed write is retried
     * ({@link #revokeOne}).
     */
    private void endWindowsWhere(Predicate<String> affected, String reason, String detail, String caller) {
        retryUnsavedWrites();
        Instant now = BatchClock.now();
        for (Grant cached : new ArrayList<>(grants())) {
            String scope = cached.getScope() == null ? null : cached.getScope().getFullName();
            if (!cached.isActiveAt(now) || scope == null || scope.isEmpty() || !affected.test(scope)) {
                continue;
            }
            Grant grant;
            try {
                grant = load(cached.getId());
            } catch (RuntimeException e) {
                LOGGER.log(java.util.logging.Level.WARNING, "Could not read grant " + cached.getId()
                        + "; ending it from its copy in memory (" + detail + ")", e);
                grant = cached;
            }
            if (grant == null) {
                dropFromCache(cached.getId()); // no file: confers nothing
            } else if (grant.getRevokedAt() != null) {
                replaceInCache(grant);
            } else {
                revokeOne(grant, caller, reason, detail);
            }
        }
    }

    /**
     * D-35c, S-09: drops from the created-items lists of active grants every item for which
     * {@code exists} is false. Called after all items are loaded (startup and reload), so a record
     * of an item deleted on disk while no listener saw it cannot confer anything on an item
     * created later under the same name by someone else. An item that failed to load loses its
     * record too, which only takes permissions away (fail-safe).
     */
    public synchronized void pruneCreatedItems(Predicate<String> exists) {
        rewriteCreatedItemsWhere(name -> !exists.test(name), name -> null,
                "dropped created-item records of items that no longer exist");
    }

    /**
     * D-35c, D-74: a new item was created under {@code fullName}; a created-item record under that
     * name belongs to an item that disappeared without a deletion event (deleted on disk and
     * reloaded) and is dropped, so it never applies to the new item. The name is compared as Jenkins
     * looks names up, without regard to letter case (S-39-02), except that a record whose own item
     * is still at exactly its name, next to the new item, stays (DEF-E17-01, {@link #ownItemStillAt}).
     * That name only: a copied folder's children are created (and possibly recorded) before the
     * folder's own creation event.
     */
    public synchronized void forgetStaleCreatedItem(Item created) {
        String fullName = created.getFullName();
        if (fullName == null || fullName.isEmpty()) {
            return;
        }
        rewriteCreatedItemsWhere(name -> sameName(name, fullName) && !ownItemStillAt(name, created),
                name -> null, "dropped the stale created-item record of '" + fullName + "', now the name of a new item");
    }

    /**
     * In the created-items list of every active grant, replaces each name {@code affected} accepts
     * with what {@code replacement} makes of it, or drops it when that is {@code null}
     * ({@link #rewriteCreatedItems}).
     */
    private synchronized void rewriteCreatedItemsWhere(Predicate<String> affected,
                                                       java.util.function.UnaryOperator<String> replacement, String what) {
        Instant now = BatchClock.now();
        for (Grant cached : new ArrayList<>(grants())) {
            if (!cached.isActiveAt(now) || cached.getCreatedItems().stream().noneMatch(affected)) {
                continue;
            }
            rewriteCreatedItems(cached, items -> {
                java.util.LinkedHashSet<String> updated = new java.util.LinkedHashSet<>();
                for (String item : items) {
                    String target = affected.test(item) ? replacement.apply(item) : item;
                    if (target != null) {
                        updated.add(target);
                    }
                }
                return new ArrayList<>(updated);
            }, what);
        }
    }

    /**
     * S-39-02: changes the created-items list of {@code cached}'s grant to what {@code update} makes
     * of it, in the cache first, so the permission checks follow the change at once, and then in the
     * grant file. A write that fails is kept in {@link #unsavedCreatedItems} and written again before
     * every later grant write, by every item event and by the periodic work, like an end
     * ({@link #retryUnsavedWrites}); every read of the file in this class applies it meanwhile. A
     * grant file that cannot be read is changed from its copy in memory; a grant without a file only
     * in memory.
     */
    private synchronized void rewriteCreatedItems(Grant cached, java.util.function.UnaryOperator<List<String>> update,
                                                  String what) {
        Grant grant;
        try {
            grant = load(cached.getId());
            if (grant == null) {
                cached.setCreatedItems(update.apply(cached.getCreatedItems())); // nothing on disk to write
                reindexCreating();
                return;
            }
        } catch (RuntimeException e) {
            LOGGER.log(java.util.logging.Level.WARNING, "Could not read grant " + cached.getId()
                    + "; changing its created-item records from its copy in memory", e);
            grant = cached;
        }
        List<String> updated = update.apply(grant.getCreatedItems());
        grant.setCreatedItems(updated);
        replaceInCache(grant);
        Grant changed = grant;
        try {
            save(changed);
            LOGGER.info(() -> "Grant " + changed.getId() + ": " + what);
        } catch (RuntimeException e) {
            unsavedCreatedItems.put(changed.getId(), new ArrayList<>(updated));
            LOGGER.log(java.util.logging.Level.SEVERE, "Could not save grant " + changed.getId() + " (" + what
                    + "); the change is in effect, and it is written again before every grant write and by the"
                    + " periodic work until that succeeds", e);
        }
    }

    /**
     * Immediately revokes a grant (SPEC item 8: Manage holders only — the web action checks;
     * the service double-checks) and appends a {@code ChangeRecord(GRANT_REVOKE)}.
     *
     * @return the revoked grant
     * @throws IllegalArgumentException if no such grant exists
     * @throws IllegalStateException if the grant was already revoked
     */
    public synchronized Grant revoke(String grantId) {
        Objects.requireNonNull(grantId, "grantId");
        Jenkins.get().checkPermission(BatchControlPermissions.MANAGE);
        Grant grant = load(grantId);
        if (grant == null) {
            throw new IllegalArgumentException("No such grant: " + grantId);
        }
        if (grant.getRevokedAt() != null) {
            throw new IllegalStateException("Grant " + grantId + " is already revoked.");
        }
        String caller = Jenkins.getAuthentication2().getName();
        revokeOne(grant, caller, null, "revoked");
        return grant;
    }

    /**
     * S-15, the kill switch: revokes every grant that is active right now and returns how many were
     * closed. Called when the change-control switch is turned off (see
     * {@code config.BatchControlGlobalConfiguration#setChangeControlEnabled}) — turning the switch
     * off has to close the windows that are already open, not merely stop new ones from conferring.
     *
     * <p>Two things this does that {@link GrantAwareACL}'s switch check alone does not:
     * <ul>
     *   <li>it is <b>durable</b>. The ACL check makes an open window stop conferring while the
     *       switch is off; without the revocation, switching change control back on would bring
     *       every one of those windows back to life for the remainder of its duration (up to
     *       {@code maxGrantMinutes}, default 240). A revoked grant never returns —
     *       {@link Grant#isActiveAt} is false for it for good.</li>
     *   <li>it is <b>visible</b>. Each revocation appends a {@code ChangeRecord(GRANT_REVOKE)}
     *       naming the account that flipped the switch, the grantee whose window was closed and the
     *       position in the batch, so the audit history says who cut the work off and how much of it
     *       there was. A silent mass revocation would be worse than none: the users whose in-flight
     *       configuration changes suddenly stop working would have nothing to read.</li>
     * </ul>
     *
     * <p><b>This is destructive to other people's in-flight work, by design.</b> Whoever turns the
     * switch off cuts off every change currently underway with no warning; that is the trade the
     * owner chose over a switch that leaves windows quietly open. The caller writes its own
     * CONFIG_TOGGLE record before calling this, so the history reads "the switch went off" and then
     * "these windows were closed", in that order.
     *
     * <p>No permission check of its own, deliberately, and unlike {@link #revoke(String)}: the
     * caller is a plain configuration setter whose only web entry point is core's
     * {@code /manage/configure} submission, already gated on {@code Overall/Administer} — which
     * implies {@code BatchControl/Manage} in any case. Putting a check here would instead impose a
     * permission gate on a setter that JCasC and startup also drive.
     *
     * @return the number of active windows that were closed
     */
    public synchronized int revokeAllActive() {
        List<Grant> active = listActive();
        if (active.isEmpty()) {
            return 0;
        }
        String caller = Jenkins.getAuthentication2().getName();
        int total = active.size();
        int closed = 0;
        retryUnsavedWrites();
        for (Grant cached : active) {
            // Revoke the store's copy — the same one revoke(String) mutates — so the persisted
            // grant and the cache cannot end up disagreeing about who revoked it and when.
            Grant grant;
            try {
                grant = load(cached.getId());
            } catch (RuntimeException e) {
                // D-74: an unreadable file must not leave this or the remaining windows open.
                LOGGER.log(java.util.logging.Level.WARNING, "Could not read grant " + cached.getId()
                        + "; revoking it from its copy in memory", e);
                grant = cached;
            }
            if (grant == null) {
                LOGGER.warning(() -> "Grant " + cached.getId() + " is active in memory but has no "
                        + "file in the store, so it cannot be revoked as part of switching change "
                        + "control off; dropping it from the cache instead.");
                dropFromCache(cached.getId());
                continue;
            }
            if (grant.getRevokedAt() != null) {
                // Already revoked behind the cache's back: nothing to write, no record to duplicate.
                replaceInCache(grant);
                continue;
            }
            closed++;
            // The record and the grant say why, not just who.
            revokeOne(grant, caller, Grant.REVOKED_CHANGE_CONTROL_OFF, REVOKED_SWITCH_OFF
                    + " by '" + caller + "' (" + closed + " of " + total + " active permission windows closed)");
        }
        int closedCount = closed;
        LOGGER.info(() -> "Change control was switched off by '" + caller + "': " + closedCount
                + " of " + total + " active permission windows were revoked");
        return closed;
    }

    /**
     * Marks {@code grant} revoked by {@code caller}, refreshes the cache, appends the
     * {@code GRANT_REVOKE} record and persists the grant. The single revocation write path, so a
     * per-grant revocation and a switch-off mass revocation cannot drift apart in what they persist
     * or record.
     *
     * <p>D-74, fail-closed: the window ends in memory before anything is written, so it confers
     * nothing from this moment whatever happens to the files. The record comes before the grant
     * file, so Jenkins stopping in between leaves a record that ends the window again at the next
     * start ({@link #applyRecordedEnds}). A grant file that cannot be written is kept in
     * {@link #unsavedEnds} and written again later; a record that cannot be appended is logged.
     */
    private synchronized void revokeOne(Grant grant, String caller, @CheckForNull String reason, String detail) {
        grant.markRevoked(BatchClock.now(), caller, reason);
        replaceInCache(grant);
        ChangeRecord record = ChangeRecord.create(ChangeType.GRANT_REVOKE,
                grant.getScope() == null ? null : grant.getScope().getFullName(), caller, describe(grant) + detail);
        record.setGrantId(grant.getId());
        try {
            store.appendChangeRecord(record);
        } catch (RuntimeException e) {
            LOGGER.log(java.util.logging.Level.SEVERE, "Could not record the end of grant " + grant.getId()
                    + " (" + detail + "); it has ended all the same", e);
        }
        try {
            save(grant);
        } catch (RuntimeException e) {
            unsavedEnds.put(grant.getId(), grant);
            LOGGER.log(java.util.logging.Level.SEVERE, "Could not save grant " + grant.getId() + " as ended ("
                    + detail + "); it confers nothing, and its end is written again before every grant write"
                    + " and by the periodic work until that succeeds", e);
        }
        LOGGER.info(() -> "Grant " + grant.getId() + " revoked by '" + caller + "'");
    }

    // ---------------------------------------------------------------- D-74 writes not yet done

    /**
     * D-74: writes the ends (and, S-39-02, the created-item records, and, D-75 (2), the changed-item
     * lists) that could not be written so far (called by the periodic work; every grant write and
     * every item event does the same first).
     */
    public synchronized void flushUnsavedEnds() {
        retryUnsavedWrites();
    }

    /**
     * Writes every end in {@link #unsavedEnds}, every created-items list in
     * {@link #unsavedCreatedItems} and every changed-items list in {@link #unsavedChangedItems} into
     * its grant file, reading the file first so that nothing else in it is lost. One that still fails
     * stays for the next attempt; a window whose file is gone is dropped (it confers nothing).
     */
    private synchronized void retryUnsavedWrites() {
        retryUnsavedWrites(null);
    }

    /**
     * As {@link #retryUnsavedWrites()}, leaving out the grant {@code skip}: {@link #save} is about to
     * write that one from a copy that already carries its pending changes and possibly newer ones, and
     * putting the file's copy with only the pending changes into the cache would undo those (an end,
     * or a list change made since the last failed write).
     */
    private synchronized void retryUnsavedWrites(@CheckForNull String skip) {
        if (unsavedEnds.isEmpty() && unsavedCreatedItems.isEmpty() && unsavedChangedItems.isEmpty()) {
            return;
        }
        java.util.Set<String> ids = new java.util.LinkedHashSet<>(unsavedEnds.keySet());
        ids.addAll(unsavedCreatedItems.keySet());
        ids.addAll(unsavedChangedItems.keySet());
        if (skip != null) {
            ids.remove(skip);
        }
        for (String id : ids) {
            try {
                Grant stored = store.loadGrant(id);
                if (stored != null) {
                    boolean ended = stored.getRevokedAt() == null && unsavedEnds.containsKey(id);
                    boolean listed = unsavedCreatedItems.containsKey(id) || unsavedChangedItems.containsKey(id);
                    applyUnsaved(stored);
                    if (ended || listed) {
                        store.saveGrant(stored);
                    }
                    replaceInCache(stored);
                }
                unsavedEnds.remove(id);
                unsavedCreatedItems.remove(id);
                unsavedChangedItems.remove(id);
                LOGGER.info(() -> stored == null ? "Grant " + id + " has no file any more; its pending changes are dropped"
                        : "The pending changes of grant " + id + " are now written");
            } catch (RuntimeException e) {
                LOGGER.log(java.util.logging.Level.FINE, "The pending changes of grant " + id
                        + " still cannot be written", e);
            }
        }
    }

    /**
     * The stored grant {@code id}, or {@code null}; if its end (or a change of its created-item
     * records or of its changed-item list) is not written yet, the copy read gets it as in memory, so
     * a write of anything else in it also writes that and never re-opens the window or restores an
     * old record. Every read of a grant file in this class goes through here.
     */
    @CheckForNull
    private synchronized Grant load(String id) {
        Grant grant = store.loadGrant(id);
        if (grant != null) {
            applyUnsaved(grant);
        }
        return grant;
    }

    private synchronized void applyUnsaved(Grant grant) {
        Grant ended = unsavedEnds.get(grant.getId());
        if (ended != null && grant.getRevokedAt() == null) {
            grant.markRevoked(ended.getRevokedAt(), ended.getRevokedBy(), ended.getRevokedReason());
        }
        List<String> created = unsavedCreatedItems.get(grant.getId());
        if (created != null) {
            grant.setCreatedItems(created);
        }
        List<String> changed = unsavedChangedItems.get(grant.getId());
        if (changed != null) {
            grant.setChangedItems(changed);
        }
    }

    /**
     * Writes {@code grant} (the only grant write in this class), after the writes not done yet. Every
     * grant written here was read through {@link #load} or taken from the cache, so it carries its
     * pending changes; once written it leaves {@link #unsavedEnds} (when ended),
     * {@link #unsavedCreatedItems} and {@link #unsavedChangedItems}. The other grants' pending writes
     * are retried first; this grant's own are not, since writing {@code grant} writes them.
     */
    private synchronized void save(Grant grant) {
        retryUnsavedWrites(grant.getId());
        store.saveGrant(grant);
        if (grant.getRevokedAt() != null) {
            unsavedEnds.remove(grant.getId());
        }
        unsavedCreatedItems.remove(grant.getId());
        unsavedChangedItems.remove(grant.getId());
    }

    /**
     * D-74: ends again, in memory and in {@link #unsavedEnds}, every window of {@code loaded} whose
     * file says it is still open but which has a {@code GRANT_REVOKE} record: its end was decided
     * and recorded, but Jenkins stopped before the grant file could be written.
     *
     * <p>S-39-03: the records are read back to the oldest such window's grant time, however many
     * there are ({@link Store#grantRevokeRecordsSince}); a window lasts at most
     * {@code maxGrantMinutes}, so that is what bounds the read, never a record count. Nor is the read
     * taken past the record of an earlier fail-closed start older than every open window
     * ({@link #isStartupEnd}, T-SEC-109), so damage that start already answered ends no window
     * granted after it. If the read
     * fails or cannot be completed, no open window's end can be ruled out: every window not already
     * ended by a record read ends now (fail-closed), revoked by SYSTEM with
     * {@link Grant#REVOKED_UNCONFIRMED} as the reason and a {@code GRANT_REVOKE} record, so its holder
     * can request it again. The ends are written by {@link #grants()} once the cache is in place.
     */
    private synchronized void applyRecordedEnds(List<Grant> loaded) {
        Instant now = BatchClock.now();
        java.util.Map<String, Grant> open = new java.util.HashMap<>();
        Instant oldest = null;
        for (Grant grant : loaded) {
            if (grant.getRevokedAt() == null && now.isBefore(grant.getExpiresAt())) {
                open.put(grant.getId(), grant);
                if (oldest == null || grant.getGrantedAt().isBefore(oldest)) {
                    oldest = grant.getGrantedAt();
                }
            }
        }
        if (open.isEmpty()) {
            return;
        }
        io.jenkins.plugins.batchcontrol.store.RecordPage<ChangeRecord> page;
        try {
            page = store.grantRevokeRecordsSince(oldest, java.util.Set.copyOf(open.keySet()), GrantService::isStartupEnd);
        } catch (RuntimeException e) {
            LOGGER.log(java.util.logging.Level.SEVERE, "Could not read the GRANT_REVOKE records since " + open.size()
                    + " open permission window(s) were granted; they end, because none of them can be shown to be"
                    + " still open", e);
            page = null;
        }
        if (page != null) {
            for (ChangeRecord record : page.getItems()) {
                Grant grant = open.get(record.getGrantId());
                if (grant == null || grant.getRevokedAt() != null) {
                    continue;
                }
                grant.markRevoked(record.getAt(), record.getUser(), reasonOf(grant, record.getDetail()));
                unsavedEnds.put(grant.getId(), grant);
                LOGGER.warning(() -> "Grant " + grant.getId() + " ended at " + record.getAt() + " (" + record.getDetail()
                        + "), but its file still said it was open; it is ended again and its end is written now");
            }
        }
        if (page == null || page.isTruncated()) {
            endUnconfirmed(open.values(), now);
        }
    }

    /**
     * S-39-03, fail-closed: ends every window of {@code open} that is still open, because the change
     * records could not be read completely and so could not rule out that it had ended. The window
     * ends in memory first; its {@code GRANT_REVOKE} record is appended when the change log can be
     * written, and its file is written by {@link #grants()} (or later, {@link #retryUnsavedWrites}).
     */
    private synchronized void endUnconfirmed(java.util.Collection<Grant> open, Instant now) {
        int ended = 0;
        for (Grant grant : open) {
            if (grant.getRevokedAt() != null) {
                continue;
            }
            ended++;
            grant.markRevoked(now, hudson.security.ACL.SYSTEM_USERNAME, Grant.REVOKED_UNCONFIRMED);
            unsavedEnds.put(grant.getId(), grant);
            ChangeRecord record = ChangeRecord.create(ChangeType.GRANT_REVOKE,
                    grant.getScope() == null ? null : grant.getScope().getFullName(), hudson.security.ACL.SYSTEM_USERNAME,
                    describe(grant) + UNCONFIRMED_DETAIL);
            record.setGrantId(grant.getId());
            try {
                store.appendChangeRecord(record);
            } catch (RuntimeException e) {
                LOGGER.log(java.util.logging.Level.SEVERE, "Could not record the end of grant " + grant.getId()
                        + " (its end could not be ruled out at startup); it has ended all the same", e);
            }
        }
        int count = ended;
        if (count > 0) {
            LOGGER.severe(() -> count + " open permission window(s) ended at startup, because the change records since"
                    + " they were granted could not all be read; their holders may request them again");
        }
    }

    /**
     * T-SEC-109, D-75 (2): whether {@code record} is the {@code GRANT_REVOKE} record by which a start
     * ended a window because the change records could not all be read ({@link #endUnconfirmed}).
     * Such a start ended every window that was open then, before any window could be registered in
     * that session ({@link #register} loads the cache first), and a window's end is appended only
     * after it was registered. So for windows that were all granted after such a record, no line
     * appended before it can be an end: the startup read stops there instead of reading, again at
     * every later start, the damaged line that start already answered by failing closed. The store
     * offers only records older than the oldest open window, so that condition holds.
     */
    static boolean isStartupEnd(ChangeRecord record) {
        String detail = record.getDetail();
        String grantId = record.getGrantId();
        return record.getType() == ChangeType.GRANT_REVOKE && grantId != null && detail != null
                && hudson.security.ACL.SYSTEM_USERNAME.equals(record.getUser())
                && detail.startsWith("Grant " + grantId + " for user '") && detail.endsWith(") " + UNCONFIRMED_DETAIL);
    }

    /** How the detail of {@code grant}'s {@code GRANT_REVOKE} record begins ({@link #revokeOne}). */
    private static String describe(Grant grant) {
        return "Grant " + grant.getId() + " for user '" + grant.getUser() + "' (" + grant.getScope() + ") ";
    }

    /**
     * The revocation reason that the detail of {@code grant}'s {@code GRANT_REVOKE} record implies
     * ({@link #revokeOne}); {@code null} (an individual revocation) when it says neither.
     */
    @CheckForNull
    private static String reasonOf(Grant grant, @CheckForNull String detail) {
        String start = describe(grant);
        if (detail == null || !detail.startsWith(start)) {
            return null;
        }
        String rest = detail.substring(start.length());
        if (rest.startsWith(ENDED)) {
            return Grant.REVOKED_ITEM_DELETED;
        }
        if (rest.startsWith(REVOKED_SWITCH_OFF)) {
            return Grant.REVOKED_CHANGE_CONTROL_OFF;
        }
        if (rest.startsWith(NOT_FOLLOWED)) {
            return Grant.REVOKED_ITEM_NOT_FOLLOWED;
        }
        if (rest.startsWith(UNCONFIRMED)) {
            return Grant.REVOKED_UNCONFIRMED;
        }
        return null;
    }

    /**
     * Every known grant, active or ended, as the permission checks see it (a copy of the list): an
     * end not yet written to its file is already in it. Screens that list windows read this, not
     * the files, so they agree with {@link #listActive()}.
     */
    public synchronized List<Grant> listAll() {
        return new ArrayList<>(grants());
    }

    /** The grant {@code id} as the permission checks see it (see {@link #listAll()}), or {@code null}. */
    @CheckForNull
    public synchronized Grant find(String id) {
        if (id == null) {
            return null;
        }
        for (Grant grant : grants()) {
            if (id.equals(grant.getId())) {
                return grant;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- cache

    /** The live cache list, lazily loaded from the store and cleared at every startup. */
    private synchronized List<Grant> grants() {
        if (Jenkins.getInstanceOrNull() == null) {
            // No Jenkins (shutdown window): answer from whatever is cached, never load.
            return cache != null ? cache : new ArrayList<>();
        }
        if (cache == null) {
            List<Grant> loaded = new ArrayList<>(store.listGrants());
            loaded.forEach(this::applyUnsaved);
            applyRecordedEnds(loaded);
            cache = loaded;
            // The ends found in the records (or ended because they could not be ruled out, S-39-03)
            // are written now; whatever still fails is retried like any other unwritten end.
            retryUnsavedWrites();
            reindexCreating();
        }
        return cache;
    }

    /** Drops grant {@code id} from the cache (it has no file: it confers nothing). */
    private synchronized void dropFromCache(String id) {
        grants().removeIf(existing -> existing.getId().equals(id));
        reindexCreating();
    }

    /** Puts {@code grant} in the cache in place of the copy with the same id. */
    private synchronized void replaceInCache(Grant grant) {
        List<Grant> grants = grants();
        ListIterator<Grant> it = grants.listIterator();
        boolean replaced = false;
        while (it.hasNext()) {
            if (it.next().getId().equals(grant.getId())) {
                it.set(grant);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            grants.add(grant);
        }
        reindexCreating();
    }
}
