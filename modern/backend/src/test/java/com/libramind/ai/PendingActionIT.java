package com.libramind.ai;

import static org.assertj.core.api.Assertions.*;

import com.libramind.IntegrationSupport;
import com.libramind.common.api.BusinessException;
import com.libramind.loan.LoanDtos.*;
import com.libramind.loan.LoanService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

class PendingActionIT extends IntegrationSupport {
    @Autowired PendingActionService actions;
    @Autowired LoanService loans;
    @Autowired ConversationService conversations;

    @Test
    void preparationDoesNotWriteAndConcurrentConfirmationWritesExactlyOnce() throws Exception {
        var reader = reader();
        var copy = copy();
        var action = actions.prepareBorrow(reader, null, null, copy.accessionNo());
        assertThat(sql.count("SELECT COUNT(*) FROM loan WHERE copy_id=?", copy.id())).isZero();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a =
                    executor.submit(
                            () -> {
                                start.await();
                                return actions.confirm(reader, action.id());
                            });
            var b =
                    executor.submit(
                            () -> {
                                start.await();
                                return actions.confirm(reader, action.id());
                            });
            start.countDown();
            assertThat(a.get().status()).isEqualTo("SUCCEEDED");
            assertThat(b.get().status()).isEqualTo("SUCCEEDED");
        }
        assertThat(sql.count("SELECT COUNT(*) FROM loan WHERE copy_id=?", copy.id())).isEqualTo(1);
        assertThat(actions.confirm(reader, action.id()).status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void expiredCancelledAndForeignActionsNeverWrite() {
        var reader = reader();
        var outsider = reader();
        var copy = copy();
        var action = actions.prepareBorrow(reader, null, null, copy.accessionNo());
        assertThatThrownBy(() -> actions.confirm(outsider, action.id()))
                .isInstanceOf(BusinessException.class);
        sql.update(
                "UPDATE pending_action SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE"
                        + " id=?",
                action.id());
        assertThat(actions.confirm(reader, action.id()).status()).isEqualTo("EXPIRED");
        var cancelled = actions.prepareBorrow(reader, null, null, copy.accessionNo());
        actions.cancel(reader, cancelled.id());
        assertThat(actions.confirm(reader, cancelled.id()).status()).isEqualTo("CANCELLED");
        assertThat(sql.count("SELECT COUNT(*) FROM loan WHERE copy_id=?", copy.id())).isZero();
    }

    @Test
    void staleRenewalFailsWithoutSecondRenewalAndReturnCanBeConfirmed() {
        var reader = reader();
        var copy = copy();
        var borrow = actions.prepareBorrow(reader, null, null, copy.accessionNo());
        actions.confirm(reader, borrow.id());
        long loanId =
                sql.count("SELECT id FROM loan WHERE copy_id=? AND status='ACTIVE'", copy.id());
        var renew = actions.prepareLoan(reader, null, loanId, "RENEW");
        loans.renew(reader, loanId, null);
        assertThat(actions.confirm(reader, renew.id()).failureCode()).isEqualTo("ACTION_STALE");
        assertThat(loans.find(reader, loanId).renewCount()).isEqualTo(1);
        var giveBack = actions.prepareLoan(reader, null, loanId, "RETURN");
        assertThat(actions.confirm(reader, giveBack.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(loans.find(reader, loanId).status()).isEqualTo(LoanStatus.RETURNED);
    }

    @Test
    void conversationOwnershipAndLeaseAndCandidateOrderArePersistent() {
        var reader = reader();
        var outsider = reader();
        var conversation = conversations.create(reader, "测试指代");
        assertThatThrownBy(() -> conversations.messages(outsider, conversation.id(), 0))
                .isInstanceOf(BusinessException.class);
        var turn = conversations.begin(reader, conversation.id(), "找Java书");
        assertThatThrownBy(() -> conversations.begin(reader, conversation.id(), "另一个并发问题"))
                .isInstanceOf(BusinessException.class);
        conversations.finish(
                reader, turn, "第一本、第二本", java.util.List.of(9L, 4L), java.util.List.of(), 10, true);
        var second = conversations.begin(reader, conversation.id(), "第二本能借吗");
        assertThat(second.candidates()).containsExactly(9L, 4L);
        assertThat(second.history()).hasSize(2);
        conversations.release(reader, second);
        conversations.archive(reader, conversation.id());
        assertThat(conversations.list(reader))
                .extracting(ConversationService.Conversation::id)
                .doesNotContain(conversation.id());
    }
}
