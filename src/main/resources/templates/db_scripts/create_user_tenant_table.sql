CREATE TABLE IF NOT EXISTS user_tenant (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    username text NOT NULL,
    tenant_id text NOT NULL,
    creation_date timestamp without time zone
);

CREATE INDEX IF NOT EXISTS username_index ON user_tenant USING BTREE (username);
