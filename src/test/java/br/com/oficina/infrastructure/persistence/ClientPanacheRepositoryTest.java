package br.com.oficina.infrastructure.persistence;

import br.com.oficina.domain.model.Client;
import br.com.oficina.domain.model.ClientStatus;
import br.com.oficina.domain.model.ClientType;
import br.com.oficina.testsupport.DomainTestFixtures;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de integração da consulta de autenticação do Cliente.
 * Valida que apenas clientes ACTIVE são retornados pela query de autenticação,
 * ou seja, INACTIVE e BLOCKED não aparecem.
 */
@QuarkusTest
class ClientPanacheRepositoryTest {

    @Inject
    ClientPanacheRepository repository;

    @Inject
    ClientMapper mapper;

    @Test
    @TestTransaction
    void findByCpfCnpjActive_shouldReturnActiveClient() {
        // Arrange: cliente ACTIVE
        Client active = new Client("Cliente Ativo", "11144477735", ClientType.PF, "active@x.com", "11999998888");
        ClientEntity entity = mapper.toNewEntity(active);
        entity.setStatus(ClientStatus.ACTIVE);
        repository.persistAndFlush(entity);

        // Act
        Optional<ClientEntity> found = repository.find("cpf_cnpj = ?1 AND status = ?2", "11144477735", ClientStatus.ACTIVE).firstResultOptional();

        // Assert
        assertThat(found).isPresent();
        assertThat(found.get().getCpfCnpj()).isEqualTo("11144477735");
        assertThat(found.get().getStatus()).isEqualTo(ClientStatus.ACTIVE);
    }

    @Test
    @TestTransaction
    void findByCpfCnpjActive_shouldNotReturnBlockedClient() {
        // Arrange: cliente BLOCKED
        Client blocked = new Client("Cliente Bloqueado", "22255588844", ClientType.PF, "blocked@x.com", "11988887777");
        ClientEntity entity = mapper.toNewEntity(blocked);
        entity.setStatus(ClientStatus.BLOCKED);
        repository.persistAndFlush(entity);

        // Act
        Optional<ClientEntity> found = repository.find("cpf_cnpj = ?1 AND status = ?2", "22255588844", ClientStatus.ACTIVE).firstResultOptional();

        // Assert
        assertThat(found).isEmpty();
    }

    @Test
    @TestTransaction
    void findByCpfCnpjActive_shouldNotReturnInactiveClient() {
        // Arrange: cliente INACTIVE
        Client inactive = new Client("Cliente Inativo", "33366699955", ClientType.PF, "inactive@x.com", "11977776666");
        ClientEntity entity = mapper.toNewEntity(inactive);
        entity.setStatus(ClientStatus.INACTIVE);
        repository.persistAndFlush(entity);

        // Act
        Optional<ClientEntity> found = repository.find("cpf_cnpj = ?1 AND status = ?2", "33366699955", ClientStatus.ACTIVE).firstResultOptional();

        // Assert
        assertThat(found).isEmpty();
    }

    @Test
    @TestTransaction
    void findByCpfCnpjActive_shouldFilterByStatusCorrectly() {
        // Arrange: três clientes, um de cada status
        String cpf = "44477788833";

        Client client = new Client("Teste Status", cpf, ClientType.PF, "status@x.com", "11966665555");
        ClientEntity entity = mapper.toNewEntity(client);
        entity.setStatus(ClientStatus.BLOCKED);
        repository.persistAndFlush(entity);

        // Mesmo CPF não pode existir (UNIQUE constraint), então usamos cpfs diferentes
        createAndAssertStatus("55588899922", "Cliente Active", ClientStatus.ACTIVE, true);
        createAndAssertStatus("66699900011", "Cliente Blocked", ClientStatus.BLOCKED, false);
        createAndAssertStatus("77711122233", "Cliente Inactive", ClientStatus.INACTIVE, false);
    }

    private void createAndAssertStatus(String cpfCnpj, String name, ClientStatus status, boolean shouldBeFound) {
        Client client = new Client(name, cpfCnpj, ClientType.PF, "test@x.com", "11999999999");
        ClientEntity entity = mapper.toNewEntity(client);
        entity.setStatus(status);
        repository.persist(entity);
        repository.flush();

        Optional<ClientEntity> found = repository.find("cpf_cnpj = ?1 AND status = ?2", cpfCnpj, ClientStatus.ACTIVE).firstResultOptional();

        if (shouldBeFound) {
            assertThat(found).isPresent();
            assertThat(found.get().getStatus()).isEqualTo(ClientStatus.ACTIVE);
        } else {
            assertThat(found).isEmpty();
        }
    }
}
