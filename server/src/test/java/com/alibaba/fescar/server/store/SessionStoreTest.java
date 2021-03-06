package com.alibaba.fescar.server.store;

import com.alibaba.fescar.core.model.BranchStatus;
import com.alibaba.fescar.core.model.BranchType;
import com.alibaba.fescar.core.model.GlobalStatus;
import com.alibaba.fescar.server.lock.LockManager;
import com.alibaba.fescar.server.lock.LockManagerFactory;
import com.alibaba.fescar.server.session.BranchSession;
import com.alibaba.fescar.server.session.GlobalSession;
import com.alibaba.fescar.server.session.SessionHelper;
import com.alibaba.fescar.server.session.SessionHolder;
import org.junit.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;

public class SessionStoreTest {

    public static final String RESOURCE_ID = "mysql:xxx";

    @BeforeMethod
    public void clean() throws Exception {
        File rootDataFile = new File("./" + SessionHolder.ROOT_SESSION_MANAGER_NAME);
        File rootDataFileHis = new File("./" + SessionHolder.ROOT_SESSION_MANAGER_NAME + ".1");

        if (rootDataFile.exists()) {
            rootDataFile.delete();
            System.currentTimeMillis();
        }
        if (rootDataFileHis.exists()) {
            rootDataFileHis.delete();
        }
        LockManagerFactory.get().cleanAllLocks();
    }

    @Test
    public void testRestoredFromFile() throws Exception {
        SessionHolder.init(".");
        GlobalSession globalSession = new GlobalSession("demo-app", "my_test_tx_group", "test", 6000);

        globalSession.addSessionLifecycleListener(SessionHolder.getRootSessionManager());
        globalSession.begin();

        BranchSession branchSession1 = SessionHelper.newBranchByGlobal(globalSession, BranchType.AT, RESOURCE_ID, "ta:1,2;tb:3", "xxx");
        branchSession1.lock();
        globalSession.addBranch(branchSession1);

        LockManager lockManager = LockManagerFactory.get();

        Assert.assertFalse(lockManager.isLockable(0L, RESOURCE_ID, "ta:1"));
        Assert.assertFalse(lockManager.isLockable(0L, RESOURCE_ID, "ta:2"));
        Assert.assertFalse(lockManager.isLockable(0L, RESOURCE_ID, "tb:3"));

        Assert.assertTrue(lockManager.isLockable(0L, RESOURCE_ID, "ta:4"));
        Assert.assertTrue(lockManager.isLockable(0L, RESOURCE_ID, "tb:5"));

        lockManager.cleanAllLocks();

        Assert.assertTrue(lockManager.isLockable(0L, RESOURCE_ID, "ta:1"));
        Assert.assertTrue(lockManager.isLockable(0L, RESOURCE_ID, "ta:2"));
        Assert.assertTrue(lockManager.isLockable(0L, RESOURCE_ID, "tb:3"));

        // Re-init SessionHolder: restore sessions from file
        SessionHolder.init(".");

        long tid = globalSession.getTransactionId();
        GlobalSession reloadSession = SessionHolder.findGlobalSession(tid);
        Assert.assertNotNull(reloadSession);
        Assert.assertFalse(globalSession == reloadSession);
        Assert.assertEquals(globalSession.getApplicationId(), reloadSession.getApplicationId());

        Assert.assertFalse(lockManager.isLockable(0L, RESOURCE_ID, "ta:1"));
        Assert.assertFalse(lockManager.isLockable(0L, RESOURCE_ID, "ta:2"));
        Assert.assertFalse(lockManager.isLockable(0L, RESOURCE_ID, "tb:3"));
        Assert.assertTrue(lockManager.isLockable(globalSession.getTransactionId(), RESOURCE_ID, "tb:3"));

    }

    //@Test
    public void testRestoredFromFile2() throws Exception {
        SessionHolder.init(".");
        GlobalSession globalSession = new GlobalSession("demo-app", "my_test_tx_group", "test", 6000);

        globalSession.addSessionLifecycleListener(SessionHolder.getRootSessionManager());
        globalSession.begin();

        // Re-init SessionHolder: restore sessions from file
        SessionHolder.init(".");
    }

    @Test
    public void testRestoredFromFileAsyncCommitting() throws Exception {
        SessionHolder.init(".");
        GlobalSession globalSession = new GlobalSession("demo-app", "my_test_tx_group", "test", 6000);

        globalSession.addSessionLifecycleListener(SessionHolder.getRootSessionManager());
        globalSession.begin();

        BranchSession branchSession1 = SessionHelper.newBranchByGlobal(globalSession, BranchType.AT, RESOURCE_ID, "ta:1", "xxx");
        Assert.assertTrue(branchSession1.lock());
        globalSession.addBranch(branchSession1);

        LockManager lockManager = LockManagerFactory.get();

        Assert.assertFalse(lockManager.isLockable(0L, RESOURCE_ID, "ta:1"));

        globalSession.changeStatus(GlobalStatus.AsyncCommitting);

        lockManager.cleanAllLocks();

        Assert.assertTrue(lockManager.isLockable(0L, RESOURCE_ID, "ta:1"));

        // Re-init SessionHolder: restore sessions from file
        SessionHolder.init(".");

        long tid = globalSession.getTransactionId();
        GlobalSession reloadSession = SessionHolder.findGlobalSession(tid);
        Assert.assertEquals(reloadSession.getStatus(), GlobalStatus.AsyncCommitting);

        GlobalSession sessionInAsyncCommittingQueue = SessionHolder.getAsyncCommittingSessionManager().findGlobalSession(tid);
        Assert.assertTrue(reloadSession == sessionInAsyncCommittingQueue);

        // No locking for session in AsyncCommitting status
        Assert.assertTrue(lockManager.isLockable(0L, RESOURCE_ID, "ta:1"));

    }

