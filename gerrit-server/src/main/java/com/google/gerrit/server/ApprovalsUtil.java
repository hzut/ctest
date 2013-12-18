// Copyright (C) 2009 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.gerrit.common.data.LabelType;
import com.google.gerrit.common.data.LabelTypes;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.PatchSetApproval;
import com.google.gerrit.reviewdb.client.PatchSetApproval.LabelId;
import com.google.gerrit.reviewdb.client.PatchSetInfo;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.change.ChangeKind;
import com.google.gerrit.server.util.TimeUtil;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Utility functions to manipulate patchset approvals.
 * <p>
 * Approvals are overloaded, they represent both approvals and reviewers
 * which should be CCed on a change.  To ensure that reviewers are not lost
 * there must always be an approval on each patchset for each reviewer,
 * even if the reviewer hasn't actually given a score to the change.  To
 * mark the "no score" case, a dummy approval, which may live in any of
 * the available categories, with a score of 0 is used.
 * <p>
 * The methods in this class do not begin/commit transactions.
 */
public class ApprovalsUtil {
  @VisibleForTesting
  @Inject
  public ApprovalsUtil() {
  }

  public static enum ReviewerState {
    REVIEWER, CC;
  }

  /**
   * Get all reviewers for a change.
   *
   * @param db review database.
   * @param changeId change ID.
   * @return multimap of reviewers keyed by state, where each account appears
   *     exactly once in {@link SetMultimap#values()}.
   * @throws OrmException if reviewers for the change could not be read.
   */
  public SetMultimap<ReviewerState, Account.Id> getReviewers(ReviewDb db,
      Change.Id changeId) throws OrmException {
    return getReviewers(db.patchSetApprovals().byChange(changeId));
  }

  /**
   * Get all reviewers for a change.
   *
   * @param allApprovals all approvals to consider; must all belong to the same
   *     change.
   * @return multimap of reviewers keyed by state, where each account appears
   *     exactly once in {@link SetMultimap#values()}.
   */
  public static SetMultimap<ReviewerState, Account.Id> getReviewers(
      Iterable<PatchSetApproval> allApprovals) {
    PatchSetApproval first = null;
    SetMultimap<ReviewerState, Account.Id> reviewers =
        LinkedHashMultimap.create();
    for (PatchSetApproval psa : allApprovals) {
      if (first == null) {
        first = psa;
      } else {
        checkArgument(
            first.getKey().getParentKey().getParentKey().equals(
              psa.getKey().getParentKey().getParentKey()),
            "multiple change IDs: %s, %s", first.getKey(), psa.getKey());
      }
      Account.Id id = psa.getAccountId();
      if (psa.getValue() != 0) {
        reviewers.put(ReviewerState.REVIEWER, id);
        reviewers.remove(ReviewerState.CC, id);
      } else if (!reviewers.containsEntry(ReviewerState.REVIEWER, id)) {
        reviewers.put(ReviewerState.CC, id);
      }
    }
    return Multimaps.unmodifiableSetMultimap(reviewers);
  }

  /**
   * Copy min/max scores from one patch set to another.
   *
   * @throws OrmException
   */
  public void copyLabels(ReviewDb db, LabelTypes labelTypes,
      PatchSet.Id source, PatchSet dest, ChangeKind changeKind)
      throws OrmException {
    Iterable<PatchSetApproval> sourceApprovals =
        db.patchSetApprovals().byPatchSet(source);
    copyLabels(db, labelTypes, sourceApprovals, source, dest, changeKind);
  }

  /**
   * Copy a set's min/max scores from one patch set to another.
   *
   * @throws OrmException
   */
  public void copyLabels(ReviewDb db, LabelTypes labelTypes,
      Iterable<PatchSetApproval> sourceApprovals, PatchSet.Id source,
      PatchSet dest, ChangeKind changeKind) throws OrmException {
    List<PatchSetApproval> copied = Lists.newArrayList();
    for (PatchSetApproval a : sourceApprovals) {
      if (source.equals(a.getPatchSetId())) {
        LabelType type = labelTypes.byLabel(a.getLabelId());
        if (type == null) {
          continue;
        } else if (type.isCopyMinScore() && type.isMaxNegative(a)) {
          copied.add(new PatchSetApproval(dest.getId(), a));
        } else if (type.isCopyMaxScore() && type.isMaxPositive(a)) {
          copied.add(new PatchSetApproval(dest.getId(), a));
        } else if (type.isCopyAllScoresOnTrivialRebase()
            && ChangeKind.TRIVIAL_REBASE.equals(changeKind)) {
          copied.add(new PatchSetApproval(dest.getId(), a));
        } else if (type.isCopyAllScoresIfNoCodeChange()
            && ChangeKind.NO_CODE_CHANGE.equals(changeKind)) {
          copied.add(new PatchSetApproval(dest.getId(), a));
        }
      }
    }
    db.patchSetApprovals().insert(copied);
  }

  public List<PatchSetApproval> addReviewers(ReviewDb db, LabelTypes labelTypes,
      Change change, PatchSet ps, PatchSetInfo info,
      Iterable<Account.Id> wantReviewers,
      Collection<Account.Id> existingReviewers) throws OrmException {
    return addReviewers(db, labelTypes, change, ps.getId(), ps.isDraft(),
        info.getAuthor().getAccount(), info.getCommitter().getAccount(),
        wantReviewers, existingReviewers);
  }

  public List<PatchSetApproval> addReviewers(ReviewDb db, LabelTypes labelTypes,
      Change change, Iterable<Account.Id> wantReviewers) throws OrmException {
    PatchSet.Id psId = change.currentPatchSetId();
    Set<Account.Id> existing = Sets.newHashSet();
    for (PatchSetApproval psa : db.patchSetApprovals().byPatchSet(psId)) {
      existing.add(psa.getAccountId());
    }
    return addReviewers(db, labelTypes, change, psId, false, null, null,
        wantReviewers, existing);
  }

  private List<PatchSetApproval> addReviewers(ReviewDb db,
      LabelTypes labelTypes, Change change, PatchSet.Id psId, boolean isDraft,
      Account.Id authorId, Account.Id committerId,
      Iterable<Account.Id> wantReviewers,
      Collection<Account.Id> existingReviewers) throws OrmException {
    List<LabelType> allTypes = labelTypes.getLabelTypes();
    if (allTypes.isEmpty()) {
      return ImmutableList.of();
    }

    Set<Account.Id> need = Sets.newLinkedHashSet(wantReviewers);
    if (authorId != null && !isDraft) {
      need.add(authorId);
    }

    if (committerId != null && !isDraft) {
      need.add(committerId);
    }
    need.remove(change.getOwner());
    need.removeAll(existingReviewers);
    if (need.isEmpty()) {
      return ImmutableList.of();
    }

    List<PatchSetApproval> cells = Lists.newArrayListWithCapacity(need.size());
    LabelId labelId = Iterables.getLast(allTypes).getLabelId();
    for (Account.Id account : need) {
      cells.add(new PatchSetApproval(
          new PatchSetApproval.Key(psId, account, labelId),
          (short) 0, TimeUtil.nowTs()));
    }
    db.patchSetApprovals().insert(cells);
    return Collections.unmodifiableList(cells);
  }
}
