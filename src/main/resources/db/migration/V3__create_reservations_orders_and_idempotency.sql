CREATE TABLE reservations (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','CONFIRMED','CANCELLED','EXPIRED')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (expires_at > created_at)
);
CREATE INDEX ix_reservations_owner ON reservations(user_id, created_at DESC, id);
CREATE INDEX ix_reservations_due ON reservations(expires_at, id) WHERE status = 'PENDING';
CREATE INDEX ix_reservations_pending_owner ON reservations(user_id) WHERE status = 'PENDING';

-- One item per reservation in this release. Item snapshots remain immutable.
CREATE TABLE reservation_items (
    reservation_id UUID PRIMARY KEY REFERENCES reservations(id),
    product_id UUID NOT NULL REFERENCES products(id),
    sku VARCHAR(64) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(12,2) NOT NULL CHECK (unit_price >= 0 AND unit_price <> 'NaN'::numeric)
);

CREATE TABLE orders (
    id UUID PRIMARY KEY,
    reservation_id UUID NOT NULL UNIQUE REFERENCES reservations(id),
    user_id UUID NOT NULL REFERENCES users(id),
    status VARCHAR(16) NOT NULL CHECK (status = 'PLACED'),
    total_amount NUMERIC(22,2) NOT NULL CHECK (total_amount >= 0 AND total_amount <> 'NaN'::numeric),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_orders_owner ON orders(user_id, created_at DESC, id);

CREATE TABLE order_items (
    order_id UUID PRIMARY KEY REFERENCES orders(id),
    product_id UUID NOT NULL REFERENCES products(id),
    sku VARCHAR(64) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(12,2) NOT NULL CHECK (unit_price >= 0 AND unit_price <> 'NaN'::numeric)
);

-- Successful results are retained indefinitely until a retention policy is introduced.
CREATE TABLE idempotency_records (
    user_id UUID NOT NULL REFERENCES users(id),
    operation_scope VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_status INTEGER NOT NULL CHECK (response_status BETWEEN 200 AND 299),
    response_body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, operation_scope, idempotency_key)
);
