-- Database generated with pgModeler (PostgreSQL Database Modeler).
-- pgModeler version: 1.1.6
-- PostgreSQL version: 17.0
-- Project Site: pgmodeler.io
-- Model Author: ---
-- object: boat4you_owner | type: ROLE --
-- DROP ROLE IF EXISTS boat4you_owner;
CREATE ROLE boat4you_owner WITH 
	SUPERUSER
	LOGIN
	 PASSWORD 'boat4you_owner';
-- ddl-end --

-- object: boat4you_app | type: ROLE --
-- DROP ROLE IF EXISTS boat4you_app;
CREATE ROLE boat4you_app WITH 
	LOGIN
	 PASSWORD 'boat4you_app';
-- ddl-end --


-- Database creation must be performed outside a multi lined SQL file. 
-- These commands were put in this file only as a convenience.
-- 
-- object: boat4you_db | type: DATABASE --
-- DROP DATABASE IF EXISTS boat4you_db;
CREATE DATABASE boat4you_db;
-- ddl-end --


-- object: public.agency | type: TABLE --
-- DROP TABLE IF EXISTS public.agency CASCADE;
CREATE TABLE public.agency (
	id bigserial NOT NULL,
	name varchar(255) NOT NULL,
	address varchar(255),
	city varchar(150),
	country varchar(100),
	zip varchar(30),
	vat_code varchar(100),
	web varchar(255),
	email varchar(150),
	phone varchar(200),
	mobile varchar(200),
	iban varchar(34),
	bank_accounts varchar(255),
	active boolean NOT NULL DEFAULT true,
	discount decimal,
	director varchar(100),
	skip_external_system boolean NOT NULL DEFAULT false,
	CONSTRAINT agency_pk PRIMARY KEY (id)
);
-- ddl-end --
COMMENT ON COLUMN public.agency.iban IS E'Maybe we should remove this.';
-- ddl-end --
COMMENT ON COLUMN public.agency.discount IS E'Boat4you discount for given agency';
-- ddl-end --
ALTER TABLE public.agency OWNER TO boat4you_owner;
-- ddl-end --

-- object: public.yacht | type: TABLE --
-- DROP TABLE IF EXISTS public.yacht CASCADE;
CREATE TABLE public.yacht (
	id bigserial NOT NULL,
	name varchar(255) NOT NULL,
	agency_id bigint,
	location_id bigint,
	model_id bigint,
	deposit decimal,
	insured_deposit decimal,
	build_year smallint,
	launch_year smallint,
	engine_power smallint,
	length decimal,
	draught decimal,
	beam decimal,
	water_tank integer,
	fuel_tank integer,
	cabins smallint,
	crew_cabins smallint,
	wc smallint,
	crew_wc smallint,
	berths smallint,
	crew_berths smallint,
	max_persons smallint,
	default_checkin varchar(10),
	default_checkout varchar(10),
	mainsail_type smallint,
	mainsail_area decimal,
	genoa_type smallint,
	genoa_area decimal,
	registration_number varchar(50),
	option_approval boolean,
	option_to_reservation boolean,
	commision decimal,
	commision_perc decimal,
	exclude_discount boolean,
	max_discount decimal,
	agency_discount_type varchar(50),
	max_discount_from_commision decimal,
	charter_type varchar(250),
	crewed_type varchar(20),
	vessel_type smallint NOT NULL,
	entry_type smallint NOT NULL,
	sys_active boolean NOT NULL DEFAULT true,
	main_image_id bigint,
	deposit_currency varchar(20),
	crew_number smallint,
	CONSTRAINT yacht_pk PRIMARY KEY (id)
);
-- ddl-end --
COMMENT ON COLUMN public.yacht.insured_deposit IS E'Deposit when insurance is applied';
-- ddl-end --
COMMENT ON COLUMN public.yacht.max_persons IS E'Max number of people on board';
-- ddl-end --
COMMENT ON COLUMN public.yacht.default_checkin IS E'Time of detault check-in';
-- ddl-end --
COMMENT ON COLUMN public.yacht.default_checkout IS E'Time of default check-out';
-- ddl-end --
COMMENT ON COLUMN public.yacht.mainsail_type IS E'Sail type';
-- ddl-end --
COMMENT ON COLUMN public.yacht.genoa_type IS E'Sail type';
-- ddl-end --
COMMENT ON COLUMN public.yacht.registration_number IS E'MMK certificate, Nausys registrationNumber';
-- ddl-end --
COMMENT ON COLUMN public.yacht.option_approval IS E'if created option needs to be approved by charter company';
-- ddl-end --
COMMENT ON COLUMN public.yacht.option_to_reservation IS E'if you can create fix booking by yourself\n(convert option to reservation) through\nAPI or Agency portal, or you charter\ncompany needs to do it for you';
-- ddl-end --
COMMENT ON COLUMN public.yacht.commision IS E'Commision in total';
-- ddl-end --
COMMENT ON COLUMN public.yacht.commision_perc IS E'MMK has only commision perc. We need to calc commision';
-- ddl-end --
COMMENT ON COLUMN public.yacht.exclude_discount IS E'Exclude agency discount for this ship';
-- ddl-end --
COMMENT ON COLUMN public.yacht.max_discount IS E'From Nausys. Not sure if needed';
-- ddl-end --
COMMENT ON COLUMN public.yacht.agency_discount_type IS E'From Nausys. Not sure if needed';
-- ddl-end --
COMMENT ON COLUMN public.yacht.max_discount_from_commision IS E'From Nausys. Not sure if needed';
-- ddl-end --
COMMENT ON COLUMN public.yacht.charter_type IS E'Is bareboat or crewed - Nausys - charterType, MMK - products';
-- ddl-end --
COMMENT ON COLUMN public.yacht.crewed_type IS E'Nausys - Indicates whether crewed charter type SKIPPER, SKIPPER_HOSTESS or\nALL_INCLUSIVE';
-- ddl-end --
COMMENT ON COLUMN public.yacht.vessel_type IS E'Yacht type. MMK - Kind can be - Sail boat , Motor boat, Catamaran, Power Catamaran, Gulet, Motorsailer, Motoryacht, Trimaran, Other. Nausys - ???';
-- ddl-end --
COMMENT ON COLUMN public.yacht.entry_type IS E'custom or external';
-- ddl-end --
COMMENT ON COLUMN public.yacht.sys_active IS E'If yacht is deactivated (deleted) by external system.';
-- ddl-end --
ALTER TABLE public.yacht OWNER TO boat4you_owner;
-- ddl-end --

-- object: public.extras | type: TABLE --
-- DROP TABLE IF EXISTS public.extras CASCADE;
CREATE TABLE public.extras (
	id bigserial NOT NULL,
	label_code varchar(100) NOT NULL,
	name varchar(100) NOT NULL,
	match_keys varchar NOT NULL,
	filter_order smallint,
	CONSTRAINT extras_pk PRIMARY KEY (id)
);
-- ddl-end --
COMMENT ON COLUMN public.extras.match_keys IS E'List of keys to match mmk and nausys extras';
-- ddl-end --
COMMENT ON COLUMN public.extras.filter_order IS E'If its returned for UI filters and position in order.';
-- ddl-end --
ALTER TABLE public.extras OWNER TO boat4you_owner;
-- ddl-end --

-- object: public.yacht_extras | type: TABLE --
-- DROP TABLE IF EXISTS public.yacht_extras CASCADE;
CREATE TABLE public.yacht_extras (
	id bigserial NOT NULL,
	yacht_id bigint NOT NULL,
	extras_id bigint,
	name varchar,
	price decimal NOT NULL,
	payable_in_base boolean NOT NULL,
	unit smallint NOT NULL,
	obligatory boolean NOT NULL,
	external_unit varchar,
	external_id bigint,
	valid_from date,
	valid_to date,
	type smallint NOT NULL,
	CONSTRAINT yacht_extras_pk PRIMARY KEY (id)
);
-- ddl-end --
COMMENT ON COLUMN public.yacht_extras.payable_in_base IS E'Can be oweriden by atribute from extras table';
-- ddl-end --
COMMENT ON COLUMN public.yacht_extras.external_id IS E'might be duplicate';
-- ddl-end --
ALTER TABLE public.yacht_extras OWNER TO boat4you_owner;
-- ddl-end --

