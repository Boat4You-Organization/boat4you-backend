-- Free-text engine descriptor for custom yachts.
--
-- Owners describe propulsion in non-uniform shapes ("2x Volvo IPS 1050",
-- "560 HP MAN", "Twin 600HP MTU diesels"), so the kW numeric on the
-- Yacht entity is a poor fit — admin couldn't capture brand or twin-engine
-- setups. Plus Mario explicitly wants custom yachts excluded from the
-- engine-power range filter on /search; leaving Yacht.engine_power NULL
-- for new custom listings keeps them out of the numeric IN clauses while
-- this column carries the descriptive value the public boat detail
-- renders verbatim.
ALTER TABLE public.custom_yacht_details
    ADD COLUMN IF NOT EXISTS engine_text TEXT;
