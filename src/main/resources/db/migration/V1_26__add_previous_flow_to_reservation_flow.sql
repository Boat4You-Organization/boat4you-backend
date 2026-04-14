ALTER TABLE reservation_flow ADD COLUMN previous_flow_id BIGINT;

ALTER TABLE reservation_flow ADD CONSTRAINT reservation_flow_fk FOREIGN KEY (previous_flow_id)
    REFERENCES public.reservation_flow (id) MATCH FULL
    ON DELETE RESTRICT ON UPDATE CASCADE;

CREATE INDEX idx_previous_flow ON public.reservation_flow
    USING btree
    (
     previous_flow_id
        );