-- object: agency_fk | type: CONSTRAINT --
-- ALTER TABLE public.yacht DROP CONSTRAINT IF EXISTS agency_fk CASCADE;
ALTER TABLE public.yacht ADD CONSTRAINT agency_fk FOREIGN KEY (agency_id)
REFERENCES public.agency (id) MATCH FULL
ON DELETE SET NULL ON UPDATE CASCADE;
-- ddl-end --

-- object: public.external_reservations | type: TABLE --
-- DROP TABLE IF EXISTS public.external_reservations CASCADE;
CREATE TABLE public.external_reservations (
	id bigserial NOT NULL,
	date_from date NOT NULL,
	date_to date NOT NULL,
	status smallint NOT NULL,
	option_expiration timestamp,
	external_id smallint,
	yacht_id bigint,
	CONSTRAINT reservations_pk PRIMARY KEY (id)
);
-- ddl-end --
COMMENT ON TABLE public.external_reservations IS E'Currently only for nausys reservations';
-- ddl-end --
ALTER TABLE public.external_reservations OWNER TO boat4you_owner;
-- ddl-end --

-- object: yacht_fk | type: CONSTRAINT --
-- ALTER TABLE public.external_reservations DROP CONSTRAINT IF EXISTS yacht_fk CASCADE;
ALTER TABLE public.external_reservations ADD CONSTRAINT yacht_fk FOREIGN KEY (yacht_id)
REFERENCES public.yacht (id) MATCH FULL
ON DELETE SET NULL ON UPDATE CASCADE;
-- ddl-end --

-- object: public.external_system | type: TABLE --
-- DROP TABLE IF EXISTS public.external_system CASCADE;
CREATE TABLE public.external_system (
	id serial NOT NULL,
	name varchar(50) NOT NULL,
	CONSTRAINT external_system_pk PRIMARY KEY (id)
);
-- ddl-end --
ALTER TABLE public.external_system OWNER TO boat4you_owner;
-- ddl-end --

-- object: public.agency_source | type: TABLE --
-- DROP TABLE IF EXISTS public.agency_source CASCADE;
CREATE TABLE public.agency_source (
	agency_id bigint NOT NULL,
	external_system_id integer NOT NULL,
	"primary" boolean NOT NULL,
	external_id bigint NOT NULL,
	CONSTRAINT agency_source_pk PRIMARY KEY (agency_id,external_system_id)
);
-- ddl-end --
ALTER TABLE public.agency_source OWNER TO boat4you_owner;
-- ddl-end --

