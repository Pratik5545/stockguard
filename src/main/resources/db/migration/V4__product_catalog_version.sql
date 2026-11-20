ALTER TABLE products ADD COLUMN catalog_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE products ADD CONSTRAINT ck_products_catalog_version CHECK (catalog_version >= 0);
