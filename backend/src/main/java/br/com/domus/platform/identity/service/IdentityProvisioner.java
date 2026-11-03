package br.com.domus.platform.identity.service;

/**
 * Contrato para criar contas no provedor de identidade (Keycloak). Só o provedor guarda senha e MFA; este contrato
 * existe para a gestão não precisar copiar o {@code sub} à mão.
 */
public interface IdentityProvisioner {

    /**
     * @param subject          identificador (claim {@code sub}) da conta criada
     * @param temporaryPassword senha provisória, mostrada uma única vez; a pessoa é obrigada a trocá-la no primeiro acesso
     */
    record Created(String subject, String temporaryPassword) {
    }

    /**
     * Cria a conta já com o papel de equipe (que no provedor força o segundo fator).
     *
     * @param email        e-mail, também usado como nome de usuário
     * @param displayName  nome de exibição
     * @param providerRole papel de realm no provedor (ex.: {@code operator})
     * @throws br.com.domus.platform.shared.error.ConflictException se já existe conta com esse e-mail
     * @throws br.com.domus.platform.shared.error.ServiceUnavailableException se o provedor não respondeu
     */
    Created create(String email, String displayName, String providerRole);

    /** Remove uma conta criada por {@link #create}; usado para desfazer quando o vínculo local falha. */
    void delete(String subject);
}
