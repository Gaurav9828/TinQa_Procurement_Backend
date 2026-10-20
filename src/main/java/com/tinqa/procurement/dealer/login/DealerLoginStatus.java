package com.tinqa.procurement.dealer.login;

/**
 * Where a dealer's login stands. Only ACTIVE signs in, and only while the dealer itself is active.
 */
public enum DealerLoginStatus {
    /** Created by a Dealer Manager, waiting for someone else to approve it */
    APPROVAL_PENDING,
    ACTIVE,
    /** Not approved; a Dealer Manager may submit it again */
    REJECTED,
    /** Switched off by a Dealer Manager; can be switched on again without a new approval */
    DISABLED
}