-- object: agency_fk | type: CONSTRAINT --
-- ALTER TABLE public.agency_source DROP CONSTRAINT IF EXISTS agency_fk CASCADE;
ALTER TABLE public.agency_source ADD CONSTRAINT agency_fk FOREIGN KEY (agency_id)
REFERENCES public.agency (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: external_system_fk | type: CONSTRAINT --
-- ALTER TABLE public.agency_source DROP CONSTRAINT IF EXISTS external_system_fk CASCADE;
ALTER TABLE public.agency_source ADD CONSTRAINT external_system_fk FOREIGN KEY (external_system_id)
REFERENCES public.external_system (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: public.sync_job | type: TABLE --
-- DROP TABLE IF EXISTS public.sync_job CASCADE;
CREATE TABLE public.sync_job (
	id bigserial NOT NULL,
	sync_time timestamptz NOT NULL,
	external_system_id integer NOT NULL,
	CONSTRAINT sync_job_pk PRIMARY KEY (id)
);
-- ddl-end --
ALTER TABLE public.sync_job OWNER TO boat4you_owner;
-- ddl-end --

-- object: external_system_fk | type: CONSTRAINT --
-- ALTER TABLE public.sync_job DROP CONSTRAINT IF EXISTS external_system_fk CASCADE;
ALTER TABLE public.sync_job ADD CONSTRAINT external_system_fk FOREIGN KEY (external_system_id)
REFERENCES public.external_system (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: public.synced_entity | type: TABLE --
-- DROP TABLE IF EXISTS public.synced_entity CASCADE;
CREATE TABLE public.synced_entity (
	id bigserial NOT NULL,
	entity_id bigint NOT NULL,
	entity_name varchar(80) NOT NULL,
	sync_time timestamptz,
	sync_job_id bigint NOT NULL,
	external_id bigint NOT NULL,
	CONSTRAINT synced_entity_pk PRIMARY KEY (id)
);
-- ddl-end --
COMMENT ON COLUMN public.synced_entity.entity_name IS E'table name of an synced entity';
-- ddl-end --
ALTER TABLE public.synced_entity OWNER TO boat4you_owner;
-- ddl-end --

-- object: sync_job_fk | type: CONSTRAINT --
-- ALTER TABLE public.synced_entity DROP CONSTRAINT IF EXISTS sync_job_fk CASCADE;
ALTER TABLE public.synced_entity ADD CONSTRAINT sync_job_fk FOREIGN KEY (sync_job_id)
REFERENCES public.sync_job (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: public.service_call | type: TABLE --
-- DROP TABLE IF EXISTS public.service_call CASCADE;
CREATE TABLE public.service_call (
	id bigserial NOT NULL,
	route varchar(255),
	request_body jsonb,
	response_body jsonb,
	response_status smallint,
	external_system_id integer NOT NULL,
	CONSTRAINT service_call_pk PRIMARY KEY (id)
);
-- ddl-end --
ALTER TABLE public.service_call OWNER TO boat4you_owner;
-- ddl-end --

-- object: external_system_fk | type: CONSTRAINT --
-- ALTER TABLE public.service_call DROP CONSTRAINT IF EXISTS external_system_fk CASCADE;
ALTER TABLE public.service_call ADD CONSTRAINT external_system_fk FOREIGN KEY (external_system_id)
REFERENCES public.external_system (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: public.external_mapping | type: TABLE --
-- DROP TABLE IF EXISTS public.external_mapping CASCADE;
CREATE TABLE public.external_mapping (
	id bigserial NOT NULL,
	external_id bigint NOT NULL,
	system_id bigint NOT NULL,
	type varchar(100) NOT NULL,
	extended_type varchar(100),
	external_system_id integer NOT NULL,
	CONSTRAINT external_mapping_pk PRIMARY KEY (id)
);
-- ddl-end --
COMMENT ON TABLE public.external_mapping IS E'Used for mapping of external system id to boat4you id';
-- ddl-end --
COMMENT ON COLUMN public.external_mapping.type IS E'Name of destination table for mapping';
-- ddl-end --
ALTER TABLE public.external_mapping OWNER TO boat4you_owner;
-- ddl-end --

-- object: external_system_fk | type: CONSTRAINT --
-- ALTER TABLE public.external_mapping DROP CONSTRAINT IF EXISTS external_system_fk CASCADE;
ALTER TABLE public.external_mapping ADD CONSTRAINT external_system_fk FOREIGN KEY (external_system_id)
REFERENCES public.external_system (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: public.reservation_options | type: TABLE --
-- DROP TABLE IF EXISTS public.reservation_options CASCADE;
CREATE TABLE public.reservation_options (
	id bigserial NOT NULL,
	date_from date NOT NULL,
	date_to date,
	minimal_duration smallint NOT NULL,
	yacht_id bigint NOT NULL,
	checkin_mon boolean NOT NULL,
	checkin_tue boolean NOT NULL,
	checkin_wed boolean NOT NULL,
	checkin_thu boolean NOT NULL,
	checkin_fri boolean NOT NULL,
	checkin_sat boolean NOT NULL,
	checkin_sun boolean NOT NULL,
	checkout_mon boolean NOT NULL,
	checkout_tue boolean NOT NULL,
	checkout_wed boolean NOT NULL,
	checkout_thu boolean NOT NULL,
	checkout_fri boolean NOT NULL,
	checkout_sat boolean NOT NULL,
	checkout_sun boolean NOT NULL,
	CONSTRAINT reservation_options_pk PRIMARY KEY (id)
);
-- ddl-end --
COMMENT ON TABLE public.reservation_options IS E'Reservation options like shorter than 7 day reservation, checkin days, etc';
-- ddl-end --
ALTER TABLE public.reservation_options OWNER TO boat4you_owner;
-- ddl-end --

-- object: yacht_fk | type: CONSTRAINT --
-- ALTER TABLE public.reservation_options DROP CONSTRAINT IF EXISTS yacht_fk CASCADE;
ALTER TABLE public.reservation_options ADD CONSTRAINT yacht_fk FOREIGN KEY (yacht_id)
REFERENCES public.yacht (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: public.location | type: TABLE --
-- DROP TABLE IF EXISTS public.location CASCADE;
CREATE TABLE public.location (
	id bigserial NOT NULL,
	name varchar(255) NOT NULL,
	country_code varchar(2) NOT NULL,
	lat decimal,
	lon decimal,
	country_id integer NOT NULL,
	city varchar(100),
	CONSTRAINT location_pk PRIMARY KEY (id)
);
-- ddl-end --
COMMENT ON COLUMN public.location.country_code IS E'2 letter country code';
-- ddl-end --
ALTER TABLE public.location OWNER TO boat4you_owner;
-- ddl-end --

-- object: location_fk | type: CONSTRAINT --
-- ALTER TABLE public.yacht DROP CONSTRAINT IF EXISTS location_fk CASCADE;
ALTER TABLE public.yacht ADD CONSTRAINT location_fk FOREIGN KEY (location_id)
REFERENCES public.location (id) MATCH FULL
ON DELETE SET NULL ON UPDATE CASCADE;
-- ddl-end --

-- object: public.model | type: TABLE --
-- DROP TABLE IF EXISTS public.model CASCADE;
CREATE TABLE public.model (
	id bigserial NOT NULL,
	name varchar(255) NOT NULL,
	manufacturer_id bigint,
	external_category_id bigint,
	CONSTRAINT model_pk PRIMARY KEY (id)
);
-- ddl-end --
COMMENT ON COLUMN public.model.external_category_id IS E'Only for nausys model category (vessel type)';
-- ddl-end --
ALTER TABLE public.model OWNER TO boat4you_owner;
-- ddl-end --

-- object: model_fk | type: CONSTRAINT --
-- ALTER TABLE public.yacht DROP CONSTRAINT IF EXISTS model_fk CASCADE;
ALTER TABLE public.yacht ADD CONSTRAINT model_fk FOREIGN KEY (model_id)
REFERENCES public.model (id) MATCH FULL
ON DELETE SET NULL ON UPDATE CASCADE;
-- ddl-end --

-- object: public.manufacturer | type: TABLE --
-- DROP TABLE IF EXISTS public.manufacturer CASCADE;
CREATE TABLE public.manufacturer (
	id bigserial NOT NULL,
	name varchar(255) NOT NULL,
	CONSTRAINT manufacturer_pk PRIMARY KEY (id)
);
-- ddl-end --
ALTER TABLE public.manufacturer OWNER TO boat4you_owner;
-- ddl-end --

-- object: manufacturer_fk | type: CONSTRAINT --
-- ALTER TABLE public.model DROP CONSTRAINT IF EXISTS manufacturer_fk CASCADE;
ALTER TABLE public.model ADD CONSTRAINT manufacturer_fk FOREIGN KEY (manufacturer_id)
REFERENCES public.manufacturer (id) MATCH FULL
ON DELETE SET NULL ON UPDATE CASCADE;
-- ddl-end --

-- object: idx_yacht_location | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_yacht_location CASCADE;
CREATE INDEX idx_yacht_location ON public.yacht
USING btree
(
	location_id
);
-- ddl-end --

-- object: idx_yacht_agency | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_yacht_agency CASCADE;
CREATE INDEX idx_yacht_agency ON public.yacht
USING btree
(
	agency_id
);
-- ddl-end --

-- object: public.equipment | type: TABLE --
-- DROP TABLE IF EXISTS public.equipment CASCADE;
CREATE TABLE public.equipment (
	id bigserial NOT NULL,
	name varchar(100) NOT NULL,
	label_code varchar(100) NOT NULL,
	category smallint NOT NULL,
	match_keys varchar NOT NULL,
	filter_order smallint,
	CONSTRAINT equipment_pk PRIMARY KEY (id)
);
-- ddl-end --
COMMENT ON COLUMN public.equipment.filter_order IS E'If its returned for UI filters and position in order.';
-- ddl-end --
ALTER TABLE public.equipment OWNER TO boat4you_owner;
-- ddl-end --

-- object: public.yacht_equipment | type: TABLE --
-- DROP TABLE IF EXISTS public.yacht_equipment CASCADE;
CREATE TABLE public.yacht_equipment (
	id bigserial NOT NULL,
	equipment_id bigint,
	yacht_id bigint NOT NULL,
	name varchar,
	external_id bigint,
	CONSTRAINT yacht_equipment_pk PRIMARY KEY (id)
);
-- ddl-end --
ALTER TABLE public.yacht_equipment OWNER TO boat4you_owner;
-- ddl-end --

-- object: external_system_uk1 | type: CONSTRAINT --
-- ALTER TABLE public.external_mapping DROP CONSTRAINT IF EXISTS external_system_uk1 CASCADE;
ALTER TABLE public.external_mapping ADD CONSTRAINT external_system_uk1 UNIQUE (external_id,external_system_id,system_id,type);
-- ddl-end --

-- object: public.yacht_image | type: TABLE --
-- DROP TABLE IF EXISTS public.yacht_image CASCADE;
CREATE TABLE public.yacht_image (
	id bigserial NOT NULL,
	url varchar(255),
	external_url varchar(255),
	"position" smallint,
	yacht_id bigint NOT NULL,
	main_image boolean,
	CONSTRAINT yacht_image_pk PRIMARY KEY (id)
);
-- ddl-end --
COMMENT ON COLUMN public.yacht_image."position" IS E'Position in images array displayed on FE';
-- ddl-end --
COMMENT ON COLUMN public.yacht_image.main_image IS E'From Nausys only.';
-- ddl-end --
ALTER TABLE public.yacht_image OWNER TO boat4you_owner;
-- ddl-end --

-- object: yacht_fk | type: CONSTRAINT --
-- ALTER TABLE public.yacht_image DROP CONSTRAINT IF EXISTS yacht_fk CASCADE;
ALTER TABLE public.yacht_image ADD CONSTRAINT yacht_fk FOREIGN KEY (yacht_id)
REFERENCES public.yacht (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: idx_yacht_image_yacht_id | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_yacht_image_yacht_id CASCADE;
CREATE INDEX idx_yacht_image_yacht_id ON public.yacht_image
USING btree
(
	yacht_id
);
-- ddl-end --

-- object: idx_yacht_model | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_yacht_model CASCADE;
CREATE INDEX idx_yacht_model ON public.yacht
USING btree
(
	model_id
);
-- ddl-end --

-- object: idx_reservation_options_yacht_id | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_reservation_options_yacht_id CASCADE;
CREATE INDEX idx_reservation_options_yacht_id ON public.reservation_options
USING btree
(
	yacht_id
);
-- ddl-end --

-- object: idx_reservation_options_yacht_id_date_from_date_to | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_reservation_options_yacht_id_date_from_date_to CASCADE;
CREATE INDEX idx_reservation_options_yacht_id_date_from_date_to ON public.reservation_options
USING btree
(
	yacht_id,
	date_from,
	date_to
);
-- ddl-end --

-- object: idx_model_manufacturer_id | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_model_manufacturer_id CASCADE;
CREATE INDEX idx_model_manufacturer_id ON public.model
USING btree
(
	manufacturer_id
);
-- ddl-end --

-- object: idx_agency_source_ext_system | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_agency_source_ext_system CASCADE;
CREATE INDEX idx_agency_source_ext_system ON public.agency_source
USING btree
(
	agency_id,
	external_system_id,
	"primary"
);
-- ddl-end --

-- object: public.offer | type: TABLE --
-- DROP TABLE IF EXISTS public.offer CASCADE;
CREATE TABLE public.offer (
	id bigserial NOT NULL,
	yacht_id bigint NOT NULL,
	location_from bigint NOT NULL,
	location_to bigint NOT NULL,
	date_from date NOT NULL,
	date_to date NOT NULL,
	client_price decimal NOT NULL,
	total_price decimal NOT NULL,
	ext_base_price decimal,
	ext_client_price decimal,
	ext_total_price decimal,
	deposit decimal,
	deposit_insured decimal,
	obligatory_extras_price decimal,
	total_discount decimal,
	ext_total_discount decimal,
	ext_discount_perc decimal,
	status smallint NOT NULL,
	payment_plans jsonb,
	type smallint NOT NULL,
	product smallint NOT NULL,
	checkin varchar(20) NOT NULL,
	checkout varchar(20) NOT NULL,
	CONSTRAINT offers_pk PRIMARY KEY (id)
);
-- ddl-end --
COMMENT ON COLUMN public.offer.total_price IS E'Price with obligatory extras';
-- ddl-end --
COMMENT ON COLUMN public.offer.ext_total_price IS E'Sometimes nausys returns null and empty obligatory extras';
-- ddl-end --
COMMENT ON COLUMN public.offer.ext_total_discount IS E'For Nausys sum of all discounts. Not applying boat4you agency discount';
-- ddl-end --
COMMENT ON COLUMN public.offer.type IS E'standard sat-sat or other';
-- ddl-end --
ALTER TABLE public.offer OWNER TO boat4you_owner;
-- ddl-end --

-- object: yacht_fk | type: CONSTRAINT --
-- ALTER TABLE public.offer DROP CONSTRAINT IF EXISTS yacht_fk CASCADE;
ALTER TABLE public.offer ADD CONSTRAINT yacht_fk FOREIGN KEY (yacht_id)
REFERENCES public.yacht (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: public.region | type: TABLE --
-- DROP TABLE IF EXISTS public.region CASCADE;
CREATE TABLE public.region (
	id serial NOT NULL,
	name varchar(100),
	country_id integer,
	country_code varchar(2),
	CONSTRAINT region_pk PRIMARY KEY (id)
);
-- ddl-end --
COMMENT ON TABLE public.region IS E'Region is helper table since Nausys doesn''t have direct location - country relationship';
-- ddl-end --
ALTER TABLE public.region OWNER TO boat4you_owner;
-- ddl-end --

-- object: public.country | type: TABLE --
-- DROP TABLE IF EXISTS public.country CASCADE;
CREATE TABLE public.country (
	id serial NOT NULL,
	name varchar(100),
	code2 varchar(2) NOT NULL,
	code3 varchar(3) NOT NULL,
	continent varchar(15) NOT NULL,
	CONSTRAINT country_pk PRIMARY KEY (id)
);
-- ddl-end --
ALTER TABLE public.country OWNER TO boat4you_owner;
-- ddl-end --

-- object: country_fk | type: CONSTRAINT --
-- ALTER TABLE public.region DROP CONSTRAINT IF EXISTS country_fk CASCADE;
ALTER TABLE public.region ADD CONSTRAINT country_fk FOREIGN KEY (country_id)
REFERENCES public.country (id) MATCH FULL
ON DELETE SET NULL ON UPDATE CASCADE;
-- ddl-end --

-- object: country_fk | type: CONSTRAINT --
-- ALTER TABLE public.location DROP CONSTRAINT IF EXISTS country_fk CASCADE;
ALTER TABLE public.location ADD CONSTRAINT country_fk FOREIGN KEY (country_id)
REFERENCES public.country (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: public.category | type: TABLE --
-- DROP TABLE IF EXISTS public.category CASCADE;
CREATE TABLE public.category (
	id serial NOT NULL,
	name varchar(50),
	external_id bigint NOT NULL,
	CONSTRAINT category_pk PRIMARY KEY (id)
);
-- ddl-end --
COMMENT ON TABLE public.category IS E'Sync for nausys category';
-- ddl-end --
ALTER TABLE public.category OWNER TO boat4you_owner;
-- ddl-end --

-- object: idx_ext_mapping_type_ext_system | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_ext_mapping_type_ext_system CASCADE;
CREATE INDEX idx_ext_mapping_type_ext_system ON public.external_mapping
USING btree
(
	type,
	external_system_id
);
-- ddl-end --
COMMENT ON INDEX public.idx_ext_mapping_type_ext_system IS E'Used for the most searches from the app';
-- ddl-end --

-- object: public.offer_extras | type: TABLE --
-- DROP TABLE IF EXISTS public.offer_extras CASCADE;
CREATE TABLE public.offer_extras (
	id bigserial NOT NULL,
	offer_id bigint NOT NULL,
	extras_id bigint,
	name varchar,
	obligatory boolean NOT NULL,
	price decimal NOT NULL,
	payable_in_base boolean NOT NULL,
	unit smallint NOT NULL,
	external_unit varchar,
	external_id bigint,
	yacht_extras_id bigint NOT NULL,
	CONSTRAINT offer_extras_pk PRIMARY KEY (id)
);
-- ddl-end --
COMMENT ON COLUMN public.offer_extras.external_id IS E'might be duplicate!';
-- ddl-end --
ALTER TABLE public.offer_extras OWNER TO boat4you_owner;
-- ddl-end --

-- object: extras_fk | type: CONSTRAINT --
-- ALTER TABLE public.offer_extras DROP CONSTRAINT IF EXISTS extras_fk CASCADE;
ALTER TABLE public.offer_extras ADD CONSTRAINT extras_fk FOREIGN KEY (extras_id)
REFERENCES public.extras (id) MATCH FULL
ON DELETE SET NULL ON UPDATE CASCADE;
-- ddl-end --

-- object: offer_fk | type: CONSTRAINT --
-- ALTER TABLE public.offer_extras DROP CONSTRAINT IF EXISTS offer_fk CASCADE;
ALTER TABLE public.offer_extras ADD CONSTRAINT offer_fk FOREIGN KEY (offer_id)
REFERENCES public.offer (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: public.offer_payment_plan | type: TABLE --
-- DROP TABLE IF EXISTS public.offer_payment_plan CASCADE;
CREATE TABLE public.offer_payment_plan (
	id bigserial NOT NULL,
	offer_id bigint,
	date date NOT NULL,
	amount decimal,
	percentage decimal,
	CONSTRAINT payment_plan_pk PRIMARY KEY (id)
);
-- ddl-end --
ALTER TABLE public.offer_payment_plan OWNER TO boat4you_owner;
-- ddl-end --

-- object: offer_fk | type: CONSTRAINT --
-- ALTER TABLE public.offer_payment_plan DROP CONSTRAINT IF EXISTS offer_fk CASCADE;
ALTER TABLE public.offer_payment_plan ADD CONSTRAINT offer_fk FOREIGN KEY (offer_id)
REFERENCES public.offer (id) MATCH FULL
ON DELETE SET NULL ON UPDATE CASCADE;
-- ddl-end --

-- object: public.location_region | type: TABLE --
-- DROP TABLE IF EXISTS public.location_region CASCADE;
CREATE TABLE public.location_region (
	region_id integer NOT NULL,
	location_id bigint NOT NULL

);
-- ddl-end --
COMMENT ON TABLE public.location_region IS E'In MMK one location can have relation to multiple sailing areas';
-- ddl-end --
ALTER TABLE public.location_region OWNER TO boat4you_owner;
-- ddl-end --

-- object: region_fk | type: CONSTRAINT --
-- ALTER TABLE public.location_region DROP CONSTRAINT IF EXISTS region_fk CASCADE;
ALTER TABLE public.location_region ADD CONSTRAINT region_fk FOREIGN KEY (region_id)
REFERENCES public.region (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: location_fk | type: CONSTRAINT --
-- ALTER TABLE public.location_region DROP CONSTRAINT IF EXISTS location_fk CASCADE;
ALTER TABLE public.location_region ADD CONSTRAINT location_fk FOREIGN KEY (location_id)
REFERENCES public.location (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: public.exchange_rate | type: TABLE --
-- DROP TABLE IF EXISTS public.exchange_rate CASCADE;
CREATE TABLE public.exchange_rate (
	id bigserial NOT NULL,
	valid_at date NOT NULL,
	currency varchar(3) NOT NULL,
	rate decimal NOT NULL,
	CONSTRAINT exchange_rate_pk PRIMARY KEY (id)
);
-- ddl-end --
ALTER TABLE public.exchange_rate OWNER TO boat4you_owner;
-- ddl-end --

-- object: idx_location_region_location_id_region_id | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_location_region_location_id_region_id CASCADE;
CREATE INDEX idx_location_region_location_id_region_id ON public.location_region
USING btree
(
	region_id,
	location_id
);
-- ddl-end --

-- object: idx_location_region_region_id | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_location_region_region_id CASCADE;
CREATE INDEX idx_location_region_region_id ON public.location_region
USING btree
(
	location_id
);
-- ddl-end --

-- object: idx_region_country_id | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_region_country_id CASCADE;
CREATE INDEX idx_region_country_id ON public.region
USING btree
(
	country_id
);
-- ddl-end --

-- object: country_idx1 | type: INDEX --
-- DROP INDEX IF EXISTS public.country_idx1 CASCADE;
CREATE INDEX country_idx1 ON public.country
USING btree
(
	code2
);
-- ddl-end --

-- object: idx_offer_yacht_date_from_date_to | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_offer_yacht_date_from_date_to CASCADE;
CREATE INDEX idx_offer_yacht_date_from_date_to ON public.offer
USING btree
(
	date_from,
	date_to
);
-- ddl-end --

-- object: idx_offer_location_from_location_to | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_offer_location_from_location_to CASCADE;
CREATE INDEX idx_offer_location_from_location_to ON public.offer
USING btree
(
	location_from,
	location_to
);
-- ddl-end --

-- object: public.yacht_locations | type: TABLE --
-- DROP TABLE IF EXISTS public.yacht_locations CASCADE;
CREATE TABLE public.yacht_locations (
	location_id bigint NOT NULL,
	yacht_id bigint NOT NULL,
	CONSTRAINT yacht_locations_pk PRIMARY KEY (location_id,yacht_id)
);
-- ddl-end --
COMMENT ON TABLE public.yacht_locations IS E'Used for custom boats as they can be related to multiple countries and locations.';
-- ddl-end --
ALTER TABLE public.yacht_locations OWNER TO boat4you_owner;
-- ddl-end --

-- object: location_fk | type: CONSTRAINT --
-- ALTER TABLE public.yacht_locations DROP CONSTRAINT IF EXISTS location_fk CASCADE;
ALTER TABLE public.yacht_locations ADD CONSTRAINT location_fk FOREIGN KEY (location_id)
REFERENCES public.location (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: yacht_fk | type: CONSTRAINT --
-- ALTER TABLE public.yacht_locations DROP CONSTRAINT IF EXISTS yacht_fk CASCADE;
ALTER TABLE public.yacht_locations ADD CONSTRAINT yacht_fk FOREIGN KEY (yacht_id)
REFERENCES public.yacht (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: public.reservation | type: TABLE --
-- DROP TABLE IF EXISTS public.reservation CASCADE;
CREATE TABLE public.reservation (
	id bigserial NOT NULL,
	date_from timestamp NOT NULL,
	date_to timestamp NOT NULL,
	external_id bigint NOT NULL,
	response jsonb,
	status smallint NOT NULL,
	sys_status smallint NOT NULL,
	external_status varchar(30),
	option_expires_at timestamp,
	external_reservation_code varchar(100) NOT NULL,
	external_created_at timestamp NOT NULL,
	created_at timestamp NOT NULL,
	product smallint,
	location_from bigint NOT NULL,
	location_to bigint NOT NULL,
	base_price decimal NOT NULL,
	client_price decimal NOT NULL,
	commission decimal,
	deposit decimal,
	total_price decimal NOT NULL,
	payment_note varchar(500),
	currency varchar(3),
	bank_details varchar(200),
	note varchar(500),
	discount decimal,
	reservation_number varchar(9),
	reservation_flow_id bigint NOT NULL,
	crew_list_url varchar(1000),
	CONSTRAINT reservation_pk PRIMARY KEY (id),
	CONSTRAINT uq_reservation_number UNIQUE (reservation_number)
);
-- ddl-end --
COMMENT ON COLUMN public.reservation.status IS E'represents mapped external status';
-- ddl-end --
COMMENT ON COLUMN public.reservation.sys_status IS E'boat4you status. Mostly for debuging';
-- ddl-end --
COMMENT ON COLUMN public.reservation.external_status IS E'Original external status int for MMK values, string for Nausys';
-- ddl-end --
COMMENT ON COLUMN public.reservation.external_reservation_code IS E'reservation code mmk, uuid nausys';
-- ddl-end --
COMMENT ON COLUMN public.reservation.product IS E'bareboat, crewed, etc';
-- ddl-end --
ALTER TABLE public.reservation OWNER TO boat4you_owner;
-- ddl-end --

-- object: public.location_view | type: VIEW --
-- DROP VIEW IF EXISTS public.location_view CASCADE;
CREATE VIEW public.location_view
AS 
SELECT 'l-' || l.id as id,
       l.id         as real_id,
       l.name,
       'MARINA'     as location_type,
       country_code as country_code
FROM location l
WHERE EXISTS (SELECT 1
              FROM yacht y
              WHERE y.location_id = l.id)
UNION ALL
SELECT 'r-' || r.id as id,
       r.id         as real_id,
       r.name,
       'REGION',
       country_code as country_code
FROM region r
WHERE EXISTS (SELECT 1
              FROM yacht y
                       JOIN location_region lr
                            ON lr.location_id = y.location_id
              WHERE lr.region_id = r.id)
UNION ALL
SELECT 'c-' || c.id as id,
       c.id         as real_id,
       c.name,
       'COUNTRY',
       code2        as country_code
FROM country c
WHERE EXISTS (SELECT 1
              FROM yacht y
                       JOIN location l
                            ON l.id = y.location_id
                          JOIN country c2
                                 ON c2.id = l.country_id
              WHERE c2.id = c.id);
-- ddl-end --
ALTER VIEW public.location_view OWNER TO boat4you_owner;
-- ddl-end --

-- object: idx_ext_mapping_type_ext_system_extended | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_ext_mapping_type_ext_system_extended CASCADE;
CREATE INDEX idx_ext_mapping_type_ext_system_extended ON public.external_mapping
USING btree
(
	external_system_id,
	extended_type,
	type
);
-- ddl-end --

-- object: public.external_reservation_payment_plan | type: TABLE --
-- DROP TABLE IF EXISTS public.external_reservation_payment_plan CASCADE;
CREATE TABLE public.external_reservation_payment_plan (
	id bigserial NOT NULL,
	reservation_id bigint NOT NULL,
	date date NOT NULL,
	amount decimal NOT NULL,
	CONSTRAINT reservation_payment_plan_pk PRIMARY KEY (id)
);
-- ddl-end --
ALTER TABLE public.external_reservation_payment_plan OWNER TO boat4you_owner;
-- ddl-end --

-- object: reservation_fk | type: CONSTRAINT --
-- ALTER TABLE public.external_reservation_payment_plan DROP CONSTRAINT IF EXISTS reservation_fk CASCADE;
ALTER TABLE public.external_reservation_payment_plan ADD CONSTRAINT reservation_fk FOREIGN KEY (reservation_id)
REFERENCES public.reservation (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: public.external_reservation_extras | type: TABLE --
-- DROP TABLE IF EXISTS public.external_reservation_extras CASCADE;
CREATE TABLE public.external_reservation_extras (
	id bigserial NOT NULL,
	reservation_id bigint NOT NULL,
	external_id bigint,
	name varchar(200),
	quantity decimal,
	unit smallint,
	price numeric,
	payable_in_base bool,
	CONSTRAINT reservations_extras_pk PRIMARY KEY (id)
);
-- ddl-end --
ALTER TABLE public.external_reservation_extras OWNER TO boat4you_owner;
-- ddl-end --

-- object: reservation_fk | type: CONSTRAINT --
-- ALTER TABLE public.external_reservation_extras DROP CONSTRAINT IF EXISTS reservation_fk CASCADE;
ALTER TABLE public.external_reservation_extras ADD CONSTRAINT reservation_fk FOREIGN KEY (reservation_id)
REFERENCES public.reservation (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: extras_fk | type: CONSTRAINT --
-- ALTER TABLE public.yacht_extras DROP CONSTRAINT IF EXISTS extras_fk CASCADE;
ALTER TABLE public.yacht_extras ADD CONSTRAINT extras_fk FOREIGN KEY (extras_id)
REFERENCES public.extras (id) MATCH FULL
ON DELETE SET NULL ON UPDATE CASCADE;
-- ddl-end --

-- object: yacht_fk | type: CONSTRAINT --
-- ALTER TABLE public.yacht_extras DROP CONSTRAINT IF EXISTS yacht_fk CASCADE;
ALTER TABLE public.yacht_extras ADD CONSTRAINT yacht_fk FOREIGN KEY (yacht_id)
REFERENCES public.yacht (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: public.external_equipment | type: TABLE --
-- DROP TABLE IF EXISTS public.external_equipment CASCADE;
CREATE TABLE public.external_equipment (
	id serial NOT NULL,
	name varchar NOT NULL,
	external_system_id integer NOT NULL,
	external_id bigint NOT NULL,
	type smallint NOT NULL,
	CONSTRAINT external_equipment_pk PRIMARY KEY (id)
);
-- ddl-end --
ALTER TABLE public.external_equipment OWNER TO boat4you_owner;
-- ddl-end --

-- object: external_system_fk | type: CONSTRAINT --
-- ALTER TABLE public.external_equipment DROP CONSTRAINT IF EXISTS external_system_fk CASCADE;
ALTER TABLE public.external_equipment ADD CONSTRAINT external_system_fk FOREIGN KEY (external_system_id)
REFERENCES public.external_system (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: equipment_fk | type: CONSTRAINT --
-- ALTER TABLE public.yacht_equipment DROP CONSTRAINT IF EXISTS equipment_fk CASCADE;
ALTER TABLE public.yacht_equipment ADD CONSTRAINT equipment_fk FOREIGN KEY (equipment_id)
REFERENCES public.equipment (id) MATCH FULL
ON DELETE SET NULL ON UPDATE CASCADE;
-- ddl-end --

-- object: yacht_fk | type: CONSTRAINT --
-- ALTER TABLE public.yacht_equipment DROP CONSTRAINT IF EXISTS yacht_fk CASCADE;
ALTER TABLE public.yacht_equipment ADD CONSTRAINT yacht_fk FOREIGN KEY (yacht_id)
REFERENCES public.yacht (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: public.reservation_flow | type: TABLE --
-- DROP TABLE IF EXISTS public.reservation_flow CASCADE;
CREATE TABLE public.reservation_flow (
	id bigserial NOT NULL,
	yacht_id bigint NOT NULL,
	offer_id bigint NOT NULL,
	created_at timestamptz NOT NULL,
	status smallint NOT NULL,
	created_by bigint NOT NULL,
	user_id bigint NOT NULL,
	email varchar(255) NOT NULL,
	name varchar(255) NOT NULL,
	surname varchar(255) NOT NULL,
	phone varchar(63),
	request varchar(1000),
	calculated_total_price decimal NOT NULL,
	cancelation_request varchar(1000),
	cancelation_request_at timestamp,
	CONSTRAINT reservation_flow_pk PRIMARY KEY (id)
);
-- ddl-end --
COMMENT ON TABLE public.reservation_flow IS E'Represents started flow of the reservation';
-- ddl-end --
COMMENT ON COLUMN public.reservation_flow.calculated_total_price IS E'Represents total price calculated by boat4you system';
-- ddl-end --
ALTER TABLE public.reservation_flow OWNER TO boat4you_owner;
-- ddl-end --

-- object: yacht_fk | type: CONSTRAINT --
-- ALTER TABLE public.reservation_flow DROP CONSTRAINT IF EXISTS yacht_fk CASCADE;
ALTER TABLE public.reservation_flow ADD CONSTRAINT yacht_fk FOREIGN KEY (yacht_id)
REFERENCES public.yacht (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: offer_fk | type: CONSTRAINT --
-- ALTER TABLE public.reservation_flow DROP CONSTRAINT IF EXISTS offer_fk CASCADE;
ALTER TABLE public.reservation_flow ADD CONSTRAINT offer_fk FOREIGN KEY (offer_id)
REFERENCES public.offer (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: reservation_flow_fk | type: CONSTRAINT --
-- ALTER TABLE public.reservation DROP CONSTRAINT IF EXISTS reservation_flow_fk CASCADE;
ALTER TABLE public.reservation ADD CONSTRAINT reservation_flow_fk FOREIGN KEY (reservation_flow_id)
REFERENCES public.reservation_flow (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: public.yacht_charter_type | type: TABLE --
-- DROP TABLE IF EXISTS public.yacht_charter_type CASCADE;
CREATE TABLE public.yacht_charter_type (
	id bigserial NOT NULL,
	yacht_id bigint NOT NULL,
	type smallint NOT NULL,
	CONSTRAINT yacht_charter_type_pk PRIMARY KEY (id)
);
-- ddl-end --
ALTER TABLE public.yacht_charter_type OWNER TO boat4you_owner;
-- ddl-end --

-- object: yacht_fk | type: CONSTRAINT --
-- ALTER TABLE public.yacht_charter_type DROP CONSTRAINT IF EXISTS yacht_fk CASCADE;
ALTER TABLE public.yacht_charter_type ADD CONSTRAINT yacht_fk FOREIGN KEY (yacht_id)
REFERENCES public.yacht (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: idx_yact_charter_type_yacht_id | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_yact_charter_type_yacht_id CASCADE;
CREATE INDEX idx_yact_charter_type_yacht_id ON public.yacht_charter_type
USING btree
(
	yacht_id
);
-- ddl-end --

-- object: public.reservation_extras | type: TABLE --
-- DROP TABLE IF EXISTS public.reservation_extras CASCADE;
CREATE TABLE public.reservation_extras (
	id bigserial NOT NULL,
	yacht_extras_id bigint,
	reservation_flow_id bigint,
	price decimal,
	CONSTRAINT selected_extra_pk PRIMARY KEY (id)
);
-- ddl-end --
COMMENT ON COLUMN public.reservation_extras.price IS E'price is only expresed when extras is paid with the price';
-- ddl-end --
ALTER TABLE public.reservation_extras OWNER TO boat4you_owner;
-- ddl-end --

-- object: yacht_extras_fk | type: CONSTRAINT --
-- ALTER TABLE public.offer_extras DROP CONSTRAINT IF EXISTS yacht_extras_fk CASCADE;
ALTER TABLE public.offer_extras ADD CONSTRAINT yacht_extras_fk FOREIGN KEY (yacht_extras_id)
REFERENCES public.yacht_extras (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: yacht_extras_fk | type: CONSTRAINT --
-- ALTER TABLE public.reservation_extras DROP CONSTRAINT IF EXISTS yacht_extras_fk CASCADE;
ALTER TABLE public.reservation_extras ADD CONSTRAINT yacht_extras_fk FOREIGN KEY (yacht_extras_id)
REFERENCES public.yacht_extras (id) MATCH FULL
ON DELETE SET NULL ON UPDATE CASCADE;
-- ddl-end --

-- object: reservation_flow_fk | type: CONSTRAINT --
-- ALTER TABLE public.reservation_extras DROP CONSTRAINT IF EXISTS reservation_flow_fk CASCADE;
ALTER TABLE public.reservation_extras ADD CONSTRAINT reservation_flow_fk FOREIGN KEY (reservation_flow_id)
REFERENCES public.reservation_flow (id) MATCH FULL
ON DELETE SET NULL ON UPDATE CASCADE;
-- ddl-end --

-- object: public.custom_yacht_details | type: TABLE --
-- DROP TABLE IF EXISTS public.custom_yacht_details CASCADE;
CREATE TABLE public.custom_yacht_details (
	id bigserial NOT NULL,
	low_price decimal NOT NULL,
	mid_price decimal,
	high_price decimal,
	yacht_id bigint NOT NULL,
	video_url varchar(500),
	pdf_url varchar(500),
	country_id varchar(20),
	low_price_description varchar(500),
	mid_price_description varchar(500),
	high_price_description varchar(500),
	CONSTRAINT custom_yacht_details_pk PRIMARY KEY (id)
);
-- ddl-end --
COMMENT ON COLUMN public.custom_yacht_details.pdf_url IS E'Saved on our services';
-- ddl-end --
COMMENT ON COLUMN public.custom_yacht_details.country_id IS E'Id of country in a format c-id';
-- ddl-end --
ALTER TABLE public.custom_yacht_details OWNER TO boat4you_owner;
-- ddl-end --

-- object: yacht_fk | type: CONSTRAINT --
-- ALTER TABLE public.custom_yacht_details DROP CONSTRAINT IF EXISTS yacht_fk CASCADE;
ALTER TABLE public.custom_yacht_details ADD CONSTRAINT yacht_fk FOREIGN KEY (yacht_id)
REFERENCES public.yacht (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: idx_custom_yacht_details_yacht_id | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_custom_yacht_details_yacht_id CASCADE;
CREATE INDEX idx_custom_yacht_details_yacht_id ON public.custom_yacht_details
USING btree
(
	yacht_id
);
-- ddl-end --

-- object: public.yacht_search_view | type: VIEW --
-- DROP VIEW IF EXISTS public.yacht_search_view CASCADE;
CREATE VIEW public.yacht_search_view
AS 
SELECT y.id                                    as id
     , y.name                                  as yacht_name
     , o.location_from                         as location_from
     , lfrom.name || '-' || lfrom.country_code as location_full_name
     , o.location_to                           as location_to
     , o.client_price                          as client_price
     , o.date_from                             as date_from
     , o.date_to                               as date_to
     , y.build_year                            as build_year
     , y.model_id                              as model_id
     , m.name                                  as model_name
     , mf.id                                   AS manufacturer_id
     , mf.name                                 AS manufacturer_name
     , yct.type                                as charter_type
     , y.vessel_type                           as vessel_type
     , y.mainsail_type                         as mainsail_type
     , y.max_persons                           as max_persons
     , y.cabins                                as cabins
     , y.berths                                as berths
     , y.length                                as length
     , y.wc                                    as wc
     , y.engine_power                          as engine_power
     , o.deposit                               AS lowest_prepayment
     , (
    ((COALESCE(y.cabins, 0) + COALESCE(y.max_persons, 0))::numeric /
     NULLIF(COALESCE(o.client_price, 1), 0)) +
    (0.01 * (SELECT COUNT(*)
             FROM yacht_extras ye
             WHERE ye.yacht_id = y.id))
    )                                          AS recommended_score
     , CASE
           WHEN o.location_from = o.location_to THEN o.location_from
           ELSE o.location_from + o.location_to
    END                                        AS total_locations
     , y.main_image_id                         as main_image
     , a.id                                    as agency_id
     , a.name                                  as agency_name
     , y.entry_type                            as entry_type
FROM yacht y
         JOIN agency a
              ON y.agency_id = a.id AND a.active = true
         JOIN offer o
              ON o.yacht_id = y.id
         JOIN location lfrom
              ON lfrom.id = o.location_from
         LEFT JOIN model m
                   ON m.id = y.model_id
         LEFT JOIN manufacturer mf
                   ON mf.id = m.manufacturer_id
         LEFT JOIN yacht_charter_type yct
                   ON yct.yacht_id = y.id
WHERE y.entry_type = 1
  AND y.sys_active = true
UNION ALL
SELECT y.id                                 as id
     , y.name                               as yacht_name
     , yl.location_id                       as location_from
     , l.name || '-' || l.country_code      as location_full_name
     , yl.location_id                       as location_to
     , cyd.low_price                        as client_price
     , null                                 as date_from
     , null                                 as date_to
     , y.build_year                         as build_year
     , y.model_id                           as model_id
     , m.name                               as model_name
     , mf.id                                AS manufacturer_id
     , mf.name                              AS manufacturer_name
     , yct.type                             as charter_type
     , y.vessel_type                        as vessel_type
     , y.mainsail_type                      as mainsail_type
     , y.max_persons                        as max_persons
     , y.cabins                             as cabins
     , y.berths                             as berths
     , y.length                             as length
     , y.wc                                 as wc
     , y.engine_power                       as engine_power
     , y.deposit                            AS lowest_prepayment
     , (
    ((COALESCE(y.cabins, 0) + COALESCE(y.max_persons, 0))::numeric /
     NULLIF(COALESCE(cyd.low_price, 1), 0)) +
    (0.01 * (SELECT COUNT(*)
             FROM yacht_extras ye
             WHERE ye.yacht_id = y.id))
    )                                       AS recommended_score
     , (SELECT COUNT(DISTINCT yl2.location_id)
        FROM yacht_locations yl2
        WHERE yl2.yacht_id = y.id)::INTEGER AS total_locations
     , y.main_image_id                      as main_image
     , a.id                                 as agency_id
     , a.name                               as agency_name
     , y.entry_type                         as entry_type
FROM yacht y
         LEFT JOIN agency a
              ON y.agency_id = a.id AND a.active = true
         JOIN LATERAL (
    SELECT *
    FROM yacht_locations yl_inner
    WHERE yl_inner.yacht_id = y.id
    LIMIT 1
    ) yl ON true
         LEFT JOIN location l ON l.id = yl.location_id
         LEFT JOIN model m ON m.id = y.model_id
         LEFT JOIN manufacturer mf
                   ON mf.id = m.manufacturer_id
         LEFT JOIN yacht_charter_type yct
                   ON yct.yacht_id = y.id
         JOIN custom_yacht_details cyd
              ON cyd.yacht_id = y.id
WHERE y.entry_type = 2
  AND y.sys_active = true;
-- ddl-end --
ALTER VIEW public.yacht_search_view OWNER TO boat4you_owner;
-- ddl-end --

-- object: public.language | type: TABLE --
-- DROP TABLE IF EXISTS public.language CASCADE;
CREATE TABLE public.language (
	id serial NOT NULL,
	locale varchar(2) NOT NULL,
	name varchar(30) NOT NULL,
	CONSTRAINT language_pk PRIMARY KEY (id)
);
-- ddl-end --
ALTER TABLE public.language OWNER TO boat4you_owner;
-- ddl-end --

-- object: public.yacht_translations | type: TABLE --
-- DROP TABLE IF EXISTS public.yacht_translations CASCADE;
CREATE TABLE public.yacht_translations (
	id bigserial NOT NULL,
	yacht_id bigint NOT NULL,
	language_id integer NOT NULL,
	value varchar NOT NULL,
	type smallint NOT NULL,
	CONSTRAINT yacht_translations_pk PRIMARY KEY (id)
);
-- ddl-end --
ALTER TABLE public.yacht_translations OWNER TO boat4you_owner;
-- ddl-end --

-- object: yacht_fk | type: CONSTRAINT --
-- ALTER TABLE public.yacht_translations DROP CONSTRAINT IF EXISTS yacht_fk CASCADE;
ALTER TABLE public.yacht_translations ADD CONSTRAINT yacht_fk FOREIGN KEY (yacht_id)
REFERENCES public.yacht (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: language_fk | type: CONSTRAINT --
-- ALTER TABLE public.yacht_translations DROP CONSTRAINT IF EXISTS language_fk CASCADE;
ALTER TABLE public.yacht_translations ADD CONSTRAINT language_fk FOREIGN KEY (language_id)
REFERENCES public.language (id) MATCH FULL
ON DELETE RESTRICT ON UPDATE CASCADE;
-- ddl-end --

-- object: idx_yacht_langauge | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_yacht_langauge CASCADE;
CREATE INDEX idx_yacht_langauge ON public.yacht_translations
USING btree
(
	yacht_id,
	language_id
);
-- ddl-end --

-- object: public.inquiry | type: TABLE --
-- DROP TABLE IF EXISTS public.inquiry CASCADE;
CREATE TABLE public.inquiry (
	id bigserial NOT NULL,
	yacht_id bigint,
	created_at timestamp NOT NULL,
	date_from date,
	date_to date,
	name varchar(255),
	surname varchar(255),
	email varchar(255) NOT NULL,
	phone varchar(63),
	message varchar(2000),
	status smallint NOT NULL,
	CONSTRAINT inquiry_pk PRIMARY KEY (id)
);
-- ddl-end --
ALTER TABLE public.inquiry OWNER TO boat4you_owner;
-- ddl-end --

-- object: yacht_fk | type: CONSTRAINT --
-- ALTER TABLE public.inquiry DROP CONSTRAINT IF EXISTS yacht_fk CASCADE;
ALTER TABLE public.inquiry ADD CONSTRAINT yacht_fk FOREIGN KEY (yacht_id)
REFERENCES public.yacht (id) MATCH FULL
ON DELETE SET NULL ON UPDATE CASCADE;
-- ddl-end --

-- object: idx_inquiry_yacht_id | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_inquiry_yacht_id CASCADE;
CREATE INDEX idx_inquiry_yacht_id ON public.inquiry
USING btree
(
	yacht_id
);
-- ddl-end --

-- object: idx_ext_squipment_ext_system | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_ext_squipment_ext_system CASCADE;
CREATE INDEX idx_ext_squipment_ext_system ON public.external_equipment
USING btree
(
	external_system_id
);
-- ddl-end --

-- object: idx_ext_reservation_yacht_id | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_ext_reservation_yacht_id CASCADE;
CREATE INDEX idx_ext_reservation_yacht_id ON public.external_reservations
USING btree
(
	yacht_id
);
-- ddl-end --

-- object: idx_yacht_equipment_yacht_id_equipment_id | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_yacht_equipment_yacht_id_equipment_id CASCADE;
CREATE INDEX idx_yacht_equipment_yacht_id_equipment_id ON public.yacht_equipment
USING btree
(
	yacht_id,
	equipment_id
);
-- ddl-end --

-- object: idx_location_country_id | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_location_country_id CASCADE;
CREATE INDEX idx_location_country_id ON public.location
USING btree
(
	country_id
);
-- ddl-end --

-- object: idx_location_country_code | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_location_country_code CASCADE;
CREATE INDEX idx_location_country_code ON public.location
USING btree
(
	country_code
);
-- ddl-end --

-- object: idx_yacht_locations_yacht_id_location_id | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_yacht_locations_yacht_id_location_id CASCADE;
CREATE INDEX idx_yacht_locations_yacht_id_location_id ON public.yacht_locations
USING btree
(
	location_id,
	yacht_id
);
-- ddl-end --

-- object: idx_offer_yacht_id | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_offer_yacht_id CASCADE;
CREATE INDEX idx_offer_yacht_id ON public.offer
USING btree
(
	yacht_id
);
-- ddl-end --

-- object: idx_offer_extras_offer_id | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_offer_extras_offer_id CASCADE;
CREATE INDEX idx_offer_extras_offer_id ON public.offer_extras
USING btree
(
	offer_id
);
-- ddl-end --

-- object: idx_offer_extras_yacht_extras_id | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_offer_extras_yacht_extras_id CASCADE;
CREATE INDEX idx_offer_extras_yacht_extras_id ON public.offer_extras
USING btree
(
	yacht_extras_id
);
-- ddl-end --

-- object: idx_yacht_extras_yacht_id | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_yacht_extras_yacht_id CASCADE;
CREATE INDEX idx_yacht_extras_yacht_id ON public.yacht_extras
USING btree
(
	yacht_id
);
-- ddl-end --

-- object: idx_offer_payment_plan_offer_id | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_offer_payment_plan_offer_id CASCADE;
CREATE INDEX idx_offer_payment_plan_offer_id ON public.offer_payment_plan
USING btree
(
	offer_id
);
-- ddl-end --

-- object: idx_reservation_flow_yacht_id | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_reservation_flow_yacht_id CASCADE;
CREATE INDEX idx_reservation_flow_yacht_id ON public.reservation_flow
USING btree
(
	yacht_id
);
-- ddl-end --

-- object: idx_reservation_flow_yacht_id_offer_id | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_reservation_flow_yacht_id_offer_id CASCADE;
CREATE INDEX idx_reservation_flow_yacht_id_offer_id ON public.reservation_flow
USING btree
(
	yacht_id,
	offer_id
);
-- ddl-end --

-- object: idx_reservation_flow_user_id | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_reservation_flow_user_id CASCADE;
CREATE INDEX idx_reservation_flow_user_id ON public.reservation_flow
USING btree
(
	user_id
);
-- ddl-end --

-- object: idx_reservation_extras_flow_id_yacht_extras_id | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_reservation_extras_flow_id_yacht_extras_id CASCADE;
CREATE INDEX idx_reservation_extras_flow_id_yacht_extras_id ON public.reservation_extras
USING btree
(
	yacht_extras_id,
	reservation_flow_id
);
-- ddl-end --

-- object: idx_reservation_extras_flow_id | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_reservation_extras_flow_id CASCADE;
CREATE INDEX idx_reservation_extras_flow_id ON public.reservation_extras
USING btree
(
	reservation_flow_id
);
-- ddl-end --

-- object: idx_reservation_reservation_flow_id | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_reservation_reservation_flow_id CASCADE;
CREATE INDEX idx_reservation_reservation_flow_id ON public.reservation
USING btree
(
	reservation_flow_id
);
-- ddl-end --

-- object: idx_external_reservation_payment_plan_reservation_id | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_external_reservation_payment_plan_reservation_id CASCADE;
CREATE INDEX idx_external_reservation_payment_plan_reservation_id ON public.external_reservation_payment_plan
USING btree
(
	reservation_id
);
-- ddl-end --

-- object: idx_external_reservation_extras_reservation_id | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_external_reservation_extras_reservation_id CASCADE;
CREATE INDEX idx_external_reservation_extras_reservation_id ON public.external_reservation_extras
USING btree
(
	reservation_id
);
-- ddl-end --

-- object: public.custom_offer | type: TABLE --
-- DROP TABLE IF EXISTS public.custom_offer CASCADE;
CREATE TABLE public.custom_offer (
	id bigserial NOT NULL,
	created_at timestamp NOT NULL,
	inquiry_id bigint,
	short_url varchar(6) NOT NULL,
	long_url varchar(1000) NOT NULL,
	request jsonb NOT NULL,
	user_id bigint,
	email varchar(255),
	CONSTRAINT custom_offer_pk PRIMARY KEY (id)
);
-- ddl-end --
COMMENT ON TABLE public.custom_offer IS E'Custom offer can be created based on user inquiry over the system, over phone call, email, etc.';
-- ddl-end --
COMMENT ON COLUMN public.custom_offer.short_url IS E'6 chars should be enugh for 56.8 billion combinations';
-- ddl-end --
COMMENT ON COLUMN public.custom_offer.long_url IS E'This is query string for yacht search';
-- ddl-end --
COMMENT ON COLUMN public.custom_offer.request IS E'Original parameters that triggered creation of this offer';
-- ddl-end --
ALTER TABLE public.custom_offer OWNER TO boat4you_owner;
-- ddl-end --

-- object: inquiry_fk | type: CONSTRAINT --
-- ALTER TABLE public.custom_offer DROP CONSTRAINT IF EXISTS inquiry_fk CASCADE;
ALTER TABLE public.custom_offer ADD CONSTRAINT inquiry_fk FOREIGN KEY (inquiry_id)
REFERENCES public.inquiry (id) MATCH FULL
ON DELETE SET NULL ON UPDATE CASCADE;
-- ddl-end --

-- object: uidx_short_url | type: INDEX --
-- DROP INDEX IF EXISTS public.uidx_short_url CASCADE;
CREATE INDEX uidx_short_url ON public.custom_offer
USING btree
(
	short_url
);
-- ddl-end --

-- object: public.service_call_cache | type: TABLE --
-- DROP TABLE IF EXISTS public.service_call_cache CASCADE;
CREATE TABLE public.service_call_cache (
	id bigserial NOT NULL,
	method smallint NOT NULL,
	hash_code bigint NOT NULL,
	created_at timestamp NOT NULL,
	CONSTRAINT service_call_cache_pk PRIMARY KEY (id)
);
-- ddl-end --
ALTER TABLE public.service_call_cache OWNER TO postgres;
-- ddl-end --

-- object: idx_serice_call_method_hash_code | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_serice_call_method_hash_code CASCADE;
CREATE INDEX idx_serice_call_method_hash_code ON public.service_call_cache
USING btree
(
	method,
	hash_code
);
-- ddl-end --

-- object: location_from_fk | type: CONSTRAINT --
-- ALTER TABLE public.offer DROP CONSTRAINT IF EXISTS location_from_fk CASCADE;
ALTER TABLE public.offer ADD CONSTRAINT location_from_fk FOREIGN KEY (location_from)
REFERENCES public.location (id) MATCH SIMPLE
ON DELETE NO ACTION ON UPDATE NO ACTION;
-- ddl-end --

-- object: location_to_fk | type: CONSTRAINT --
-- ALTER TABLE public.offer DROP CONSTRAINT IF EXISTS location_to_fk CASCADE;
ALTER TABLE public.offer ADD CONSTRAINT location_to_fk FOREIGN KEY (location_to)
REFERENCES public.location (id) MATCH SIMPLE
ON DELETE NO ACTION ON UPDATE NO ACTION;
-- ddl-end --

-- object: location_from_fk | type: CONSTRAINT --
-- ALTER TABLE public.reservation DROP CONSTRAINT IF EXISTS location_from_fk CASCADE;
ALTER TABLE public.reservation ADD CONSTRAINT location_from_fk FOREIGN KEY (location_from)
REFERENCES public.location (id) MATCH SIMPLE
ON DELETE NO ACTION ON UPDATE NO ACTION;
-- ddl-end --

-- object: location_to_fk | type: CONSTRAINT --
-- ALTER TABLE public.reservation DROP CONSTRAINT IF EXISTS location_to_fk CASCADE;
ALTER TABLE public.reservation ADD CONSTRAINT location_to_fk FOREIGN KEY (location_to)
REFERENCES public.location (id) MATCH SIMPLE
ON DELETE NO ACTION ON UPDATE NO ACTION;
-- ddl-end --

-- object: grant_rawd_d12e0f154a | type: PERMISSION --
GRANT SELECT,INSERT,UPDATE,DELETE
   ON TABLE public.region
   TO boat4you_app;
-- ddl-end --

-- object: grant_rawd_bf8d40ddd1 | type: PERMISSION --
GRANT SELECT,INSERT,UPDATE,DELETE
   ON TABLE public.country
   TO boat4you_app;
-- ddl-end --

-- object: grant_rawd_2e277ed8d1 | type: PERMISSION --
GRANT SELECT,INSERT,UPDATE,DELETE
   ON TABLE public.category
   TO boat4you_app;
-- ddl-end --


