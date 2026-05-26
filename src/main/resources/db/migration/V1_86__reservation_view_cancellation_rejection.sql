-- Surface the V1_85 `cancelation_rejected_at` + `cancelation_rejected_reason`
-- columns on `reservation_flow` through `reservation_view`. The view feeds
-- both customer (/my-bookings) and admin (/admin/reservations) read paths;
-- without surfacing these columns the admin UI has no way to learn that
-- a customer's cancellation request was rejected.
--
-- Postgres `CREATE OR REPLACE VIEW` only allows appending columns at the
-- end with matching types — see V1_63 for the same DROP+CREATE pattern.
-- No views depend on `reservation_view`, so a single DROP is safe.
DROP VIEW IF EXISTS reservation_view;
CREATE VIEW reservation_view AS
SELECT r.id                        AS reservation_id,
       r.reservation_flow_id       as reservation_flow_id,
       r.status                    AS reservation_status,
       r.sys_status                as reservation_sys_status,
       r.created_at                AS reservation_created_at,
       r.option_expires_at         as reservation_option_expires_at,
       r.total_price               as reservation_total_price,
       r.discount                  as reservation_discount,
       r.client_price              as reservation_client_price,
       r.external_id               as reservation_external_id,
       r.external_reservation_code as reservation_external_reservation_code,
       r.reservation_number        as reservation_number,
       r.note                      as reservation_note,
       r.payment_note              as reservation_payment_note,
       r.crew_list_url             as reservation_crew_list_url,
       rf.user_id                  AS reservation_user_id,
       rf.name                     AS reservation_flow_name,
       rf.surname                  AS reservation_flow_surname,
       rf.email                    AS reservation_flow_email,
       rf.phone                    AS reservation_flow_phone,
       rf.request                  AS reservation_flow_request,
       rf.offer_id                 AS offer_id,
       rf.status                   AS reservation_flow_status,
       o.date_from                 AS offer_date_from,
       o.date_to                   AS offer_date_to,
       o.checkin                   AS offer_checkin,
       o.checkout                  AS offer_checkout,
       ags.external_system_id      AS agency_source_external_system_id,
       y.id                        AS yacht_id,
       y.name                      AS yacht_name,
       m.name                      AS model_name,
       y.main_image_id             AS yacht_main_image,
       mf.name                     AS manufacturer_name,
       lfrom.name                  AS location_from_name,
       lfrom.country_code          AS location_from_country,
       lto.name                    AS location_to_name,
       lto.country_code            AS location_to_country,
       r.date_from                 AS reservation_date_from,
       r.date_to                   AS reservation_date_to,
       cu.id                       AS created_by_id,
       cu.name                     AS created_by_name,
       cu.surname                  AS created_by_surname,
       cf.id                       AS created_for_id,
       cf.name                     AS created_for_name,
       cf.surname                  AS created_for_surname,
       r.external_status           AS reservation_external_status,
       cu.email                    AS created_by_email,
       a.id                        AS agency_id,
       a.name                      AS agency_name,
       a.email                     AS agency_email,
       a.phone                     AS agency_phone,
       a.city                      AS agency_city,
       a.address                   AS agency_address,
       a.zip                       AS agency_zip,
       a.country                   AS agency_country,
       rf.cancelation_request_at   AS reservation_cancelation_request_at,
       rf.cancelation_request      AS reservation_cancelation_request,
       rf.cancelation_rejected_at  AS reservation_cancelation_rejected_at,
       rf.cancelation_rejected_reason AS reservation_cancelation_rejected_reason,
       rf.calculated_total_price   AS calculated_total_price,
       o.client_price              AS offer_client_price,
       a.vat_code                  AS agency_vat_code,
       o.product                   AS charter_type,
       o.ext_base_price            AS offer_list_price,
       r.agency_price              AS reservation_agency_price,
       r.commission                AS reservation_commission,
       r.admin_notes               AS reservation_admin_notes
FROM reservation_flow rf
         JOIN users cu ON rf.created_by = cu.id
         JOIN users cf ON rf.user_id = cf.id
         JOIN reservation r ON r.reservation_flow_id = rf.id
         JOIN yacht y ON y.id = rf.yacht_id
         LEFT JOIN offer o ON o.id = rf.offer_id
         LEFT JOIN model m ON y.model_id = m.id
         LEFT JOIN manufacturer mf ON m.manufacturer_id = mf.id
         LEFT JOIN location lfrom ON lfrom.id = COALESCE(o.location_from, r.location_from)
         LEFT JOIN location lto ON lto.id = COALESCE(o.location_to, r.location_to)
         JOIN agency a ON y.agency_id = a.id
         JOIN agency_source ags ON ags.agency_id = a.id AND ags.primary = true
;
GRANT SELECT ON reservation_view TO boat4you_app;
