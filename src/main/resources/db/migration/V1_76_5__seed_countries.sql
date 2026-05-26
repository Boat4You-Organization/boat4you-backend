-- Country reference data, seeded BEFORE the region/location migrations that
-- FK-reference it (V1_77 hardcodes country_id 86 = Greece). The canonical seed
-- used to live in repeatable R__1_07, which Flyway runs LAST — after V1_77 — so
-- a from-scratch migration died on a country_fk violation. Versioned + ordered
-- before V1_77 fixes greenfield and the local Docker DB. Idempotent so an
-- out-of-order apply on an already-seeded DB is a no-op.
CREATE TEMP TABLE _country_seed (
    id integer, name varchar(100), code2 varchar(2), code3 varchar(3), continent varchar(15)
) ON COMMIT DROP;

COPY _country_seed (id, name, code2, code3, continent) FROM stdin;
1	Afghanistan	AF	AFG	Asia
2	Albania	AL	ALB	Europe
3	Algeria	DZ	DZA	Africa
4	American Samoa	AS	ASM	Oceania
5	Andorra	AD	AND	Europe
6	Angola	AO	AGO	Africa
7	Anguilla	AI	AIA	North America
8	Antarctica	AQ	ATA	Antarctica
9	Antigua and Barbuda	AG	ATG	North America
10	Argentina	AR	ARG	South America
11	Armenia	AM	ARM	Asia
12	Aruba	AW	ABW	North America
13	Australia	AU	AUS	Oceania
14	Austria	AT	AUT	Europe
15	Azerbaijan	AZ	AZE	Asia
16	Bahamas	BS	BHS	North America
17	Bahrain	BH	BHR	Asia
18	Bangladesh	BD	BGD	Asia
19	Barbados	BB	BRB	North America
20	Belarus	BY	BLR	Europe
21	Belgium	BE	BEL	Europe
22	Belize	BZ	BLZ	North America
23	Benin	BJ	BEN	Africa
24	Bermuda	BM	BMU	North America
25	Bhutan	BT	BTN	Asia
26	Bolivia	BO	BOL	South America
27	Caribbean Netherlands	BQ	BES	North America
28	Bosnia and Herzegovina	BA	BIH	Europe
29	Botswana	BW	BWA	Africa
30	Bouvet Island	BV	BVT	Antarctica
31	Brazil	BR	BRA	South America
32	British Indian Ocean Territory	IO	IOT	Asia
33	Brunei Darussalam	BN	BRN	Asia
34	Bulgaria	BG	BGR	Europe
35	Burkina Faso	BF	BFA	Africa
36	Burundi	BI	BDI	Africa
37	Cabo Verde	CV	CPV	Africa
38	Cambodia	KH	KHM	Asia
39	Cameroon	CM	CMR	Africa
40	Canada	CA	CAN	North America
41	Cayman Islands	KY	CYM	North America
42	Central African Republic	CF	CAF	Africa
43	Chad	TD	TCD	Africa
44	Chile	CL	CHL	South America
45	China	CN	CHN	Asia
46	Christmas Island	CX	CXR	Asia
47	Cocos (Keeling) Islands	CC	CCK	Asia
48	Colombia	CO	COL	South America
49	Comoros	KM	COM	Africa
50	Democratic Republic of the Congo	CD	COD	Africa
51	Congo	CG	COG	Africa
52	Cook Islands	CK	COK	Oceania
53	Costa Rica	CR	CRI	North America
54	Croatia	HR	HRV	Europe
55	Cuba	CU	CUB	North America
56	Curaçao	CW	CUW	North America
57	Cyprus	CY	CYP	Europe
58	Czechia	CZ	CZE	Europe
59	Côte d'Ivoire	CI	CIV	Africa
60	Denmark	DK	DNK	Europe
61	Djibouti	DJ	DJI	Africa
62	Dominica	DM	DMA	North America
63	Dominican Republic	DO	DOM	North America
64	Ecuador	EC	ECU	South America
65	Egypt	EG	EGY	Africa
66	El Salvador	SV	SLV	North America
67	Equatorial Guinea	GQ	GNQ	Africa
68	Eritrea	ER	ERI	Africa
69	Estonia	EE	EST	Europe
70	Eswatini	SZ	SWZ	Africa
71	Ethiopia	ET	ETH	Africa
72	Falkland Islands	FK	FLK	South America
73	Faroe Islands	FO	FRO	Europe
74	Fiji	FJ	FJI	Oceania
75	Finland	FI	FIN	Europe
76	France	FR	FRA	Europe
77	French Guiana	GF	GUF	South America
78	French Polynesia	PF	PYF	Oceania
79	French Southern Territories	TF	ATF	Antarctica
80	Gabon	GA	GAB	Africa
81	Gambia	GM	GMB	Africa
82	Georgia	GE	GEO	Asia
83	Germany	DE	DEU	Europe
84	Ghana	GH	GHA	Africa
85	Gibraltar	GI	GIB	Europe
86	Greece	GR	GRC	Europe
87	Greenland	GL	GRL	North America
88	Grenada	GD	GRD	North America
89	Guadeloupe	GP	GLP	North America
90	Guam	GU	GUM	Oceania
91	Guatemala	GT	GTM	North America
92	Guernsey	GG	GGY	Europe
93	Guinea	GN	GIN	Africa
94	Guinea-Bissau	GW	GNB	Africa
95	Guyana	GY	GUY	South America
96	Haiti	HT	HTI	North America
97	Heard Island and McDonald Islands	HM	HMD	Antarctica
98	Holy See	VA	VAT	Europe
99	Honduras	HN	HND	North America
100	Hong Kong	HK	HKG	Asia
101	Hungary	HU	HUN	Europe
102	Iceland	IS	ISL	Europe
103	India	IN	IND	Asia
104	Indonesia	ID	IDN	Asia
105	Iran	IR	IRN	Asia
106	Iraq	IQ	IRQ	Asia
107	Ireland	IE	IRL	Europe
108	Isle of Man	IM	IMN	Europe
109	Israel	IL	ISR	Asia
110	Italy	IT	ITA	Europe
111	Jamaica	JM	JAM	North America
112	Japan	JP	JPN	Asia
113	Jersey	JE	JEY	Europe
114	Jordan	JO	JOR	Asia
115	Kazakhstan	KZ	KAZ	Asia
116	Kenya	KE	KEN	Africa
117	Kiribati	KI	KIR	Oceania
118	North Korea	KP	PRK	Asia
119	South Korea	KR	KOR	Asia
120	Kuwait	KW	KWT	Asia
121	Kyrgyzstan	KG	KGZ	Asia
122	Lao People's Democratic Republic	LA	LAO	Asia
123	Latvia	LV	LVA	Europe
124	Lebanon	LB	LBN	Asia
125	Lesotho	LS	LSO	Africa
126	Liberia	LR	LBR	Africa
127	Libya	LY	LBY	Africa
128	Liechtenstein	LI	LIE	Europe
129	Lithuania	LT	LTU	Europe
130	Luxembourg	LU	LUX	Europe
131	Macao	MO	MAC	Asia
132	Madagascar	MG	MDG	Africa
133	Malawi	MW	MWI	Africa
134	Malaysia	MY	MYS	Asia
135	Maldives	MV	MDV	Asia
136	Mali	ML	MLI	Africa
137	Malta	MT	MLT	Europe
138	Marshall Islands	MH	MHL	Oceania
139	Martinique	MQ	MTQ	North America
140	Mauritania	MR	MRT	Africa
141	Mauritius	MU	MUS	Africa
142	Mayotte	YT	MYT	Africa
143	Mexico	MX	MEX	North America
144	Micronesia	FM	FSM	Oceania
145	Moldova	MD	MDA	Europe
146	Monaco	MC	MCO	Europe
147	Mongolia	MN	MNG	Asia
148	Montenegro	ME	MNE	Europe
149	Montserrat	MS	MSR	North America
150	Morocco	MA	MAR	Africa
151	Mozambique	MZ	MOZ	Africa
152	Myanmar	MM	MMR	Asia
153	Namibia	NA	NAM	Africa
154	Nauru	NR	NRU	Oceania
155	Nepal	NP	NPL	Asia
156	Netherlands	NL	NLD	Europe
157	New Caledonia	NC	NCL	Oceania
158	New Zealand	NZ	NZL	Oceania
159	Nicaragua	NI	NIC	North America
160	Niger	NE	NER	Africa
161	Nigeria	NG	NGA	Africa
162	Niue	NU	NIU	Oceania
163	Norfolk Island	NF	NFK	Oceania
164	North Macedonia	MK	MKD	Europe
165	Northern Mariana Islands	MP	MNP	Oceania
166	Norway	NO	NOR	Europe
167	Oman	OM	OMN	Asia
168	Pakistan	PK	PAK	Asia
169	Palau	PW	PLW	Oceania
170	Palestine, State of	PS	PSE	Asia
171	Panama	PA	PAN	North America
172	Papua New Guinea	PG	PNG	Oceania
173	Paraguay	PY	PRY	South America
174	Peru	PE	PER	South America
175	Philippines	PH	PHL	Asia
176	Pitcairn	PN	PCN	Oceania
177	Poland	PL	POL	Europe
178	Portugal	PT	PRT	Europe
179	Puerto Rico	PR	PRI	North America
180	Qatar	QA	QAT	Asia
181	Romania	RO	ROU	Europe
182	Russian Federation	RU	RUS	Europe
183	Rwanda	RW	RWA	Africa
184	Réunion	RE	REU	Africa
185	Saint Barthélemy	BL	BLM	North America
186	Saint Helena, Ascension and Tristan da Cunha	SH	SHN	Africa
187	Saint Kitts and Nevis	KN	KNA	North America
188	Saint Lucia	LC	LCA	North America
189	Saint Martin (French part)	MF	MAF	North America
190	Saint Pierre and Miquelon	PM	SPM	North America
191	Saint Vincent and the Grenadines	VC	VCT	North America
192	Samoa	WS	WSM	Oceania
193	San Marino	SM	SMR	Europe
194	Sao Tome and Principe	ST	STP	Africa
195	Saudi Arabia	SA	SAU	Asia
196	Senegal	SN	SEN	Africa
197	Serbia	RS	SRB	Europe
198	Seychelles	SC	SYC	Africa
199	Sierra Leone	SL	SLE	Africa
200	Singapore	SG	SGP	Asia
201	Sint Maarten (Dutch part)	SX	SXM	North America
202	Slovakia	SK	SVK	Europe
203	Slovenia	SI	SVN	Europe
204	Solomon Islands	SB	SLB	Oceania
205	Somalia	SO	SOM	Africa
206	South Africa	ZA	ZAF	Africa
207	South Georgia and the South Sandwich Islands	GS	SGS	Antarctica
208	South Sudan	SS	SSD	Africa
209	Spain	ES	ESP	Europe
210	Sri Lanka	LK	LKA	Asia
211	Sudan	SD	SDN	Africa
212	Suriname	SR	SUR	South America
213	Svalbard and Jan Mayen	SJ	SJM	Europe
214	Sweden	SE	SWE	Europe
215	Switzerland	CH	CHE	Europe
216	Syrian Arab Republic	SY	SYR	Asia
217	Taiwan	TW	TWN	Asia
218	Tajikistan	TJ	TJK	Asia
219	Tanzania, the United Republic of	TZ	TZA	Africa
220	Thailand	TH	THA	Asia
221	Timor-Leste	TL	TLS	Asia
222	Togo	TG	TGO	Africa
223	Tokelau	TK	TKL	Oceania
224	Tonga	TO	TON	Oceania
225	Trinidad and Tobago	TT	TTO	North America
226	Tunisia	TN	TUN	Africa
227	Turkmenistan	TM	TKM	Asia
228	Turks and Caicos Islands	TC	TCA	North America
229	Tuvalu	TV	TUV	Oceania
230	Türkiye	TR	TUR	Europe
231	Uganda	UG	UGA	Africa
232	Ukraine	UA	UKR	Europe
233	United Arab Emirates	AE	ARE	Asia
234	United Kingdom of Great Britain and Northern Ireland	GB	GBR	Europe
235	United States Minor Outlying Islands	UM	UMI	Oceania
236	United States of America	US	USA	North America
237	Uruguay	UY	URY	South America
238	Uzbekistan	UZ	UZB	Asia
239	Vanuatu	VU	VUT	Oceania
240	Venezuela	VE	VEN	South America
241	Viet Nam	VN	VNM	Asia
242	Virgin Islands (British)	VG	VGB	North America
243	Virgin Islands (U.S.)	VI	VIR	North America
244	Wallis and Futuna	WF	WLF	Oceania
245	Western Sahara	EH	ESH	Africa
246	Yemen	YE	YEM	Asia
247	Zambia	ZM	ZMB	Africa
248	Zimbabwe	ZW	ZWE	Africa
249	Åland Islands	AX	ALA	Europe
250	Netherlands Antilles	AN	ANT	North America
251	Kosovo	XK	XKX	Europe
252	Singapore	SG	SGP	Asia
\.

INSERT INTO public.country (id, name, code2, code3, continent)
SELECT id, name, code2, code3, continent FROM _country_seed
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('public.country', 'id'),
              COALESCE((SELECT MAX(id) FROM public.country), 1), true);
