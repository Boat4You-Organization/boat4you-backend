CREATE TABLE IF NOT EXISTS reservation_payment_phase
(
    id BIGSERIAL NOT NULL
    CONSTRAINT pk_reservation_payment_phase_id PRIMARY KEY,
    deadline DATE NOT NULL,
    amount DECIMAL NOT NULL,
    paid_on TIMESTAMP,
    stripe_session_id VARCHAR(511),
    reservation_flow_id bigint not null,
    constraint fk_reservation_payment_phase_reservation_flow foreign key(reservation_flow_id)
    references reservation_flow(id),

    creator_id BIGINT,
    modifier_id BIGINT,
    created TIMESTAMP NOT NULL,
    modified TIMESTAMP,
    entity_status VARCHAR(31) NOT NULL
);

CREATE INDEX IF NOT EXISTS uidx_reservation_payment_phase_stripe_session_id ON reservation_payment_phase(stripe_session_id);

-- TODO FIX PERMISSIONS

-- Grant permissions on all existing tables in the schema
GRANT SELECT, UPDATE, INSERT, DELETE ON public.reservation_payment_phase TO boat4you_app;

-- Optional: Grant permissions on sequences in the schema
GRANT USAGE, SELECT ON public.reservation_payment_phase_id_seq TO boat4you_app;
