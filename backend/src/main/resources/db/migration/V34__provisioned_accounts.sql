-- Contas que a própria API criou no provedor de identidade (criação de conta da equipe pela tela). Só elas podem ter a senha
-- reposta pela tela e são apagadas do provedor quando a última associação é revogada; contas criadas à mão no Keycloak não.
CREATE TABLE provisioned_account (
    subject    VARCHAR(255) PRIMARY KEY,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
