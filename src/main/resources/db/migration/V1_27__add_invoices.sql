CREATE TABLE IF NOT EXISTS invoice
(
    id BIGSERIAL NOT NULL
    CONSTRAINT pk_invoice_id PRIMARY KEY,

    reservation_flow_id BIGINT NOT NULL,
    CONSTRAINT fk_invoice_reservation_flow FOREIGN KEY(reservation_flow_id)
    REFERENCES reservation_flow(id),

    recipient_type VARCHAR(63) NOT NULL,
    recipient_name VARCHAR(255) NOT NULL,
    recipient_city VARCHAR(255) NOT NULL,
    recipient_street VARCHAR(255) NOT NULL,
    recipient_zip_code VARCHAR(63) NOT NULL,
    recipient_country VARCHAR(3) NOT NULL,
    invoice_number VARCHAR(255) NOT NULL,
    invoice_date VARCHAR(255) NOT NULL,
    invoice_language VARCHAR(3) NOT NULL,
    include_vat BOOLEAN NOT NULL,
    vat_amount REAL,
    price_override_amount DECIMAL,

    creator_id BIGINT,
    modifier_id BIGINT,
    created TIMESTAMP NOT NULL,
    modified TIMESTAMP,
    entity_status VARCHAR(31) NOT NULL
    );

CREATE INDEX IF NOT EXISTS idx_invoice_reservation_flow ON invoice(reservation_flow_id);

-- TODO FIX PERMISSIONS

-- Grant permissions on all existing tables in the schema
GRANT SELECT, UPDATE, INSERT, DELETE ON public.invoice TO boat4you_app;

-- Optional: Grant permissions on sequences in the schema
GRANT USAGE, SELECT ON public.invoice_id_seq TO boat4you_app;
