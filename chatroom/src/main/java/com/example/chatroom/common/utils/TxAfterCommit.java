package com.example.chatroom.common.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 把"事务提交之后才该做的事"（主要是 WebSocket 推送）推迟到 commit 之后再执行。
 * <p>
 * <b>为什么需要它</b>：在 {@code @Transactional} 方法里直接推送有三个问题：
 * <ol>
 *   <li><b>幽灵消息</b>：推送成功、对方界面已经显示出来了，但事务随后回滚 ——
 *       对方看到的消息库里根本不存在，而且永远不会消失。这是最严重的一条。</li>
 *   <li>推送是网络 IO，会<b>拉长事务持有时间</b>，一直占着数据库连接不放；
 *       群里几十个人、有人网络卡，整个会话的其他请求都跟着排队。</li>
 *   <li>推送抛异常会<b>把业务一起回滚</b>。但"没实时通知到"和"消息没存下来"
 *       严重程度完全不同，不该让前者把后者干掉。</li>
 * </ol>
 * <p>
 * 用法：把推送代码原样包进 lambda 即可，不用关心当前有没有事务。
 *
 * <pre>{@code
 * TxAfterCommit.run(() -> onlineUserManager.sendTo(memberId, push));
 * }</pre>
 */
@Slf4j
public final class TxAfterCommit {

    private TxAfterCommit() {
    }

    public static void run(Runnable action) {
        if (action == null) {
            return;
        }

        // 没有事务、或者事务同步没开（比如被非事务方法直接调用）→ 立刻执行，别把推送弄丢了
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    action.run();
                } catch (Exception e) {
                    // 业务已经提交成功了，推送失败不能反过来把请求变成 500。
                    // 代价只是"这次没实时通知到"，对方下次拉列表/进会话就能看到。
                    log.warn("事务提交后的推送失败（业务已成功，不影响数据）：{}", e.getMessage(), e);
                }
            }
        });
    }
}
