-- Adaptador para controladoras da família Dahua/Intelbras (API CGI com autenticação Digest).
ALTER TABLE device DROP CONSTRAINT device_adapter_check;
ALTER TABLE device ADD CONSTRAINT device_adapter_check
    CHECK (adapter IN ('SIMULATOR', 'GENERIC_HTTP', 'ONVIF', 'AGENT_RELAY', 'DAHUA_CGI'));
