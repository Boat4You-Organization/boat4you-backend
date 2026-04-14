ALTER TABLE public.invoice
    RENAME COLUMN vat_amount to vat_percentage;
ALTER TABLE public.invoice
    ADD COLUMN recipient_vat_code VARCHAR(255),
    ADD COLUMN invoice_item       VARCHAR(1023),
    ADD COLUMN price_without_vat  DECIMAL,
    ADD COLUMN vat_amount         DECIMAL,
    ADD COLUMN total_price        DECIMAL;

UPDATE public.invoice
SET vat_percentage = 25.0
WHERE vat_percentage IS NULL;

UPDATE public.invoice
SET recipient_vat_code = ''
WHERE recipient_vat_code IS NULL;

UPDATE public.invoice
SET invoice_item = ''
WHERE invoice_item IS NULL;

UPDATE public.invoice
SET price_without_vat = 0
WHERE price_without_vat IS NULL;

UPDATE public.invoice
SET vat_amount = 25
WHERE vat_amount IS NULL;

UPDATE public.invoice
SET total_price = 0
WHERE total_price IS NULL;

ALTER TABLE public.invoice
    ALTER COLUMN vat_percentage SET NOT NULL,
    ALTER COLUMN recipient_vat_code SET NOT NULL,
    ALTER COLUMN invoice_item SET NOT NULL,
    ALTER COLUMN price_without_vat SET NOT NULL,
    ALTER COLUMN vat_amount SET NOT NULL,
    ALTER COLUMN total_price SET NOT NULL;

-- Keep price_override_amount column for now