    @Test
    public void testRestoredFromFileCommitRetry() throws Exception {
        SessionHolder.init(".");
        GlobalSession globalSession = new GlobalSession("demo-app", "my_test_tx_group", "test", 6000);

        globalSession.addSessionLifecycleListener(SessionHolder.getRootSessionManager());
        globalSession.begin();

        BranchSession branchSession1 = SessionHelper.newBranchByGlobal(globalSession, BranchType.AT, RESOURCE_ID, "ta:1", "xxx");
        branchSession1.lock();
        globalSession.addBranch(branchSession1);

        LockManager lockManager = LockManagerFactory.get();

        Assert.assertFalse(lockManager.isLockable(0L, RESOURCE_ID, "ta:1"));

        globalSession.changeStatus(GlobalStatus.Committing);
        globalSession.changeBranchStatus(branchSession1, BranchStatus.PhaseTwo_CommitFailed_Retryable);
        globalSession.changeStatus(GlobalStatus.CommitRetrying);

        lockManager.cleanAllLocks();

        Assert.assertTrue(lockManager.isLockable(0L, RESOURCE_ID, "ta:1"));

        // Re-init SessionHolder: restore sessions from file
        SessionHolder.init(".");

        long tid = globalSession.getTransactionId();
        GlobalSession reloadSession = SessionHolder.findGlobalSession(tid);
        Assert.assertEquals(reloadSession.getStatus(), GlobalStatus.CommitRetrying);

        GlobalSession sessionInRetryCommittingQueue = SessionHolder.getRetryCommittingSessionManager().findGlobalSession(tid);
        Assert.assertTrue(reloadSession == sessionInRetryCommittingQueue);
        BranchSession reloadBranchSession = reloadSession.getBranch(branchSession1.getBranchId());
        Assert.assertEquals(reloadBranchSession.getStatus(), BranchStatus.PhaseTwo_CommitFailed_Retryable);

        // Lock is held by session in CommitRetrying status
        Assert.assertFalse(lockManager.isLockable(0L, RESOURCE_ID, "ta:1"));

    }

    @Test
    public void testRestoredFromFileRollbackRetry() throws Exception {
        SessionHolder.init(".");
        GlobalSession globalSession = new GlobalSession("demo-app", "my_test_tx_group", "test", 6000);

        globalSession.addSessionLifecycleListener(SessionHolder.getRootSessionManager());
        globalSession.begin();

        BranchSession branchSession1 = SessionHelper.newBranchByGlobal(globalSession, BranchType.AT, RESOURCE_ID, "ta:1", "xxx");
        branchSession1.lock();
        globalSession.addBranch(branchSession1);

        LockManager lockManager = LockManagerFactory.get();

        Assert.assertFalse(lockManager.isLockable(0L, RESOURCE_ID, "ta:1"));

        globalSession.changeStatus(GlobalStatus.Rollbacking);
        globalSession.changeBranchStatus(branchSession1, BranchStatus.PhaseTwo_RollbackFailed_Retryable);
        globalSession.changeStatus(GlobalStatus.RollbackRetrying);

        lockManager.cleanAllLocks();

        Assert.assertTrue(lockManager.isLockable(0L, RESOURCE_ID, "ta:1"));

        // Re-init SessionHolder: restore sessions from file
        SessionHolder.init(".");

        long tid = globalSession.getTransactionId();
        GlobalSession reloadSession = SessionHolder.findGlobalSession(tid);
        Assert.assertEquals(reloadSession.getStatus(), GlobalStatus.RollbackRetrying);

        GlobalSession sessionInRetryRollbackingQueue = SessionHolder.getRetryRollbackingSessionManager().findGlobalSession(tid);
        Assert.assertTrue(reloadSession == sessionInRetryRollbackingQueue);
        BranchSession reloadBranchSession = reloadSession.getBranch(branchSession1.getBranchId());
        Assert.assertEquals(reloadBranchSession.getStatus(), BranchStatus.PhaseTwo_RollbackFailed_Retryable);

        // Lock is held by session in RollbackRetrying status
        Assert.assertFalse(lockManager.isLockable(0L, RESOURCE_ID, "ta:1"));

    }

    @Test
    public void testRestoredFromFileRollbackFailed() throws Exception {
        SessionHolder.init(".");
        GlobalSession globalSession = new GlobalSession("demo-app", "my_test_tx_group", "test", 6000);

        globalSession.addSessionLifecycleListener(SessionHolder.getRootSessionManager());
        globalSession.begin();

        BranchSession branchSession1 = SessionHelper.newBranchByGlobal(globalSession, BranchType.AT, RESOURCE_ID, "ta:1", "xxx");
        branchSession1.lock();
        globalSession.addBranch(branchSession1);

        LockManager lockManager = LockManagerFactory.get();

        Assert.assertFalse(lockManager.isLockable(0L, RESOURCE_ID, "ta:1"));

        globalSession.changeStatus(GlobalStatus.Rollbacking);
        globalSession.changeBranchStatus(branchSession1, BranchStatus.PhaseTwo_CommitFailed_Unretryable);
        SessionHelper.endRollbackFailed(globalSession);

        // Lock is released.
        Assert.assertTrue(lockManager.isLockable(0L, RESOURCE_ID, "ta:1"));

        lockManager.cleanAllLocks();

        Assert.assertTrue(lockManager.isLockable(0L, RESOURCE_ID, "ta:1"));

        // Re-init SessionHolder: restore sessions from file
        SessionHolder.init(".");

        long tid = globalSession.getTransactionId();
        GlobalSession reloadSession = SessionHolder.findGlobalSession(tid);
        Assert.assertNull(reloadSession);

    }
}
