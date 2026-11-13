ALTER TABLE collector_delivery DROP CONSTRAINT collector_delivery_state_check;
ALTER TABLE collector_delivery ADD CONSTRAINT collector_delivery_state_check CHECK(state IN ('READY','RETRY','SENDING','DELIVERED','DEAD'));
ALTER TABLE collector_delivery ADD COLUMN dead_letter_at TIMESTAMPTZ;
ALTER TABLE collector_delivery ADD COLUMN error_code VARCHAR(64);
