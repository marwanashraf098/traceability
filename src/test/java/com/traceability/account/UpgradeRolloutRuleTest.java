package com.traceability.account;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Build C — who is offered the official-app upgrade banner (ConnectionsController.upgradeOffered). */
class UpgradeRolloutRuleTest {

    @Test
    void flagOff_nobody() {
        assertThat(ConnectionsController.upgradeOffered(false, "", "a.myshopify.com")).isFalse();
        assertThat(ConnectionsController.upgradeOffered(false, "a.myshopify.com", "a.myshopify.com")).isFalse();
    }

    @Test
    void emptyList_everyone() {
        assertThat(ConnectionsController.upgradeOffered(true, "", "a.myshopify.com")).isTrue();
        assertThat(ConnectionsController.upgradeOffered(true, null, "b.myshopify.com")).isTrue();
        assertThat(ConnectionsController.upgradeOffered(true, " , ", "b.myshopify.com")).as("blank entries = empty").isTrue();
    }

    @Test
    void listed_seesIt_unlisted_doesNot() {
        String list = " A.myshopify.com , c.myshopify.com";
        assertThat(ConnectionsController.upgradeOffered(true, list, "a.myshopify.com")).as("case / spaces ignored").isTrue();
        assertThat(ConnectionsController.upgradeOffered(true, list, "c.myshopify.com")).isTrue();
        assertThat(ConnectionsController.upgradeOffered(true, list, "b.myshopify.com")).isFalse();
        assertThat(ConnectionsController.upgradeOffered(true, list, null)).as("no store").isFalse();
    }
}
