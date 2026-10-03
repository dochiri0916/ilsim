package com.example.staticrules;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class TransactionJoin {
    // EXPECT: no TX-001 (the caller already runs in a transaction and the callee joins it)
    @Transactional
    public void joined() { inner(); }

    @Transactional
    public void inner() {}

    // EXPECT: TX-001 (a non-transactional caller bypasses the proxy)
    public void bypass() { audited(); }

    @Transactional
    public void audited() {}

    // EXPECT: TX-001 (a REQUIRES_NEW callee needs the proxy even when the caller is transactional)
    @Transactional
    public void newTx() { separate(); }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void separate() {}
}
