-- High priority: used in reservation_view JOIN and frequent queries
CREATE INDEX IF NOT EXISTS idx_reservation_flow_created_by ON reservation_flow(created_by);
CREATE INDEX IF NOT EXISTS idx_reservation_location_from ON reservation(location_from);
CREATE INDEX IF NOT EXISTS idx_reservation_location_to ON reservation(location_to);
CREATE INDEX IF NOT EXISTS idx_custom_offer_user_id ON custom_offer(user_id);
CREATE INDEX IF NOT EXISTS idx_custom_offer_inquiry_id ON custom_offer(inquiry_id);

-- Medium priority: FK columns used in joins
CREATE INDEX IF NOT EXISTS idx_yacht_extras_extras_id ON yacht_extras(extras_id);
CREATE INDEX IF NOT EXISTS idx_yacht_equipment_equipment_id ON yacht_equipment(equipment_id);
CREATE INDEX IF NOT EXISTS idx_external_bases_agency_id ON external_bases(agency_id);
CREATE INDEX IF NOT EXISTS idx_external_bases_location_id ON external_bases(location_id);
CREATE INDEX IF NOT EXISTS idx_yacht_translations_language_id ON yacht_translations(language_id);