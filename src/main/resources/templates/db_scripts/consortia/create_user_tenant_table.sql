CREATE TABLE IF NOT EXISTS user_tenant (
    id uuid PRIMARY KEY,
    tenant_id text NOT NULL,
    user_id uuid NOT NULL,
    username text NOT NULL,
    creation_date timestamp without time zone
);

CREATE INDEX IF NOT EXISTS tenant_id_index ON user_tenant USING BTREE (tenant_id);
