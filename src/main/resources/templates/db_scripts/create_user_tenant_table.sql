CREATE TABLE IF NOT EXISTS user_tenant (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    username text NOT NULL,
    tenant_id text NOT NULL,
    creation_date timestamp without time zone
);

CREATE UNIQUE INDEX IF NOT EXISTS username_tenant_id_index ON user_tenant USING BTREE (username, tenant_id);
