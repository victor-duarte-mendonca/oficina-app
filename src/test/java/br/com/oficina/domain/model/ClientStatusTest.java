package br.com.oficina.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ClientStatusTest {

    @Test
    void canAuthenticate_shouldReturnTrue_forActiveStatus() {
        assertThat(ClientStatus.ACTIVE.canAuthenticate()).isTrue();
    }

    @Test
    void canAuthenticate_shouldReturnFalse_forInactiveStatus() {
        assertThat(ClientStatus.INACTIVE.canAuthenticate()).isFalse();
    }

    @Test
    void canAuthenticate_shouldReturnFalse_forBlockedStatus() {
        assertThat(ClientStatus.BLOCKED.canAuthenticate()).isFalse();
    }

    @Test
    void canAuthenticate_onlyActiveCanAuthenticate() {
        for (ClientStatus status : ClientStatus.values()) {
            if (status == ClientStatus.ACTIVE) {
                assertThat(status.canAuthenticate())
                    .as("%s should allow authentication", status)
                    .isTrue();
            } else {
                assertThat(status.canAuthenticate())
                    .as("%s should deny authentication", status)
                    .isFalse();
            }
        }
    }
}
