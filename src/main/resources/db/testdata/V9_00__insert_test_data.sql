--
-- PostgreSQL database dump
--

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Data for Name: users; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.users (id, name, surname, password, email,  creator_id, modifier_id, created, modified, entity_status, login_attempts, language, currency, registration_status, invite_status) FROM stdin;
1	Pero	Škvorčević	$2a$10$CQd0ZdAerBaOmw5Akg2dpufPwQYkadFtGtTuz9nBLf3eDyqkWE/Bu	pskvorcevic@workspace.hr	1	\N	2024-03-24 16:11:17.17473	\N	ACTIVE	0	EN	USD	REGISTERED	ACCEPTED
5	Asan	Štekl	$2a$10$CQd0ZdAerBaOmw5Akg2dpufPwQYkadFtGtTuz9nBLf3eDyqkWE/Bu	astekl@workspace.hr	1	\N	2024-03-24 16:11:17.17473	\N	ACTIVE	0	EN	USD	REGISTERED	ACCEPTED
6	Ena	Olčar	$2a$10$CQd0ZdAerBaOmw5Akg2dpufPwQYkadFtGtTuz9nBLf3eDyqkWE/Bu	eolcar@workspace.hr	1	\N	2024-03-24 16:11:17.17473	\N	ACTIVE	0	EN	USD	REGISTERED	ACCEPTED
7	Lovre	Barunđek	$2a$10$CQd0ZdAerBaOmw5Akg2dpufPwQYkadFtGtTuz9nBLf3eDyqkWE/Bu	lbardundjek@workspace.hr	1	\N	2024-03-24 16:11:17.17473	\N	ACTIVE	0	EN	USD	REGISTERED	ACCEPTED
8	Georgije	Janjik	$2a$10$CQd0ZdAerBaOmw5Akg2dpufPwQYkadFtGtTuz9nBLf3eDyqkWE/Bu	gjanjik@workspace.hr	1	\N	2024-03-24 16:11:17.17473	\N	ACTIVE	0	EN	USD	REGISTERED	ACCEPTED
9	Aleksandar	Valjan	$2a$10$CQd0ZdAerBaOmw5Akg2dpufPwQYkadFtGtTuz9nBLf3eDyqkWE/Bu	avaljan@workspace.hr	1	\N	2024-03-24 16:11:17.17473	\N	ACTIVE	0	EN	USD	REGISTERED	ACCEPTED
10	Paola	Ilanić	$2a$10$CQd0ZdAerBaOmw5Akg2dpufPwQYkadFtGtTuz9nBLf3eDyqkWE/Bu	pilanic@workspace.hr	1	\N	2024-03-24 16:11:17.17473	\N	ACTIVE	0	EN	USD	REGISTERED	ACCEPTED
11	Vatroslav	Čupin	$2a$10$CQd0ZdAerBaOmw5Akg2dpufPwQYkadFtGtTuz9nBLf3eDyqkWE/Bu	vcupin@workspace.hr	1	\N	2024-03-24 16:11:17.17473	\N	ACTIVE	0	EN	USD	REGISTERED	ACCEPTED
12	Armando	Simat	$2a$10$CQd0ZdAerBaOmw5Akg2dpufPwQYkadFtGtTuz9nBLf3eDyqkWE/Bu	asimat@workspace.hr	1	\N	2024-03-24 16:11:17.17473	\N	ACTIVE	0	EN	USD	REGISTERED	ACCEPTED
14	Kruno	Marasović	$2a$10$CQd0ZdAerBaOmw5Akg2dpufPwQYkadFtGtTuz9nBLf3eDyqkWE/Bu	kmarasovic@workspace.hr	1	\N	2024-03-24 16:11:17.17473	\N	ACTIVE	0	EN	USD	REGISTERED	ACCEPTED
15	Erik	Galijan	$2a$10$CQd0ZdAerBaOmw5Akg2dpufPwQYkadFtGtTuz9nBLf3eDyqkWE/Bu	egalijan@workspace.hr	1	\N	2024-03-24 16:11:17.17473	\N	ACTIVE	0	EN	USD	REGISTERED	ACCEPTED
4	Vicka	Ćutić	$2a$10$CQd0ZdAerBaOmw5Akg2dpufPwQYkadFtGtTuz9nBLf3eDyqkWE/Bu	vcutic@workspace.hr	1	\N	2024-03-24 16:11:17.17473	2024-03-24 15:35:28.018746	ACTIVE	0	EN	USD	REGISTERED	ACCEPTED
3	Božo	Hokman	$2a$10$CQd0ZdAerBaOmw5Akg2dpufPwQYkadFtGtTuz9nBLf3eDyqkWE/Bu	bhokman@workspace.hr	1	\N	2024-03-24 16:11:17.17473	2024-03-24 15:38:59.555915	ACTIVE	0	EN	USD	REGISTERED	ACCEPTED
13	Luis	Špruk	$2a$10$CQd0ZdAerBaOmw5Akg2dpufPwQYkadFtGtTuz9nBLf3eDyqkWE/Bu	lspruk@workspace.hr	1	\N	2024-03-24 16:11:17.17473	2024-03-24 16:01:26.849376	ACTIVE	0	EN	USD	REGISTERED	ACCEPTED
2	Trešnja	Hat	$2a$10$CQd0ZdAerBaOmw5Akg2dpufPwQYkadFtGtTuz9nBLf3eDyqkWE/Bu	that@workspace.hr	1	\N	2024-03-24 16:11:17.17473	2024-03-24 16:07:17.294558	ACTIVE	0	EN	USD	REGISTERED	ACCEPTED
\.

COPY public.role_assignments (id, user_id, role_id, creator_id, modifier_id, created, modified, entity_status) FROM stdin;
1	1	2	1	\N	2024-03-24 16:11:17.17473	\N	ACTIVE
2	2	1	1	\N	2024-03-24 16:11:17.17473	\N	ACTIVE
3	3	3	1	\N	2024-03-24 16:11:17.17473	\N	ACTIVE
4	4	2	1	\N	2024-03-24 16:11:17.17473	\N	ACTIVE
5	5	1	1	\N	2024-03-24 16:11:17.17473	\N	ACTIVE
6	6	3	1	\N	2024-03-24 16:11:17.17473	\N	ACTIVE
7	7	2	1	\N	2024-03-24 16:11:17.17473	\N	ACTIVE
8	8	1	1	\N	2024-03-24 16:11:17.17473	\N	ACTIVE
9	9	3	1	\N	2024-03-24 16:11:17.17473	\N	ACTIVE
10	10	2	1	\N	2024-03-24 16:11:17.17473	\N	ACTIVE
11	11	1	1	\N	2024-03-24 16:11:17.17473	\N	ACTIVE
12	12	3	1	\N	2024-03-24 16:11:17.17473	\N	ACTIVE
13	13	2	1	\N	2024-03-24 16:11:17.17473	\N	ACTIVE
14	14	1	1	\N	2024-03-24 16:11:17.17473	\N	ACTIVE
\.


--
-- Name: role_assignments_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.role_assignments_id_seq', 14, true);


--
-- Name: users_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.users_id_seq', 15, true);


--
-- PostgreSQL database dump complete
--

