-- Weekly offers are STANDARD regardless of check-in weekday (Mario 12.7.2026,
-- EBcharter MARILA case). The old OfferType.getFromDates required Saturday on
-- both ends, so every Sunday-to-Sunday (etc.) fleet's 7-day weeks were stored
-- as OTHER — and the detail page's standard-offers endpoint (the only OfferType
-- consumer) renders STANDARD only, so those boats looked permanently sold out.
-- ~49k rows at authoring time. 7 days apart implies same weekday on both ends,
-- so no extra weekday predicate is needed.
UPDATE offer
SET    type = 'STANDARD'
WHERE  type = 'OTHER'
  AND  (date_to - date_from) = 7;
