CREATE TABLE products (
    id UUID PRIMARY KEY,
    sku VARCHAR(64) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    price NUMERIC(12, 2) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_products_sku UNIQUE (sku),
    CONSTRAINT ck_products_sku_not_blank CHECK (btrim(sku) <> ''),
    CONSTRAINT ck_products_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_products_price CHECK (price >= 0 AND price <> 'NaN'::numeric)
);

CREATE TABLE inventory (
    product_id UUID PRIMARY KEY REFERENCES products(id),
    on_hand_quantity INTEGER NOT NULL DEFAULT 0,
    reserved_quantity INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_inventory_on_hand_nonnegative CHECK (on_hand_quantity >= 0),
    CONSTRAINT ck_inventory_reserved_nonnegative CHECK (reserved_quantity >= 0),
    CONSTRAINT ck_inventory_reserved_within_on_hand CHECK (reserved_quantity <= on_hand_quantity)
);

-- Available quantity is derived as on_hand_quantity - reserved_quantity.
-- Application writes must maintain updated_at and create inventory with each product.
-- Prices use one application currency (INR); multi-currency support is out of scope.
