-- ===================================================
-- V6: Papel de leitura para Lambda de autenticação.
--
-- O Lambda autenticador consulta clientes diretamente no banco.
-- Usar o usuário mestre daria à função exposta na internet poder de DROP TABLE.
-- Este papel é dedicado, com SELECT restrito a colunas necessárias.
--
-- A senha entra por placeholder do Flyway (lambda_ro_password), alimentado
-- pelo Secret do Kubernetes em produção — nunca literal em arquivo.
-- ===================================================

DO $$
BEGIN
    -- Criar o papel apenas se não existir (idempotência).
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'oficina_auth_ro') THEN
        CREATE ROLE oficina_auth_ro LOGIN PASSWORD '${lambda_ro_password}';
    END IF;
END
$$;

-- Permissões de banco e schema
GRANT CONNECT ON DATABASE oficina_db TO oficina_auth_ro;
GRANT USAGE ON SCHEMA public TO oficina_auth_ro;

-- SELECT apenas nas colunas necessárias para autenticação (R3)
GRANT SELECT (id, name, cpf_cnpj, client_type, status) ON clients TO oficina_auth_ro;

-- O Lambda não precisa de email nem phone e não deve poder lê-los.
