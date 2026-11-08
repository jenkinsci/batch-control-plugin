package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Failure;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.ui.RecordLookup;
import io.jenkins.plugins.batchcontrol.ui.SectionAccess;
import java.io.IOException;
import jenkins.model.Jenkins;
import jenkins.security.stapler.StaplerAccessibleType;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Routing object for {@code /batch-control/grants/active/...}. Active grants are listed on the
 * grants index page; this subtree only carries the revoke endpoint
 * {@code POST /batch-control/grants/active/<grantId>/revoke}.
 *
 * <p>The whole subtree sits behind the {@link GrantsSection#getTarget()} permission gate; the
 * revoke endpoint additionally requires {@code Manage} (SPEC item 8).
 *
 * <p>{@link StaplerAccessibleType}: without a web method of its own (the former {@code doIndex}
 * is now an {@code index.jelly}), Jenkins' routing filter would not let
 * {@link GrantsSection#getActive()} return this type, and the revoke URLs below would answer 404.
 */
@Restricted(NoExternalUse.class)
@StaplerAccessibleType
public class ActiveGrantsSection {

    /**
     * The bare {@code /batch-control/grants/active/} URL has nothing to show: its
     * {@code index.jelly} redirects to the grants index (hosting review: no {@code doIndex}).
     * Permissions for that view: the Grants screen's ({@link SectionAccess#grants()}), which the
     * parent gate {@link GrantsSection#getTarget()} has already enforced.
     */
    public Permission[] getViewPermissions() {
        return SectionAccess.grants();
    }

    /**
     * #132: the check of {@code index.jelly}, made before its redirect: {@link #getViewPermissions()}
     * on Jenkins, the nearest access-controlled ancestor, as the parent gate
     * {@link GrantsSection#getTarget()} does. The view has no layout, so {@code h} (set by
     * {@code l:view}) is undefined there and {@code h.checkAnyPermission} would check nothing.
     */
    public void checkViewPermission() {
        Jenkins.get().checkAnyPermission(getViewPermissions());
    }

    /**
     * Stapler: serves {@code /batch-control/grants/active/<grantId>/...}; {@code null} renders a
     * 404 when {@link GrantService} knows no grant with that id, for a malformed id and for any
     * other failed lookup (S-39-01, {@link RecordLookup}). An existing grant that is no longer
     * active still resolves: {@link GrantService#revoke} rejects it with a message.
     */
    @CheckForNull
    public Item getDynamic(String grantId) {
        Grant grant = RecordLookup.find(grantId, "grant", i -> GrantService.get().find(i), Grant::getId,
                g -> true);
        return grant == null ? null : new Item(grant.getId());
    }

    /** One active grant; only carries the revoke endpoint. */
    public static final class Item {

        private final String grantId;

        Item(String grantId) {
            this.grantId = grantId;
        }

        /**
         * A GET of {@code /batch-control/grants/active/<grantId>/} has no detail view: its
         * {@code index.jelly} redirects to the grants index (hosting review: no {@code doIndex}).
         * The only state change in this subtree is the {@code @RequirePOST} revoke endpoint below.
         */
        public Permission[] getViewPermissions() {
            return SectionAccess.grants();
        }

        /**
         * #132: the check of {@code index.jelly}, made before its redirect; see
         * {@link ActiveGrantsSection#checkViewPermission()}.
         */
        public void checkViewPermission() {
            Jenkins.get().checkAnyPermission(getViewPermissions());
        }

        /**
         * POST {@code /batch-control/grants/active/<grantId>/revoke} — immediately revokes the
         * grant. {@code Manage} only (SPEC item 8); a GET never reaches the service because of
         * {@code @RequirePOST}.
         */
        @RequirePOST
        public void doRevoke(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
            Jenkins.get().checkPermission(BatchControlPermissions.MANAGE);
            try {
                GrantService.get().revoke(grantId);
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new Failure(e.getMessage() == null ? "The revoke was rejected" : e.getMessage());
            }
            // Back to the grants index (base of this URL is .../grants/active/<grantId>/).
            rsp.sendRedirect2("../..");
        }
    }
}
