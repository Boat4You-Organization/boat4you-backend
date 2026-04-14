CREATE TABLE mmk_agencies
(
    id           SERIAL PRIMARY KEY,
    mmk_id       bigint,
    yacht_number integer,
    name         varchar(255),
    address      varchar(255),
    zipcode      varchar(50),
    city         varchar(100),
    state        varchar(100),
    country      varchar(100),
    phone        varchar(100),
    mobile       varchar(100),
    email        varchar(100),
    website      varchar(255),
    director     varchar(100)
);

COPY public.mmk_agencies (mmk_id, yacht_number, name, address, zipcode, city, state, country, phone, mobile, email,
                          website, director) FROM STDIN WITH (FORMAT CSV, DELIMITER ';');
7857;1;LUXURY & CHARTER;VIA ALEXANDER FLEMING 2/A;9100;Cagliari;;Italy;393924797325;;info@luxurychartersrl.it;https://www.luxurychartersrl.it;Francesco Santi
7475;1;Centro Italia Vela;Viale delle Milizie, 104;192;Roma;;Italy;+39 6 83970646;;info@centroitaliavela.it;www.centroitaliavela.it;Massimo Francesco Schina
7474;3;BROD Yachting;Akar Caddesi I Towers 3/23 Sisli;34100;Istanbul;;Turkey;905333608676;;info@brodyachting.com;www.brodyachting.com;Serhat Buyukbayrak
7473;10;Sailing Zorba;KEAS 9;18541;PIRAEUS;;Greece;+48 602 63 63 55;;agn.sieradzka@gmail.com;www.sailingzorba.com;Grzegorz Bieluczyk
7471;1;GreeceXperience;Gouriana Agnanton;47043;Arta;Epirus;Greece;+30 2681 121423;+30 698 0622444;margonis@mindwork.com.gr;www.greecexperience.gr;Dimitrios Margonis
7470;1;Hispania Mare;C/ Desempedrada, 11;45003;Toledo;Toledo;Spain;(+34) 618 25 62 27;;antonio@hispaniamare.com;hispaniamare.com;Fernando Tomas Peralta Roldan y Antonio Rodriguez Holgun
7463;2;Almarea Charter;via del Bersagliere 53;90143;Palermo;;Italy;+39 329 0659621;;bookingmanager@almareacharter.com;;Marco Mancini
7462;5;Oceantrail Odyssey;Urb. Terracos do Rio 14 3D;3030-770;Coimbra;;Portugal;+351 91 391 27 57;;pedro@oceantrail-odyssey.com; www.oceantrail-odyssey.com;Pedro Carradinha
7461;1;Adriatic Sense;Ulica Domovinskog rata 60;21000;Split;;Croatia;+385 98 757 536;38598757536;info@adriaticsense.com;https://www.adriaticsense.com;Emil Tomasevic
7460;1;Optimum Bau sp z o.o. ;Gostchorze 54;66-600;Krosno Odryanskie;;Poland;+48 532 440 090;;kontakt@katamaranrejsy.pl; www.katamaranrejsy.pl ;Jacek Barelkowski
7458;1;Aeolus Yachting;;57019;Limenaria;;Greece;+30 6997116056;;info@aeolusyachting.com;www.aeolusyachting.com;
7452;1;Silver Wave Yacht Charters;69 Marsden Road;200;Paihia;;New Zealand;+64 21 178 2328;;info@silverwave.co.nz;www.silverwave.co.nz;Scott Farrand
7451;2;Blue Oceans Yacht Charter;Szlak 77/222;31-153;Krakow;;Poland;+48 572-572-535;;office@blue-oceans.pl;;Mark Skorecki
7444;2;Sea Land Yacht Charter ;Strada Farini 18;43121;Parma;;Italy;390521508275;;info@sealand.it;www.sealand.it ;Angelo
7442;2;Arctic Ice Sailing;Leirvågveien 121;9426;Bjarkøy;;Norway;+47 908 26 750;;sales@arcticicesailing.com;www.arcticicesailing.com ;Arne Kvaale
7432;1;Coral Motor Yachts;Dubrovacka 65;21000;Split;;Croatia;00 385 99 475 1700;;info@coralmotoryachts.com;www.coralmotoryachts.com;Davor Raguz
7431;1;Loxarel;Can mayol s/n;08735 Vilobi del Penedes;Barcelona;;Spain;+34 654 510 489;;mitjans@loxarel.com;;Joseph Mitjans Buxens
7429;1;Trimarancruise;Global Interactive TV;L9990;Weiswampach;;Luxembourg;+33 6 81 18 80 80;;contact@trimarancruise.com;trimarancruise.com;Bruno Reverso
7425;6;Latitudine 38 SRL;Via Vincenzo Spinelli 1;90144;Palermo;;Italy;+39 376 2433986;;booking@latitudine38.com;latitudine38.com;Simone De Capitani
7424;3;Magia Charter SRL;Via Galvani 24;59100;Prato;;Italy;+39 3758767301;;info@magiacharter.com;https://www.magiacharter.com/;Massimo Bianchi
7409;1;Zingaro Sail;Via Molinello, 21c;91014;Castellammare del Golfo;Sicily;Italy;+39 393 4823998;;zingarosail@gmail.com;zingarosail.com;Giacomo Cascio
7403;2;Caymangroupcharter;Via Notarbartolo 9/DO;90141;Palermo;;Italy;+39 3936101443;+39 3936101443;booking@caymangroupcharter.it;https://www.caymangroupcharter.it;
7392;8;Dock & Roll;Akti Poseidonos 2;18531;Piraeus;;Greece;+30 211 4036438;;dockandrollyachting@gmail.com;www.dockandroll.eu;Markos Abatis
7391;1;HYLOKA Charter;al. Wilanowska 67B/9;02-656;Warszawa;;Poland;+48 602 609 982;;svskalpel2@gmail.com;;Mikolaj Wroniszewski
7390;1;Perigiali Yachting MCPY;L. Syggrou 196;17671;Kallithea;;Greece;+30 694 799 9032;;kyamalidis@gmail.com;;Kostas Giamalidis
7388;3;Aquila Shipping;130 Ypsilantou street;18532;Piraeus;;Greece;+302104101053-4;;operations@aquilashipping.gr;www.aqrentarib.gr;Panagiotis Kalogeropoulos
7387;8;Bijoux d.o.o.;Cesta prekomorskih brigada 12;52100;Pula;;Croatia;385992725925;385992725925;petra.susak@gmail.com;;Petra Susak
7385;1;Mita Yachting;Poljicka ulica 5;10000;Zagreb;;Croatia;+43664 5423849;;michael.tatschl@mbat.at;;Michael Tatschl
7382;2;NAS YACHTS;Anatolikis Thrakis 18;68132;Alexandroupolis;;Greece;+30(694)823-1703;+30(694)823-1703;nasyachts@gmail.com;www.northaegeansailing.com;Athanasios Didaskalou
7371;1;Pleamar 24 Nudos SL;Marina de Denia, edificio D, local 2;3700;Denia;;Spain;+34 697 841 058;;m.zapatero@pleamar.net;dynastyyachts.com;
7368;1;Cruising Services Giuseppe Castellano;Via dei Marinai 48;7020;Golfo Aranci;;Italy;393357070339;;wcast@tiscali.it;;Giuseppe Castellano
7366;1;Voyager Sailing MCPY;64 Alexandras Avenue;11473;Athens;;Greece;+30(697)755-8855;;Voyagersailing77@gmail.com;;Konstantinos Stathopoulos
7362;3;A1Boat Charters;The Strand;SLM1025;Sliema;;Malta;+356 7777 3825;;chartersa1boat@gmail.com;https://a1boatchartersmalta.com/;Michael
7361;1;Seasun Sailing Charter;263 Rue Paradis;13006;Marseille;;France;+33 6 06 41 70 07;;th.a.dortoli@gmail.com;www.seasunsailingcharter.com;Thomas Angles d'Ortoli
7360;2;Nave  Yachting;Mantos Mavrogenous 13;15238;Athens;;Greece;306974062621;;charter@naveyachting.com;https://naveyachting.com;Dimitrios Milios
7355;1;Funny Boats d.o.o.;Mikulandre 144;22000;Sibenik;;Croatia;491722905905;;msk.kirsch@gmail.com;;Stefan Kirsch
7353;1;Aberton Yachts;230, 230 Works Business Centre, 2nd Floor, Eucharistic Congress Road;MST 9039;Mosta;;Malta;306946525252;;marina.schizas@abertonyachts.com;https://abertonyachts.com/;
7348;1;Lefkas Yachting;Pontoirakleias 41;45222;Ioannina;;Greece;+30(698)574-0686;;kougioumle@yahoo.gr;;Anastasia Skourti
7344;3;Caledonia Sailing;14 Upland Road;SE22 9EE;London;;United Kingdom;+44 754 024 6375;;info@caledonia-sailing.com;caledonia-sailing.com;Harald Spachinger
7342;1;LG Yachting;T.Kornarou 4;65404;Kavala;;Greece;306972721533;;lgyachting.l@gmail.com;www.lg-yachting.com;Lefteris Tzoumas
7341;5;Viravira;Atatürk Mahallesi, Çitlenbik Caddesi, No:4 Tadım Plaza, Kat:7, Ataşehir;34959;Istanbul;;Turkey;+44 203 514 8947;;booking@viravira.co;https://www.viravira.co/tr;Baran Yildirim
7335;1;Buyers Club;Strada di Settimo 405;10156;Torino;;Italy;+39 335 8303094;;info@buyersclubitalia.com;buyersclubitalia.com;Costantino Imperatore
7333;12;Southwest Boats;Edifcio Astrolbio Bloco 24  Loja n2;8600-780;Lagos;;Portugal;+351 913 002 025;;info@southwestboats.pt;https://southwestboats.pt/;Pedro Ramos
7332;10;Anka Yachting;Sarıana Mah. M. Münir Elgin Bulvarı T-03 BKO Sahası;TR-48700;Muğla Marmaris ;;Turkey;0043 664 462 18 36;;office@ankayat.com;www-anka-yachting.com;Canan Eraksan
7330;1;Aquable;Ammouliani port;63075;Ammouliani;;Greece;306975534713;;aquable.vip@gmail.com;https://www.instagram.com/aquable_halkidiki_yachting/;Simeon Petrov
7327;5;Sailamor Turizm;;;Fethiye;;Turkey;;905327230112;can.bilgin@sailamor.com;www.sailamor.com;Can Bilgin
7326;3;Guldeniz Sailing Cruises;Hüseyin Cilek Sokak 33/1;48400;Bodrum;;Turkey;+31 6 2364 1228;;info@guldenizsailing.com;https://guldenizsailing.com/en/;Nedim Cetin
7319;4;Coral Nautica;Put Mulina 36A,;21220;Trogir;;Croatia;385996600269;385996600269;Coralnautica@gmail.com;;Mladen Marceta
7317;4;Skiathos Yachts;New Port Skiathos;37002;Skiathos;;Greece;+30(697)363-3961;;info@skiathosyachts.com;www.skiathosyachts.com;Dimitris Toptsis
7316;1;WakaWaka Charter;Via Della Tecnologia 19;7041;Alghero;;Italy;+39 393 9026457;;charterbioikos@gmail.com;www.charterbioikos.it;Antonio vacca
7309;8;CD Yachting Mykonos;Naksou 28;16122;Kesariani;;Greece;+30(697)277-9491;;info@cd-yachting-mykonos.com;https://www.cd-yachting-mykonos.com/;Davris Christos
7307;2;Romygka Sailing;Korai 103;38333;Volos;;Greece;+30(697)709-4237;;sailingromygka@gmail.com;www.romygkasailing.gr;Ilias Rousakos
7305;2;Navy Blue;Th. Aninnou 12;26332;Patras;;Greece;+30(210)700-1790;;support@navy-blue.com;www.navy-blue.com;Charis Dimopoulos
7302;2;Bayan Boat Management & Charters;Rua João Infante n130 Lote 10 1A;2750-384;Cascais;;Portugal;+351-91-448-2516;;leonorsp@bayan.pt;https://www.bayan.pt;José Gonçalves Gomes
7292;2;Greko Million;Zaimi 26;;Palaio Faliro;;Greece;+30 6988587784;;info@grekomillion.com;www.grekomillion.com;Omiros Papastergiou
7291;9;Independent YachtOwners Team;Kärntnerstrasse 64;6020;Innsbruck;;Austria;+43 6604642899;;info@worldwidesailing.at;www.worldwidesailing.at;Klaus Mayer
7280;1;Sailing Turtles;Roumelis 28;15233;Athens;;Greece;+30(694)306-9967;;info@sailingturtles.gr;;Ilias Kakavogiannis
7273;5;All Blue;Lampelet 22;18541;AtticA;;Greece;+30 694 8420 323;;allblueyachting@gmail.com;www.all-blue.gr;Αγγελική Μισαηλίδου
7271;2;Azorean Dream Yacht Charter;Rua Mestre Maduro Dias n 44;9700-517;São Bartolomeu Regatos;;Portugal;+351-91-255-3039;;azoreandream@gmail.com;www.azoreandream.com;Ricardo Mendonça
7269;1;Astraea Yacht;15 Aristeidou Str.;18531;Piraeus;;Greece;359899790055;;booking@astraeayacht.com;http://www.astraeayacht.com;Radoslav Dinchev
7267;4;Passion Yachting;A.Papandreou 20;16675;Glyfada;;Greece;306946920840;;info@passionyachting.gr;www.passionyachting.gr;Roufiktos Dionysios
7257;1;Paf Sailing ;Piazzale degli eroi 16;136;Roma;;Italy;393471236961;;pafsailingsrl@gmail.com;;Alessandro Paoloni
7256;6;Bali Catamarans Charter;CD PORT PIN ROLLAND;83430;ST MANDRIER SUR MER;;France;+33 (6) 09 04 24 47;;Balicatamaranscharter@catanagroup.com;;Aurelien Poncin
7250;1;Callisto Yachting;Narkissou 45 Psychico;15452;Athens;;Greece;306947150459;;info@callistoyachting.com;www.callistoyachting.com;Ria Lamprinoudi
7246;14;Loc Voile Armor;Esplanade du Nouveau Port;22410;Saint-Quay Portrieux;;France;+33 2 96 70 92 94;+33 6 23 57 94 99;contact@locvoilearmor.com;https://www.locvoilearmor.com;Vincent Talbourdet and Angélique Roussel
7245;1;Zest Yacht Charter;Eleftherias Avenue 11;17455;Alimos;;Greece;+44 (0) 7973389135;;info@zestyachtcharter.co.uk;www.zestyachtcharter.co.uk;Gye Dibben
7244;1;Honey Sailing;Agiou Dimitriou 8;15126;Maroussi;;Greece;+30 693 646 0035;;info@honeysailing.gr;www.honeysailing.gr;Maria Kapsi
7239;2;Morii Yachting;Rijecka 3;21000;Split;;Croatia;+38599 200 4237;;booking@morii.eu;https://morii.eu;Jurko Anzulovic
7228;4;Spicy Sailing;Kosmira Ioannina;45500;Ioannina;;Greece;+30(697)021-0302;;pkalfin@gmail.com;;Peter Kalfin
7225;1;Derin Deniz Yachting;Sariana Mahallesi 8. Sokak no:6/4;48700;Marmaris;;Turkey;+90 531 368 8348;;contact@derindeniz.info;https://derindeniz.info/;Ahmet Çevik
7202;1;ALION Charter;CL POLIGONO CALA DEL PINO URB BIRSAMAR 18;30380;MANGA DEL MAR MENOR;;Spain;+34 722 54 33 01;;shelby.joni@gmail.com;https:\\www.alionyacht.com;Tomilov Evgenii
7201;1;Lady Lota;Ravan 2 br. 13;21400;Mirca;;Croatia;385915346656;;sinisa.mazar333@gmail.com;;Sinisa Mazar
7196;4;4S Sailing;Arapaki 97;17675;Kallithea;Attica;Greece;+30(216)939-0812;+30(698)712-3850;charter@4ssailing.com;https://4ssailing.com/;Theodore Kontargyris
7193;4;DaDi Rent;Via San Francesco 17;57123;Livorno;;Italy;+39 3701564317;393701564317;dadi.rent@gmail.com;https://www.dadirent.it/;Di Rocca Filippo
7192;4;Dimonika Marine;Vinogradarska 10;20236;Mokošica;;Croatia;+385 91 6001 201;;info@garitransfer.com;www.garitransfer.com;Niksa Perovic
7191;1;Ece Yachting;Karagozler Mah. Fevzi Cakmak Cad. No:49 -Fethiye;48300;Mugla;;Turkey;+90 252 614 40 30;;info@eceyachting.com;www.eceyachting.com;Cenk Ammiler
7183;4;Island Castaways;Eden Island Marina;0;Victoria;;Seychelles;+248 281 3279;;info@islandcastaways.sc;http://www.islandcastaways.sc;Shantial Dhanjee
7179;11;Charter Service Punat;Puntica 7;51521;Punat;;Croatia;385913222555;;vladimir.hunjadi@gmail.com;www.charterservicepunat.com;Vladimir Hunjadi
7171;0;Kuhnle-Tours;HAFENDORF MUERITZ 17248;17248;Rechlin;;Germany;+49 171 3623488;;onlinebooking@kuhnle-tours.de;www.kuhnle-tours.de;Harald Kuhnle
7163;2;TheBokun;Put Nemire 43a;21310;Omis;;Croatia;+385 99 444 1314;;info@thebokun.com;www.bokunagency.com;Anita Misetic
7160;2;Campania Overland;Corso Umberto 479;80013;Casalnuovo di Napoli;;Italy;8118905591;+39 351 7978285;booking@campaniaoverland.com;https://www.campaniaoverland.com/;Salvatore Cerbone
7158;1;Canarian Fun;CALLE MUELLE DE LOS CRISTIANOS, S/N PUERTA 8;38650;ARONA, Canary Islands;;Spain;+34 639 56 38 89;;navalcanserviciostecnicos@gmail.com;www.ladysunshine.es;Vasilij Sergejev
7153;1;QSail;Stara Knezija 20;10000;Zagreb;;Croatia;358405249292;;antero.salo@qgroup.fi;www.qsail.fi ;Antero Salo
7152;1;Sailing Sicily Travelling;Corso 6 Aprile, 264;91011;Alcamo ;Sicily;Italy;390924040024;;sales@sicilytravelling.com;www.sailingsicilytravelling.com;Nicolo Rubino
7151;2;Bella Yachting;Akarca Mh No 156aa Blok A - Fethiye;48300;Mugla;;Turkey;905359762074;;charter@bellayachting.com;www.bellayachting.com;Mehmet Cesur Sabahlar
7149;1;Ionian Serenity Sailing;Propontidos 6;48100;Preveza;;Greece;35799540184;;accounting@ionianserenity.com;https://ionianserenity.com;Ilias Maragkoulas
7148;1;The Dreamer;Via Confalonieri 78/A;31015;Conegliano;;Italy;393939995566;;thedreamersrl@hotmail.it;;Andrea Fenti
7142;1;Starboard Charter;Apolpena 0;31100;Lefkada;;Greece;302641888013;;hello@star-board-charter.com;https://star-board-charter.com/;Dawn Murphy
7128;3;MARIS CHARTER;Put Pudarice 11 A;23000;Zadar;;Croatia;+385 99 480 8016;;marischarterzadar@gmail.com;https://marischarterzadar.com/;Barbara Markić
7127;2;La Mouette;Ul.Stanislawa Lema 32/53;31-571;Krakow;;Poland;33744955998;;info@lamouette.eu;www.lamouette.eu;Alex Polovyna
7120;3;Lux Nautic;Munat 62, Ližnjan;52204;Pula;;Croatia;385995419649;+385 95 871 990;info@luxnautic.com;https://luxnautic.com/;Luka Jelenković
7117;2;EBcharter;Via Venaria 41;10072;Caselle Torinese ;;Italy;39335403427;;booking@ebcharter.com;https://www.ebcharter.com/;Ercole Blandino
7110;1;Adria Voyage;Jurdani 98;51211;Matulji;;Croatia;+385 996 720 404;;beskhr1@gmail.com;;Mykola Beskorovainyi
7109;1;The Anchor Mediterranean Yachting;Etiler district Evliye Celebi st No: 23/106 Muratpasa;7000;Antalya;;Turkey;905349199007;905349199007;info@anchoryachting.net;https://anchoryachting.net;Yasin Aygun
7106;10;SailWithUs;Gagernstrasse 8;60385;Frankfurt am Main;;Germany;4917658887261;;info@sailwithus.de;www.sailwithus.de;Carl Grubert
7105;1;Nibo Nautika;Gundulićeva ulica 15;10000;Zagreb;;Croatia;385957377816;;bozovic9@icloud.com;https://www.nibonautika.com;Nikola Bozovic
7104;1;SIMO Yachting;Senoina 12;51000;Rijeka;;Croatia;385957743347;;office@simo-yachting.com;www.simo-yachting.com;Szilard Molnar
7103;2;Eolian Gulet Boats;Via XXVII Luglio is. 195 n 38;98123;Messina;;Italy;393929458661;;stracuzzidaniela@libero.it;https://www.eolianguletboats.it/;Daniela Stracuzzi
7095;1;Emerald Yachting;Strada Secondaria A2 N318;4014;Pontinia;;Italy;+ 39 3807577878;;info@emeraldyachting.com;www.emeraldyachting.com;Gabriel Lentini
7093;1;Sailing Appa;Ece Saray Marina;48300;Mugla;;Turkey;+90 531 426 91 44;;sailingappa.med@gmail.com;www.sailing-appa.com;Asli Basarir Maylone
7091;2;Hang Loose Catamarans;Diputación 238-242, 6º 3ª;8007;Barcelona;;Spain;(+34) 661 521 222;;carlos@alquilercatamaran.es;https://alquilercatamaran.es/;Carlos Viñals
7086;1;L.A. Yachting;Esperou 19;17561;Athens;;Greece;+48 668-890-650;;kimmyachting@gmail.com;;Andrzej Latusek
7085;1;Artemisia Sailing;Foça Mahallesi 853.Sokak No:84 Kapi No:1, Fethiye;48300;Mugla;;Turkey;905336988936;;semiherten@hotmail.com;www.sailingartemisia.com;Recep Semih Erten
7072;1;Boat Rentals Dubai;H.H.SH SAUD BIN SAQAR, Al Muteena 39-4;;Dubai;;United Arab Emirates;491721934295;;stefangeisthardt@yahoo.de;www.instagram.com/boat_rentals_dubai?utm_source=qr&igsh=YWd5YWtkaDFqem5z;Robert Geisthardt
7071;1;Wrungel 505;Rade Končara 36;52450;Vrsar;;Croatia;385916158109;;reception@wrungel.com;www.wrungel.com/sailing;Kseniia Bredneva
7070;7;VMG Yacht Charters;740 Route Saint Joseph;73260;Les Avanchers-Valmorel;;France;+33 7 63 98 69 69;;isabelle@vmgyc.com;https://www.vmgyc.com;Thierry Ote
7069;1;JGonSea;37 Glebe Road;SW13 0DZ;London;;United Kingdom;447771771833;;jamesgellatley@gmail.com;www.jgonsea.co.uk;James Gellatley
7065;4;Blue in White: Sailing & Eudaimonia;Veneti Avenue & 12th, Anavyssos;19013;Athens;;Greece;+30(694)649-3008;;charter@blueinwhite.com;https://blueinwhite.com/;Stelios Zompanakis
7057;1;Yacht Charter Adria;Začretska ulica 2a;10000;Zagreb;;Croatia;+38 69 66 00 77;+38 69 66 00 77;info@yacht-charter-adria.com;https://yacht-charter-adria.com;Christian Weiss
7054;3;Seatime Yachting;Villaggio Porto Cervo Marina 11/C;7021;Arzachena;;Italy;+39 347 18 90 139;+ 39 347 1890139;seatimeyachting@gmail.com;https://www.seatimeyachting.com;Maria Pina Muresu
7052;4;Jet Boat Cruises;Galini;84300;Naxos;;Greece;+30(697)7247775;;jbcnaxos@gmail.com;www.jetboatcruises.com ;Stelios Kritikos
7050;3;Absolut Charter;Put Dragulina 62g;21220;Trogir;;Croatia;+38591 117 0007;;info@absolut-charter.com;www.absolut-charter.com;Marija Goleš
7040;3;Sailing Boat Service;Via Notarbartolo 10;90141;Palermo;;Italy;393283363770;;info@sailingboatservice.com;www.sailingboatservice.com;Luigi Iacono Isidoro
7039;2;Jacky Charter;Ul. Dr. Frane Franića, Biskupa 76;21214;Kastel Kambelovac;;Croatia;385998312295;;office@jackycharter.com;www.jackycharter.com;Renata Gruszecka
7037;4;Morgana Sailing;Via Esterna Volito 22;73040;Castrignano del Capo;;Italy;3205747192;;morganasailing@gmail.com;www.morganasailingcharter.com;Antonio Boccadamo
7035;2;Nautica Deluxe;4B ESC A PTA 16  Paiporta;46200;Valencia;;Spain;34620768291;;paola@nauticadeluxe.com;www.nauticadeluxe.com;Paola De Luca
7032;2;Charter Patricia Makarska;Vukovarska 86;21300;Makarska;;Croatia;+385 95 507 5607;;patriciagrabner1510@gmail.com;www.charter-makarska.com;Petar Čaljkušić
7031;1;SailBliss;Monastiriou 13;54629;Thessaloniki;;Greece;306946618224;;info@sailbliss.com;www.sailbliss.com;Antonis Podiotis
7025;4;Joy Yachting;Inlice Mahallesi Tepe Sokak No:31/1A;48310;Fethiye;;Turkey;441707712255;;info@joyyachting.com;www.joyyachting.com;Ozgur Nayir
7021;4;Corfu Sailor;Eleftheriou Benizelou 18B;49100;Corfu;;Greece;306936102075;306943841111;corfusailor360@gmail.com;;Basilis Konstantinos
7020;7;A&C Yacht Charter;Port de Plaisance C/O A&C Yacht Broker BD Allegre;97290;Le Marin;;Martinique;33962665681;;contact@acyachtcharter.com;www.acyachtcharter.com;Frédéric Thouroude
7013;2;Luxury Escape;10 Sirou;38223;Volos;;Greece;+306946234 008;;info@luxuryescape.gr;www.luxuryescape.gr;Panos Servetas
7009;1;Trade Shop;Via Toledo 406;80134;Napoli;;Italy;393248216321;;newtrade@traesio.it;www.traesio.it;Alberto Traesio
7003;5;Nostos Mykonos Yachts;Spyrou Lekka 5;15124;Marousi;;Greece;+30(698)225-5111;;hello@nostosmykonos.com;www.nostosmykonos.com;Nik Kantas
7001;0;Montemarin Montenegro;Putnicki terminal;8500;Bar;;Montenegro;+382 67 069 794;;info@montemarin.me;www.montemarin.me;Mihailo Vukic
7000;1;Latvian Cruise Yachts Association;Alantu iela 2c;LV-1030;Riga;;Latvia;37129162919;;sail@cruisinglatvia.lv;https://cruisinglatvia.lv/;Ronalds Blums
6998;1;Star Sailing Club;22/35 Moo. 3 Wichit;83000;Phuket;;Thailand;66890795176;;starsailingclub@gmail.com;;Anna Massold
6992;1;Pure Bliss Charter Menorca;Calle Arces 37, puerta 16;29018;Malaga;;Spain;+34 668 512 682;;info@pureblisschartermenorca.com;www.pureblisschartermenorca.com;Kalem Pebbles Mc Quillan
6991;1;DUDYACHTING;Ifigienias 55;19016;Artemida;;Greece;306977019881;;dudyachting@gmail.com;www.dudyachting.com;Dudkiewicz Piotr
6988;1;Sailing Libar;M. Gupca 240c;49210;Zabok;;Croatia;3850915041791;;info@sailinglibar.com;www.sailingyachtlibar.com;Zoran Zigman
6987;1;Mykonos Catamaran Group;Kantiana 26;71409;Heraklion;;Greece;+30 698 317 5780;;info@mykonoscatamarangroup.com;www.mykonoscatamarangroup.com;Georgios Iatridis
6985;1;Solrise Cruises;M. Ranfas 3/FL;20214;Male;;Maldives;9609896668;;info@solrise.mv;https://solrise.mv;Mariyam Ali Didi
6981;1;Catamaranes Menorca;C/ Republica Argentina, 80;7760;Ciutadella de Menorca;;Spain;34638253151;;catamaranesmenorca@gmail.com;https://www.catamaranes-menorca.com;Bernat Casasnovas Barber
6979;1;Yacht Sense;Siggrou Avenue 22;11742;Athens;;Greece;+30(697)3432752;;sense.yacht@gmail.com;https://www.yachtsense.gr/;Eleftheria Protopsalti
6977;1;Yachts Athens;D. Gounari 227;16674;Glyfada;;Greece;+30(694)693-3294;;charter@yachtsathens.com;www.yachtsathens.com;Vasileios Paraskevopoulos
6976;3;Marina Kras;Turtijanska 4;52100;Pula;;Croatia;38598531679;;info@marina-kras.hr;https:\www.marina-kras.hr;Fabijan Mihovilović
6975;24;Boutique Ocean Travel - Vito Nautika;Werner-Heisenberg-Straße 8;85254;Sulzemoos / München;;Germany;4989746749750;;booking@vitonautika.com;www.boutique-ocean-travel.com;Thomas Borsutzky
6973;1;Tipejyas Yachting;3 Antheon street;15351;Kantza;Attica;Greece;+30(693)244-1065;;tasos@firststeps.gr;www.tipejyasyachting.com;Tasos Chatziliadis
6972;1;Zenith Cruise;Piazza Diaz 1;20900;Monza;;Italy;393519010117;;info@zenithcruise.com;https://www.zenithcruise.com;Gianturco Paolo
6967;1;Orion Charter;Calle Almería 14, 2doA;28320;Madrid;;Spain;34605835940;;info@orioncharter.com;https://www.orioncharter.com;Luis Madriz
6966;1;WindTime Sailing;ul. kosciuszki 10;48-300;Nysa;;Poland;+48 607 617 797;;kuba.wierzbowski1@gmail.com;www.windti.me;Jakub Wierzbowski
6965;1;Indian Ocean Cruises;c/o Chartist associates;1713-01;Beau Bassin;;Mauritius;33623128670;33623128670;jm@seaspiritcruises.com;www.ioc-catamaran.com;JEAN-MARC SEINE
6961;3;Aqua Marina and Yachting;Gospostina Vidikovac no 8;85310;Budva;;Montenegro;905320613277;;deryaozguven@gmail.com;www.aquayachtingmontenegro.com;Mustafa Hilmi Ozguven
6959;6;Apolon Plovila;Put Gvozdenova 283;22000;Šibenik;;Croatia;385913101512;;info@apolon-plovila.com;www.apolon-plovila.com;Ivan Maretić
6958;2;Sailing with Smile;Iroon Polytechneiou 10;31100;Lefcada;;Greece;+30 698 72 966 82;;yacht.mentoring@gmail.com;https://www.lp.vp4.me/tbzi;Asaf Shekler Itamar sapir
6955;13;Premium Yachting;ul. Czerniowiecka 9/5;02-705;Warszawa;;Poland;48600034174;;czartery@premiumyachting.pl;www.czartery.premiumyachting.pl;Kasper Orkisz
6948;2;Ray Yachting;45 Nik. Plastira;54250;Thessaloniki;;Greece;306937100708;;info@rayyachting.com;www.rayyachting.com;Gerry Dimitratos
6943;2;Zante Adventure Yachts;Lithakia;29100;Zakynthos;;Greece;+31 6 12353975;;zanteadventureyachts@gmail.com;www.zante-adventure-yachts-luxury-boat-excursions.com;Spiros Bozikis
6935;1;Maya Yacht Rental;Artemidos 0;84600;Mykonos;;Greece;306980476200;;booking@maya-yacht.com;https://www.maya-yacht.com/;Emmanouil Rousounelos
6934;4;Global Yachting;Avenida de Francia 14;46023;Valencia;;Spain;34963301303;34626430866;r.garcia@corporateyachting.es;https://valenciayachtingcenter.es/;Ramon Garcia Serra
6930;3;Caldera Yachting;Trizonion 1;16346;Athens;;Greece;302286023000;;charters@calderayachting.gr;www.calderayachting.gr;Ioannis Matthaios
6928;1;Kefalonia Day Trips;Svoronata;28100;Argostoli Kefalonia;;Greece;306934027000;;info@kefaloniadaytrips.com;www.kefaloniadaytrips.com;Ilias Tsigantes
6927;1;Christeol;27 Av De Vallauris;6400;Cannes;;France;33620337875;;christeol.catamaran@gmail.com;www.taplink.cc/christeol;Guazzoni Olivier
6925;2;Sunward Sailing;Antinoros 12;11634;Athens;;Greece;+447980 876713;;sunward.sailing@gmail.com;www.sunwardsailing.com;Adrian Worrall
6921;5;Mavi Yolculuk;Akarca Mah. Mustafa Kemal Bulv. No:161/1 Ic Kapi No: 2;48300;Fethiye/Mugla;;Turkey;+90 532 446 11 91;;info@maviyolculuk.org;www.maviyolculuk.org;Çaglar Birituna
6919;1;BabaSails Yachting;Kiouptsidou 11;55133;Kalamaria;;Greece;306934854854;;info@babasails.eu;https://www.babasails.eu;Symeon Ziogou
6911;2;Free your Mind Experience;CRTN 340, La Vega s/n;11380;Tarifa;;Spain;34608838487;;info@freeyourmindexperience.com;www.freeyourmindexperience.com;Tanja Rosenkranz
6902;1;Dreams Charter;Calle Travesia del Mar N1;7815;Sant Antonio de Portmasny;Ibiza;Spain;34664112188;;info@dreamscharter.com;https://www.dreamscharter.com;Joaquin Fullana Arenas
6900;2;Genesi Charter;Viale A. Gramsci, 21;80122;Naples;;Italy;393246348891;;genesicharter@gmail.com;www.genesicharternapoli.it ;Luca Scoppa
6899;1;White Pearl Sailing;Kallidromiou 7;10680;Athens;;Greece;+30(694)0103044;;info@whitepearlsailing.com;https://www.whitepearlsailing.com;Anestis Filopoulos
6893;1;Emme Sail;Via Giuseppe Gioacchino Belli n. 86;193;Rome;;Italy;393473178152;;emmesailsrl@gmail.com;https://www.emmesail.com;Andrea Magnanimi
6891;2;Karaban Yachting;Kandici 15;23205;Bibinje;;Croatia;3850993505282;;karabana@net.hr;;Ante Karaban
6890;1;Sulleonde;Z. I. Comparto 11;88046;Lamezia Terme ;CZ;Italy;393290563415;;info@funcharter.it;;Giampaolo Carnovale
6889;10;Otter Easy Houseboats;Marktstraat 4;6049BA;Herten;;Netherlands;31615095489;;info@otter-ehb.com;http://otter-ehb.com/;Seppo Hensgens
6883;1;Sunset Yacht Space 2022 SL;Avinguda De Joan Miro 327;7015;Palma;;Spain;34626516371;;syscharters@gmail.com;https://www.sunsetyachtspace.com;Pedro Palmer
6879;6;Mucci Boat;Via del Tridente 52;48;Nettuno (Roma);;Italy;645663638;393661634684;info@mucciboat.it;https://www.mucciboat.it;Luca Di Gregorio
6877;1;Perla Yacht;Tsoga 3;31100;Lefkada;;Greece;306994417883;;info@perlayacht.gr;www.perlayacht.gr ;Endrit Pazi
6876;4;Eva Sails Tenerife;Management company - Marina San Miguel Urbanizacion, Amarila Golf;S/N 38639;Santa Cruz Tenerife;;Spain;34677886006;;eva.sails.tenerife@gmail.com;www.evasails.com;Leonid Holub
6871;1;Boomyelken Murat Yasar Yatcilik; D-Marin Turgutreis, Gazi Mustafa Kemal Bulvari;48960;Mugla;;Turkey;905325691122;905325691122;murat.yasar@yahoo.com;www.boomyelken.com;Murat Yasar
6869;1;Foresail d.o.o.;Stjepana Radica 41;22000;Šibenik;;Croatia;48605476458;;foresail2013@gmail.com;http://foresail.pl/en;Mateusz Szalkowski
6868;1;Astir Yachts Cruising;Iteas 1;16674;Glyfada;;Greece;306947636172;;amillayacht@gmail.com;;Theodore Lampiris
6867;1;Dragonfly Trimaran Charter;Wimmerstraße 3/22;A-4060;Leonding;;Austria;436642020686;436642020686;office@dragonflycharter.eu;https://www.dragonflycharter.eu;Peter Gattinger
6866;1;Expedition Charter;Dynamic Sunset Invex SL;8037;Barcelona;;Spain;+34 617 621 384;;liam@expeditioncharter.com;https://www.expeditioncharter.com;Jeremy Shannon
6861;1;What a Wonderful Tour;Via Italia 67;19020;Bolano;;Italy;3929899756;;gcamug@gmail.com;https://whatawonderfultour.it;Camuglia Gian Paolo
6855;4;Adria Luxury Boats;Liburnijska 7a;51414;Ičići;;Croatia;091 72 71 866;;info@alb.hr;https://alb.hr/;Vjekoslav Vahtarić
6853;4;KappaBeta Yachting;Ioanninon 23;12137;Athens;Peristeri;Greece;306981807991;;charter@kappabeta.gr;www.kappabeta.gr;Dimitris Kakatsios
6849;0;Canarias Sailing;Of. Puetro Calero N.0. Piso 2 Edf. Antiguo Varadero;35571;Yaiza;Las Palmas De Gran Canaria ;Spain;393923598735;;info@sailingcanarias.es;www.sailingcanarias.es;Roberto Corpina
6841;1;Sea Soul Yachting;Viška 16;21000;Split;;Croatia;38598212438;;info@seasoul-yachting.com;www.seasoul-yachting.com;Ana Rončević
6837;3;Nika Cruise;Akarca Mahallesi 893, sokak no 16, Ic kapi No 1;48000;Fethiye;Mugla;Turkey;905330542300;;nikacruise@gmail.com; www.nikacruise.com;Kerim Aslan
6832;7;Anemos Yachting;39, Thoukidiou Str.;17455;Alimos;;Greece;302109850052;306932644166;info@anemos-yachting.gr;http://www.anemos-yachting.gr;Alexis Kontoleon
6830;72;Croatia Sailing Academy;Kučine 92;52203;Medulin;;Croatia;+385 99 712 7772;;crew@crosailingacademy.com;https://www.crosailingacademy.com;Toni Brkić
6828;8;Epidaurum & Dubrovnik Boat Adventure;Šetalište Žal 31;20210;Cavtat;;Croatia;385994359102;;info@boat-dubrovnik.com;www. boat-dubrovnik.com ; Boris Boradović
6824;16;Yates Mallorca;Calle Porto Pi 4a;7015;Palma;;Spain;34971401883;;charter@yates-mallorca.com;https://www.yates-mallorca-charter.de/en/;John Rossbach
6819;1;Sailing Affairs;Agio Dimitriou 41;18546;Pireas ;;Greece;+49 176 41467942;;mail@sailingaffairs.de;https://www.sailingaffairs.de;Mr. Steffen Witz
6816;2;Sailing Guji;San Mateo, Calle D Sur Entre Av. 4TA oeste y sta oeste, Edificio Panleb, Provincia Chiriqui;;David;;Panama;4973544789980;;sailingguji@gmail.com;www.sailingguji.de;Christian Guter
6814;3;Offshore Marbella;Urb. Los Arcos de la Quinta, Calle Ponce de Leon, Alhucema 12, Puerta 3;29679;Benahavis;Malaga;Spain;34605084052;;info@offshoremarbella.com;www.offshoremarbella.com;Ivan Amselem
6813;3;Adara Yachts;Via Toscana 42;9018;Sarroch;Sardegna;Italy;393803894960;;charter@adarayachts.com;www.adarayachts.com;Manca Davide
6812;1;Assos Sailing;Ksilieri Thesi 1;28084;Assos;;Greece;+30(694)9407480;;assossailing@gmail.com;www.assossailing.com;Ilia Chatzistyli
6807;7;Kaikko Tropea;Via Dante Alighieri 44;89900;Vibo Valentina;;Italy;3478445676;;info@tropeaincaicco.com;HTTPS://www.tropeaincaicco.com;Collia Antonio
6806;1;Sardinia Dream Service srl;Via Catello Piro 20;7026;Olbia;;Italy;393929207506;;sardiniadreamservice@gmail.com;;Monica Masia
6805;9;Amalfi Charter;Via San Giovanni a Mare 5;84010;Minori SA;;Italy;393334821326;;info@amalficharter.it;www.amalficharter.it;Alfonso Imperato
6804;12;Llauts i Velers;Carrer Salvador Albert i Pey 0;17230;Palamos;Girona;Spain;34683469715;;info@llautsivelers.com;https://www.llautsivelers.com;Nuria Ferrer
6801;1;Stadium Marine;Thetidos 1;15232;Chalandri;;Greece;+30 6906 280988;;spilios.stadium@gmail.com;https://stadium-marine.com;Spilios Kordos
6800;1;NORTH AEGEAN SAILING MIKE;str.Verginas 37;66100;DRAMA;;Greece;306988881313;;dolcevitasailing.gr@gmail.com;;Stefania Della
6796;1;Libertad VII;C/Ronda Sant Marti 2 3 2a;25002;Lleida;;Spain;34633837733;;libertad7catamaran@gmail.com;www.libertadseven.com;Juan Pedro
6794;1;Pangea Yachting;Via Dante 6;54100;Massa;;Italy;393292442425;;booking@pangeayachting.com;https://www.pangeayachting.com/;Francesco Cimica
6793;9;Aldebaran Charter Company;Calle Manuel Sorà número 5;7800;Ibiza;;Spain;+34 631180522;+34 631180522;aldebaranchartercompany@gmail.com;www.aldebaranchartercompany.com;Kisko Fernandez
6777;5;Vamosavela;Baie de Apu, pk5,  Niua-Poutoru;98734;Tahaa;;French Polynesia;68987340148;;vamosavelasailing@gmail.com;https://www.vamosavela.com;Vittorio Morbidelli
6775;1;Saudade Sailing Club;La Marina de Valencia 8;CP 46024;Valencia;;Spain;+34 615 280 979;;hola@saudadesailingclub.com;www.saudadesailingclub.com;Lucas Coll
6773;5;Salty Boat Trips;Ornos;84600;Mykonos;;Greece;+30(694)511-5663;;info@saltyboattrips.com;www.saltyboattrips.com;Katherine Garyfallou
6772;1;Bonbon Sailing;Barbaros Mah. 7041 Sok No 14;35430;Urla;Izmir;Turkey;905323226966;905323226966;ahmet@bonbonsailing.com;https://www.bonbonsailing.com;Ahmet Arasan
6766;5;Antonio Cruising;Hrvatskih velikana 39;21315;Dugi Rat;;Croatia;+38591/498-4879;+38591/498-4879;antoniolozic1@gmail.com;;Lozic
6765;3;Has Yachts;Tuzla Mahallesi Sadi Pekin Caddesi No:28/1;48300;Fethiye;;Turkey;905557190330;;info@hasyatkiralama.com;;Fatih Tok
6757;1;Endless Blue Yacht Charters;999 Venderbilt Beach Rd Suite 200;34108;Naples;;U.S.A.;12393251871;;angela.mavredis@endless-blue.com;www.endlessblueyachts.com;Angela Mavredis
6756;1;Nautical Booking;Lozica 10;20236;Mokošica;;Croatia;;;pjero@dubrovnikcharter.com;www.dubrovnik-charter.com;Pjero Kusijanovic
6755;1;Relax Sailing;Vallelunga 90;52100;Pula;;Croatia;385992993798;;info@relaxsailingcroatia.com;www.relaxsailingcroatia.com;Bruno Žulić
6745;5;Sail Tahiti;Parcelle Ah 1, Centre Vaima lot 47;98701;Papeete;;French Polynesia;68987242875;;charter@sailtahiti.com;https://www.sailtahiti.com;Nikki Puttergill and David Allouch
6741;1;Levante Yacht;Put cvitačke 7;21300;Makarska;;Croatia;3850995016829;;luxury.boat.alex@gmail.com;;Ivo Beroš
6735;0;Family Luxury Yachting;Ari Kasfiki 1;49100;Corfu;;Greece;48502581837;;familyluxuryyachting@gmail.com;www.facebook.com/FamilyLuxuryYachting;Dariusz Solarski
6732;4;Navy Jane;Put Podglavičkih Žrtava 16;22203;Rogoznica;;Croatia;420705896250;;charter@navyjane.com;www.navyjane.com;Maroš Baran
6728;2;Herevai Charter;Tahiti, Yacht club de Tahiti;98701;Arue;;French Polynesia;68989798352;68989798352;herevaicharter@gmail.com;www.herevaicharter.com;Monnier Manutea
6723;24;Barcando Charter;Lungomare Duca degli Abruzzi 84,;121;Roma;;Italy;+39 06 94507580;;info@barcandocharter.com;www.barcandocharter.com;Antonio Palermiti
6722;0;Desde El Mar Charter;calle Camilo Jose Cela n 1 portal 3, 5 A;41018;Sevilla;;Spain;34670083598;;fernandodesdeelmar@gmail.com;www.desdeelmarcharter.com;Fernando Gonzalez-Vallarino Fernandez de Castro
6715;1;Acrux Vela;Calle Los Olmos, n 2. 1-G. San Juan de Alicante;3550;Alicante;;Spain;34625691729;;acruxvela@gmail.com;;Javier Moreno
6714;1;Dolin;Mate Balote 84;23000;Zadar;;Croatia;385959017455;;ivica.benic1@gmail.com;;Ivica Benic
6711;5;Aegeas Yachting;7 Filikis Etairias St.;65403;Kavala;;Greece;+30 694 991 4259;;charter@aegeasyachting.com;www.aegeasyachting.com;Stathis Sofitsis
6709;1;Sunnyacht;Trident Chambers, PO Box 146;VG1110;TORTOLA;;British Virgin Islands;41762418863;41762418863;contact@sunnyacht.com;;Jean Pierre Vernier
6708;5;Sea Horses;Papanastasiou 42;18533;Piraeus;;Greece;306979591200;;kalogerhs@hotmail.com;;Cris Kalogeris
6705;9;BlueLife Ltd;Bequia Marina;;Ocar Bequia;;St. Vincent and the Grenadines;+1 (784) 593 - 1843;;booking@bluelifesailing.com;www.bluelifesailing.com;Thor Magnus Lie
6703;3;Chill Out Sailing;4426 E 6th st, Apt 1;CA-90814;Long Beach ;California;U.S.A.;17148048549;;hello@chilloutsailing.com;www.chilloutsailing.com;David Chateau
6699;3;Aventura Catamarans;MArina de denia, Edificio G Planta Baja local 1B;3700;Denia;Alicante;Spain;0034 616056012;;miguel@aventurayachtsesp.com;;Francois Bellotte
6692;1;Helionautica;Vakxou 1;54629;Thessaloniki;;Greece;+30 697 4761299;+43 678 1241595;info@helionautica.com;http://helionautica.com;Alexios Kesopoulos
6689;1;BLUE CHARTERS;TREMPESINAS 46;17343;AGIOS DIMITRIOS ATTIKIS;;Greece;+30 693257969;;n.stafylas@gmail.com;WWW.BLUECHARTERS.GR;NIKOLAOS STAFYLAS
6688;1;Gulet Nation;TEPE MAH. 33 SK. MURATHAN IS MERKEZI, NO: 1 KAPI NO: 26;;MARMARIS/ MUGLA;;Turkey;+90 536 420 5590;;burak@guletnation.com;https://www.guletnation.com;Sedef Gaygusuzoglu
6682;1;Charter Adventure;Via Molinara 4C;25036;Palazzolo sull'Oglio;;Italy;39335452743;;info.charteradventure@gmail.com;www.charter-adventure.com;Turra Leonardo
6679;1;Skiathos Boats ;;37002;Skiathos;;Greece;306977574167;;info@skiathosboats.gr;www.skiathosboats.gr;Mouresiotis Vassilis
6676;3;Yacht Maldives;13F-P6 RAINCREST;20017;HULHUMALE;;Maldives;9607784769;;max@yachtmaldives.com;http://www.yachtmaldives.com;Max Molteni
6675;1;One Step Yachting;Triklino Alepou;49100;CORFU;;Greece;306970389075;;info@onestepyachting.com;www.onestepyachting.com;Chris Korakianitis
6673;1;Mediterranea Yachting;Via Francesco Galloppo 78;84128;Salerno;;Italy;393333457782;;mediterraneayachting@gmail.com;www.mediterraneayachting.com;Giandomenico Flagella
6670;6;Genoa Sailing;Savastias 39B;15771;Athens;;Greece;;30698648434;service@genoasailing.com;www.genoasailing.com;Afentras Nikolaos
6668;1;Zanzibar Catamaran Charter;Afroditis, 14, Flat/Office 1904;1060;;Lefkosia;Cyprus;+49 173 3673345 Whatsapp only;;mail@kitekahunas.com;https://www.kitekahunas.com/zanzibar_catamaran_yacht/;Dr. Wolfram Reiners
6663;1;Daidalos Yachts;Drosinis 1;26442;Patra;;Greece;306947024040;;daidalosyachts@gmail.com;;Panagiotis Andronis
6660;8;Jachtwerf Oost;Ljouwerterdyk 37;8491GB;Akkrum;;Netherlands;31566651632;;info@jachtwerfoost.nl;www.jachtwerfoost.nl;Wolter Oost
6658;1;Seastars Yachting;Nestoros 3;17564;Palaio Faliro;Attica;Greece;302102778177;;info@seastars.gr;www.seastars.gr;Eleni Kolokouri
6653;2;Aiolos Zante;Pandokratoras;29092;Zakynthos;;Greece;+30 697 093 0329;;aioloszante@gmail.com;www.aioloszante.com;Alexandros  Bisarakis
6648;1;Hinvest Haram;Martavegen 14A;6013;Ålesund;;Norway;4794816859;;bookingfjordtimes@gmail.com;www.fjordtimes.com;Hasse Haram
6645;3;Flo Sail;Via Anna Magnani - int. 54 38;58100;Grosseto;;Italy;393318110390;;flosail.p.a@gmail.com;;Ranieri Lorenzo
6643;1;Ouna;Vouliagmenis 26 avenue;16452;Argiroupoli;;Greece;+30 6944459569;;nikos@ouna-catamaran.com;www.ouna-catamaran.com;Nikos Dosios
6642;2;Ida Sailing;Kritis 11;15351;Pallini;;Greece;302106823170;;book@idasailing.com;www.idasailing.com;Dimitris Tsitras
6639;3;RyCharter;Via Presti Paolo Villaggio Portorosa ;98054;Furnari;Messina ;Italy;393664908448;;rizzoyachting@gmail.com;https://www.rycharter.com/;Rizzo Salvatore
6634;2;Midas Cruises;ENTOS OIKISMOU AMVRAKIKOU KORONISIAS ARTIS 0;47150;ARTA ARTAS;;Greece;306970374933;;info@midascruises.com;www.midascruises.com;Dimitris Liolios
6632;3;The Leading Yachts;Via Pozzobonelli 18;178;Roma;;Italy;3383814511;;theleadingyachts@pec.it;www.theleadingyachts.com ;Ugo Hofman
6629;28;New Port (Nowy Sztynort);SZTYNORT 11;11-600;WEGORZEWO;;Poland;48663427782;;a.tobin@sztynort.pl;www.sztynort.pl;Kamil Stankiewicz
6627;4;Aloha Sailing;Başkent Organize Sanayi Bölgesi 4. Cad No: 4;6909;Ankara;;Turkey;+90 532 445 14 48;+90 532 445 14 48;info@alohasailingtr.com;www.alohasailingtr.com;Anıl DOGRU
6625;1;Electron Libre;54 rue des Salines;56000;Vannes;;France;33617570705;;david@electron-libre.co;www.electron-libre.co;David Le Douarin
6624;3;Dubrovnik Boat Gabriel;Zrinskih 8;20210;Cavtat;;Croatia;+385 91 447 8775;;info@dubrovnikboatgabriel.com;www.dubrovnikboatgabriel.com;Periša Fiorenini
6605;1;Sailing360;Medveščina 31 D;10000;Zagreb;;Croatia;+385 1 4101 550;38598868223;info@sailing360.com;www.sailing360.com;Mislav Kodarić
6595;6;Yacht-Match Charter & Management;Kennedylaan 30;5242BB;Rosmalen ;;Netherlands;+31 646440794;;daria@yacht-match.com;www.yacht-match.com ;Dirk Agter
6583;2;Ionian Spirit;Ierou Lochou 24;18013;Pireaus;;Greece;306994795895;;contact@ionianspirit.com;www.ionianspirit.com;Anna Maria Florou
6580;10;The Yacht Experiences;CALLE INSURGENTES SUR, 1458 COLONIA ACTIPAN BENITO JUAREZ;3230;CIUDAD DE MEXICO;;Mexico;+52 9981227583;;otas@theyachtexperiences.com;https://www.theyachtexperiences.com/;Carlos Frias
6574;1;Novelty Tours;Huga Badalica 26 A;10000;Zagreb;;Croatia;385916033801;;info@noveltytours.com;https://www.noveltytours.com;Antun Sostaric
6572;3;Adriatic Sailing Academy;Marije K.Kozulic 2;51000;Rijeka;;Croatia;385992154977;;info@adriaticsailing-academy.com;www.adriaticsailing-academy.com;Sanja Kraljić-Matijević
6571;1;Catamaran Falco;Poljicka cesta 9;21315;Dugi Rat;;Croatia;385916091969;385916091969;darenazor4@gmail.com;www.catamaranfalco.com;Dare Nazor
6561;3;Eila;Put Bravarica 7;23312;Novigrad (Dalmacija);Zadarska;Croatia;385915238717;;info@eila.hr;www.eila.com.hr;Maja Buterin
6559;2;Abovento Nautika;Vrbik IV. 3;10000;Zagreb;;Croatia;385915013030;;booking@apexcharter.com;www.apexcharter.com;Sandra Steigl
6557;5;Seatravel Croatia;Prolaz Marije Krucifikse Kozulić 2;51000;Rijeka;;Croatia;3859551188888;;seatravel.hr@gmail.com;https://seatravel.hr;Anatoly Shutov
6552;1;Boat Charter Trieste;Via G. Carducci 62;33010;Tavagnacco UD;;Italy;+39 335 7614870;;info@offroad4ruote.com;www.boatchartertrieste.com;Alessandro Del Fabbro
6551;3;Virgin Islands Boats;12011 Orchid Ln;GA 30009;Alpharetta;;U.S.A.;18006743649;;info@virginislandsboating.com;www.pyperyachts.com;John Carter
6540;2;Ivamare Boats;Molizanskih Hrvata 27;21300;Makarska;;Croatia;38598858745;;info@ivamareboats.com;www.ivamareboats.com;Radenko Herceg
6529;5;Posidonia Boats;C/Sancho Panza 12;7800;Ibiza, Baleares ;;Spain;34685050071;;rufo.vaquero@gmail.com;www.posidoniaboats.com;Rufo Vaquero
6525;1;Langkawi Cruise;507, Block E, Pusat Dagangan Phileo Damansara 1;46350;Petaling Jaya;;Malaysia;601116681250;;contact@langkawicruise.com;https://www.langkawicruise.com;Alain Augsburger
6520;3;Phillip Yachtsun Charte;Unguja, West 'B', Nyamanzi, 71215Unit F9/2 Fumba Town;43305;Zanzibar;;Tanzania;255713038966;;office@phillipyachtsuncharter.com;www.phillipyachtsuncharter.com;Filip Szlek
6515;1;Mariny Charter;Via Picenna 17;80046;San Giorgio a Cremano;;Italy;393400579941;;marinycharter@gmail.com;www.marinycharter.com;Francesco Donadio
6501;1;ACD Yachting;Kustepe Mahallesi, Mecidiyekoy Yolu Caddesi, Trump Tower, Blok No:12, Ic Kapi No:221;34400;Sisli;Istanbul;Turkey;+90 532 641 67 00;+90 532 641 67 00;acd.yachting@hotmail.com;https://www.acdyachting.com/;Arda Can Demiray
6499;1;Cizmin;Ul. Otona Ivekovića 8;23000;Zadar;Zadarska;Croatia;;385952421941;cizmin.doo@gmail.com;www.cizmin.hr;Darko Čizmin
6488;1;Elle Advisor;Via Plinio, 47;80053;Castellammare di Stabia;;Italy;393334289857;;elleadvisorsrl@gmail.com;;Loredana Strianese
6487;10;Poe Charter Tahiti;Marina de Papeete - BP40843;98713;Papeete, Tahiti;;French Polynesia;+689 87 71 55 55;;Contact@poecharter.pf;www.poecharter.pf;Bruce ANDRIEUX
6484;9;Viva Yacht Charter;Muelle de la Lonja, Pantalan 2;7012;Palma de Mallorca;;Spain;34971738019;;info@vivacharter.com;www.vivacharter.com;Enrique Cipres
6476;5;Charter Team;Stiglicheva 28;52100;Pula;;Croatia;+385 995 450 801;;office@charter-team.com;www.charterteam.eu;Joanna Zofia Biegala
6472;2;Live Panama Sailing;PH OCEAN BUSINESS Piso 10 oficina 1010 calle 47;7179;Panama City;;Panama;+507 6939 4537;;infotangosail@gmail.com;www.tangosailcatamaran.com;German Diego Bestier
6466;3;K Yachting;Chalikaki;18010;Aegina, Saronic Gulf;;Greece;306942222468;;kyachtingathens@gmail.com;kyachtingathens.com;George Kyriakidis
6455;1;Anares Turizm;Kultur mahallesi sevket bey apartmani 156;33010;Mersin;;Turkey;905326811198;;kaptanmemco@gmail.com;;Mehmet Dogan
6449;4;Nemo Yachting Charter;Via Provinciale Santa Maria a Cubito n. 670;80145;Napoli;;Italy;393482427123;393791898090;nemoyachtingcharter@libero.it;https://www.nemocharter.com;Antonella Romano
6445;7;Kek Yachting;Brodarica, kamenita 12;22000;Šibenik;;Croatia;385916081305;;booking.kek@gmail.com;www.kekyachting.com;Ante Prebanda
6443;5;Eolmare;VIA MEDICI 184;98076;SANT AGATA DI MILITELLO ;MESSINA;Italy;393358282494;;eolmare@gmail.com;www.eolmare.it;Giovanni Lo Schiavo
6442;2;Ragusa Nautica;Vatroslava Lisinskog 27;20000;Dubrovnik;;Croatia;38598497098;;info@ragusanautica.com;www.ragusanautica.com;Vice Skelin
6422;0;Sailor Expert;Avenida Andalucia 10;29007;Benalmadena;Andalusia;Spain;48514705135;;office.spain@sailorexpert.com;www.sailorexpert.com;Mirek Nogaj
6417;1;Maritime Escape;9 Chemin de la Gare;31620;Castelnau d'Estretefonds;;France;+33 6 82 13 32 15;;contact.shamane@gmail.com;www.shamane.fr;Marie ange DE GAULEJAC
6416;1;Sail Friesland;Meerkoetweg 68;8446 JZ;Heerenveen;;Netherlands;31611420322;;diederik@sailfriesland.nl;https://www.sailfriesland.nl;Diederik Postma
6414;1;George Kotsiris;Eakou 75;13122;Ilion;;Greece;306977504509;;geokotsis@yahoo.gr;https://www.epikourosyacht.com ;George Kotsiris
6413;3;Dubrovnik Boat and Yacht;Lazarina 3;20000;Dubrovnik;;Croatia;385914229728;;charter@dubrovnikboatsandyacht.com;https://www.dubrovnikboatandyacht.com;Ivana Mustahinić
6412;1;Alida Yachts;Carretera de Manacor, km 8, Sa casa Blanka;7199;Palma de Mallorca;;Spain;34664851282;;capitan@alidayachts.es;www.alidayachts.com;Maria Rosa Carreras
6402;1;Carpe Diem XI Yachting;Baris Mah Iskele meydani No.62;48480;Mugla;;Turkey;905322215018;+90 532 221 5018;info@carpediemxiyachting.com;www.carpediemxiyachting.com;Halil Celebioglu
6400;1;Balu Sailing;C/Punta dels Escuts 3, Castel Platja de Aro;17250;Girona;;Spain;34638343470;;sofyasark@icloud.com;www.nbyachts.com;Sofya Sarkisyan
6399;3;Theta Yachts;Nikiana Lefkada;31100;Lefkada;;Greece;306981541103;;info@thetayachts.gr;;Dimitrios Kopsidas
6395;1;Premium Properties Pag;Štefanovečka cesta 10;10000;Zagreb;;Croatia;385916001254;;premiumpropag@gmail.com;;Angela Schonstein
6386;0;Media Ship International;PortoTuristico di Roma - Ufficio 873;121;Roma;;Italy;390662205512;393920731299;crewed@mediaship.it;www.mediaship.it;Giulia Rascaglia
6381;1;LAYSAR;VIA GENERALE VINCENZO STREVA 24;90142;Palermo;;Italy;3348710033;;laysarsrl@libero.it;www.laysaryachting.com;DOMENICO ALBA
6380;6;Promare;Molizanskih Hrvata 64;21300;Makarska;;Croatia;+385 989474555;;info@promare.hr;www.promare.hr;Marijo Prnjak
6372;2;Happy Sailing;Via Guido Reni 42;196;Roma;;Italy;393462246192;;info@happysailing.it;www.happysailing.it;Cinzia Graziani
6369;1;Blue Med Charter;Ángel Guimerá 8 ático;8328;Alella [08328];;Spain;34683537974;;info@bluemedcharter.com;https://bluemedcharter.com/;Didac Salvatierra Oliveras
6362;1;Marine Infinite;Pflugstrasse 10;90461;Nuremberg;Bayern;Germany;;491794534283;booking@marine-infinite.com;www.marine-infinite.com;Stephan Lobodda
6360;6;Rent a Boat Phoenix;Nadbiskupa Alojzija Stepinca 1;51500;Krk;;Croatia;385989624656;;phoenixkrk@gmail.com;www.rent-a-boat-krk.com;Tomislav Kirinčić
6356;2;SEAGuru;Zvonimira Rogoza 14;10000;Zagreb;;Croatia;385916001254;;seaguru37@gmail.com;;Mislav Butković
6352;1;Danielis Yachting;Ulica Grada Chicaga 4;10000;Zageb;;Croatia;38598273416;;charter@danielis-yachting.com;www.danielis-yachting.com;Danijel Razi
6309;2;Charter Eolie;C/Da Due Torri 18;98057;Milazzo;;Italy;393406405678;;info@chartereolie.it;www.chartereolie.it;Gabriele Paratore
6304;1;Asterias;Katsimatoika 1;49082;Paxos;;Greece;+41 52 551 02 13;;andreas@madianos.ch;www.sy-asterias.gr;Andreas Madianos
6303;1;Sailing Nafplio;Akti Miaouli 3;21100;Nafplio;;Greece;+30 6984823266;;info@sailingnafplio.com;www.sailingnafplio.com;Elena Tassopoulou
6302;2;Ja Nedam;Carrer Castella i lleo n 39, esc. 1, bjs A;7013;Palma;;Spain;34669772393;;info@janedam.com;www.janedam.com;Guillermo Tortella Rullan
6300;1;Trapani Vela Club;Via Virgilio, 115;91100;Trapani;;Italy;393284857865;;trapanivelaclub@gmail.com;https://www.facebook.com/trapanisailing;Michele Castiglione
6296;3;Piri Tour Yachting;Yenikoy Mah. Karakaya Sk. Bo: 25/2;;Bodrum;48440;Turkey;+90 533 3575071;;info@piritouryachting.com;www.piritouryachting.com;Pelin Aybatan
6292;4;Wind Tribe;Via Massa Avenza, 38/B;54100;Massa;;Italy;393496189626;;vela@windtribe.it;www.windtribe.it;Stefano Gentili
6283;1;T.E.L.N.A.V.;Dinka Šimunovića 7;21000;Split;;Croatia;385912031000;;marinas.yachting@gmail.com;;Neven Viskovic
6260;3;Uniko Charter;Via Tuscania snc;1028;Orte;;Italy;393668690619;;booking@unikocharter.com;www.unikocharter.com;Loris Francesco
6259;1;Glafki Cruises;Kolymvari marina;73005;Chania;;Greece;6906092707;;info@glafkicruises.gr;https://glafkicruises.gr/index.php/en/;Evagelia Maganari
6246;8;Fazana Boats;Galižanska cesta 136;52212;Fažana;;Croatia;+385955 555999;;fasana.charter@gmail.com;www.fazana.eu;Sara Božić Ristovski
6244;4;Tamaris Charter;Kneza Trpimira 149;21220;Trogir;;Croatia;385917697559;;info@tamaris-charter.com;www.tamaris-charter.com;Toni Vitezica
6237;3;VTC Charter;Via B. Peretti n. 1;58015;Talamone;Grosseto - Toscana;Italy;+39 3472607983;;info@vtccharter.it;www.velapassion.it; Carlo Laucci
6236;1;eMMe Charter;Via Fiume, 68;84129;Salerno (SA);;Italy;+39 349 2148395;;info@emmecharter.com;www.emmecharter.com;Domenico Sganga
6231;1;Venus Yachting;Marinturk Göcek Village Port;;Muğla;;Turkey;+90 530 242 99 51;;info@venusyachting.com;www.venusyachting.com;Yagmur Eser
6229;2;Agafya;28th October 4;38333;Volos;;Greece;+30 694 990 2149;;agafyaike@gmail.com;agafyaike.com;Miljana Nimcevic
6222;2;Top Catamaran;Ante Starčevića 32, B/21;21300;Makarska;;Croatia;436764600365;;topcatamaran@gmail.com;https://topcatamaran.com;Oleksandr Siladi
6217;2;Wave Charter;Produkcyjna 7;05-074;Józefin;;Poland;+48 886 419 336;;booking@wavecharter.eu;www.wavecharter.eu;Artur Zochowski
6212;1;Azimuth Charter;Via A.manzoni 6;80123;Napoli;;Italy;+39 349 433 53 35;;nadia@azimuthvela.it;www.azimuthvela.it;Nadia D'Amore
6206;2;Daphne Sail ;Begonvil Sk. 7/B;48300;Fethiye;Mugla;Turkey;+90 536 460 73 12;+90 539 924 59 12;daphnesailcharter@gmail.com;www.daphnesail.com;Kenan Guven
6204;1;EVENTI;Via Monte di Dio, 9;80132;Naples;;Italy;+39 349 0580800;;info@e-venti.net;www.e-venti.net;Marco Ferrari
6186;6;NexGen Yachting;66 West Flagler St, Suite 900;33130;Miami;;U.S.A.;+1 855 639-4369;;bookings@nexgenyachting.com;www.nexgenyachting.com;JP Hartman
6176;1;Perla Catamaran;Brdo I 20;21405;Milna;;Croatia;+385-91-313-1135;;perlacatamaran@gmail.com;;Ivan Čipčić
6175;7;Corsica Multicoques;Port De Plaisance Tino Rossi;20 000;Ajaccio;CORSE;France;+33 (0)4 95 72 29 53;+33 (0)6 35 17 22 44;location@corsica-multicoques.com;www.corsica-multicoques.com;James CAM
6173;1;Croatia Charter Fleet;Vukovarska 53;21000;Split;;Croatia;+385-21-413-403;;vanja.kudric@sailingsupport.hr;www.croatiacharterfleet.com;Vanja Marinovic
6172;1;Mythos Sailing Experience;Gouvia Marina;49100;Kontokali, Corfu;;Netherlands;+31 6 53493243;;info@mythossailingexperience.com;https://mythossailingexperience.com/;Bob Vereijken
6166;1;Mia Catamaran;Calle Gonzalo Barrachina, 4 Alcoy;3801;ALCOY;Alicante;Spain;34620768291;;miacatamaran@gmail.com;;Jorge Molina
6160;3;GeFra Charter;Strada del Gesu 6;80053;Castellammare di Stabia;Napoli;Italy;393271478193;;francescogaeta999@gmail.com;www.gefracharter.it;Vincenza Buzzella
6158;1;Sport Sub Ciclope;S. Agostino 9;95014;Giarre;catania/sicilia;Italy;+39 3286573763;;sportsubciclope@gmail.com;www.sportsubciclope.it;Rosario Cuscona
6155;1;Darv Yacht M.C.P.Y.;Street Australis 114;85100;Rodos;;Greece;+39 3928946860;;darvyacht@gmail.com;www.catamaranoroma.com;Danilo Piacentini
6149;12;Octana Yacht Services;83, Poseidonos Avenue;16675;Glyfada;;Greece;+30 213 0123 172;;charter@octana.gr;www.octana.gr;Thanos Andronikos
6144;2;Cavtat Boat Trips;Gruda 164;20215;Gruda;;Croatia;385977961165;;cavtatboattrips@gmail.com;http://www.cavtatboattrips.com;Božo Bećir
6140;1;My Bubu;Via Atzori n.233;84014;Nocera inferiore (Sa);;Italy;+39 3338018063;;info@my-bubu.it;www.mybubu.cloud;Luigi Petti
6135;8;Otium Yachts;Mezanovci 27;21231;Klis;;Croatia;+385 98 195 4564;;info@otiumyachts.com;www.otiumyachts.com;Ivana Klaric Poljak
6133;3;UNA;Njegoseva 3;85320;Tivat;;Montenegro;38267604171;;agencija@una.me;www.agencyuna.me;Risto Lucic
6125;1;KatSails Yachting;Kavalos;31100;Lefkada;;Greece;306947021041;;katsailsyachting@gmail.com;www.katsailsyachting.com;Dimitrios Katsimantis
6118;4;Captain Bigfoot;28th Oktovriou 45 str;54642;Thessaloniki;;Greece;306976390957;;charter@captainbigfoot.com;www.captainbigfoot.com;George Podaras
6115;2;Maldives Charters;ADK Building 7th Floor;20280;Male;;Maldives;9607926922;9607926922;sales@maldivescharters.com;https://www.maldivescharters.com;Ali Adil
6111;1;Salty Breeze;Mesimvrias 5;65403;Kavala;;Greece;+30 6936901248;;info@saltybreeze.gr;www.saltybreeze.gr;Maria Kalogiorgi
6108;1;Mola Sailing;Adalet Mah. Manas Bulvari Folkart Towers No47B/2804 Bayrakli;;Izmir;;Turkey;905323056551;;info@molasailing.com;www.molasailing.com;Isil Sakiz
6107;1;Ajaccio Croisieres;12 port Tino Rossi;20000;AJACCIO;;France;+33 6 09 33 44 61;;ajacciocroisieres@gmail.com;www.ajacciocroisieres.com;Jerome PRADAL
6104;16;RM Croisieres;Marina du Marin;97290;Le Marin;Martinique;France;+596 696 722544;;rmservicesnautiques@live.fr;www.rmcroisieres.com;Roberto Maxera
6102;1;Ambar Charter ;C/Llagut, 4;43860;Ametlla de Mar ;Tarragona ;Spain;34667466630;;ambarcharter@gmail.com;www.ambarcharter.com;Mr. Roque Moreno Carrillo
6100;2;Kubaso;Riga Feraiou 239;38222;Volos;;Greece;306979224164;;info@kubaso.com;www.kubaso.com;Evangelos Tsirnovas
6096;11;Vernicos Crewed;11 Poseidonos Ave.;17455;Alimos (Athens);;Greece;+30 2109896000;;crewed@vernicos.gr;www.vernicos.com;Anna Ramfou
6092;1;Alisa Adriatic Cruise;Bilice II/6;21000;Split;;Croatia;+385 91 9135337;;booking@alisaadriaticcruise.com;www.alisaadriaticcruise.com;Nikica Frleta
6085;44;Crouesty Location;11 chemin du Crouesty;56640;Arzon;;France;+33 2 97 53 76 00;;info@crouesty-location.com;www.crouesty-location.com;Severine Cheron Bonhomme
6079;2;Zsolt Yachting;Petrinjska 14;10 000;Zagreb;;Croatia;+36 704749894;;zsoltyacht@gmail.com;;Zsolt Molnar
6073;5;Windward Islands Yachting & Travel;760bis chemin de la Grande Bastide;6250;Mougins;;France;+33 6 23 40 70 35;;ca@windward-islands.net;www.windward-islands.net;Emmanuel Pertuisot
6067;2;Paper Boat Sailing;Akarca Mah. Mustafa Kemal Bulv. No: 173/B;48300;Fethiye, Mugla;;Turkey;+90 532 479 379 0;;sales@paperboatsailing.com;www.paperboatsailing.com;Murat Yilmaz
6056;2;Strambando;VIA SANTA MARIA DEL PIANTO 26;80143;NAPOLI;;Italy;39335207089;;prenotazioni@strambando.it;www.strambando.it;Umberto Belaeff
6044;8;Vliho Yacht Club;Vliho;31084;Vliho;;Greece;+30 2645 029282;;vlihoyc@hotmail.com;https://vlihoyc.com/;
6029;3;Aegean Yachts;10. POSEIDONOS AVE;16777;HELLINIKON;;Greece;+30 6972779191;;ayachts@otenet.gr;www.yachtholidaysgreece.com;ALEX KORIZIS
6026;1;Istra Yacht Gastro;ANDRIJE MEDULICA 4;51000;RIJEKA;;Croatia;32476974168;;info@istra-yacht-gastro.eu;www.istra-yacht-gastro.eu;Dirk de Sterck
6025;1;KlaPa Nautica;Ul. Kralja Tomislava 44, Gradići;10410;Velika Gorica ;;Croatia;+385 91 6223 705;;info@klapa-nautica.com;https://klapa-nautica.com;Roman Krunić
6024;0;Pure Sailing;Radnicka Cesta 39;10000;Zagreb;;Croatia;+36 30 718 5009;;lotte@pure-sailing.eu;www.pure-sailing.eu;Laura Janssen & Lotte Janssen
6008;15;Wind Charter;Rodovia Rio-Santos BR101, KM576;239700-000;Paraty;Rio de Janeiro;Brazil;552424040020;;contato@windcharter.com.br;http://windcharter.com.br;Mariani Castilhos
6003;7;MCL Charter;Artatore 140;51550;Mali Lošinj;;Croatia;385996450238;;info@mcl.hr;www.mcl.hr;Luka Nolden
5998;8;Bellen;Petrovićeva Ulica 8;52100;Pula;;Croatia;385989767054;;mare.radolovic@gmail.com;;Martina Bernobić
5994;7;Exploreseas;Viru valjak 2;10111;Tallinn;;Estonia;306975600904;;exploreseas@gmail.com;www.exploreseas.com;Andreas Vlachakis
5990;3;Jako More;Marasovićeva 57/4;21000;Split;;Croatia;491788027802;;booking@jako-yachting.com;www.jako-more.de;Jakov Kukic and Jakoslav Matic
5986;1;Palmarola;PIAZZA MAMELI N 5;91025;MARSALA TP;;Italy;+39 3201772446;;haythem89@live.it;;Haythem Abedellali
5985;1;JeDo Sailing;Q2,Level 7 Quad Central Triq I-Esportaturi Central Business District;CBD1040;Birkirkara ;;Malta;34674816839;;jedo-sailing@outlook.com;www.jedo-sailing.com;Dominikus Lercher
5982;6;Sea Dream Yachtcharter;Sv. Katarina, Ul. Vallelunga 90;52100;Pula;;Croatia;491734255089;;seadream.charter@gmail.com;www.seadream-charter.com;Heidrun Feck
5974;2;Adriatic Management;Put sv. Lovre od Ostroga 35;21215;Kaštel Lukšič;Split-Dalmatien;Croatia;+385 22 295 094;;charter@exclusive-yachtcharter.com;www.exclusive-yachtcharter.com;Alexander Geiger
5955;1;Float Boat Adventure;Av Paseo de la Marina 3, Marina Vallarta;48335;Puerto Vallarta, Jalisco;;Mexico;+52 333 3216512;;info@float.mx;www.float.mx;Raul Guitron
5941;0;More Sailing;Fabriksgatan 13;412 50;Göteborg;;Sweden;46313096300;;niklas@intoit.se;www.moresailing.se/en;Malin Stom
5917;2;Sailing Yachts Greece;Thrakis 15;17342;Agios Dimitrios;;Greece;306946500656;;info@sailingyachtsgreece.com;www.sailingyachtsgreece.com;Constantinos Makaritis
5907;1;Eolia Yachting;Athanasiou Diakou 29A;26224;Patras;;Greece;306947301000;;vicky@greece-yachtcharter.gr;www.greece-yachtcharter.gr;Vicky Masourou
5905;1;Bordada;Filipa Pavešića 12;51262;Kraljevica;;Croatia;385914861001;;bordada.sailing@gmail.com;;Tomislav Furlan
5902;1;Anasa Experiences;Mavromihali 19;65302;Kavala;;Greece;+30 6974998310;;angelos@anasaexperiences.com;www.anasaexperiences.com;Angelos Alexelis
5901;3;Luna Charter;Kresimirova 147;21211;Vranjic;;Croatia;38598452225;;croatia@lunacharter.com;www.lunacharter.com;Slawomir Piotrowski
5887;11;GBT Yachting;a Brand of Global Business Travel ;;Denizli;;Turkey;496924704825;491726746746;l.levent@gbtyachting.com;www.gbtyachting.com;Lutz Levent
5883;1;M & S obrt za turizam;Četvrt kralja Slavca 23;21310;Omiš;;Croatia;385992173408;;mario.zecic@gmail.com;;Lucija Družić Zečić
5874;1;Aurora Yacht Charters;Emek Sokak No: 5/1;48300;Göcek Mahallesi;Fethiye/Mugla;Turkey;905301302930;;osman@aurorayachtsturkey.com;https://www.aurorayachtsturkey.com/;Osman Sahin
5873;2;SP Sailing;32 Gianni Chalkidi str.;54229;Thessaloniki;;Greece;359883216916;359888088078;info@spsailing.com;https://spsailing.com/#homepage;Stepan Papazyan
5868;3;Private Yacht Charter N.V.;Juancho Yrausquin Boulevard 22;;Philipsburg;;St. Maarten (Dutch);17215815305;;piesxm@gmail.com;www.daychartersxm.com;Pierre Altier
5861;4;Anyachting;Leof. Poseidonos 50, Palaio Faliro;175 62;Athina, Athens;;Greece;+30 210 9816674;;charter@anyachting.com;www.anyachting.com;Aneta Apostolopoulou Motlova
5860;0;Mareventi Charter;VIA SAN GIACOMO 9;48;NETTUNO RM;;Italy;+39335 5495516;;info@mareventicharternettuno.it;www.mareventicharternettuno.it;Mario Rosati
5858;1;Quixotic Charters;House 18, Nuku Circle;PD134;Nadi;;Fiji;+679 920 9569;;bookings@quixoticcharters.com;https://www.quixoticcharters.com;Aleksander Kavs
5855;0;Sea Joy;Iridos 31;16673;Voula;;Greece;306947471548;;info@rentboatcorfu.com;www.rentboatcorfu.com;Olena Smorzhenyuk
5853;12;Sea Alliance Group;42 Seirinon str.;17561;Athens ;Greece ;Greece;+30 693 698 8922;;chartersgr@sea-alliance.com;www.sea-alliance.com;Itay Singer
5849;1;Hobos;Put Malenice 19;23211;Drage;;Croatia;385993156071;;hobos.marketing@gmail.com;www.rentaboatcroatia.hr;Marko Špralja
5847;4;Klobuk Travel;Katarine Zrinske 9;21312;Podstrana;;Croatia;385993353010;;info@klobuk-travel.hr;www.klobuk-travel.hr;Anđela Škaro Pezer
5846;1;Omnia Yachts;7 Markora str;26442;Patra;;Greece;306940993988;;omniayachts@gmail.com;www.omniayachts.com;Lampros Charis
5840;1;Rada Sea Ventures;Via Cassia 1043;189;Roma;;Italy;+39 328 8299077;;booking@radaseaventures.com;www.radaseaventures.com;Pierluigi Mezzanotte
5838;21;Sightsea Yachting;Leof. Andrea Siggrou 214;176 72;Kallithea;;Greece;302109517633;;info@sightsea.gr;www.sightsea.gr;KONSTANTINOS FOUFOUNIS
5837;2;Everblue Yachting;Elia Eliou 75;11744;Athens;;Greece;+30 6999 510 442;;elias@everblue-yachting.com;www.everblue-yachting.com;Konstantinos Giannakis
5827;1;Ghimar Beau Bato;1210 route de Crest;26160;PONT DE BARRET;;France;+33 7 82 14 61 99;;ghimarbeaubato@gmail.com;https://www.ghimarbeaubato.com/;Aurélie Lamandé
5825;1;Dolcevita Sailing;Via San Dalmazzo 24;10122;Torino;Torino;Italy;+39 333 109 5085;;info@dolcevitasailing.it;www.dolcevitasailing.it;Franco Picotto
5824;4;Notos Yachting;ARGOLIDAS;213 00;Porto Cheli;;Greece;302754052000;306985799202;elena@notosyachting.com;www.notosyachting.com;Nikos Oikonomou & Ariel Adoram
5810;1;Vive La Vie;Elite Business Centre;1824;Msida, MSD;;Malta;+385 5 557 99 06;;booking@lesperance.life;;Luke Attard
5807;2;Canal Yachting;Palaio Kalamaki 0;20010;Istmia, Corinth ;;Greece;+30 690 94 10 704;;canalyachting@gmail.com;www.canalyachting.com;Stamatiou Ioannis
5805;1;Euphoria Yachting;Filippou 32;57019;Perea;Thessaloniki;Greece;302392306712;;booking@euphoriayachting.com;euphoriayachting.com;Paris Panagiotidis
5798;1;Sense Sailing ;Cinzebi 46;52403;Zajci, Potpičan;;Croatia;436765015258;436765015258;office@sensesailing.com;https://sensesailing.com;Jaroslav Belsky
5793;1;Five Star International;CETVRT ZARKA DRAZOJEVICA 1;21310;OMIS;;Croatia;385919188221;;info@5star.hr;5STAR.HR;MARIO VUKUSIC
5788;2;Mango Sailing;INLICE MAH. INLICE (INL) SK. NO: 61 IC KAPI NO:2;34000;FETHIYE / MUGLA  ;;Turkey;+90 530 123 6839;;info@mangosailing.com;www.mangosailing.com;Hakan Bozbiyik
5772;2;Latria Charter;196, Syggrou avenue, Kallithea;17671;Athens;;Greece;306980106822;;info@latria-charter.com;www.latria-charter.com;Volodymyr Huebner
5747;4;Sicily Charter;VIA SIRTORI 65/V;91025;MARSALA ;Trapani;Italy;393483626530;;sicilycharter@gmail.com;www.sicilycharter.com;Antonio D'ina
5745;6;Brasil Yacht Charter;Avenida Engenheiro Winston Maruca S/N Centro Náutico Marina Verolme Bloco 6 loja E;23914345;Angra dos Reis;;Brazil;5521997406834;;felipe@byc.com.br;www.byc.com.br;Marcos Pinto Rizzo Soares
5744;4;Seagma Yachting;25th March Str. 71;124 61;Nea Peramos, Athens;;Greece;306907171532;;info@seagmayachting.gr;www.seagmayachting.gr;Mamaloukos Konstantinos
5740;1;Metallas Yachting;GRIPARI STR. 4;16345;ATHENS;;Greece;306972912669;;lmetallas@gmail.com;www.myachting.gr;Likourgos Metallas
5736;4;HKA Neta Yachting;OGUZLAR MAHALLESI MEVLANA BULVARI 139/A5;6520;ÇANKAYA ANKARA;;Turkey;905322445263;;evrimgultekin@hotmail.com;www.hkanetayachting.com;Evrim Gültekin
5733;8;Sea Marinero;Ljudevita Gaja 1;22211;VODICE;;Croatia;385994202677;;info@sea-marinero.com;www.sea-marinero.com ;Antonio Ivas
5729;1;Black Duck Nautica;Stjepana Radića 41;22000;Šibenik;;Croatia;+ 385 98 9335 181;;info@mareboat.com;www.sailingblackduck.com;Stefan Howarth
5727;1;Armenistis Sails;Proespera Rahes;83301;Ikaria;;Greece;306932472248;;tsantiris.kiriakos@gmail.com;;Kiriakos Tsantiris
5721;1;Iason Sailing;NEOCHORIOU 3;13451;KAMATERO;;Greece;306936995608;;info@iasonsailing.com;www.iasonsailing.com;Dimogiannis Nestor
5720;1;Vicko Sails;Ulica 9 Juzna obala 103;22234;Prvic Sepurine;;Croatia;385998322230;;vicko_kursar@yahoo.com;;Vicko Kursar
5718;0;SkyFall Yacht Charter;Karpathou square 3-4;18539;Kallipoli, Piraeus;;Greece;306973567882;;skyfallcharter@gmail.com;https://www.skyfallyachtcharter.com/;Angelos Papakonstantinou
5714;5;South Star;Via Monticelli, 5 - Salerno;84131;;;Italy;+ 39-3283047071;;info@southstar.it;www.southstar.it;Sabrina Schiavone
5696;1;Navegando Sin Rumbo;Calle la Pinta 60, Bajo A.;41927;Sevilla;Mairena del Aljarafe;Spain;+34 671 676 066;;hola@navegandosinrumbo.com;www.navegandosinrumbo.com;Daniel Álvarez Yaque
5687;4;Touch Yachting;Atatürk Bulvari No:122/A3;48470;Bodrum;Mugla;Turkey;+90 533 031 14 17;;gencer@touchyachting.com;www.touchyachting.com;Gencer Yalcin
5684;1;Yachting Mykonos;ORNOS BEACH;84600;MYKONOS ISLAND;;Greece;+30 2289023313;;info@yachtinmykonos.com;yachtinmykonos.com;IOANNIS THEOCHARIS
5682;1;Shark Sails;VAKHOU 1;54629;THESSALONIKI;;Greece;306972221928;306972221928;sharksailsnepa@gmail.com;https://sharksails.gr/;Maria Averkiadou Sekulic
5666;3;Sailing Life Academy;Sariana Mah. Mustafa Munir Elgin Bulvari, Netsel Marina 38-11 No: 16;48700;Marmaris;Mugla;Turkey;905323621030;905357180655;charter@sailinglifeacademy.com;www.sailinglifeacademy.com;Erol Algul
5657;2;Pentena;VIA DELLE MANTELLATE 8;50129;FIRENZE;;Italy;+39 3404204970;;diego.marraffa@pentena.it;WWW.PENTENA.IT;LORENZO BENINI
5654;1;Bluetiful;Mutilinis 1;14569;Anoixi;;Greece;;;mmk@bluetiful.gr;;Konstantinos Kalogerakis
5649;1;Yachting Together;AGIOU DIMITRIOY 41;18546;PIRAEUS;;Greece;+48 509 122 142;;yachting.together@gmail.com;https://yachtingtogether.com/;Joanna Machulik
5641;1;Come on Board;6, 6th September str.;1000;Sofia;;Bulgaria;359879283109;;charter@comeonboard.bg;www.lazycharter.com;Stefan Varadinov
5639;3;Signature Sailing;4 RUE DES GERANIUMS;98000;Monaco;;Monaco;33749644272;;signaturesailing@gmail.com;www.signaturesailing.com;Konstantin Kutyrev
5637;1;Aiolis Experience;IVIS ATHANASIADOU 19;17563;P.FALIRO, ATHENS;;Greece;306984861537;;cruises@aiolisexperience.com;www.aiolisexperience.com;Dimitriadou Denise
5632;1;Greece Adventure;Nikomideias 48;38446;N. Ionia Volos;;Greece;;359878820500;gdiamantopoulos@hotmail.com;www.greeceadventure.eu;Georgios Diamantopoulos
5631;1;Jolly Roger Sailing;39 Chrysostomou Smyrnis St;41223;Larissa;;Greece;+30 6995373400;;reservations@jollyrogersailing.gr;www.jollyrogersailing.gr;Sotiris Kyparissis
5626;1;Enjoy Sailing;Ploutarhou 3;50132;Kozani;;Greece;+30 693 70 252 70;;info@enjoy-sailing.gr;www.enjoy-sailing.gr;Dimitris Tsiamis
5624;4;Oceanis Yachting;Agiou Dimitriou 41. Piraeus;18546;Athens;;Greece;+30 690 8821115;;dvloceanis@gmail.com;www.oceanis-yachting.com;Vlad Sambur
5620;22;Arcadie Plaisance;22 Place Spoerry  Port Grimaud II;83310;Port Grimaud;Var;France;+33 (0)4 94 55 23 29;;charter@major-group.fr;www.arcadieplaisance.com;Antoine BERNAZ
5619;2;MGV Yachts Cruises;Ant. Miliaraki 14;71306;Heraklion;Crete;Greece;306970522373;;info@mgv-yachts-cruises.com;www.mgv-yachts-cruises.com;Ioannis Vasilakis
5603;2;Adriatic Sea Dalmatia;Zatonska 35;22211;Vodice;;Croatia;38598825729;;huljev13@gmail.com;;Marko Huljev
5598;5;Sebleke Sailing;Hjellestadvarden 53;5259;Hjellestad;;Norway;+47 41 222 572;;post@sebleke-sailing.com;https://torgeirtitland.wixsite.com/my-site;Torgeir Titland
5591;1;GiuGioMa Charter;via Resuttana 367;90146;Palermo;;Italy;+39 3398338976;;mgiordan2687@gmail.com;www.giugiomacharter.it;Maurizio Giordano
5589;5;Navis Marine;Trg Žrtava fašizma 5;10000;Zagreb;;Croatia;+385 1 4635 261;;charter@navis-marine.com;www.navis-marine.com;GORDAN ZADRO
5586;2;Boat Sharing Puglia;via Galeso 400;74123;Taranto;;Italy;393938740465;;info@boatsharingpuglia.com;www.boatsharingpuglia.com;Vincenzo Fanelli
5585;3;Navi-Gate;Seestrasse 851;8706;Meilen;Meilen;Switzerland;+41 76 505 40 31;;aurel@navi-gate.ch;navi-gate.ch;Aurel Vidailhet
5584;2;Adria Fun;Šetalište Vladimira Nazora 7a;51260;Crikvenica;;Croatia;385953806215;;info@adriafun.hr;www.adriafun.hr;Marko Vukovic, Petar Josipovic
5568;1;Kavala Sailing Holidays;P. Mela 15;65302;Kavala;;Greece;+30 6972026100;;tropicos@otenet.gr;www.kavalasailing.gr;Ioannis Karavas
5567;1;Milu Sail;CORSO CALATAFIMI 541/A;90129;PALERMO PA;;Italy;393313278755;;milusail.it@gmail.com;www.milusail.it;Pietra Bologna
5566;1;Starz Sailing;Atatürk Mah.Ertugrul Gazi Sok.No:2B Metropol Istanbul Sitesi C1 Blok K:15 D:247;34758;Atasehir / Istanbul;;Turkey;902166932093;905322345034;info@starzsailing.com;https://www.starzsailing.com;Berk Üner
5565;4;Neo Sailing;Marmaris Netsel Marina;48700;Marmaris, Mugla;;Turkey;+90 506 532 31 31;;info@neosailing.com;www.neosailing.com/en ;Gizem Peksari
5555;2;Falasarna Sailing;VRISON 21;73133;CHANIA;;Greece;+30 693 715 1933;;info@falasarnasailing.gr;www.falasarnasailing.gr;VASILIS MITROPOULOS
5548;5;Sail On Yachting ;Baglarbasi Mah.Sahin Sok.No:25;34844;Maltepe/Istanbul;;Turkey;+90 539 789 65 49;;begum@sailonyachting.com;www.sailonyachting.com;Hasan Murteza
5538;3;Argentum Charters;Alimos Marina;17455;Alimos;;Greece;+30 6988599579;;info@argentumcharters.gr;https://argentumcharters.gr/;Sarantopoulou Andromachi
5532;3;Ammos Yachting;VAS KONSTANTINOU 32A;19400;KOROPI;;Greece;+30 6972922754;;sales@ammosyachting.com;www.ammosyachting.com;Tsitoura Katerina
5531;1;Gulet Alba;Rovinjska 4;21000;Split;;Croatia;+385 98 315 691;;info@guletalba.com;www.guletalba.com;Marin Bojić
5530;4;La Bella Verde;Calle Josep Lluis Sert 28;7819;Jesus, Ibiza;;Spain;34663657636;;info@labellaverde.com;www.labellaverde.com;Maarten Bernhart
5529;2;Kamar Yachting;12 PENTAGION STR.;11147;GALATSIOU;;Greece;+30 6944338711;;charter@kamaryachting.com;www.kamaryachting.com ;VASILEIOS KAMAROTAKIS
5515;1;OrizzonteVela;Via Brescia 42;198;Roma;Roma;Italy;698968877;;info@orizzontevela.it;http://www.orizzontevela.it;Maria Cristina Leggiero
5508;3;Charter International;HULHUMALE LOT 10133;20237;Male;;Maldives;9607774091;9607774091;hello@charter-international.com;https://www.charter-international.com/;Ahmed Visham
5506;1;Lampedusa Charter;Via Pirandello, 10;92031;Lampedusa e Linosa (AG);;Italy;+39 922 971197;;info@lampedusa-charter.it>;www.lampedusa-charter.it;Giuseppe Vita
5488;1;Top Sail Italy;Via Riomaggiore 66;54;Fiumicino;Rome;Italy;+39 3284696610;;topsailit@gmail.com;www.topsailitaly.com;William Addobbati
5477;1;Chingene Sailing;Aksoy Mah. Yali Bulv. No: 386/2;35580;Izmir;;Turkey;+90 533 421 22 61;;info@chingenesailing.com;www.chingenesailing.com;Aykut Kayar
5475;1;A&H byGRACE;Cosmijeva 5;21000;Split;;Croatia;3859187309409;;letizia.b@bygrace.hr;www.bygrace.hr;Nikola Mimica
5470;1;Chalkidiki Yachts;LAGADA 170;56429;DIMOS PAVLOY MELA, THESSALONIKI;;Greece;+30 6973815622;;info@chalkidiki-yachts.gr;www.chalkidiki-yachts.gr;Svarna Gregory
5458;1;Portofino Yacht Charter;via Aurelia 10;16038;Santa Margherita Ligure, GE;;Italy;+39 340 224 4742;;info@portofinoyachtcharter.it;www.portofinoyachtcharter.it;Andrea Stagnaro
5456;1;Bellatrix Yachting;Plagenti B1;85330;Kotor;;Montenegro;+382 67 570 203;;info@bellatrixyachting.com;https://www.bellatrixyachting.com;Tripo Moskov
5447;1;Greece Yacht Rent;50 ALIMOU AVE.;17455;ALIMOS;;Greece;306944646511;;themis.sailing@gmail.com;www.greeceyachtrent.com;Themis Iliopoulos
5445;2;New Yacht Sailing;LEMONA 5A;15126;MAROUSSI;;Greece;+30 6955190636;;info@newyachtsailing.gr;;Mantelos Konstantinos
5439;1;d'Este Yacht Charter;Corso Villa reggia 60;25015;Riva Ligure;Imperia;Italy;393458880994;;info@desteyacht.com;www.desteyacht.com;Andrea Dominoni
5423;1;Sailing Court;Via F.lli Rosselli 4;54100;Massa (MS);;Italy;393287832790;;daniele.lorieri@gmail.com;www.sailingcourt.it;Daniele Lorieri
5414;12;Sailor Expert;Matika 27;;Brod Moravice;51312;Croatia;+385 99 5444470;;office@sailorexpert.com;www.sailorexpert.com;Miroslaw Nogaj
5411;1;Sea Cruises ;;70001;Krousonas, Crete;;Greece;306977170881;;manosloumpakis2014@gmail.com;www.sea-cruises.gr;EMMANOUIL LOUMPAKIS
5403;1;Calabria Adventure;Via Nazionale S.S 106.161;87076;Villapiana Scalo (CS);;Italy;393392931463;;info@calabriadventure.it;www.calabriadventure.it;Mario Gallo
5393;1;Helios Sails;Dermetzili 7a;14671;Nea Erythraia, Attica;;Greece;+30 693 689 5809;;helios.yachtschartering@gmail.com;;NIKOLAOS APOSTOLIDIS
5392;1;Dream Sailing;ELEFTHERIAS ARACHOVITIKA 50;26504;ANO KASTRITSI;;Greece;+30 6975301727;;info@dream-sailing.gr;www.dream-sailing.gr;KONSTANTINOS STASINOPOULOS TZEN
5390;5;Ocean Freedom;Via Scipione Ammirato, 39;50136;Firenze (FI);;Italy;+39 333 2656033;;info@oceanfreedom.eu;www.oceanfreedom.eu;Federico Costalli
5389;3;Setsail World;Valencia Mar, Cami Canal 91;46024;Valencia;Valencia;Spain;34661602902;34661602902;info@setsail.world;https://www.setsail.world;Alfredo Perez Pellicer
5386;3;Lagoon Charter;C/ del Cardener, 32 Atico 3;8024;Barcelona;;Spain;34669236000;;info@lagooncharter.com;WWW.LAGOONCHARTER.COM;PABLO REYES
5384;4;Porto Pollo Charter;Via Tramontana 3, Porto Pollo;7020;Palau (SS);;Italy;+39 3402753468;;info@portopollocharter.it;www.portopollocharter.it;Massimiliano Galeandro
5382;3;One Yacht;GOCEK MAH. INONU (GCK) BULVARI NO:35/3;48300;FETHIYE;MUGLA;Turkey;+90 552 334 94 88;;charter@oneyacht.org;https://oneyacht.org/;MERT DEMIRCAN
5380;4;Sailmax Sailing;Adakoy Mah. Kizilkum Mevkii No:76;48700;Marmaris;Mugla;Turkey;+90 533 280 76 28;+90 533 280 76 28;info@sailmax.com.tr;sailmax.com.tr;Umit DEMIR
5370;8;Asmira Marine;Mustafa Kemal Atatürk Mah.Aydin Cad. No:96;35880;Torbali / Izmir;;Turkey;;;charter@asmira.com.tr;www.asmiramarine.com;Taha DALGIÇ
5360;5;Cayo Tardo Charter;VIA Messina, 22;90141;PALERMO;;Italy;+ 39 338 4281633;;infocayotardo@gmail.com;www.catamarando.it;Lorenzo Agnello Cajozzo
5357;9;Almira Yachting;Akarca Villalari C10 Alikahya/Izmit;41310;Kocaeli;;Turkey;+90 252 988 02 80;+90 552 243 90 41;ersun.guzel@almira.tc;www.almira.tc;Ersun Güzel
5352;7;Derya Yachting;Karagözler Mah.Fevzi Çakmak Cad.No5/A;48300;Fethiye/Mugla;;Turkey;+90 533 958 25 94;;info@deryayachting.com;www.deryayachting.com;Mahammet ( Mahmut ) TEKINEL
5349;4;Just Sail;Serifali mah. Edep Sokak No:30/2;;Umraniye Istanbul;;Turkey;902524400252;;mutlu@justsail.com;www.justsail.com;Volkan Sönmez
5345;3;Cata Tribe;Via Crocefisso, 8;20122;Milano (MI) ;;Italy;+39 328 7060720;;info@catatribe.eu;catatribe.eu;Steven Cocchi
5344;1;Nel Blu Charter;Via principe Pantelleria 12 b;90146;Palermo (PA);;Italy;+39 3661694329;;info@nelblucharter.com;www.noleggiobarcheavelasicilia.com;Samuela Salerno
5343;5;Matrix Sailing;Göcek Mah. Akboyun Sk. No: 6;;Göcek- Fethiye / Mugla;;Turkey;+90 532 527 07 26;+90 532 527 07 26;booking@matrixsailing.com;www.matrixsailing.com;Orkun SAVASAN
5334;1;Franjkovic Charter;Lomnićka 83 A;10410;Velika Gorica;;Croatia;+385 98 311556;;info@franjkovic.hr;www.boat-apartments4u.com;Dražen Franjković
5328;2;Braves Sailors Estonia;Valujkoja 8/1;11415;Tallinn;;Estonia;+372 5232195;;erel@erel.ee;www.bravessailorsestonia.com;Riccardo Lelmi
5325;4;Special Charter;VIA ARRIGO BOITO 19;9129;CAGLIARI;;Italy;+39 392 9802060;;info@special-charter.com;www.special-charter.com;Valentino Bua
5322;9;Sailway;Selimiye Mah. Çeşme-i Kebir Sok. No:1A/1;;Üsküdar;ISTANBUL;Turkey;+90 530 302 71 71;;halil@sailway.com.tr;www.sailway.com.tr;Halil YILMAZ
5319;1;Masnàutic Yacht Charter;Calle Pou 2;8320;El Masnou;Barcelona;Spain;+34 631 417 528;;masnautic@gmail.com;www.masnautic.com;Bogdan-Ionut Bondane
5316;24;Rudder and Moor;Ali Suavi sokak No:9;;Maltepe / Ankara;;Turkey;+90 535 0192654;;nazli@rudderandmoor.com;www.rudderandmoor.com;Nazlı Pekmezcioğlu
5311;1;Chatzigiannis G & EE;TERPANDROU 1 NEOS KOSMOS;11743;Athens;;Greece;306972714951;;gigaskipper@yahoo.gr;www.captainyannis.com;Ioannis Chatzigiannis
5310;5;Buenoways;Foça, 1030. Sk. NO:166/B;48300;Fethiye Mugla;;Turkey;905334029070;905334029070;info@buenoways.com;www.buenoways.com;ERES SAKA
5307;2;Vrakotas Big Blue Charters;Danilia - Corfu;49100;Corfu;Ionion;Greece;+30 6936647465;;info@vrakotasyachting.com;www.vrakotasyachting.com;Victor Vrakotas
5306;3;3Lacs Yacht Charter;Waldrain 22;2562;Port;;Switzerland;41323333030;;charter@trois-lacs.ch;www.trois-lacs.ch;Gabael Houmard
5297;3;Kalis Mare;154 EGNATIA STR;54636;THESSALONIKI;;Greece;+30 697 911 1293;;socrateskalisperatis@yahoo.gr;www.kalismareyachts.com;KALISPERATIS SOKRATIS
5293;1;Lato Cruiser;EL.VENIZELOY 7;;ZEYGOLATIO;;Greece;306986139999;;latocruiser@gmail.com;www.latocruiser.com;Konstantinos Psiliotis
5292;6;Sailman Yachting;Marina Nikiti;63088;Chalkidiki;;Greece;306942450882;;info@sailman.gr;www.sailmanyachting.com;Jordan Mavidis
5286;1;Happy Captain;14 Panagias Myrtidiotissis st;14565;Agios Stefanos;Attiki;Greece;302103838573;306932050204;info@happycaptain.gr;https://www.happycaptain.gr;Yannis Karampelas
5282;1;Sailing Geo;via dei colli 3;40136;Bologna;;Italy;393381962849;;info@sailing-geo.com;www.sailing-geo.com;Pietro Buscaroli
5277;1;Nostalgia Sailing;1 K. Kerkyras street;41221;Larissa;Larissa;Greece;(+30) 6995326833;;info@nostalgiasailing.com;www.nostalgiasailing.com;Chalatsis Vasilis
5276;1;Pandora Sailing;Palairos;30012;Aitoloakarnania;;Greece;+30 6973694019;;hello@pandorasailing.gr;www.pandorasailing.gr;Lazaridis Anastasios
5275;1;Niriides;Kanakari 190;26222;Patras;Greece;Greece;306979243603;;info@niriidescruises.com;niriidescruises.com;Stergios Kourtellas
5269;6;Canal Boats Telemark;Stangsgate 1;3915;Porsgrunn;;Norway;+47 472 50 053;;post@canalboats.no;https://canalboats.no/en/;Nora Sjögren Johre
5254;1;Recuperaciones y desguaces La Pina;Marcel Li Gene 20 Vilanova i la Geltrú;8800;Vilanova i la Geltrú;Barcelona;Spain;+34690 79 95 38;;carlesgim87@gmail.com;;Carles Gimeno
5249;108;ARCHON Yachting;Georgiou Papandreou str.;85300;Kos ;;Greece;+30 6945220231;+30 6945220231;info@archonyachting.com;https://archonyachting.com/;George Ritsiardis
5245;1;Cool Change;Vladimira Nazora 2;51211;Matulji;;Croatia;3850958153148;;coolchangecatamaran@gmail.com;coolchangecatamaran.com;Nikola Krsanac
5237;1;Matern Yachting;Port of Volos;38221;Volos;;Greece;+359 888 355 711;+359 888 828 522;maternyachting@gmail.com;www.matern-yachting.com;Silvia Matern
5234;2;Charter & Sail;Pressentinstrasse 22;18147;Rostock;;Germany;+385 99 2004 237;;jurko.anzulovic@charter-and-sail.de;www.charter-and-sail.de;Tobias Waiss
5223;3;Primaboats;Avda Almudaina 8;7157;Puerto de Andratx;Mallorca;Spain;34971673447;;info@primaboats.com;www.primaboats.com;Juan Francisco Clar Marscaró
5216;1;Heyblue Yachting;Agiou Gerasimou str.,3a;17455;Alimos;;Greece;+30 6982 555615;;heyblueyachting@gmail.com;www.heyblue.gr;Antoniadou Maria, Kokkotas Thodoris
5212;1;Aziab Seafaris;Hamata Marina;;Marsa Alam;;Egypt;+20 1001040043;;info@aziab-seafaris.com;www.aziab-seafaris.com;Omar Sherif Or Yousra Gamea
5194;3;Good Sails;PEO Athinon - Patron & Paralia Platanou;25006;Akrata;;Greece;+30 694 470 0723;+30 694 470 0723;info@goodsails.gr;www.goodsails.gr;Theodoros Pappas
5162;6;Zantium Travel;R. F. Mihanovića 9;10000;Zagreb;;Croatia;38512231000;;hello@zantium-travel.com;www.zantium-travel.com;Roman Reicher
5161;1;Dilios Yachting MCPY;Oumplianis 18;16121;Athens;;Greece;302108041093;306944274197;diliosyachts@gmail.com;www.diliosyachting.com;Thanassis Kontonatsios
5158;1;The Ocean Week;Sitio do Ribeiro, Cond. Casa na Praia, Lote 10 R/C D;8500;Alvor;;Portugal;351914094347;;welcome@theoceanweek.com;www.theoceanweek.com;Miguel Luis
5157;1;LuxMediterranean Experience;Zagrađe 12;22222;Skradin;;Croatia;;+385 99 525 7350;office@luxmediterranean-experience.com;www.luxmediterranean-experience.com;Vlado Odribožić
5146;1;Yacht Escape;Put Gradine 1;21220;Trogir ;;Croatia;385915425625;385915425625;info@yachtescape.eu;www.yachtescape.eu;Ioan Grecu
5118;1;La Luna Sailing;DONANMACI MAH. KEMALPASA CAD. ALTINHAN ISHANI NO: 147 IC KAPI NO: 101 KARSIYAKA;35000;Izmir;;Turkey;905335938800;905335938800;coskun@lalunasailing.com;https://lalunasailing.com/;Coskun Ince
5110;1;Euroholiday;Ob. dr. F. Tuđmana 33;23207;Turanj;;Croatia;+38595 529 9057;;euroholiday.agency@gmail.com ;www.euroholiday.hr;Joško Bolić
5106;1;Yacht Lagaro;Avenida 8 de Agosto no 38.;7800;Eivissa;;Spain;+36 70 2608381;;smartwavec@gmail.com;www.yachtlagaro.com;
5103;9;Julio Verne Nautica;Calle del Reloj 5, oficina 3;36300;Baiona, Pontevedra;;Spain;+34 683 251 682;;office@juliovernenautica.com;www.juliovernenautica.com;IVAN PEREZ-GANDARAS
5098;3;The Sailing Experience;Oran mah. Faik Öztirak Cad. Beykent Sitesi A Blok No: 2/11;;Cankaya / Ankara;;Turkey;905559830177;;yasemin@thesailingexperience.net;www.thesailingexperience.net;Yasemin Gündüz
5097;2;Co Yacht;79 ARISTOTELOUS STR.;;ATHENS;;Greece;+30 6937349485;;info@coyachtingreece.com;www.coyachtingreece.com;Haris Kotsokolos
5091;3;Zanzibar Yachtfun;Ul Lipowa 2/1;84-208;Warzno;;Poland;+48 515 545 887;+48 510 033 218;office@zanzibaryachtfun.com;www.zanzibaryachtfun.com;Czeslaw Wincent Kowalczyk
5090;4;Sailor's Moon;RIJEČKA 18;21000;Split;;Croatia;385958110379;;KRISTINA@OMGSAILING.COM;www.sailorsmoon-charter.com;Kristina Vlasova
5087;5;Global Yachting;Rovinjska 4;21000;Split;;Croatia;40745236003;;office@globalyachting.ro;www.globalyachting.ro;Mihai Blaguescu
5080;1;Sailines;Ypsilantou 7;18532;Piraeus;Athens;Greece;+30 216 7004627;;info@sailines.gr;https://sailines.gr/;Michalis Kritikos
5076;1;Sail & Charter;C/MUNTANER Nº 81;8011;Barcelona;;Spain;+34 932523289;;rcellier@sailcharter.es;www.sailcharter.es;Rosario Cellier
5071;15;Yachtcharter-Rhodes;AFSTRALIAS-NEW MARINA;85100;Rhodes;;Greece;+30 2241 0745 04;;george@yachtcharter-rhodes.com;https://www.yachtcharter-rhodes.com;SOTIRIOU ATHANASIOS
5070;5;Lebić Yachts;Fra Bonina 23;21000;Split;;Croatia;+385 91 5479 970;;lebicyacht@sailingcroatiacharter.com;sailingcroatiacharter.com;
5057;1;Starfish Chartering;Platonos 12;;Piraeus;;Greece;+30 694 32480 70;;booking@starfishchartering.com;www.starfishchartering.com  ;Zampetis Eleftherios
5055;1;Fornes Catamaran;Roncesvalles 5 bajo;46901;Torrent;Valencia;Spain;+34 666 285 219;;info@fornescatamaran.com;WWW.FORNESCATAMARAN.COM;Carlos Victor Carrasco Fornes
5044;0;Evergreen;Petar Raychev Str. 2;9000;Varna;;Bulgaria;359895432152;;dobreveverr@gmail.com;www.yachttour-bg.com;Danail Dobrev
5042;12;Yaloou Services P.C.;Grigoriou Afxentiou 1;17455;Alimos;Attiki;Greece;302103356015;306985757773;charters@yaloou.com;www.yaloou.com;Francesco Lemonis
5022;4;7seas Maritime;Spefsippou 7;10675;Athens;;Greece;306931481111;;charter@7seas-maritime.com;www.7seas-maritime.com;Christiana Tsiligianni
5019;5;Catamaran Sailing;Kvarnerska cesta 35;51211;Matulji;Primorsko - goranska;Croatia;;385912914311;info@catamaran-sailing.hr;www.catamaran-sailing.hr;Igor Lukanović
4999;2;Apoplous Yachting; Marina Lefkadas;311 00;Lefkada;Ionian islands;Greece;306972275242;;charters@apoplous.gr;www.apoplous.gr;Konstantinos Tetriggas
4993;2;Sea Smiles;115 DELIGIORGI STREET;18534;PIRAEUS;;Greece;306948054477;;info@seasmiles.eu;www.seasmiles.eu;MICHAEL HATZIMIHALIS
4982;3;Sea Spirit Mauritius;Coastal Road, ;1713-01;Pointe D'esny;;Mauritius;+230 5727 3216;;contact@catamarancruisesmauritius.com ;www.seaspiritcruises.com;Christophe Desmarais
4971;1;Add Wind;Via Anile 12 Lamezia Terme;88046;Catanzaro;;Italy;393351335749;;addwindasd@gmail.com;www.addwind.it ;Gianluca Coratto
4969;12;Mediterranean Yachting Holidays;Kolokotroni 1 , Kifisia;14562;Athens;;Greece;302107010359;;chartering@myholidays.gr;www.myholidays.gr;Mr Xenos Theodoros
4963;7;Globus Yachting;Yposminagou Parousi 86 - 88;17676;Kallithea;;Greece;302110128590;;info@globusyachting.com;www.globusyachting.com;john Mexas
4960;1;Sea Infinity MCPY;MITHRIDATOU 24;11632;ATHENS;;Greece;+30 6932738009;;info@seainfinity-yachting.com;www.seainfinity-yachting.com;Ioannis Speis
4949;38;BeExperience;Avda. Perez Galdos 17-2;;Valencia;;Spain;34644577597;;gestion@beexperience.es;www.beexperience.es;Maria Jose Martinez Maroto
4948;5;Nonno Blue;Kultur Mah. Arnavutkoy Yolu SK No 56;;Istanbul;;Turkey;902122870050;905352206851;doruk.kaya@nonnoblue.com;www.nonnoblue.com;Doruk Kaya
4945;1;Butterfly Water Sports Croatia;Kraj 83;21325;Tucepi;Dalmatia;Croatia;+385 216 23777;+385 99 655 47 68;yachting@butterfly-water-sports.com;www.butterfly-water-sports.com;Sebastian Theilig
4942;16;Noe's Sailing;Husinecká 903/10;13000;Praha 3;;Czech Republic;+420 776 598 196;;info@noesailing.com;www.noesailing.com;Andrea Samalova
4941;2;Paluko Yachting;Yahsi Mah. Hortma Cad.;;Bodrum;Mugla;Turkey;905336534437;;info@paluko.com;www.paluko.com;Murat Esim
4940;1;Skippertech;Via Cammarano 19;80129;Napoli;;Italy;393346937453;;gm@skippertech.it;www.skippertech.it;Giulio Mangia
4938;2;Kornati Yachting;A.G. Matoša 4;23210;Biograd na Moru ;;Croatia;385916050647;;info@Kornatiyachting.hr;https://kornatiyachting.hr/;Branimir Kolarić
4936;7;Sailera Yacht Charter;Setur Netsel Marina No 54;48700;Marmaris;Muğla;Turkey;+90 252 412 34 56;+90 532 434 53 72;reservations@sailera.com;https://www.sailera.com;Nusret Dönmez
4931;5;Avdiros Sailing;28Th October 175;;Xanthi;;Greece;306948892119;;charter@avdiros-sailing.gr;www.avdiros-sailing.gr;Baloglou I.
4925;1;MV yachts;71-75 Shelton Street, Covent Garden, London;WC2H 9JQ;London;;United Kingdom;306944299554;;mvyachts@outlook.com;mvyachts.gr;Vagenas Michael
4906;2;Vento Di Grecale s.r.l.s;VIA DEI CESPUGLI, 9;95024;ACIREALE;Sicily;Italy;393939003214;;info@ventodigrecale.com;http://www.ventodigrecale.com/;VINCENZO SORBELLO
4899;1;Have Fun Sailing;Thessalonikis 36;18345;Moschato;Athens;Greece;306947464540;;info@havefunsailing.gr;http://www.havefunsailing.com/;Panagiotis Plakiotis
4894;2;Poole Yacht Charter;29, Golf Links Rd;Bh18 8BQ;Poole, England;;United Kingdom;+44 7769658412;;chris@pooleyachtcharter.co.uk;http://www.pooleyachtcharter.co.uk/;Chris Underwood
4883;2;Scirocco Sailing;Via Lia n.55;89122;Reggio di Calabria;Reggio di Calabria;Italy;393289074417;;info@sciroccosailing.com;https://www.sciroccosailing.com/;Alessandro Azzara
4882;1;Catamaran Experience;Via Carducci 22;20123;Milano (MI);;Italy;+39 3888672683;;annamaria.dauria@catamaranexp.com;www.italycat.com;Annamaria D'Auria
4872;4;SAM Sailing;Orhaniye Mah. Iskele Sok. No: 14/1, 48700, Marmaris, MUĞLA;48700;Marmaris, Orhaniye;;Turkey;905312612686;;info@sam-sailing.com;www.sam-sailing.com;Melih Ersöz
4870;18;Split Yacht Charter;Uvala Baluni 8;21000;Split;;Croatia;38521358344;385916053947;info@splityachtcharter.com;https://www.splityachtcharter.com;Ivana Botić
4863;9;Conch Charters;Fort Burt Marina;VG1110;Road Town;Tortola;British Virgin Islands;+1 284 494 4868;;sailing@conchcharters.com;www.conchcharters.com ;Brian Gandy, Cindy Chestnut, Peter Twist
4851;12;Swede Charter Travel AB;Södra Strandgatan 10;44266;Marstrand;;Sweden;+46 (0)303 61689;+46 (0)767 771150;staffan@swedecharter.com;http://www.swedecharter.com/;Staffan Francke
4847;12;Outsail Srl;Piazza Vittorio Veneto, 4;50123;Florence;;Italy;+39 3664787655;;maranoandrea84@gmail.com;http://www.outcageviaggi.com/;Andrea Marano
4842;0;Seven Charter;Via Vittorio Veneto 12/a Terranuova Bracciolini;52028;Arezzo;toscana;Italy;393534216049;;info@sevencharter.it;www.sevencharter.it; MAURO MORELLI
4841;3;ADRIATIC BOAT CHARTER;Avenija Dubrovnik 16;;Zagreb;;Croatia;385993809905;;bookings@adriaticboatcharter.com;www.adriaticboatcharter.com;Jacob Rz
4840;12;Amfitriti Discovery ;MONIS VATOPEDIOU 19;26336;Patras;;Greece;306944662282;;takisath@gmail.com;amfitriti-sail.gr;IOANNIDIS IRODION
4835;0;Scubaspa Maldives;Boduthakurufanumagu, H.Aagadhage 3rd floor;C0510/2013;Male;Male;Maldives;9609636050;;charters@scubaspa.com;https://scubaspa.com/;Maru Rivera
4834;7;Touch Adriatic;Njegoševa 6;21 000;Split;Croatia;Croatia;+385 91 577 4552;;info@touchadriatic.com;www.touchadriatic.com;Darko Šupuk
4830;1;Scorpion Yacht Charter;Štefani 12f;51216;Viškovo;Croatia;Croatia;+385 99 214 3404;;zarcanin@gmail.com;https://www.facebook.com/Yacht-Bavaria-44-Scorpion-Rijeka-103081495227611/;Zoran Arčanin
4828;6;Westsails Yacht Charter;BARON RUZETTELAAN 94;8310;ASSEBROEK;;Belgium;+32 477 25 46 36;;info@westsails.be;http://www.westsails.be/;HENDRIK LAVAERT
4825;14;Infinity Yacht.S;Vakchou 1;54629;Thessaloniki; Central Makedonia;Greece;+49 1717395005;;service@infinity-yachts.de;http://www.infinity-yachts.gr;Mark Straßberger
4823;0;VIDO;Via Catania 78;90141;Palermo;;Italy;+39 333 5498592;;vidosrls21@gmail.com;www.vidosrls.it;Domenico Marco
4813;3;Aquariva;Grabenstrasse 15a;6340;Baar (ZG);;Switzerland;+41 44 836 50 80;;booking@aquariva.ch;www.aquariva.ch;Patrick Pfister
4812;1;Adriatic cruising;Žilića potok 1a;21315;Dugi Rat;;Croatia;385915106100;;adriaticcruisn@gmail.com;;Petar Matković
4798;1;Palmyra Yachting;Kiprou 50;16231;Vironas;;Greece;0030 6955555550;;charter@palmyrayacht.com;www.palmyrayacht.com;IOANNIS KARAGIANNIS
4776;2;Sail on Wave;Via Itria, 9;91025;Marsala;;Italy;+39 328 66 311 96;;info@sailonwave.com;www.sailonwave.com;Giuseppe Linares
4770;2;MAREL rent a boat;Kringa 4;52444;Tinjan;;Croatia;+385 91 2686 622;;artteksem@gmail.com;;Marino Erman
4762;2;Daddario Yacht;Via per Massafra 82/84;74123;Taranto ;;Italy;+39 391 116 8159;;charter@daddarioyacht.it;www.daddarioyacht.it;Daniela D'Addario
4760;1;TMV Travel & Sailing ;Nachtigallenweg 14;61462;Königstein;;Germany;491774019117;;tanjavidovic@web.de;https://tmv-reisen.de/;Tanja Mamic-Vidovic
4748;8;TYC Serenity;Koza 1 Caddesi. No: 124/1;6670;Ankara;;Turkey;905427331975;905427331975;melih.topcu@tycyachting.com;www.tycyachting.com;Melih Topcu
4747;4;Salty Dog Charter;;;;London;United Kingdom;;;mike@solischartergroup.com;;Keidar
4732;1;Makani Safari;Calle Clavel 1, 38, El Medano;38612;Granadilla de Abon;;Spain;+371 26 177 512;;makanisafari@gmail.com;www.makanisafari.com;Natalia Tolstokorova
4731;19;Croatia Sailing Charter;Kučine 92, Vinkuran;52203;Medulin;Istarska;Croatia;;+385 99 712 7772;charter@crosailingacademy.com;www.crosailingacademy.com;Toni Brkić
4727;1;Croatia Cruises;Molo Lozna 23;21410;Postira;;Croatia;;+385 98 165 6025;booking@luxuryholidaycharter.com;https://luxuryholidaycharter.com/;Ante Šerka
4725;1;Sail Croatia Adriatic Adventures;Supetarska Draga 270;51280;Rab;;Croatia;+385 993578811;;info@sail-croatia-adventures.com;www.sail-croatia-adventures.com;Denis Makaus
4715;30;Ancyra Sailing;Izmir 2 Cad. 55/18 Kizilay;6420;Ankara;;Turkey;(+90 532) 214 1604;;info@ancyrasailing.com;http://www.ancyrasailing.com/;Mrs. CANDAN TOKYUREK
4709;1;ARC Sailing ;Eynardou 8;15237;Athens;;Greece;+30 6944474499;302106855379;kareteos@gmail.com;www.facebook.com/NaiadaSailingConstantinos;Constantinos Areteos
4692;0;Kydonas Yachting;Alexandrou Vrahnou 5;11524;Athens;;Greece;306907395754;;kydonas.yachting@gmail.com;;Prodromos Kydonas
4689;1;Tsakos Yachting;Agiou Alexandrou 65;17561;Palaio Faliro, Attiki;;Greece;306985550558;;alkis@tsakosyachting.com;www.tsakosyachting.com;Panagiotis Tsakos
4688;3;Arca Sailing;Viale Regina Margherita, 28;88900;Crotone;;Italy;;+39 340 135 9671;info@arcasailing.com;www.arcasailing.com ;Sonia Larsen
4686;4;MyWay Yachtcharter;Marina Frapa, Uvala Soline 1;22203;Rogoznica;;Croatia;+43 664 4621185;;info@myway-yachtcharter.com;www.myway-yachtcharter.com;Andreas Mühlgassner
4683;13;Aquatrotters;51, Politechniou Str.;54625;Thessaloniki;;Greece;302316014941;306973231846;charters@aquatrotters.com;www.aquatrotters.com;EFFROSYNIDIS LEONIDAS
4682;1;Codezero Sailing;AGIOU DIMITRIOU 41;18546;Pireus;;Greece;;+39 349 8845484;info@codezerosailing.com;www.codezerosailing.com;Gianluca Vitelli
4676;3;Aelia Yachting;Valtetsiou 12;18450;Nikaia ATTIKIS;;Greece;302104007953;306988028722;info@aeliayachting.com;www.aeliayachting.com;Damianos Makris
4674;6;Ocean Yacht Charter;26A Plena Court Triq Il- Vittmi Tal-Gwerra;BKR 4272;Birkirkara;;Malta;38521210288;;ivan@ocean-yacht-charter.com;ocean-yacht-charter.com;Ivan Speranda
4672;9;Tailwind Yachting;Lefkosias  27;17455;Alimos;;Greece;;;info@tailwindyachting.com;www.tailwindyachting.com;Maria Mazaraki
4666;4;Lagoon-Charter;Calle Mallorca 18;7160;Paguera;;Spain;34617519978;;info@lagoon-charter.eu;www.Lagoon-charter.eu;Thomas Schaal
4664;22;Marina Yacht Charter;Via Mulino 3;6855;STABIO;;Switzerland;+41 91 21 01 369;+41 76 837 76 37;booking@marinayachtcharter.com;https://www.marinayachtcharter.com;
4659;1;MT NEPA;IPPOKRATOUS 13;68200;ORESTIADA;;Greece;+30 2109849201;+30 6941410633;mtnepa@yahoo.com;https://mtnepa.wixsite.com/mysite;TABAKOS FOTIS
4656;2;Fournaros Yachts;Grigoriou Lampraki 12;18454;Nikea/Athens;;Greece;+30 6971937227;;info@fournaros-yachts.com;www.fournaros-yachts.com;Stavros Fournaros, Alexandros Fournaros
4650;2;Sestante Charter ;Via Puccini 6;52025;Montevarchi (AR);;Italy;+39 3207036490;;info@sestantecharter.it;https://sestantecharter.it;Francesco Manetti
4647;14;Cavo Yachting;11 Eleftherias Avenue;17455;Alimos, Athens;;Greece;306980590048;;charters@cavoyachting.com;www.cavoyachting.com;Eva Frey
4646;5;Sailorsbreeze;Gouvia marina , Corfu , 49100 , Greece;;;;Greece;0030 2661098581;0030 698 741 8374;partner@sailorsbreeze.com;https://partners.sailorsbreeze.com;Panayotis (Potis) Theofilakos
4644;1;Gulet Broker Yachting;Put Bioca 55;22000;Šibenik;;Croatia;905428300863;905428300863;info@guletbroker.com;www.guletbroker.com;Oktay Unlü
4638;1;Yacht Charter Greece;GRIVA 45;85100;RHODES;;Greece;306932911900;;info@yachtchartergreece.gr;https://www.yachtchartergreece.gr/;THANOS PAPAILIOU
4629;1;Reisedienst Ehret;Erlenweg 37, D-61352 Bad Homburg;D-61352;Bad Homburg;;Germany;+49 6172 45 80 19;;Info@JoergEhret.de;www.JoergEhret.de;Jörg Ehret
4623;3;SAIL2DAY;Vliho;GR31084;Lefkas;;Greece;306931150794;;info@sail2day.com;http://www.sail2day.com;Christiana Fass
4622;14;DEDA Sailing - Sailing Holidays Hub;GLAVANI 50, VOLOS;38221;VOLOS;;Greece;;447894313120;info@dedasailing.com;www.dedasailing.com;Dimitrios Karampatos
4613;13;Crosail;Slavonska avenija 72;10000;Zagreb;;Croatia;+385 91 6180 177;;info@crosail.com;https://crosail.com/;Ilija Topić, Karla Krasnić
4611;10;MEDITERRANEAN SAILING GROUP;77km Athens-Sounio Road;19500;Lavrio;;Greece;+30 6977 793938;;info@medsailgroup.com;www.medsailgroup.com;GEORGE MANTZARIS
4607;2;Gamma Sailing;HLEKTROUPOLEOS 6,ARGYROUPOLIS,ATHENS;;ATHENS;;Greece;00 30 6940647484;;yiotarizou@gmail.com;;IOANNIS XIROGIANNOPOULOS
4604;1;Dalula Marine;C/ Xaloc de Binisafua, 14;7711;San Luis (Menorca);;Spain;34696085214;;info@sailingesquitx.com;www.sailingesquitx.com;Federico Cardona Pons
4600;2;MY Superior;Vicolo San Rocco;30034;Mira;;Italy;+39 333 88 64 532;;palmarin.mose@gmail.com;https://www.venicesuperiorboat.com/;Mosè Palmarin
4595;2;Mala Luna ;Domovinskog rata 7a;21210;Solin;;Croatia;;385912500079;info@mala-luna.hr;www.charter.a-yachts.hr;Marko Lakic
4584;2;IDEA;Via Roma, 63;92014;Porto Empedocle;;Italy;+39 3345826415  (Ivano);;cat@idea-srl.eu;www.ideacatamaran.com;Ivano Midulla
4581;16;Blue Memories;21 Lefkata;31100;Lefkada ;;Greece;+ 30 6972 320008;;charter@bluememories.gr;www.bluememories.gr ;Anastasios Papaioannou
4571;1;Capsiwa;Finikas;84100;Syros, Kiklades;;Greece;306971668991;;info@capsiwa.ch;www.capsiwa.ch;Pellet Sylvain
4570;1;Antoniou Yachting;Gortzi 49;34100;Chalkida;;Greece;306936795605;;kozatos@hotmail.com;;Antoniou Athanasios
4548;2;Charter Miri;Ivana Matetić Ronjgova 113;;Zagreb;;Croatia;385992132826;;andrea.mikulic@zeljko.hr;;Dragan Zeljko
4536;1;CG-VILLAS & YACHTING;Agios Ioannis (Palli);49100;Corfu;;Greece;306944258481;306944258481;info@catamaranholidays.gr;https://www.catamaranholidays.gr;Doukakis Charalampos (Babis)
4533;1;Euphoria Sailing;K.AGRA 4;58200;EDESSA;;Greece;306973443639;;euphoriaseasailing@gmail.com;www.euphoriaseasailing.com;Tanousi Maria
4505;0;Rentayachteurope;Nieuwborgstraat 94;5922VD;VENLO;;Netherlands;306988238626;;booking@rentayachteurope.com;www.rentayachteurope.com;Dionisios Pastras
4503;9;Booktheboat;Kucukbebek Cad. Dayibey Sok. No: 2A Bebek- Besiktas;34342;Istanbul;;Turkey;+90 (252) 316 16 00;905335713141;info@booktheboat.com;www.booktheboat.com;Baris Selamioglu
4488;1;Colibri Charters;Corso Vittoria Colonna, 88;80077;Ischia;;Italy;393336319925;393336319925;info@colibricharter.com;https://colibricharter.com/;Settimio Costa
4485;4;Butch Sailing;Iakovou Polyla 3A;49100;Kerkyra;;Greece;31645090003;;info@butchsailing.com;www.butchsailing.com;Jannetje Plas, Ielse Mulders, Jan Pieter van Zijtveld, Yvan T
4484;3;Montenegro Submarine;Dobrota, Plagenti - Blok C br.3;85330;Kotor;;Montenegro;+38267/711999;;info@montenegrosubmarine.me;www.montenegrosubmarine.me/me/;Ilija Pajović
4438;45;Vernicos Yachts;11 Poseidonos Avenue;17455;Alimos;;Greece;+30 2109896000;;charter@vernicos.gr ;https://vernicosyachts.com/;Spyridon Filippou
4432;3;WTT Sailing;Av. D. Afonso Henriques 1124, H707;4450-011;Matosinhos;;Portugal;+351 935672814;;paulo.lima@wttsailing.com;https://wttsailing.com;Paulo Lima
4431;2;MJ charter;26, 757 Str., Sofia, Bulgaria / office Greece: Marina Syvota, Lefkada;;;;Bulgaria;+30 698 981 0642;+ 359 896 613 520;office@MJcharter.com;www.mjcharter.com;Vladimir Petrov
4428;3;Ionian-Ray;16str, Filikis Etaireias, Ayia Zoni;3031;Limassol;;Cyprus;;306932441601;charter@ionian-ray.com;www.ionian-ray.com ;Theofano Mpardaki
4421;0;Quicksail Charter;Locales comerciales Marina Norte, local 8 (Marina Real Juan Carlos I);46024;Valencia;;Spain;34615229700;;info@quicksail.es;https://barcoscharter.com;Isabel Gil Forteza
4414;3;Amon Ra Yacht Charter;Vinka Jelića 1;23000;Zadar;;Croatia;38641795633;;sailing@kontiki-sailing.com;;Sebastijan Levstik
4405;2;Ukiyo Yacht Management;50 Vasileos Georgiou A' Square;26221;Patra;;Greece;+30 6934054071;;info@ukiyo-yachts.com;www.ukiyo-yachts.com;Panos Koutsourakis
4396;3;Abruzzo Skipper;Corso de Parma 13/Aa;66054;Vasto CH;;Italy;+39 0873 671503;;info@abruzzoskipper.com;www.abruzzoskipper.com;Ettore Battista
4391;19;Zen Yachting;Irodou Attikou 7;14561;Athens;;Greece;+30 6980113646;;info@zen-experience.com;https://zen-experience.com/;Margarita Boumi - Theoni Gerouli
4385;1;IstraCharter;Trgetari Ivanušići 9E;52220;Raša;;Croatia;;385989500968;r.jelcic@googlemail.com;www.istracharter.com;Robert Jelčić
4383;13;Lycian Sail;Senlikkoy Mah. Yesilkoy Halkali cad. Aqua Florya;34153;Istanbul;;Turkey;+ 90 538 678 80 08;;info@lyciansail.com;https://lyciansail.com/;Varol Kose
4378;1;Gecko Charter;Vukovarska 30;21000;Split;;Croatia;+385 99 468 8850;;info@gecko-charter.com;www.gecko-charter.com  ;Aleksandar Solarić
4371;18;Most Sailing;Karagozler Mah. Fevzi Cakmak Cad. No. 135, Fethiye;48300;Mugla;;Turkey;+90 532 289 1427;;charter@mostsailing.com;www.mostsailing.com;Pinar Erzin & Ozgur Tefe
4366;3;Apeiron Yachting;Str. Dagkli 23;54621;Thessaloniki;;Greece;+30 2310275 244;;info@apeironyachting.gr;www.apeironyachting.gr;Dimitrios Kopellou
4348;1;Sailing Balearic;C CERA 55, PLANTA 4,PUERTA 3;8001;Barcelona;;Spain;;;sailing@sea-barcelona.com;www.sailingbalearic.com;Alexander De Vos
4347;4;Messinia Yachts;International House, 64 Nile Street, London, United Kingdom, N1 7SR;N1 7SR;Operating Address: Kalamata Marina;London;United Kingdom;306937405456;;charter@messiniayachts.com;www.messiniayachts.com;Petikides Dimitrios
4324;5;Spiridakos Sailing Cruises;Ermou 11;16674;Glyfada;;Greece;+30 22860 23755;;sailing@spiridakos.gr;www.santorini-yachts.com;Nikolas Lardis
4314;6;Ibiza Formentera Charter;Venda des monestir 1153;7872;Formentera;;Spain;34619112611;;reservas@ibizaformenteracharter.com;www.ibizaformenteracharter.com;Joaquin Rambla Blasco
4307;4;Arundel yachting;VIA GIUSEPPE MACAGGI 23/3;16121;Genova;;Italy;+39 335 6765359;;info@arundelyachting.com;www.arundelyachting.com;MAURO ZAMPINI
4303;1;Sunrise Sorrento;Via Petrulo, 43;80063;Piano di Sorrento (NA) ;;Italy;+39 334 994 0458;;info@sunrisesorrento.com;www.sunrisesorrento.com;Nello D' Esposito
4300;3;Yacht Getaways;6th Floor 2 London Wall Place;;London;;United Kingdom;+44 203 637 9391;;charter@navigatetravel.com ;www.yachtgetaways.com;Daniel Painter
4294;1;Luxe Yachting;GIMNASTIRIOU 42 STREET;18454;NIKAIA, ATTICA;;Greece;306975576057;;luxeyachtingreece@gmail.com;https://luxe-yachting.com/;OMIROS TSENOGLOU
4282;1;Aegeo Sailing;Rathes Skopelos;37003;Skopelos;;Greece;+ 30 6974736333;;info@aegeo-sailing.gr;www.aegeo-sailing.gr;Vasilios Parasidis
4281;3;Sailing Planet;26 Peiraios str.;19013;Palaia Fokia;;Greece;306941633033;+30 6941633033;charter@sailingplanet.eu;https://www.sailingplanet.eu/en/;Yulia Cherezova
4280;3;BLITZ;LUJACI 5;20234;ORAŠAC;;Croatia;98285378;;info@blitzcroatia.com;www.blitzcroatia.com;MAROJE BANIČEVIĆ
4274;1;Globalia Yachting LTD;Nicosia, Themistokli Dervi, 41 Hawaii Nicosia tower, office 806-807, 1066;;Limassol;;Cyprus;35799091109;;charters@globaliayachting.com;www.globaliayachting.com;Mr. Nino Tager
4272;1;Simonetta;Mitilinis 1 Anoixi, Athens;14569;Athens;;Greece;+4179 439 60 52;;simonettamcpy@gmail.com;;Konstantinos Kalogerakis
4269;2;Rib Solution;Calle Cesar Roger Piquet 78;;Ibiza;Balearic Islands;Spain;+34 871 113 111;;info@ribsolution.com;www.ribsolution.com;Antonio Scognamillo
4264;12;Valentino Yachts;109, Leof Amfitheas, P. Faliro;17563;Athens;;Greece;306970331222;;info@valentinoyachts.com;www.valentinoyachts.com ;Dimitris Delikaris
4258;2;Pearl 1 Yachting;Kurfürstendamm 32;10719;Berlin;Berlin;Germany;+49 305 1695 8443;;yachting@pearl1.de;yachting.pearl1.de;Till-Oliver Kalähne
4252;6;Sailink Charter;V Traversa del Pescatore;89900;Vibo Valentia (VV);;Italy;+39 347.5821454;;reservation@sailinkcharter.it;www.sailinkcharter.it;Danilo Montagnese
4248;10;Italiamare;Via Melisurgo 23;80133;Napoli;;Italy;+39 0818186615;+39 3290740427;noleggio@italiamaresrl.it;www.italiamaresrl.it;Giulio Barra
4241;5;Nimbus Center Croatia;Obala Rtine 1a;22213;PIROVAC;;Croatia;+385 99 5458 646;;ivana@nimbuscharter.com;www.nimbuscharter.com;PIOTR PILCH
4239;4;Panda Sailing;Uvala Soline 1;22203;Rogoznica;;Croatia;+385 95 8034 227;+385 91 794 31 33;info.pandasailing@gmail.com;www.pandasailing.hr;Zoran Jarak
4238;3;Inti SRL;STRADA STATALE N.113 KM, 326 SNC;91011;Alcamo TP;;Italy;+39 3477602394;;info@intisrl.com;www.inticharter.it ;Francesco Fundarò
4223;1;Sail-Med;Karagozler Mahallesi Fevzi Cakmak Caddesi No 29/B;48300;Fethiye Mugla;;Turkey;+90 252 612 1005;+90 532 516 1005;info@sail-med.com;www.sail-med.com;Cem Gurkan
4212;1;IonianYachtCharter;Paleros;30012;Paleros;;Greece;+44 (0) 7470 044928;;davewhittel@hotmail.co.uk;ionianyachtcharter.co.uk;David James Whittel
4209;3;Greek Yachts;5 Ammochostou Street;165 61;Glyfada, Athens;;Greece;306936555866;;info@greekyachts.com;www.greekyachts.com;Loukas Antonatos
4208;3;Greek Sun Yachts;Veikoy str 119;11741;Athens ;;Greece;306936594391;;greeksunyachts@gmail.com;www.greeksunyachts.com;George Aggelakos
4203;5;Azmarine;Viale Africa 17/19;95129;Catania     ;Sicilia;Italy;+39 0931 1840680;+39 349 2687387;info@azmarine.it;https://www.azmarine.it;Armando Crispino
4201;1;Sundream;VIA F SCO CRISPI N 108;90139;PALERMO ;PA;Italy;+39 091 2511066;;info@eoliesundream.it;https://www.scuolanauticasundream.it;Irene Casilli
4188;6;Marimari;Via Longobardi I trav. 9;89900;Vibo Valentia Marina;;Italy;+39 328 0659996;;booking@marimari.it;www.marimari.it;Francesco Murmura
4177;3;Padomar;21, Poseidonos Ave.;17455;Alimos;;Greece;(+30) 2109828454;;info@padomar.gr;www.padomar.gr ;Michael Kostopoulos
4175;4;Brava Charter;C/ Eugeni d'Ors, no15, baixos;17480;Rosas;Girona;Spain;+34 628 429 619;;info@bravacharter.com;www.bravacharter.com;Marc Vila
4174;1;Navigare Worldwide;7 D;;Dublin;Ireland;Ireland;+353 834649863;;charter@navigareworldwide.com;https://www.navigareworldwide.com/;Carlo Farris
4173;14;Dynamic Sailing;Panormos;19500;Lavrion;;Greece;306976600383;;dynamicsailing@gmail.com;www.dynamicsailing.gr;Ioannis Vogiatzoglou Kosmas
4172;1;BVA Charter;Aristidou str. 15;18531;Athens, Pireas;;Greece;+359 898467478;;office@bvacharter.com;www.bvacharter.com;Borislav Borisov
4164;4;Adonis Sailyachts;11th KLM LARISA-VOLOS;40009;PLATYKAMPOS, LARISA;;Greece;+30 6936938296;;info@adonis-sailyachts.com;www.adonis-sailyachts.com;Antonios Kostavaras
4157;1;Neta Cruises;Karya 0;31080;Lefkas;;Greece;+30 6974497517;;netacruises@gmail.com;;Giorgos Mouratidis
4154;1;Mediterra Yachts;TSIROGIANNI 47;;AMFILOCHIA;;Greece;306944466364;;mediterrayachts@gmail.com;www.mediterrayachts.com;PANAGIOTOPOULOU SOULTANA
4152;15;Exadas Yachts;Proedrou Tzanetou 3;17342;Athens ;;Greece;+30 210 9844460;;charter@exadasyachts.com;www.exadasyachts.com;Paris Loutriotis
4146;0;Catamaran Center;Local 324,  Port Ginesta;8860;Castelldefels ( Barcelona);;Spain;+34 936 652 211;34678548979;francoise@catamarancenter.com;http://www.catamaran-center.com;Françoise Mangin
4145;0;OneToSea;10, Avenue Maréchal Lyautey;83400;Hyeres;France;France;+33 (0)9 83 530 690;;booking@onetosea.fr;www.onetosea.fr;Philippe d'Yvoire
4135;16;Marvero;Via Maddalena 2;98055;Lipari;;Italy;+39 3495309904;;info@marvero.it;www.marvero.it;Fabrizio Delia
4130;1;Navegar en Catamaran;San Blas 32;50003;Zaragoza;;Spain;+34 678 53 21 87;;contacto@navegandoencatamaran.com;www.navegandoencatamaran.com;François Xavier Crone
4127;3;123yachtcharter;Negrijeva 33;52100;Pula;;Croatia;436641230011;;charter@123yachtcharter.com;www.123yachtcharter.com;Christian Ysopp
4126;7;AciSail;Rudolfa Strohala 2;51000;Rijeka;;Croatia;+ 385 99 496 0756;;info@aci-sail.com;www.aci-sail.com;Kristijan Pavić
4122;5;Aegean Blue Sailing;Agamemnonos 51;18452;NIKAIA;;Greece;+30  6979591200;;charter@aegeanbluesailing.com;www.aegeanbluesailing.com;Chris kalogeris
4118;1;Thetis Yachts;Xrysostaomoy Smirnis 54;38446;Volos;Magnisia;Greece;0030 6981048041;;aiolosyachts@gmail.com;www.thetisyachts.com;Kamvrogiannis Panagiotis
4113;13;Blue Eternal;District 1, 7 Intrarea Geneva Str, 2nd apartment , 1st floor, office no. 32;;Bucharest;;Greece;306973406043;;blueeternalyachting@gmail.com;www.blue-eternal.com;-
4112;2;Greek Sea;Agnandon 13 T.K.11741 Koukaki;11741;Athens;;Greece;+30 693 824 1416;+30 693 824 1416;master748@windowslive.com;www.Greeksea.gr;Sidaros Giannis
4106;5;Greek Sails;35 Alkyonis str.;17562;Palaio Faliro;;Greece;306974035970;306974035970;info@greek-sails.com;www.greek-sails.com;Minas Koklas
4104;11;Sail & Surf Rügen;Am Fährberg 8;18573;Altefähr;;Germany;493830623253;;charter@dehlerpoint.de;www.dehlerpoint.de;Knut Kuntoff
4097;0;ONBOAT.EVENTS;Nord-West-Allee 8;51789;Lindlar - Region: Köln;Germany;Germany;+49 173 3940906;;move@ctpm.de;https://www.onboat.events;Stefan Müller
4090;1;Moire Charter;Tome Strižića 19b;51000;Rijeka;;Croatia;+385 95 845 8220;;moire.charter@gmail.com;www.moirecharter.eu;Jasminka Čiča
4067;5;White Blue Seas;104, Saki Karagiorga Str.;16675;Athens;16675 Glyfada;Greece;+30 2109607902;+30 6972058020;info@whiteblueseas.com;www.whiteblueseas.com;Grigoris Papathanassiou
4065;2;Križni Vijak Nautika;Hrvatskih književnika 31;23000;Zadar;;Croatia;00385 23 220640;00385 915483434;kriznivijak@gmail.com;www.vijakrentaboatzadar.com;Srećko Stavnicki
4057;1;Chillax Yachting;45, Agion Saranta  Str;18346;Athens;Moschato PC;Greece;211 234 2847;0030 6944328469;info@chillax.cruises;www.chillax.cruises;Stylianos Rapakoulias
4049;4;Seyscapes Yacht Charter;Hermitage;;Mahe;;Seychelles;2482575048;;bookings@seyscapes.co.uk;www.seyscapes.co.uk;Derell Edwin, Adrienne
4048;6;Patronis Sailing;;37300;Kato  Lechonia ;;Greece;306976741978;;patronis.sailing@gmail.com;www.patronis-sailing.gr;Koufonikou Eleni
4040;6;Yachts Society;Ippodamias sq. 8;18531;Piraeus;;Greece;302104283574;;charter@yachtssociety.com;www.yachtssociety.com;Konstantina Chachali
4027;32;Wimmer Yachting;Stelzhamerstraße 47a;A;Steyr;;Austria;0043 7252 43839;;office@wimmer-yachting.at;www.wimmer-yachting.at;Wolfgang Wimmer
4019;3;Greek Sailing Holidays;30 IOULIOU 31 str;37400;;N.Agxialos;Greece;306906926345;306906926345;bellatrixnepa@gmail.com;www.gs-holidays.com;Dimos Evangelou
4018;15;Bax Yachting;12th Nikolaou Plastira;PC 55132;Thessaloniki ;Thessaloniki ;Greece;306948057872;;info@baxyachting.com;https://www.baxyachting.com/;Baxevanis Vagelis
3987;7;GA Holidays;P. F. Panagou 18;31100;Lefkada;Greece;Greece;306949615105;306949615105;info@gaholidays.gr;www.gaholidays.gr;George Acheimastos
3979;0;CI Charter;Frana Supila 3;23210;Biograd na Moru;Croatia;Croatia;+43 800 880 881;;service@yachtcharter24.de;www.charter.at;Manuela Reiner
3975;6;BDA Sailing Experience;Ramon Turro 40;8005;Barcelona;;Spain;+34 93 221 75 74;;info@barcodealquiler.com;www.barcodealquiler.com;Pedro Rojas
3971;1;Barko Yachting;Platonos 21;14563;Kifisia;Attika;Greece;306945493438;;info@barkoyachting.com;www.barkoyachting.com;Pantelis Koutsoulentis
3969;11;Barefoot Yacht Charters;Blue Lagoon, Ratho Mill;VC 0100;Kingstown;ST Vincent, Grenadines;St. Vincent and the Grenadines;+1 784 456 9334;;bookings@barefootyachts.com;www.barefootyachts.com;Philip Barnard
3961;19;Porto Colom Yachting;Calle Pescadors 25;7670;Porto Colom;Islas Baleares, Spain;Spain;34971826078;34635767751;info@portocolomyachting.com;www.portocolomyachting.com;Uwe Barchfeld Oliver Wallpott
3957;1;Zenith;Mitropolitou Meletiou 29;48100;Preveza;;Greece;+30 6998801907;+30 6998801907;info@zenithyachting.com;http://www.zenithyachting.com;Olympia-Paraskevi Gkavanozi
3929;1;Windventures;Iakovou Polyla 37;49100;Corfu;;Greece;+30 698 6723963;;info@windventures.gr;http://www.windventures.gr;Sabine van Putten
3926;11;Hellenic Vessel Management;2as Merarchias 11;16672;Vari;;Greece;+30 6949422860;;info@hevema.com;www.hevema.com;Nikolaos Gkougkousis
3919;8;Charter 4 Fun;Miline 132C;22203;Rogoznica;;Croatia;+385 995 206 207;;booking@charter4fun.com;www.charter4fun.com;Jędrzej Wojciech Kocewicz
3912;4;GR Yachting;Thiseos 31;37300;Volos;;Greece;306906654155;;infogryachting@gmail.com;www.gryachting.com;Alexandros Tompoulidis
3909;1;Blue Aegean Sailing;Manna 0;84100;Syros;;Greece;+30 2281075711;;holidays@blueaegeansailing.com;www.blueaegeansailing.com;Sotiris Fouroulis
3875;3;Pika Sailing;Smokvina 2;21226;Vinišće;Splitsko-dalmatinska;Croatia;385981953724;;info@rent-pika.hr;ww.rent-pika.hr;Marijana Orlić
3871;1;Dream Cat Lab;Via delle Risaie 4;84131;Salerno;;Italy;+39 3358357215;;salesandevents@dreamcat.it;www.soleanis.com;Dino Giordano
3867;2;Eol Nostrum;Braće Fućak 13;51000;Rijeka;;Croatia;+385 91 252 13 29;;charter@eolnostrum.hr;www.eolnostrum.hr;Marin Brajković
3855;4;Filipi Marine;Put Mićiča 1;23000;Zadar;;Croatia;;+385 91 253 65 86;info@filipimarine.com;www.filipimarine.com;Ljubomir Zrilic
3853;2;Nautilia Yachting;Skiathou 2;54646;Thessaloniki;Greece;Greece;+30 2310 558192;;info@nautilia-yachting.com;www.nautilia-yachting.com ;Nick Fatsis
3852;0;Sail Fast Roses;Avda. Nord, 55;17480;Roses;Girona;Spain;+34 609 77 66 56;;info@sailfastexperience.com;www.sailfastexperience.com;Mario Font
3847;2;Adriatic Sailing TO;Fra Bonina 12;21000;Split;Croatia;Croatia;21772024;38598183224;charter@ladyana.com;www.ladyana.com;Boris Štolfa
3842;30;Istion Luxury Yachts;17 Alimou Avenue;GR 174 55;Alimos;;Greece;+30 210 981 1515;;greece@istionluxuryyachts.com;www.istionluxuryyachts.com;
3836;4;Finest Yachting;Calle Monterrey 33b;7012;Palma de Mallorca;;Spain;34684411188;34684411188;dk@finest-yachting-mallorca.de;https://www.finest-yachting-mallorca.de/;Daniela Krause
3831;11;EazyBlue;1-7, Arch. Makariou III & Evagorou Avenue;1065;Nicosia;;Cyprus;302103008181;306983722330;experts4yot@gmail.com;www.eazyblue.com;Theodoros Theodoridis
3819;4;Perfect Sailing - Sailing Holidays Hub;Kabouroglou 7;;Volos;;Greece;+30 697 418 1708 (LAMBROS);;info@perfectsailing.gr;www.perfectsailing.gr;Lambis Bombaj
3810;1;Greek Stars;KIMOLOU 18  11362  ATHENS;11741;Athens;;Greece;306975026970;+30 6975026970;greekstars.charter@gmail.com;www.greekstarscharter.com;Emmanouel Mavris
3808;1;Rhodes Yachting;Afantou Paraskopi;85103;Rhodes;;Greece;306974904233;;stefanosrodos@yahoo.gr;www.rhodesyachting.gr;Stefanos Gkoumotsios
3801;9;Kavala Yachting;Ethnikis Antistasis 2;65403;Kavala;;Greece;306942201528;;info@kavalayachting.com;www.kavalayachting.com;Danai Giannakoudi
3800;2;Sun Sail Sea Catamarans;Camino Real de los Neveros 6;18008;Granada;;Spain;+34 677 165 105;;info@sunsailsea.es;www.sunsailsea.es;Enrique Martin
3769;2;Sea Walker;via giusti 28 filare Gavorrano;58023;Gavorrano;Tuscany;Italy;+39 3894532128;;seawalkerpuntala@gmail.com;https://seawalker.it;Marzio Morelli
3764;1;Mediteran Yacht;Kremenacova 90/6;10400;Prague;;Czech Republic;905055863042;;mediteran.sailing@gmail.com;www.seas4you.com;Alexander Astrikhinskii
3758;0;Sole MiO M.C.P.Y.;8 Ippodameias Square;;Piraeus;;Greece;37257737509;;go@theyachtcamp.com;;Paulina J. Khait
3735;7;Aqua Charters;53/35 Moo 10, Banglamung;20250;Nongprue;Chonburi;Thailand;+66(0)822074588;;info@aqua-charters.com;www.aqua-charters.com;DENIS SALTYKOV
3732;1;Sail More;ul. Sw. Marcin 29/8;61-806;Poznan;;Poland;+48 501 321 001;;magdalena.kaleta@sailmore.pl;www.sailmore.pl;Rafal Kaleta
3725;18;Med Cat Yachts;Port Ginesta - Local 524;8860;Barcelona;;Spain;+34 671 79 26 45;+376 342 421;charter@medcatyachts.com;www.medcatyachts.com;Carlos Peris
3724;2;Kaboky Yachting;3 Chapel Street, Hyde;SK14 1LF;Cheshire;;United Kingdom;+447533306617 (Ana Markota);;charter@kabokymarine.com;www.kabokyyachting.com;Ahmed Hussein
3722;1;Setemana Charter;Hrvatske mornarice 20;21000;Split;Croatia;Croatia;;00385 95 9010313;toni@setemana-cruising.com;;Andrea Dezulovic
3719;1;Antibavela;VIA DEL POZZO 34;44121;FERRARA ;;Italy;+39 339 3011728;;info@antibavela.com;www.antibavela.com;Marco Balboni
3718;9;Balearic Yacht Club;Avda. Alexandre Rossello 15º3-C;7002;Palma de Mallorca;Palma ;Spain;+ 34 971750124;+ 34 658 079 579;info@balearicyachtclub.com;https://www.balearicyachtclub.com;Adrian  Bravo
3706;6;All-Seas;Santas str. 3;18451;Nikea;;Greece;+30 2109846410;306944327600;info@all-seas.gr;www.all-seas.gr;Rouzinos George
3699;1;Romeo Juliett and the Sea;Josipa Marohnića 1/1, 7. Stock;10000;Zagreb;;Croatia;431870460;43069917191363;booking@romeojuliettandthesea.com;www.romeojuliettandthesea.com;Robert Judtmann
3690;3;Genoa Yachting ;9, Filellinon str.;18536;Piraeus;;Greece;+30 210 4294017;+30 6932 338933;charter@genoayachting.com;www.genoayachting.com;Grigoris Panagiotopoulos
3684;1;Sail Adria Charter;Brdo I 20;21405;Milna;;Croatia;385955555692;;esko.trofast@gmail.com;www.sailadria.com;E W Trofast
3674;2;Mistral Sailing;Corso Italia 22/12b;16145;Genova;;Italy;+39 3479498966;;info@mistralsailing.it;mistralsailing.it;Luca Pedevilla
3672;11;ACM Catamaran;269 Rue du 11 novembre 1918;69620;Le Bois d'Oingt;;France;+33 4 78 15 98 63;;commercial@acm-cata.com;https://acm-cata.com/;Eric LE PUIL
3659;7;Platten Sailing Cuba;Calle 35, % 6 y 8, Marina Cienfuegos;55100;Cienfuegos;;Cuba;+49 8239 959078;+49 175 2771833;info@cuba-sailing.de;www.cuba-sailing.de;
3657;4;Sailing Kavala;Efxinou pontou 10;65404;Kavala;kavala;Greece;306955195915;;info@sailingkavala.com;https://sailingkavala.com/;Argiris Kalaitzis
3638;16;Saysail;Göcek Mah. Sahilyolu Cad. MCI Tur Sit. 28 Blok No:88;48300;Fethiye;;Turkey;385913323331;;booking@saysail.com;www.saysail.com;Domagoj Milišić
3631;7;Smile Boats;Bellamar 22 local 7, Can Pastilla;7610;Palma de Mallorca;Baleares;Spain;34617395799;;info@smileboats.com;www.smileboats.com;Juan Bibiloni Coll
3627;3;Gala Yachting & Travel ;Gocek, Iskele Meydan 4/5;48300;Fethiye;Mugla;Turkey;+(90) 252 645 3018;+(90) 532 232 5949;charter@galayachting.com;www.galayachting.com;Umit Karakose
3625;3;Laguna Yachting;VIA GIUSEPPE GIUSTI 42;90144;PALERMO;PA/SICILIA;Italy;393475260089;;info@lagunayachting.it;www.lagunayachting.it;Mauro Barone
3622;3;Ionian Island World;8 Palaion Patron Germanou;22132;Tripolis;;Greece;306986698000;;mihail1gekas@yahoo.gr;www.goforsailing.com;Mihail Gekas
3617;7;VOG FLEET;161 boulevard du montparnasse;75006;Paris;France;France;+33 650 37 54 37;;contact@vogfleet.com;www.vogfleet.com;Clément Rouch
3612;3;Ikarian Cruises;CHALKIDI 20;18345;MOSCHATOATHENS;Greece;Greece;302109404770;;ikariancruises@yahoo.gr;www.ikarian-cruises.com;MAVRODONTIS VASILEIOS
3608;2;Exploring Sailing;Apollonion;31082;Dragano;Lefkada;Greece;359888519476;;info@exploring-bg.com;www.exploring.bg;Ilian Petrov
3605;9;7 Waves Yachting;Av de Gabriel Roca 36;7014;Palma de Mallorca;;Spain;491743428646;34657670149;fabian.fischer@7waves-yachting.de;http://www.7waves-yachting.de;Fabian Fischer
3604;9;Albatross Yachting;Frangon 26 str;54625;Thessaloniki;;Greece;306972070137;;ikyvernitis@albatros-yachting.gr;www.albatross-yachting.gr;Ioannis Kyvernitis
3587;2;Ares Sailing;Bitez Mah. 1525 Sok. no 23;48400;Bodrum;;Turkey;+90 555 725 32 74;;koksaldemirdag@gmail.com;;Koksal Demirdag
3586;1;Yachting Slano;Put Vucipolja 18;21312;Podstrana;;Croatia;00385 91 9089859;;travelcroatiaboat@gmail.com;travelcroatiaboat.com;Bogoslav Mladin
3582;21;NorthSailing;Keramoti port;64011;Kavala;;Greece;+30 2591051180;+30 6978415004;reservation@northsailing.gr;northsailing.gr;Christoforos Gritzelakis
3580;1;M Blue Yachts;Naousa Parou;;Paros;;Greece;+30 6970101877;;george@m-blueyachts.com;https://m-blueyachts.com/;
3575;9;Eolian Sailing;Via Giovanni Meli, 59;98076;Sant'Agata M.llo;ME;Italy;+39 3923598735;+39 392 3598735;info@eoliansailing.it;www.eoliansailing.it;Roberto Corpina
3569;4;Hellenic Sails;Saframpoleos 14;142 33;Nea Ionia;Attica;Greece;306978354050;306944515488;info@hellenicsails.com;www.hellenicsails.com;George Gialytakis
3568;1;Catamaran-Mala;Poljicka cesta Bajnice 54;21314;Jesenice;;Croatia;385915197379;;info@catamaran-mala.com;catamaran-mala.com;Zorislav Vukovic
3561;8;Gota Kanal Charter;Råggatan 10;59232;Vadstena;;Sweden;+46 705 46 91 69;;boka@kanalcharter.se;www.gotakanalcharter.se;Pontus Björsner
3560;1;We Sail Greece;VITSI 55;54627;THESSALONIKI;;Greece;306945702080;;charter@mtyachts.com;www.mtyachts.com;METZAKIS MANOLIS
3552;4;Ibiza Blue Line Charters;C/ Fco. de Goya 22, 1º C;7820;Sant Antoni;;Spain;+34 635 97 38 01;;info@ibizablueline.com;www.ibizablueline.com;Paula Ramon Vingut
3551;2;Vela Dream Charter;Via De Larderel 8;;Livorno;Italy;Italy;393484727220;;info@veladreamcharter.com;www.veladreamcharter.com;Andrea Conforti
3544;15;Yachtcharter Huibers;Palissade 11;4143 GA;Leerdam;;Netherlands;;;info@yachtcharterhuibers.nl;www.yachtcharterhuibers.nl;Jan Huibers
3541;20;Odyssey Sailing;Antonopoulou 158d;38221;Volos;;Greece;+30 24280 94128;+30 6974659156;info@odysseysailing.gr;www.odysseysailing.gr;Francesca Mansfield
3538;0;TECNIT;Via Toscana 42;9018;Sarroch;Cagliari;Italy;+39 3467511870;;charter@adarayachts.com;;Davide Manca
3534;6;Ankereva Yacht Charters;Makedonias 10;40100;Tirnavos;;Greece;306932613189;;ankereva.sailing@gmail.com;volos-sailing.com;Dimitris Zaragotas
3528;6;Chatzigiannis Yacht;Gianni Dimou 140;38221;Volos;Magnesia;Greece;+30 690 707 2249;;chatzisyachts@gmail.com;www.chatzigiannisyacht.gr;Dimitrios Chatzigiannis
3524;6;Flaka Sailing;Eskicesme Mah. Safak Sokak No.26;;Bodrum;;Turkey;905318115389;;r.tiemessen@gmail.com ;www.flaka.nl;Rene Tiemessen
3521;4;Sail Band;Stariput 6/1, Tivat;85320;Podgorica;;Montenegro;38268566126;;support@sailband.com;www.sailband.com/eng;Dmitry Nikolaev
3518;5;Barchetta;Patriarchou Maximou 3;14562;Kifisia;;Greece;306978331900;306978331900;info@barchettayachts.com;www.barchettayachts.com;Stavros Chrysostalis
3513;8;Just Sail Charter;Zrinsko Frankopanska 60C;21300;Makarska;;Croatia;;385998051400;booking@justsail.hr;www.justsail.hr;Ernest Kalajžić
3511;79;Yachtcharter De Drait;;;;;Netherlands;0031(0)513276 /  0049(0)3381 604590;;contact@dedrait.com;www.yachtcharterdedrait.com;
3509;2;Sailand;Katounis, Episkopos Lefkadas, 31100 Lefkada;31100;Lefkada;;Greece;+30 6982780295;;charter@sailand.info;www.sailand.gr;KOINWNIA KLIRONOMWN GABRILLI ANDRE
3503;49;Aquatoria Yachting;Ulica Miroslava i Janka Perice 13;23000;Zadar;;Croatia;38523777008;;info@aquatoria-yachting.com;http://www.aquatoria-yachting.com;Yury Logushkin
3502;43;Hellenic Yachting;Koliatsou 30, P.O;20131;Korinthos;;Greece;+30 2107002030;;info@hellenic-yachting.gr;www.hellenic-yachting.gr ;Vaggelis Tsourapas
3499;4;Zouras Yachting;Gytheiou 7;;Kallithea;;Greece;306944554960;;info@zouras-yachting.gr;www.zouras-yachting.gr;Nikos Zouras
3492;3;Sailhunt;Franje baruna Trenka 130;23000;Zadar;;Croatia;+385 99 348 0686;;info@sailhunt.com;http://sailhunt.net;Ivan Nemeš
3491;3;SA Yachting;Eleftherias Avenue 11, 17455;17455;Athens;;Greece;306984136000;;info@sayachting.com;www.sayachting.com;Barelou Nektaria
3489;2;Oasis Sailing;Gounari 4;66100;Drama;Drama;Greece;306945598636;;info@oasisailing.com;www.oasisailing.com;Mike Soutos
3488;1;Friends Yachting;483 Green Lanes;N13 4BS;London;;United Kingdom;+44 203 488 2057;40757557557;hello@friendsyachting.com;www.friendsyachting.com;Alex Olteanu
3484;1;Mita Nepa;Miron 10;10434;Athens;;Greece;381628433905;;veljko.orbovic@gmail.com;www.zabasailing.com;Veljko Orbovic
3478;1;Velvet Yachting;Pleiadon 4;17561;Palaio Faliro;;Greece;306985578799;;velvetyachting@gmail.com;https://www.velvetyachting.gr/;Ioannou Alexandros
3477;1;Catamaran Experience Ibiza;Greko, 16;7830;Sant Josep de sa Talaia. Ibiza;Balearic Islands;Spain;+34 657 900 362;;info@catamaranibiza.com;www.catamaranibiza.com;Toni Muñoz
3475;13;Panouseris Yacht Charters;Saki Karagiorga 43;16675;Glyfada;;Greece;+30 2109604278;+30 6956333743;info@panouseris-yachts.com;www.panouseris-yachts.com;Dimitris Panouseris
3447;25;Puresailing Yachting;71 -75 Shelton Street, Covent Garden;WC2H 9JQ;;;United Kingdom;Athens office +30 210 9856997;+30 6932415114;info@puresailing.gr;www.puresailing.gr;Frank Kydoniefs
3442;8;Aegean Cruises;92, Dimokratias str, 175 63 P. Faliro;;Athens;Greece;Greece;0030 210 98 24 611;;info@aegean-cruises.gr;www.aegean-cruises.gr;Zak Papadakis
3441;2;Saline Breeze Yachting;7-9 Sykoutri Str., Nea Smyrni, Athens, Greece;17122;Athens;;Greece;+30 210 93 54 438;+30 6947 89 24 14;charter@salinebreeze.com;;Yannis Papapetrou
3425;4;Dk Yachting Greece;Leoforos Eleytherias 11;17455;Athens;Alimos;Greece;+30 6989593935;;charter@dkyachting.com;dkyachting.com;Karageorgiou Dimitrios
3410;1;Ostria Sailing;33th Street 2;19007;Marathon;;Greece;306971923808;;sailing.ostria@gmail.com;www.yacht-in-greece.com;Svitlana Mazurkevych
3405;5;Athens Diamond Cruises;Alkiviadou 40;16674;Glyfada;;Greece;+30 213 026 8288;+30 213 026 8288;info@athensdiamondcruises.com;athensdiamondcruises.com ;Christina Manti
3399;1;Yachtfun;Matika 27;51312;Brod Moravice;;Croatia;+48 515545887;;booking@zanzibaryachtfun.com;www.zanzibaryachtfun.com;Vincent Czeslaw Kowalczyk
3391;12;MY SeaTime Yachtcharter;Stjepana Radića 68;22000;Šibenik;Šibensko-kninska županija;Croatia;491714726103;;info@myseatime.de;www.my-seatime.com;Detlef Hagel
3380;6;VELANOMADA YACHT CHARTER;Josep Pla 43, Hotel Ancora;17230;Palamos;Spain;Spain;34696954400;;info@velanomada.com;www.velanomada.com;Joaquim Gispert
3366;1;Aurora Yachting;Aristidоu Street 15;18531;Piraeus;;Greece;359886333322;;booking@aurorayachting.com;www.aurorayachting.com;Velin Kerimov
3357;4;Sardinia Yachting;Via Papandrea n.3;7026;Olbia (SS);;Italy;393791664830;393791664830;info@sardiniayachting.it;www.sardiniayachting.it;Azara Marta
3356;1;Schupfer Yachtcharter;Bartolići 47;10000;Zagreb;;Croatia;;+43 664 4674040;office@schupfer-yachtcharter.at;www.schupfer-yachtcharter.at;Mst. Roland Schupfer
3349;3;Sport Sails;Ive Senjanina 12 A;23000;Zadar;;Croatia;+48 605 165 501;;igor.stepniak@sporteum.pl;www.sporteum.pl;Igor Stepniak
3345;1;Lighthouse Charter;VIA G. LEOPARDI 7CUPELLO;66051;CUPELLO;Chieti;Italy;393405806376;;info@lighthousecharter.it;lighthousecharterandservice.com;CARLO BOSCHETTI
3327;0;Sail A Way;G.Sklavou 15;26442;Patras;;Greece;+30 6945322012;+30 6931333000;info@sailyourway.gr;https://www.sail-a-way.gr/;
3322;1;Marinis;Bulevar oslobođenja 31;51000;Rijeka;;Croatia;+385 98 9156 565;+385 992442223;marinis.rijeka@gmail.com;https://www.clickandboat.com/en/boat-rental/rijeka/sailboat/jeanneau-sun-odyssey-45-xyz2q;Milan Pavlović
3319;8;CV Yachts;Churchill House, 137-139 Brent Street;NW4 4DJ;London;;United Kingdom;+30 6947723694;;info@cvyachts.com;www.cvyachts.com;Maggie Niagassas
3307;2;Thestion Yachting;Peloponnisou 126;13231;Petroupoli;Athens;Greece;+30 2105051002;+30 6973844700;thestionyachting@gmail.com;https://thestionyachting.com/;Yiannis Grapsas
3283;1;Ventum;Apartado de Correos nª2;43894;Camarles;Tarragona;Spain;34626482695;;info@ventum.barcelona;ventum.barcelona;Marc Abad
3280;12;Thrace Yachting;22 Marinou Antypa;17455;Alimos (Athens);;Greece;+30 210 98 75 090;306940266782;charters@thraceyachting.com;www.thraceyachting.com;Costas Halioris
3266;2;Vagovia Yacht Charter;Via Roma, 471;90142;Palermo;;Italy;+39 3470430012;;info@vagoviayachtcharter.com;www.vagoviayachtcharter.com;Claude
3250;2;Greece Sail;Pirronos 66;16346;Athens;;Greece;+30 6947530412;;greece2sail@gmail.com;;Vasiliki Dramoni
3245;4;Dream Sail Charter;Paseo del Pinillo local 11 marina isla Canela;21409;Ayamonte;;Spain;+34 658802397;;dreamsailcharter@gmail.com;www.dreamsailcharter.com;Víctor Mayoral Martínez
3237;4;Global Sailing;Içerenköy Mah Kayisdagi Cad Eston Çamli Evler Akçam Apt 4 Atasehir;34758;Istanbul;;Turkey;905423107979;;sailing@globalsailing.com.tr;www.globalsailing.com.tr;Deniz Karamanoglu Saral
3224;3;Ionistia;Neochori Messologgiou;30001;Neochori;;Greece;306944266557;;ionistia@yahoo.gr;;Spyros Makris
3208;20;SailEazy;61 Bd des Dames;13002;Marseille;;France;+33 0484256297;;contact@saileazy.com;www.saileazy.com;Grégoire Guignon
3207;18;Le Rotte di Portolano;Via Nazario sauro 51;73100;Lecce;;Italy;+39 0832 314800;+39 392 9804272;booking@rottediportolano.com;www.rottediportolano.com;Eupremio Portolano
3203;1;Cataexperience;Carrer de la Llibertat 64, 4º;43860;L'Ametlla de Mar;Tarragona;Spain;+0034 650 009 270;;cataexperience1@gmail.com;www.cataexperience.com;Jaime de Yebra Sugrañes
3196;1;Blue-Lagoon;ulica Szlak 77/222;31-128;Kraków;;Poland;+48 503 129 717;;office@blue-lagoon.eu;www.blue-lagoon.eu;Janusz Owczarek
3187;21;LoveSail.net;Yacht Classic Otel Marina Karagözler Mah. Fevzi Çakmak Cad. No:24;48300;Fethiye - MUGLA;;Turkey;+90 545 509 84 48;+90 545 509 84 48;lovesail@lovesail.net;www.lovesail.net;
3179;18;Miknatis Yachting;Netsel Marina;48700;Marmaris;Mugla;Turkey;+90 252 412 32 07;+90 532 673 31 71;info@miknatisyachting.com;www.miknatisyachting.com;Zafer Öznur / Olgac Esin
3173;1;Sunrise Charter;Via Magna Grecia 84;183;Roma;;Italy;+39 3355388436;+39 3355388436;info@sunrise-charter.com;www.sunrise-charter.com;Mauro Toro
3172;1;Ydrea Yachts;TSAMADOU 1 Str , Piraeus;18531;Piraeus;;Greece;302104125840;306941403919;info@ydreayachts.com;www.ydreayachts.com;George Tsigkaris
3170;16;L'O Yachting;KARAOLI AND DIMITRIOU 3;17562;PALIO FALIRON;;Greece;+ 30 22890 27057;306932526292;booking@loyachting.com;www.loyachting.com;Laurent Labarthe Medrano
3164;3;Icelandic Yacht Charter;Grundarhóll;116;Reykjavík;;Iceland;+354 661 9777;;booking@icelandyachtcharter.com;www.icelandyachtcharter.com;Guðbergur Birkisson
3152;1;Mullinahone Yachts;Calderon de la Barca, 1;30150;La Alberca;;Spain;+34 679614013;;reservasmaitia@gmail.com;www.myweekchartermenorca.com;Luigi Bleve
3149;3;High Five Sailing;Leendreef 9;8750;Wingene;;Belgium;+32 497137451;;info@highfivesailing.com;www.highfivesailing.com;Koen Simkens
3140;13;Crystal Sea Yachting;4o km IOANNINON IGOYMENITSAS;44003;IOANNINA;;Greece;302103003200;;booking@crystalseayachting.gr;www.crystalseayachting.gr;Zisopoulos Pantelis
3135;1;Seafarer Sailing;36 Afxediou St.;17455;Athens;;Greece;+30 2109847404;+30 6970075222;info@seafarer.gr;www.seafarer.gr;George Orphanos
3128;26;Starsails Yachtcharter;Eichendorffstr. 4;50823;Köln;NRW;Germany;49221630608130;;booking@starsails.de;www.starsails.de;Helge Kröger
3125;7;Prima Yachting;Menelaou 52 str, Saint Dimitrios;17343;Athens;;Greece;+30 6983377875;;info@primayachting.gr;www.primayachting.gr;Nasos Kontakis
3114;1;Top Sailing Charter;Avd. Francesc Macià 11;17257;Torroella de Montgrí;Girona;Spain;+34 972759290;+34 606102946;fleet@topsailingcharter.com;www.topsailingcharter.com;Sergi Alós
3111;15;VASKON MARINE & YACHTING;Marathons Ave 266;145 75;Stamata Attica;;Greece;306944455750;;vaskonmarine@gmail.com;https://vaskonyachts.gr;Nikos Fragopoulos
3098;3;Multihull Yacht Sales;12 Rue Bellot;76600;Le Havre;;France;33986871747;33678737206;office@multihullyacht.com;www.multihullyacht.com;David Steckar
3089;2;TI-LU yachts;Brkićeva ulica 8a;10413;Velika Buna;;Croatia;+385 91 430 0063;;info@tiluyachts.hr;http://tiluyachts.hr/;Kristina Trumbetic
3078;17;Ocean Sailing House;Put Pazdigrada 6B;21000;Split;;Croatia;+385 21 250 220;+385 99 8000 908;office@oceansailinghouse.com;www.oceansailinghouse.com;Jakov Bošnjak
3072;27;GAIS s.r.o;Velké náměstí 47;76701;Kroměříž;;Czech Republic;420736117206;;booking.gais@gmail.com;www.gaisplachetnice.cz;Ivana Michelini
3066;35;Open Sail;13 BOULEVARD DE LA PLAGE;33148;TAUSSAT;;France;33611868226;;contact@open-sail.com;www.open-sail.com;Jean-Marc Calmet
3063;4;MASTER SAILING SRLS;Via 1° Maggio 4/G;20060;Cassina de' Pecchi;;Italy;393351519166;393351519166;master@noleggiobarchevela.net;www.noleggiobarchevela.net;Leonardo Pugliese
3060;13;Sea Drive;Kurilovečka 41;10410;Velika Gorica;Zagrebacka;Croatia;385917878418;385917878418;info@seadrive.hr;www.seadrive.hr;Marin Sever
3056;5;OceanMed Sailing;Viale Salerno 28/6;75025;Policoro;;Italy;3908351880186;393451515954;info@oceanmedsailing.com;www.oceanmedsailing.com;Antonio Marsano
3044;14;MadMax Franchising Nautico;Via Aurelio Lampredi 3;57121;Livorno;;Italy;393713234443;;info@madmaxcharter.it;www.madmaxcharter.it;Lucas Lucarelli
3041;13;Pianeta Mare Charter;Via Albizzeschi 9;58024;Massa Marittima;Grosseto;Italy;393792996765;;info@pianetamarecharter.com;www.pianetamarecharter.com;Andrea Calestrini
3034;6;Sail in Greece adventures;Rizountos & Thrakis 16A;16777;Athens;Attica;Greece;302112152595;;simos@sail-ingreece.com;www.sail-ingreece.com;Alexander Nastos
3026;6;Eversails Yachting;Nikis 39-43;17455;Alimos;Athens;Greece;302109888252;;charter@eversails.com;www.eversails.com;Konstantinos Tzinis
3025;3;Veleasegno Charter;"Via Spririto Santo II tronco 5/A coop ""Magnolie""";89128;Reggio Calabria;RC;Italy;;+39 3348776292;info@veleasegnocharter.com;www.veleasegnocharter.com;Carmelo Antonio Pennestrì
3021;25;Bleyachting;Panepistimiou 39;26441;Patras;Greece;Greece;306948060190;;bookings@bleyachting.com;bleyachting.com;Dimitris Kofteris
3017;38;Under the Heavens Sailing;Slaviceva 15;21000;SPLIT;;Croatia;385913300691;385913300691;booking@uth-sailing.com;www.uth-sailing.com;Vadim Ievstafiev
3015;24;Cata Sailing;Put Brodograditelja 16;21220;Trogir;;Croatia;00385 21 444 602;;info@cata-sailing.com;www.cata-sailing.com;Jakov Bogdanovic
2998;1;BeBlue Sailing;Via Barche 53;30035;Mirano;;Italy;+39 041 862 7010;;info@beblue.it;https://www.bebluesailing.com/;Emanuele Bradas
2996;153;Athenian Yachts;17, Alimou Avenue;17455;Athens;Alimos;Greece;+30 210 9849620;+30 6932758923;info@athenian-yachts.gr;http://www.athenian-yachts.gr;
2980;4;Sardinia Week;Baia Sardinia ;7021;Arzachena;Sardinia;Italy;+39 3477648305;+39 3477648305;info@sardinia-week.com;www.sardinia-week.com;Mulazzi Milena
2977;1;Gentedimare of Zara Nicoletta & c.;Traversa Primo Levi n. 26;80030;Castello di Cisterna;;Italy;393516998771;393516998771;info@gentedimare.it;www.gentedimare.it;Nicoletta Zara
2972;7;Rigel Vela Charter;Via Mario Moschi, 15;50055;Lastra a Signa (FI);;Italy;+39 3337314083;+39 3337314083;info@rigelvela.it;www.rigelvela.it;Berti Silvia
2971;23;Med Sailing Holidays;Arch. Makarios III & Evagorou, 1-7;1065;Nicosia;;Cyprus;0020 3289 9366;;info@medsailingholidays.com;www.medsailingholidays.com;Geoff Woodcock
2963;2;Fantasia Sailing;Madytou 8 Str;58100;Giannitsa Pella;;Greece;306980442380;;info@fantasia-sailing.com;;Anastasia Kitka
2959;1;Candia Yachting;Zervoudaki 1;71306;Herakleion;;Greece;(+30) 6944845858;;candiayachting@gmail.com;www.candiayachting.com;Konstantinos Kalogerakis
2943;3;BaMa Yachts;Crotenlaider Str. 34;8393;Meerane;;Germany;+49 365 83 28 88 18;+49 (0) 173 8248160;office@bama-yachts.com;www.bama-yachts.com;Jens Bachmann
2934;14;Octopus Yachting;21 Metamorfoseos;17455;Alimos;Athens;Greece;+30 21 5515 0995;+30 6984555365;charter@octopusyachting.com;octopusyachting.com;Kyriakos Markou
2923;2;Sail Along;Rua do Poço 43;9545-540;São Vicente Ferreira;Azores;Portugal;00351 918755726;;hello@sail-along.com;www.sail-along.com;Diogo Santos
2906;1;Lumar;Trondheimska 33;21000;Split;;Croatia;385992098599;385992098599;lumarcharter@gmail.com;;Andrea Dezulovic
2903;212;The Moorings;93 N Park Place Blvd.;33759;Clearwater;Florida;U.S.A.;+1 888 952 8420;;mmk@moorings.com;www.moorings.com;
2898;9;Sail Your Myth;69 Theotokopoulou str.;11144;Athens;Attica;Greece;306945452626;306945452626;charter@sailyourmyth.com;https://www.sailyourmyth.com;George Simiriotis
2889;231;Sunsail;Mariner International Travel (UK) LimitedMariner International Travel (UK) Limited;;Surbiton;Surrey;United Kingdom;;;mmk@sunsail.com;www.sunsail.co.uk;
2887;3;Ambassador Yachting;CEVAT SAKIR CAD YOKUSBASI MAH ;48400;Bodrum;Mugla;Turkey;+90 5327881606;905327881606;info@ambassadoryachting.com;www.ambassadoryachting.com;James Kip
2886;13;My Sailing;Lohbachstr. 12;58239;Schwerte;;Germany;0049 2304 59 41 41;0049 1522 1968428;info@my-sailing.com;my-sailing.com;Wolf Tawakol
2883;11;Altair International;Jacobuslei 29;2930;Brasschaat;Belgium;Belgium;3236514062;3236514062;info@altairyachtcharter.be;www.altairyachtcharter.be;Carlo Peetroons
2870;1;Commodore Yachting Croatia;Bana Josipa Jelačića 23;21400;Supetar;Splitsko-Dalmatinska zupanija;Croatia;385918920603;385953875878;info@commodore-yachting.eu;www.commodore-yachting.eu;Rafaela Šerka
2863;1;Darling Sailing;Eleftherias Av. 5;17455;Athens;Elliniko;Greece;306984251597;;oceanisenergy@gmail.com;;Constantopoulos Christos
2850;24;BeCharter;Gil Vernet 35;43890;Tarragona;L'Hospitalet de L'Infant;Spain;34670437910;;partners@becharter.com;www.becharter.com/en;Antoni Subirana
2828;2;Velamica;V. Petrarca n° 2;19032;Maralunga di Lerici;SP;Italy;0039 348 5819582;;info@velamica.eu;www.velamica.eu;Adriana Cipriano
2822;3;Fortek Nautica;Via Fiscaletti Snc;63074;San Benedetto del Tronto;Ascoli Piceno;Italy;+39 0735 591248;;nautica@forteksrl.com;www.forteknautica.it;Giovanni Fulgenzi
2818;3;Minas Yachting;Marina gouvia ;49100;Corfu;;Greece;0030 26610 44244;0030 69790 05787;info@minas-yachting.com;www.minas-yachting.com;Petros Minas
2808;2;MainSail Yachting;21 P. Kavalas str;12242;Athens;;Greece;30 6941626034;30 694 162 6034;tsiamisc@gmail.com;www.mainsailyachting.gr;Tsiamis Costas
2792;16;K2 Yachting;Vyronos 29, Marousi;15122;Athens;;Greece;+30 6931701278;+30 6931701278;info@k2yachting.com;www.k2yachting.com;Passadelis Sotiros
2788;1;LR Drugi impuls;Gračanski mihaljevac 2B;10000;Zagreb;;Croatia;+385 1 464 6671;+385 99 2260 013;renata.skurina@gmail.com;;Renata Škurina
2784;12;GOA Catamaran;C/General Balanzat, 28;7820;Sant Antony de Portmany (Ibiza);;Spain;+34 634 929 121;+34 678 824 079;info@goacatamaran.com;www.goacatamaran.com;Carlos Moro
2783;7;Prime Yachting;Cesta dr. Franje Tuđmana 183;21 212;Kaštel Sućurac ;;Croatia;+385 95 206 0600;+385 95 206 0600;booking@motoryachtscroatia.com;https://motoryachtscroatia.com/;Ante Vujčić
2781;4;Adriatic Escape;Grljevačka 2a;21312;Podstrana;;Croatia;+385 98 9317 449;+385 98 9317 449;info@adriaticescape.com;www.adriaticescape.com;Branko Kurtović
2773;5;Dea dei Mari;Via dei Colli 9;19121;La Spezia;La Spezia/ Liguria;Italy;+39 327 1710988;;info@deadeimari.it;www.deadeimari.it;Ivo Chiodi
2762;4;Asterion Sailing;Boomberglaan 71C;1217 RP;Hilversum;;Netherlands;+30-6974-260-440;+30-6974-260-440;info@asterion-sailing.com;www.asterion-sailing.com;Robert Koster and Patrick Roder
2756;1;Sailmoments;Avda/Eucaliptus,9 Urban: Erbosseres.Mail box 266;43392;Castellvell del Camp (Tarragona);;Spain;34630047130;34630047130;info@sailmoments.com;www.sailmoments.com;Alfonso Romero
2755;14;A Toda Vela Charter;Calle Salitre Nº11 P. 4 Oficina 3;29002;Malaga;Spain;Spain;+34 952 398 735;+34 952 398 735;turavansur@gmail.com;www.atodavelacharter.com;Carlos Camacho Lozano
2753;11;Beyond Yachting;4 Davaki str., Alimos;17455;Athens;;Greece;+359 899909315;+359 899909315;office@beyondyachting.com;www.beyondyachting.com;Atanas Vladimirov Panov
2738;10;Okej-Czarter;Szczawieńska 2;58-310;Szczawno-Zdrój;;Poland;48570000116;;k.zielinski@okej-czarter.pl;www.okej-czarter.pl;
2737;5;Higanas Boats;Kascuni 57 A;52100;Pula;;Croatia;+385 91 799 1316;;info@higanas.com;www.higanasboats.com;Mitar Maric
2725;2;Time Charter Náutica;Avenida de Santa Eulària des Riu, s/n;7800;Ibiza;Islas Baleares;Spain;+34 660 002 339;+34 660 002 339;time@timecharternautica.es;www.timecharternautica.es;Jesus Guil Guerrero
2701;28;George Vlamis Yachts and Yacht Management;Kapodistriou 6;17455;Alimos;;Greece;302102201858;306936712542;mailer@vlamis.gr;www.vlamis.gr;George Vlamis
2691;1;Profilo Race ´n´ Fun;Via Villini Svizzeri Dir. Gulli 5;89126;Reggio Calabria;Reggio Calabria;Italy;+39 329 623 3082;+39 327 437 7602;info@profiloracefun.com;www.profiloracefun.com;Paolo Lia
2686;2;VM Yachting;Mouson 43-45 P. Faliro;17562;Athens;;Greece;+30 210 981 7879;+30 693 223 7583;vmyachting@gmail.com;www.vmyachting.gr;Evaggelos Moutoglis
2683;1;Magic Sails Charter;Karaiskaki 10;31100;Lefkada;;Greece;+30 694 227 3694;+30 694 227 3694;info@magicsails.it;www.crocierevelagrecia.it;Fabio Gresta
2682;289;Locaboat Holidays;Quai du Port au Bois - BP 150;89303;JOIGNY Cedex;;France;+49 (0) 761 207 370;;info@locaboat.com;www.locaboat.com;
2679;2;BB Jezera;Donji put 33;22242;Jezera;;Croatia;+385 99 422 7888;+385 99 422 7888;info@bbjezera.com;www.bbjezera.com;Damir Bradaševič
2674;37;Ahoj Czarter;Przemyslowa 10;11-600;Wegorzewo;;Poland;+48 87 427 15 37;+48 602 398 208;biuro@ahoj.pl;www.ahoj.pl;
2672;11;South Sea Sail ;77Km Athens Sounion Ave;19500;Lavrio;Attiki;Greece;+ 30 22920 24181;+ 306 941 588542;christos@southseasail.com;southseasail.com;Christos Tsafaroglou
2671;4;ND Sails;Gr. Lambraki 19;16675;Glyfada;;Greece;+30 69 444 55 497;+30 69 444 55 497;charter@ndsails.com;www.ndsails.com;Nicolas Economides
2662;8;mediamare yachtcharter;Zollbrücke 16;16259;Oderaue;Brandenburg;Germany;;(+49) 0176-45961829;info@mediamare-yachtcharter.de;www.mediamare-yachtcharter.de;Tobias Morgenstern
2660;20;GMM-Yachting;Hans-Bornkessel-Str.3;90763;Fürth;Bayern;Germany;+90 252 4131597;+90 533 9739445;info@gmm-yachting.com;www.gmm-yachting.com;Dennis Wild
2653;31;World Wide Charter;Testata Mosconi;34073;Grado;;Italy;+39 0899340854;;info@worldwidecharter.it;www.worldwidecharter.it;Luigi Coretti
2649;3;Sailing.ee;Sadama 25/4;15051;Tallinn;;Estonia;+372 5333 1117;+372 5333 1117;info@sailing.ee;www.sailing.ee;Kasper Eisel
2641;1;Sailing Delight Spain;Grzybowa 14;62-030;Lubon;;Poland;+48 512 126 134;+48 512 126 134;maciej.piotr.czarnecki@gmail.com;swiadomerejsy.pl;Maciej Czarnecki
2630;13;Sailway;Edificio El Tinglado- Muelle de Comercio nº 2;36202;Vigo, Pontevedra;;Spain;+34 986 442 351;;info@sailway.es;www.sailway.es;Ruben Perez Iglesias
2623;1;My Sailing Week;Via Felice Cavallotti 53;19121;La Spezia;La Spezia;Italy;+39 0187-730 338;;info@mysailingweek.com;www.mysailingweek.com;Gustav Birger
2622;21;Yacht-Charter-Center;Vrsar istarska 8;52450;Vrsar;1;Croatia;+49 (0) 6101 9879 79;+49 (0) 6101 9879 79;mmk@yacht-charter-center.de;www.yacht-charter-center.de;Kai Pohatschka
2617;14;Tendance Voile;Quai de La Galiote;83310;Cogolin;var;France;+33 (0)4 9455 2323;660759460;info@tendance-voile.com;www.tendance-voile.com;De Smedt Vincent Gilles
2606;4;Grecosail Yachting;Kypselis 2A;11362;Athens;Alimos;Greece;+30 210 803 5779;+30 693 723 1505;george@grecosail.com;www.grecosail.com;George Papaioannou
2582;6;Dufi Sail Charter;Via Firenze 78/e;59100;Prato;;Italy;3905651791544;393458858971;charter@dufisailcharter.com;www.dufisailcharter.com;Andrea Calamai
2578;2;Sailty ;Parodos Ypapantis;;Kato Kastritsi;;Greece;306972774474;306972774474;info@sailty.gr;www.sailty.gr;Giannis Ioannou
2573;2;Vourazelis Yachting;30 Troias Vrilissia;15235;Athens;Greece;Greece;306972230448;306972230448;info@vourazelis-yachting.gr;www.vourazelis-yachting.gr;
2568;14;Ekvator Yachting;Obala Jerka Šižgorica 1;22000;Šibenik;;Croatia;385957261506;385957261506;info@ekvator-yachting.hr;;Matej Novak
2560;2;Georgina Yachting;Xiou 80;16561;Athens;;Greece;+30 210 9648 595;+30 694 4511 288;info@mentisyachting-greece.com;www.mentisyachting-greece.com;Konstantinos Mentis
2556;30;Sail Lanka Charter;06 Cambridge Place;700;Colombo 07;;Sri Lanka;94714405000;;contact@sail-lanka-charter.com;https://www.sail-lanka-charter.com/;Mario Stubbs
2542;5;Atoll Comfort Sailing;Bracka 15;21000;Split;Croatia;Croatia;+43(0)6642032422;;hello@comfortsailing.eu;www.comfortsailing.eu;Stefan Zainer
2535;6;EGO Charter;;15344;Gerakas, Athens;;Greece;+30 2292152023 lavrio base;+30 6975991908;m.kolonia@egocharter.com;www.egocharter.com;Igor Telnich
2532;5;Invictus Sicily;Via Trani 59/b;97015;Modica (Rg);;Italy;+39 339 27 22 543;393333785958;sail.charter@invictus-sicily.com;http://www.invictus-sicily.com;Raffaele Pisana
2526;4;MarGeo Yachts;24 Klearchou st.,;17343;Athens;;Greece;+30 694 920 3966;+30 694 920 3966;margeo24@hotmail.com;www.sailyacht.gr;George Nikolopoulos
2520;4;Aquarius Yachts;Aristidou street 15;18531;Piraeus;Greece;Greece;+30 210 4128041;+30 6932606223;charter@aquariusyachtcharter.com;http://aquariusyachtcharter.com/;Fanis Gaitanos
2517;22;Sailme Charter;Isidor Macabich, 15, 2B;7800;Ibiza;Balearic Islands;Spain;+ 34662587111 (Ivan);+34662587111 (Ivan);info@sailme-charter.com;sailme-charter.com;Ivan Briukhovets
2511;16;Chilla Sailing;Petra Hektorovića 2;10000;Zagreb;;Croatia;+385 91 254 1986;+385 91 254 1986;booking@chillasailing.hr;www.chillasailing.hr;
2509;7;POSEIDON SAILS Y.KANTZIS L.P.;25 Karaoli Dimitriou str.;12461;Chaidari;;Greece;306974871250;306974871250;charter@poseidonsails.com;https://www.poseidonsails.com;Yiannis Kantzis
2507;4;Papoulakis Yachting;148 Vas. Olgas str.;54645;Thessaloniki;;Greece;302311470480;306977060744;charter@papoulakis.gr;www.papoulakis.gr;Leonidas Papoulakis
2504;17;Free Charter Srl;Via Cavalcanti 13;9100;Cagliari;;Italy;393938441956;39070401512;info@free-charter.com;www.free-charter.com;
2501;13;CS Charter ;Loc. Guardiamori snc Carloforte;9014;Cagliari;;Italy;393421433106;393421433106;info@cscharter.it;https://www.cs-charter.it/;Davide Gorgerino
2458;7;More Yachting;Aoou 63;26442;Patra;;Greece;+30 697 403 4034;+30 697 403 4034;moreyachting.eu@gmail.com;http://www.moreyachting.eu;Takis Georgiopoulos
2387;16;IRIS Yachtcharter;C/ Roses, s/n;7600;Llucmajor;;Spain;+ 49 228 9359 400;+ 49 157 5836 1019;info@irisyachtcharter.com;www.irisyachtcharter.com;Matthias Büchting
2383;2;Bravasail;Port Marina Palamós, c/Salvador Albert Pei s/n;17230;Palamós;Spain;Spain;+34 616 428 481;+34 616 428 481;info@bravasail.com;www.bravasail.com;Alex Palacios
2371;9;Sailing in Blue;El. Venizelou 215;16341;Ilioupoli, Athens;;Greece;+30 210 7649580;+30 6937142249;charter@sib.gr;www.sib.gr;Georgaki Georgia
2357;4;Blue Emotion;Balančane 12;21220;Trogir;;Croatia;+385 91 888 43 87;;blueemotioncharter@gmail.com;www.blueemotion.eu;Ana Bakica
2350;1;Commodo Navis;Kralja Petra Svačića 2 (Nova trznica I/II);23210;Biograd n/m;;Croatia;+385 23 384 644;+385 98 9813231;cnavis@zd.ht.hr;;Luka Brčić
2336;1;Catamaran Adventures;Bella Vista, Calle Calle 47, edificio: Ocean Business Plaza, departamento: Piso 10, Oficina 10;XFGG+QVF;Panama;Provincia de Panama;Panama;+ 1 (954) 982-8530;;infocharter@catamaranadventures.net;www.catamaranadventures.net;Alvaro Garat
2332;3;CroSails;Vukovarska 169;21000;Split;;Croatia;385 95 90 91 979;385 95 90 91 979;info@CroSails.com;www.CroSails.com;Nikša Božić
2329;2;IBIZAWINDS;C/General Balanzat 1 - Entresuelo 17;7820;Sant Antonio de Portmany;Ibiza;Spain;+34 625 778 803;+34 625 778 803;reservas.ibizawinds@gmail.com;www.ibizawinds.com;Javier Perez-Almanaz Reverte
2322;12;Riginos Sails;27 Pergamou Street;16675;Glyfada;;Greece;+30 210 9621274;+30 6941 497820;sailing@riginos.com;www.riginos.com;George Riginos
2317;11;Croatia-Adriatic;Put brodograditelja 16;21220;Trogir;;Croatia;+385 99 209 0052;+385 99 209 0052;info@croatia-adriatic.eu;www.croatia-adriatic.eu;Irena Alić
2315;42;VPM Bestsail - EIS Finance SARL;65, Rue de la Croix;9200;Nanterre;;France;+49 761 380630;;ib@bestsail.net;www.vpm-yachtcharter.com;Lucie Barone
2307;1;Kralj Centar;Markuševečka Trnava 12;10166;Zagreb;;Croatia;+385 91 366 0217;+385 91 366 0217;princy_kralj@yahoo.de;www.kralj-centar.hr;Tomislav Kralj
2304;16;Goolets;Taborska cesta 38d;1290;Grosuplje;;Slovenia;+386 40 875 095;;ziva.lencek@goolets.net;www.goolets.net;Mitja Mirtič
2303;6;Ploce Sailing ltd;Vladimira Nazora 47;20340;Ploče;;Croatia;+385 20 679 933;+385 99 815 2981;info@plocesailing.com;www.plocesailing.com;Teo Marinović
2302;3;AM Charter;Via G. Pacchioni 9;163;Roma;;Italy;+39 0766501217;+39 3497674292;charter@am-charter.com;www.am-charter.com;Marianna Accurso
2295;4;Solis Charter Group;SEPTEMBRIOU 144;11251;ATHENS;;Greece;447880359973;;Mike@solischartergroup.com;www.solischartergroup.com;Mike Lean
2288;3;Seafree International Charters;Lg. das Palmeiras 9;1050-168;Lisbon;;Portugal;+351 917 550 015;;info@seafree.eu;www.seafree.eu;Luis Fernandes
2283;2;Adria Yachten;M. Krleze 5;23000;Zadar;;Croatia;+385 98 1693 403;+385 98 1693 403;info@adriayachten.com;www.adriayachten.com;Davor Babac
2277;13;Isalos Yachting ;103 Vasilissis Sofias Avenue;11521;Athens;;Greece;+30 694 8607 949;+30 694 8607 949;info@isalosyachting.gr;www.isalosyachting.gr;Ilias Iliadis
2276;6;Blue Sails ;Theomitoros & Ellhs Alexiou 58 & 1;17342;Agios Dimitrios ;;Greece;+30 694 289 1642;+30 694 289 1642;charter@bluesails.gr;www.bluesails.gr;Marinos Krestas
2275;5;TZENNET Yachting;1, Maleshevska str.;2800;SANDANSKI;;Bulgaria;+30 210 6826748;+30 697 7472 329;info@tzennet-yachting.gr;www.tzennet.gr;Takis Tzennetoglou
2266;4;Rinia Yachting;Davaki 4;17455;Alimos;;Greece;+48 501 455 697;+48 501 455 697;riniayachting@gmail.com;riniayachting.com/?lang=en;Adam Widmanski
2257;4;Pure Sail;Lugar de Santana;9580-487;Santana de Baixo;;Portugal;+351 916 667 216;+351 916 667 216;info@puresailazores.com;www.puresailazores.com;Mauro Almeida Castanheira
2256;7;Portiate;Marina de Portimao Loja 1N;8500-354;Portimao;;Portugal;+351 96 393 2595;+351 96 393 2595;portiate@portiate.com;www.portiate.com;José Taveira
2252;18;Malta Charters;Tal-Brama Triq il-Prinjol;IKL1823;L-lklin;;Malta;35677361840;+356 798 90954;info@maltacharters.com;www.maltacharters.com;Jonathan Gambin
2247;3;Sail Your Soul;Christophorou Kontokali 8;49100;Corfu;;Greece;+30 26610 91632;+30 69470 72616;info@sailyoursoul.com;www.sailyoursoul.com;Klaus Mayer
2246;5;Azul sailing;Moll de la Marina 10;8005;Barcelona;Cataluña;Spain;+34 932 847 664;+34 687 867 417;info@azulsailing.es;www.azulsailing.es;Joost F.H.M.Baggen
2243;2;Rosa dei Venti Yacht Charter;Via Universitá 8;98122;Messina;Sicilia;Italy;+39 320 0227774;+39 320 0227774;info@rosadeiventicharter.it;www.rosadeiventicharter.com;Fabrizio de Salvo
2241;7;Nautica Corcho;Marina de Denia, Edif. F-2, Local 2;3700;Denia (Alicante);;Spain;+96 578 6891;+96 649 775 327;info@nauticacorcho.com;www.nauticacorcho.com;Kiko
2239;9;AVeleCazzate;Via Karaiskaki 10;31100;Lefkas;;Greece;+39 335 466444;+30 693 8735585;info@avelecazzate.com;www.avelecazzate.com;Linguerri Roberto
2235;9;Sail and Experience ;Via Napoli 77 Mugnano;80018;Napoli;;Italy;+39 345 6628 217;+39 345 6628 217;info@sailandexperience.it;www.sailandexperience.it;Claudia Ibba
2234;5;CRO-MAR ;Ist 57, 23293, otok Ist;23293;Zadar, 23000;Zadarska;Croatia;3859832025;;info.segatravel@gmail.com;www.cromarholidays.com;Jasmina Segaric
2233;7;EOLO Charter;via Giuseppe Pavone, 80;90151;Palermo;;Italy;+39 342 0707 839;+39 342 0707 839;eolocharter@gmail.com;www.eolocharter.com;Alessia Caputo
2227;3;Mayer d.o.o.;Kralja Tomislava 41;21220;Trogir;;Croatia;+385 21 806 015;+385 91 588 6518;mayer@st.t-com.hr;;Boris Ćurković
2219;6;Palmayachts;Avenida Brasília - Doca de Belém;1400-038;Lisbon;;Portugal;+351 937 065 280;+351 937 065 280;info@palmayachts.com;www.palmayachts.com;Rui Palma
2193;40;IonionSails.gr;Mantzagriotaki 89;17672;Kalithea, Athens;;Greece;0030 2645 0 22747;0030 6944 747 248;info@ionionsails.com;www.ionionsails.com;Gabriel Efstathiou
2188;13;WPS YACHTING;Marina Gouvia;49100;Corfu;;Greece;;;info@wps-yachting.com;http://www.wps-yachting.com/;Uwe Barchfeld
2178;1;Epifany Yachting;67 ADRIANOUPOLEOS STR;17124;NEW SMIRNI;Athens;Greece;+30 697 470 5381;;epifanyyachts@gmail.com;www.epifany-yachting.com;Denaxas Dimitris
2170;1;Galazio Sailing;Egnatias 66;12137;Peristeri;Athens;Greece;+30 6932 205556;;charter@galaziosailing.gr;www.albatrossyachts.gr;Tasos Pappas
2164;1;SERAPHILUS marine charter;Gornje Prekrižje 14;10000;Zagreb;;Croatia;38514619279;385912051805;info@seraphilus-marinecharter.hr;seraphilus-marinecharter.hr;
2158;6;Perfect Yachts;Efstathiou Vogiatzi 20, Olympic Marine;19500;Lavrio;Attiki;Greece;+30 22920 69108;;booking@perfectyachts.gr;www.perfectyachts.gr;Vangelis Tampakopoulos
2155;13;Smart Sail;Mel 1/B;51250;Novi Vinodolski;;Croatia;+385 95 3333 791;+385 95  33 33 791;booking@smartsail.hr;https://smartsail.hr/;Adnan Sinanović
2145;1;Greek Isles Yachting;7, GRIGORIOU AFXENTIOU STREET;17455;Alimos;;Greece;302109811665;306970026863;info@greekislesyachting.com;www.greekislesyachting.com;
2142;8;Aeolian Yachts;45 Grigoriou Lampraki Street;14123;Lykovrisi - Athens;;Greece;(+30) 210 4294730;;charters@aeolianyachts.com;www.aeolianyachts.com;Andreas Ioannidis
2133;5;Fair Wind;Luke Botića 9;21210;Solin;;Croatia;+385 981620992;+385 981620992;info@fairwind.hr;https://fairwind.hr/;Ivana Kovačić
2126;34;Veritas Yachting Europe GmbH;Alte Berliner Straße 43;34298;Helsa;Hessen;Croatia;385994378300;;info.dalmatia@four-seasons-yachting.com;www.four-seasons-yachting.com;Uwe Barchfeld
2125;6;Imbat Yacht;Akarca Mah. 887 Sok. 11/AA;48300;Muğla;;Turkey;+90 252 611 33 88;905336632118;info@imbatyacht.com;www.imbatyacht.com;Sezgi Köktener
2121;1;Trata;Mali Brgud 33;51213;Jurdani;;Croatia;099 677 6219;;mysibaricro@gmail.com;http://www.sibariyacht.com/;Christijan Nikolac
2116;2;Color Charter;Via G. Ferrari 2;195;Roma;;Italy;+39 3494026064 (Solo SMS);;info@colorcharter.com;www.colorcharter.com;
2112;11;Vogue Yachting;Rovinjska 4;21000;Split;;Croatia;;;office@vogueyachting.com;www.vogueyachting.com;Marcel Fleser
2111;66;ADVENTURE CHARTER;B. Ćirila i Metoda 5;22211;Vodice;;Croatia;+385 22 466 061;+385 98 330 599;info@adventure-charter.hr;www.adventure-charter.hr;Maja Skočić
2107;4;Lagoon NEPA;253 P. Tsaldari street;176 75;Athens;;Greece;;306936938418;info@lagoon-nepa.gr;lagoon-nepa.gr;Giorgis Evdoxiadis
2105;5;Blue Nautica;Put Cumbrijana 19;21220;Trogir;;Croatia;+385 99 6600 269;+385 99 6600 269;info@blue-nautica.com;https://www.blue-nautica.com;Joško Kandija
2104;7;Navigator charter;Biogradska 4;21000;Split;;Croatia;+385 98 186 7736;+385 98 186 7736;booking@navigator-charter.com;www.navigator-charter.com;Dubravka Čikeš
2075;7;Ionian Yachts;Anthotopos;47100;Arta;;Greece;+30 26810 98122;+30 6947 433755;info@ionian-yachts.gr;www.ionian-yachts.gr;Costas Chalkias
2041;22;Batana Navtik;Kostanjevica na Krasu 90;5296;Kostanjevica;;Slovenia;00386 41 320 919;00386 41 420 527;info@batana-navtik.si;www.batana-navtik.si;Goran Brovč
2030;7;Lava Charter;Avda. Islas Canarias CC Las Maretas, Local 32 A;35508;Costa Teguise;Lanzarote;Spain;34928663209;;booking@lavacharter.com;www.lavacharter.com;Jan Schäper
1996;32;SailWays;57, Sokratous Str.;17563;Palaio Faliro, Athens;Attiki;Greece;+30 2109801700;;info@sailways.gr;www.sailways.gr;Aleksander Shehu
1994;35;Bemex Boot;Marina Dalmacija 14;23206;Sukošan;;Croatia;420724041592;420724041592;office@bemexboot.com;www.bemexboot.com  ;Vladislav Bezecný
1988;1;Danora;Kralja Petra Svačića 63;23210;Biograd na Moru;Zadarska;Croatia;+385 23 386 620;+385 98 273 215;danorayacht@gmail.com;;Marina Jadre
1966;12;Feel Yachting;Šoltanska 16;21000;Split;;Croatia;+385 (0)21 474 464;+385 (0)99 216 5500;agent@croatiacharter.com;www.croatiacharter.com;
1952;8;Parallelo 38 Charter;Via Argine Destro Annunziata 11 B/C;89122;Reggio Calabria;Reggio Calabria;Italy;+39 3760576777;;booking@parallelo38charter.it;www.parallelo38charter.it;Vincenzo Ricordo
1947;3;Sail42;G.Vizoukidi 41;54636;Thessaloniki;;Greece;306944293133;;info@sail42.gr;www.sail42.gr;Konstantinos Patsis
1941;2;Velasquez;Via Manfredonia 41;171;Rome;;Italy;+39 06 97603660;;info@velasquez.it;www.velasquez.it;Claudio Fiorentino
1939;31;Sea Folk by Private Travel;Via Piancorella 2;90149;Corteglia;;Switzerland;+39 334 1420983;+39 334 1420983;info@seafolk.net;www.seafolk.it;Antonio Sparacino
1928;2;Nord Sail;Vene St 10-8;10123;Tallinn;;Estonia;+372 566 99919;;info@nordsail.ee;www.nordsail.ee;Lauri Kurvits
1923;4;Ortsa Sailing;Ploutonos 16;17562;Paleo Faliro;;Greece;+30210 9837010;306944896781;charter@ortsasailing.com;https://www.ortsasailing.com;Dimitris Logothetis
1921;30;Yachtcharter Sweden;Box 8947;402 73;Göteborg;;Sweden;+46 (0)31 511040;+46 (0)730 941400;info@ycsweden.com;www.yachtchartersweden.se;Thomas Hallarås
1907;4;Octopus sailing;Marmaris Adakoy Marina;;Marmaris;Mugla;Turkey;+90 536 996 54 74;;info@octopus-sailing.com;www.octopus-sailing.com;Mikhail Mishchenko
1886;5;Alamar Charter;Avda Cortes Valencianas;7820;Pobla de Farnals;Spain;Spain;34615929706;;info@papilloncharter.com;www.papilloncharter.com;
1883;3;Opensea Yachting;ANTHEON 31 STR;11143; ATHENS;;Greece;306980800748;;info@opensea-yachting.com;www.opensea-yachting.com;Maria Masiala
1871;1;Sailtours;Rua Dr. Luís Ribeiro nº 1;9700-112;Angra Do Heroismo;Açores;Portugal;+351 966062370;+351 966062370;info@sailtours.pt;http://www.sailtours.pt;Nicolau Bettencourt
1869;2;MDM Charter;Tomasinijeva 9;52100;Pula;;Croatia;38598421461;;mdm-piria@inet.hr;mdm-piria.hr;Milorad Dragaš
1862;2;VoiliVoilou;Vasilis 3;11851;Thissio;;Greece;+30 698 1010 757;;voilivoilou16@gmail.com;www.voilivoilou.com/fleet;Stéphane Christopoulos
1857;15;Helios Stratos;Cesta Dr.Franje Tuđmana 1118;21217;Kaštel Štafilić;;Croatia;+385 99 4441728;+385 99 4441728;info@helios-stratos.hr;www.helios-stratos.hr;Danijel Klarić
1855;2;Hobbs of Henley;Station Road;RG9 1AZ;Henley of Thames;Oxfordshire;United Kingdom;441491572035;;boats@hobbsofhenley.co.uk;www.hobbsofhenley.com;Jonathan Hobbs
1852;8;METS Yachting;Barbaros Cad. No: 141;48700;MARMARIS;;Turkey;+90 252 4134340;+90 505 415 9109 (Ethem);reservation@metsyachting.com;http://metsyachting.com/;Ethem Yigit
1842;1;H.C. Sea Service;Via Appia Nuova 572;179;Rome;;Italy;393318110910;;hcseaservice1@gmail.com;www.chartertoscanaelba.com;
1814;5;Le Bateau Blanc;Via XXV Aprile 8;19031;Ameglia;La Spezia;Italy;390187601254;;lebateaublanc@lebateaublanc.it;https://www.lebateaublanc.it/;Guastini Sandro
1804;1;Travel Boat;Klaipedos Pilies uostas;;Klaipeda;;Lithuania;+370 698 34089;;info@travelboat.lt;www.travel-boat.com;
1802;82;AF Yachting;6 Eleftherias Avenue, Alimos;17455;Athens;;Greece;(+30) 210 9236721;;info@af-yachting.com;www.af-yachting.com;Alexandros Foukas
1785;17;Splendid Yachting;Obala Jerka Sizgorica 1;22000;Sibenik;;Croatia;+385 22 335 516;+385 99 733 8118;info@splendidyachting.com;www.splendidyachting.com;Betina Zupanovic
1768;42;Sail Aegean Europe;Rue du Duc 22;1150;Brussels;;Belgium;+32 2 771 74 71;+30 6947861081;info@sailaegean.eu;www.sailaegean.eu;Christina Bletsas
1709;1;Sail&More;Via dei Tessadri, 9;38123;Trento;;Italy;;393891266864;info@sailandmore.it;www.sailandmore.it;Luca Lescio
1682;18;4U Yachting;Bagdat Cad. No: 293/8;34728;Istanbul;Kadikoy;Turkey;902163368484;905327047665;info@4uyachting.com;http://www.4uyachting.com;Ozgur Cengiz
1676;2;Dubrovnik Yachting;Na Skali 2, Mokošica;20 236;Dubrovnik;;Croatia;00385(0)914881999;;info@yachting-dubrovnik.com;www.yachting-dubrovnik.com;Karmela Obrovac, Denis Vukas
1664;12;Alternautika;Pantovčak 42;10000;Zagreb;;Croatia;+385 1 6604 427;+385 98 461 686;info@alternautika.com;www.alternautika.com;Nikola Zuber
1652;2;Aegean Sails;Platonos str. 17;17455;Athens;Alimos;Greece;302109880089;306976692528;info@aegeansails.gr;www.aegeansails.gr;Vassilis Georgopoulos
1644;3;Leut Vagabundo Charter;Krlezina 2;21000;Split;;Croatia;385992365755;385992365755;info@leut-vagabundo.com;www.leut-vagabundo.com;Ante Carevic
1637;11;ASC Yachting Gomar;Splitska 21;23210;Biograd na Moru;;Croatia;38598298431;38598298431;nadja@asc-yachting.com;www.asc-yachting.com;Gordan Borcilo
1574;0;Sail Croatia;Hrvojeva 6;21000;Split;;Croatia;+44 (0) 800 193 8289;;sales@sail-croatia.com;www.sail-croatia.com;Grant Seuren
1537;1;Sun ByTheSea;Östrabyvägen 9;263 94;MJÖHULT;;Sweden;+39 3924891779;;info@vanillablues.com;www.sunbythesea.se;Mauro Cappiello
1523;5;Surcando Mares;Av. Miguel Utrillo, 90;8870;Sitges. Barcelona;Barcelona;Spain;+34 93 894 93 17;+34 630 60 30 14;surcandomares@surcandomares.com;www.surcandomares.com;Alexis Anillo Esquivel
1520;17;El Yachting;Theatraki, Marina Patras;26442;Patras;;Greece;+30 2610 994 300;+30 694 650 05 17;info@elyachting.com;www.elyachting.com;
1503;3;Nautika Koren;Obala Rtine 1A;22213;Pirovac;;Croatia;38631600665;;nc.yacht@gmail.com;www.nc-yacht.com;Branko Krasovec
1502;68;Master Yachting;Marina Dalmacija 14;HR-23206;Sukosan;;Croatia;+385 23 393 230;+385 99 449 8226;info@masteryachting.hr;www.masteryachting.hr;Darko Obradovic
1494;4;GTF;Senova 14;10000;Zagreb;;Croatia;+385 1 6155 433;+385 91 6605 498;info@gtf-no1.hr;www.gtf-no1.hr;
1492;26;GR Sailing;Aliartou 3;26443;Patras;;Greece;+30 6936 777 284;;charter@grsailing.gr;www.grsailing.gr;Liopetas George
1477;5;Waterfront Jachtcharter;Veerdam 5;4484 NV;Kortgene;;Netherlands;+31 (0) 111 67 28 90;;info@jachtcharter.com;www.jachtcharter.com;Danny van Solm
1476;11;Master Charter;Bezine 14, Prugovo;21231;Klis;;Croatia;+385 21 777 094;+385 98 1769 305;info@mastercharter.com;www.mastercharter.com;Stipe Petricevic
1427;33;SK-Yachting;Stephan Kohler Yat Turizm Insaat Tic. Ltd. Sti.;48700;Marmaris;Mugla;Turkey;+49 558 446 10 85 00;+90 532 137 89 37;serra@sk-yachting.com;www.sk-yachting.com;Peter Nölle
1408;5;Mondovela Charter;Via Washington 7;20146;Milano;;Italy;+39 02 4819071;+39 335 477007;info@mondovela.it;www.mondovela.it;
1362;18;Hermes Yachting;57 Eleftherias Ave.;174 55;Alimos;Attica;Greece;302104110094;306948086562;charter@hermesyachting.com;https://www.hermesyachting.com/;Apostolos Tsarouchas
1357;7;Vastardis Yachting;3 Kalamakiou str.;17455;Athens;;Greece;+30 210 98 48 099;+30 694 43 41 708;info@vastardisyachting.gr;https://vastardisyachting.gr/;Agapitos Vastardis
1330;1;FeelDouro Yacht Charter;Douro Marina;4400-554;Villa Nova de Gaia;;Portugal;+351 220 990 922;+351 913 103 983;charter@feeldouro.com;www.feeldouro.com;
1324;6;Thea Sailing ;Adaköy Club Marina ;48700;Marmaris;;Turkey;902663127458;905353616955;info@theasailing.com;;Nazli Uygun
1322;16;Aqua Libra;Drietak 81 (administratief adres);3640;Kinrooi;Limburg;Belgium;32471476761;32471476761;info@aqua-libra.be;www.aqua-libra.be;Luc Vanthoor
1321;2;Fancy Sailing;4 Achaion street;17564;P. Faliro;;Greece;+ 30 210 942 34 40;+ 30 6948 686 540;info@fancysailing.com;www.fancysailing.com;Maria S. Tsirou
1310;1;Suncat;Via G. ferrari 2;192;Roma;Rm;Italy;;+39 347 220 77 61;suncatcharter@gmail.com;www.suncat.it;
1284;18;Yelkenli Yachting;Çukurambar Mah.  1459 Cad. No:2/6;6530;Ankara;;Turkey;+90 312 4170396;+90 537 5270240;yelkenli@yelkenli.com;www.yelkenli.com;Burak Murat BAYRAM
1254;20;Paralos Yachts;14 Themidos str.;17563;Athens;;Greece;+30 211 1824707;+30 693 6596111;info@paralosyachts.com;www.paralosyachts.com;Lefteris Melachrinos
1237;7;Blue Trend;Via Bonito 27a;80129;Napoli;;Italy;+39 (089) 99 52 256;;info@bluetrend.it;www.bluetrend.it;
1230;52;Sailing Sicily;Fondo Mineo 2;90146;Palermo;;Italy;+39 091 580 679;+39 349 226 05 29;info@sailingsicily.com;www.sailingsicily.com;
1227;3;Mate Charter;Put Kotlara 15;23000;Zadar;Zadarska;Croatia;;+385 (99) 335 80 90;info@mate-charter.hr;www.mate-charter.hr;Romano Dadić
1222;45;Albatros Yachting;Ivana Mestrovica 2;23000;Zadar;;Croatia;+385 23 332 282;+385 98 329 521;info@albatros-yachting.com;www.albatros-yachting.com;
1220;10;PUUR Yachtcharter;Eckenbertstraße 34;67549;Worms;;Germany;+49 6241 306861;+49 151 6471 9060;info@puur-yachtcharter.de;www.puur-yachtcharter.de;Hans-Juergen Uhink
1186;7;Shamandura Charter;Via Della Libertà 58;90143;Palermo;;Italy;+39 338 421 8477;;info@shamandura.com;www.shamandura.com;
1136;38;Waypoint;Antuna Mihanovica 37;21000;Split;Splitsko Dalmatinska;Croatia;+385 21 367 686;Temeljni kapital 310.000,00 kn uplaćen u cijelosti.;booking@waypoint.hr;www.waypoint.hr;Damir Čargo
1135;10;Ionian Charter;Thiras Str 28;GR-16561;Glyfada - Athens;;Greece;+30 210 9617834;+30 6932 262164;info@ionian-charter.com;www.ionian-charter.com;Eleni Vryoni
1114;11;Vishe Radugi Yachting;SARIANA MAHALESI, MUSTAFA MUNIR ELGIN BUL.,  NETSEL MARINA, BLOK NO: 38-6, IC KAPI NO: D;487000;Marmaris;;Turkey;79857742284;;book@monoflot.com;www.monoflot.com;Anna Panasyuk
1096;5;Race & Cruise S.a.s;Dorsoduro 1711;30123;Venezia;;Italy;+ 39 393 969 0870;;info@raceandc.com;www.raceandc.com;
1093;14;Sailing in Italy Charter;Via Duccio Galimberti, 27;136;Roma;RM;Italy;390680079982;;booking@sailinginitaly.it;www.sailinginitalycharter.it;Claudio Gastaldi
1081;3;Lagoon Life;Marina di Nettuno Box 13a;48;Nettuno;Roma Lazio ;Italy;+39 06 9880998;+39 335 8095169;info@lagoonlife.it;http://www.lagoonlife.it;Vittorio Mango
1071;2;+39 Charter;viale San Concordio 738;55100;Lucca;;Italy;+39 3483337796;;39charter@gmail.com;www.39charter.com;
1060;35;Seafarer Cruising & Sailing Holidays;4th Floor, Hamilton House;WC1H 9BB;London;;United Kingdom;+44 (0) 208 324 3118;;info@seafarerholidays.com;www.seafarersailing.co.uk;Chris Lorenzo
1058;27;Adriatic Yacht Charter;Braće Leonardelli 33;52100;Pula;;Croatia;+385 98 273 880;+385 95 906 2866;ayc@ayc.hr;www.ayc.hr;Neven Maras, Boško Čelić
1010;5;One Day Charter;Kalimanj bb;85320;Tivat;;Montenegro;38269140400;;info@onedaycharter.me;www.onedaycharter.me;Nevena Todorović
998;0;AAB Yachting - Alter AB;Office: Ul. Jožeta Jame 14;1000;Ljubljana;Slovenia;Slovenia;38615106392;38631678783;yachting@aab.si;www.aab.si;Bojan Žvanut
928;9;DeToni charter;Rimski put 72;21220;Trogir;;Croatia;385915177681;+385 91 253 30 41;info@detonicharter.com;www.detonicharter.com;Antonio Buble
923;22;Sun Yachting;21, Poseidonos Avenue;174 55;Alimos;;Greece;210 983 7312;;info@sunyachting.gr;www.sunyachting.gr;
892;3;Dolphin Yachts OE;Gouvia marina , Corfu;49100;Corfu;;Greece;302661099778;;charter@dolphinyachts.gr;https://www.dolphinyachts.gr;George Theofilakos
864;20;Yildiz Yachting;Ece Saray Marina, 1 Karagözler Mevkii;48300;Fethiye;Mugla;Turkey;+90 252 313 20 28;+90 532 566 72 78;info@yildizyachting.com;www.yildizyachting.com;
859;19;Cagliari Sailing Charter;Via Sonnino 108;9127;Cagliari;Sardegna ;Italy;+39 3351503902;;info@cagliarisailingcharter.com;www.cagliarisailingcharter.com;Alessandro Picciau
845;1;6 Nodi;V.le Parioli 12;;Roma;;Italy;393294946726;;info@6nodi.com;www.6nodi.com;Lisanna Tiburzi
826;3;Classic Adria;Nikole Tesle 6;23000;Zadar;Zadarska ;Croatia;+385 23 235 535;+385 91 222 50 19;info@classic-adria.com;www.classic-adria.com;Leo Režan
809;30;Sailing Forever;Franje Tuđmana 213;21213;Kastel Gomilica;;Croatia;+385 21 223 614;+385 99 2658 190;info@sailingforever.com;www.sailingforever.com;Silvio Botić
796;33;Adriatic Sailing;Antuna Mihanovića 31a;21 000;Split;;Croatia;+ 385 (0)91 3777 206;;charter@adriatic-sailing.hr;www.adriatic-sailing.hr;Zlatko Vodanović
709;7;Vela Charter;Via Santa Maria dell'Orto 19;80053;Castellammare di Stabia;Campania;Italy;393397086055;393939046232;info@velacharter.it;www.velacharter.it;Gianluca Lauro
706;10;Boreal Yachting;Postboks 4107;N-9280;Tromsø;;Norway;+47 77729200;;post@boreal-yachting.com;www.boreal-yachting.com;Espen Bertelsen
703;6;Turistica Il Gabbiano;Villaggio Turistico PortoRosa;98054;Furnari ;ME;Italy;+39 094 187 4291;;info@turisticailgabbiano.com;www.turisticailgabbiano.com;GIOVANNI DENARO
667;19;Candor Charter;Marina Kaštela;21213;Kaštel Gomilica;;Croatia;+385 98 985 4680;+385 98 985 4680;info@candor.hu;www.candor.hu;Gabor Szor
665;2;Yachtingadria;Gornji Šušanj bb;85000;Bar;;Montenegro;+382 69 590 657;+381 63 692 287;office@yachtingadria.com;www.yachtingadria.com;Nikola Milentijević
648;66;Aladar sail;BY Private Travel SA;6873;Corteglia;;Switzerland;+41 076 5124320;;info@aladarsail.com;www.aladarsail.com;
618;34;Boomerang;Via Garibaldi 40;7026;Olbia;Sassari;Italy;39078924293;;info@boomerangcharter.com;www.boomerangcharter.com;Angelo Usai
609;17;Marina & Yachting;Nikole Škevina 15;22244;Betina;;Croatia;+385 22 435 936;;marina.yachting@st.t-com.hr;www.myd-sailing.com;Mag. Petra Holzmann Koppl
583;15;Venturi-Sailing;Kettingweg 4;4401 KX;Yerseke;Zeeland;Netherlands;+31 6 21 52 62 78;;info@venturi-sailing.nl;www.venturi-sailing.nl;Lennart Rijksen
575;8;MBS-Charter, Mallorca-Balear-Sailing & Charter;Carrer de les Roses 4, 2ª;7400;Port Alcudia;;Spain;+34 971098860;;service@mbs-charter.com;www.mbs-charter.com;
548;25;Offshore Boote p.m.;Am Heumarkt 3/1/48;1030;Wien;Wien;Austria;+43 (1) 7992345;;info@offshore-boote.com;www.offshore-boote.com;Thomas Mesaric
543;11;Croatia Sailing;Oder 2;92449;Steinberg am See;;Germany;+49 171 7630093;;croatiasailing@yachtcharter-gradl.de;www.croatiasailing.de;
525;4;CM Charter / Sailme Mallorca;C. Monges 6;7470;Pollensa;;Spain;34971867332;;info@cmcharter.com;www.cmcharter.com;
512;46;Ban Tours;Trg bana Josipa Jelačića 5;10000;Zagreb;;Croatia;"+38598257954;  VAT: HR91025164621 / MB 0915408; ID CODE: HR-AB-01-1-61509";;yachting@bantours.hr;www.bantoursyachting.hr;ID CODE: HR-AB-01-1-61509
426;23;Adria Service Yachting;Tomažičeva ulica 4a;6310;Izola;;Slovenia;38656401102;;booking@asy.si;www.asy.si;Vesna Brozič
412;1;Odisej Yachting;Obala Jerka Sizgorića 1;22000;Sibenik;;Croatia;+385 22 33 18 83;+ 385 99 22 65 650;info@odisej-yachting.com;www.odisej-yachting.com;Domagoj Milišić
387;1;Mirakul Yachting;Jadranska 25 b;23210;Biograd;;Croatia;+385 23 394 102;+385 91 253 4400;info@mirakul-yachting.hr;www.mirakul-yachting.hr;Mara Ročić
354;13;Sailing Europe Charter;Prilaz Gjure Dezelica 80;10000;Zagreb;;Croatia;+385 1 488 22 00;+385 91 488 22 08;booking@sailingeuropecharter.com;http://www.sailingeuropecharter.com;
317;82;Jadranka Yachting;Drazica 1;51550;Mali Losinj;;Croatia;+385 51 233 086;+385 98 872 676;booking@jadranka-yachting.com;www.jadranka-yachting.com;Arman Perčinlić
295;3;Now Charter;Molo Marinai d'Italia, 14;55049;Viareggio;LU;Italy;+39 0584386764;+39 3284956453;info@nowcharter.com;www.nowcharter.com;Elena Guarascio
280;23;Sunsicily Yacht Charter;Via Ghibellina 48;98122;Messina;;Italy;+39 90 292 7680;+39 348 257 3832;info@sunsicily.com;www.sunsicily.com;Laura Ilacqua
273;7;Blue Dream Charter & Service;VIA DUOMO 290/C;80138;Naples;;Italy;+39 3298784116;;info@bluedreamcharter.com;www.bludreamcharter.com;Antonio Musella
261;15;Cruising Charter;Via F. Tanara 5;43121;Parma;PR;Italy;39078925944;;info@cruisingcharter.it;www.cruisingcharter.it;Mr. Barabino
260;154;NSS Charter;Via S.Pellico 1/B;56025;Pontedera ;Pisa;Italy;+39 0587 59124;;info@nsscharter.com;www.nsscharter.com;Simone Morelli
258;7;Sailmarine;Brantvägen 3;SE-133 42;Saltsjöbaden;Stockholm;Sweden;+46 8 240 230;;charter@sailmarine.com;www.sailmarine.com;
246;19;Nova Eurospectra;Petra Peraradovića 7;22000;Šibenik;;Croatia;38521884090;;ivana@eurospectra.hr;www.eurospectra.hr;Andrey Zaikin
241;92;Alboran Charter;Pedregar 3;7014;Palma de Mallorca;Islas Baleares;Spain;0034 971 90 19 91;;alboran@alboran-charter.com;www.alboran-charter.com;Marivi Perez
239;13;Cruesa Mallorca Yachtcharter;Magalhaes 12, bajos.;7014;Palma de Mallorca;Baleares;Spain;34971282821;;info@cruesa.com;www.cruesa.com   Insc. Registro Mercantil de Mallorca – Tomo 1690, Folio 63, Hoja PM-8.423, Inscripción 22. INT'L VAT REG: ESB07065113;Cristina Sastre Fallet
233;67;Nomicos Yachts;Avenue Eleftherias 7;17455;Athens;;Greece;0030 210 9851 385;;info@nomicos-yachts.com;www.nomicos-yachts.com;
231;23;Adria Yacht Center;Tiefer Graben 7;1010;Wien;;Austria;+43 1 5330640;;ayc@ayc.at;www.ayc.at;
227;2;Montenegro Charter Company;Obala bb;85320;Tivat;Crna Gora;Montenegro;+382 67 144 555;+382 67 201 655;info@montenegrocharter.com;www.montenegrocharter.com;Sanja Bozovic
220;23;Trend Travel Yachting;Achenstrasse 6;6322;Kirchbichl;;Austria;0043 5332 74291;;charter@trend-travel-yachting.com;www.trend-travel-yachting.com;Hannes Grassl, Christian Grassl, Christoph Grassl
203;24;Star Voyage Antilles;Port de Plaisance;97290;Le Marin;France;Martinique;33142561562;33612981567;josephine@starvoyage.com;www.starvoyage.com;
201;10;Carloforte Sail Charter;Via Danero 52;9014;Carloforte;Sardegna;Italy;0039 3472733268;;info@carlofortesailcharter.it;www.csailcharter.it;Stefania Gorgerino
191;4;Controvento;Loc. Fontebrizzi 46;58023;Gavorrano;;Italy;+39 3472991570;+39 3472991570;staff@controvento.it;www.controvento.it;Pasquale Bevilacqua
186;15;Pelsys;Ružmarinka 19;10000;Zagreb;;Croatia;;+385 (0) 99 496 7426 (Tea - booking manager);pelsys-charter@pelsys.hr;www.pelsys-charter.com;
185;31;Kroki Nautika;Jabukovac 16A;10000;Zagreb;;Croatia;;+385-98-220214;kroki@kroki.hr;www.kroki.hr;
179;23;Rumbo Norte Ibiza;Paseo de la Mar, Edificio Faro, Locales 8 y 10;7820;SANT ANTONI DE PORTMANY - IBIZA;ISLAS BALEARES;Spain;0034 971094994;;info@rumbonorte.es;www.rumbonorte.es;
174;42;Nautika Centar Nava;Branimirova obala 11;21 000;Split;;Croatia;+385-21-407700;;charter@navaboats.com;www.navaboats.com;
173;4;Beta Charter;Ulica dr.Franje Tuđmana 24;23206;Sukošan;;Croatia;+385 23 360 726;+385 98 9822891;info@beta-charter.com;www.beta-charter.com;Matjaž Avšič
172;69;Ultra Sailing;Uvala baluni 8;21000;Split;Splitsko-dalmatinska zupanija;Croatia;00385-21-398578;00385 98 488 668;booking@ultra-sailing.hr;https://www.ultra-sailing.hr;Emil Tomašević
168;33;Yachting 2000;Am Steinberg 8;4112;St. Gotthard/Linz;;Austria;+43 7234 84 545;;office@yachting2000.at;www.yachting2000.at;
167;15;KWS Adria;Polačišće 2/II;23000;Zadar;;Croatia;;;info@meridijan.hr;www.meridijan.hr;Ladislav Prouza
160;29;Baotic Yachting;Maksimirska cesta 282;10040;ZAGREB;;Croatia;38521880791;;seget@baotic-yachting.com;http://www.baotic-yachting.com/;Željko Baotić
140;3;Sail Adventures;Via Pantelleria 8;91100;Trapani;;Italy;+39 3357291409;;sailadventures@yahoo.com;www.sailadventures.com;Massimo Sparta
129;157;Spartivento;Via Calabria 9-11-13;187;Roma;;Italy;+39 06 400 60 490;;info@spartivento.it;www.spartivento.it;Stefano Pizzi
126;30;Nautika Kufner;Stjepana Gradića 13;10 000;Zagreb;;Croatia;;+385 91 6789 203;info@nautik-kufner.com;www.nautik-kufner.com;Damir Kufner
120;4;Sailor.tex;Via Giuseppe Ripamonti, 44;20141;Milano;Milano / Lombardia ;Italy;+39 3496189626 Booking;;info@sailortex.it;www.sailortex.it;Matteo Italo Ratti
116;20;SunLife Charter;Ljubićeva 24;21000;Split;;Croatia;+385 21 398 496;0385 91 3456 783;info@sunlife.hr;www.sunlife.hr;Tina Gazin
109;53;Adriatic Charter;Hektorovićeva 2;10000;Zagreb;;Croatia;+385 (0)23 394 021;;info@adriatic-charter.com;www.adriatic-charter.com;Alan Miklenic
90;8;Latitud CERO;La Lonja Marina Charter;7012;Palma de Mallorca;Islas Baleares;Spain;+34 607304045;;latitudcero@latitudcero.es;www.latitudcero.es;José María Jiménez
89;84;NCP-Charter;Obala Jerka Šižgorića 1;22200;ŠIBENIK;;Croatia;++385 (0) 22 312 999;;booking@ncp-charter.com;www.ncp-charter.com;
79;9;FullTeam;Ul. Ivana Mažuranića 6 OIB: 44840118794;23210;Biograd na Moru;Zadarska;Croatia;+385 (0)23 384 502;+385 (0)98 272 974;info@full-team.com;www.full-team.com;Ivan Nikša
66;2;Mare Charter;Sprečka 22;10000;Zagreb;ZG;Croatia;+385 1 6113 008;+385 91 5225 819;marecharter@inet.hr;www.marecharter.hr;Zoran Lazic
65;9;Inter Yachting;Plješivička 1, 10040 Zagreb;10040;Zagreb;;Croatia;++385 1 2989484;++385 98 822151; mc@inter-yachting.hr ;www.inter-yachting.hr;gosp. Crnkovic
31;15;ECC Yacht Charter;C/Miraflores, 19;38003;Santa Cruz de Tenerife;Canary Islands;Spain;+34 922 24 0559;34615348576;info@eccyacht.com;http://www.eccyacht.com;ANGEL ESCOLAR
12;16;Navigo Yacht Charter;Nad.Vicka Zmajevica 12;23000;Zadar;;Croatia;00385 98 820202;00385 98 332637;info@navigo.hr;www.navigo.hr;
\.


DO
$$
    DECLARE
        rec   record;
        newId bigint;
    BEGIN
        FOR rec IN SELECT * FROM mmk_agencies
            LOOP
                newId := nextval('agency_id_seq');
                INSERT INTO agency (id, country, name, address, city, zip, web, email, active, director)
                VALUES (newId, rec.country, rec.name, rec.address, rec.city, rec.zipcode, rec.website, rec.email, true,
                        rec.director);
                INSERT INTO agency_source (agency_id, external_system_id, "primary", external_id)
                VALUES (newId, 1, TRUE, rec.mmk_id);
            END LOOP;
    END
$$;