-- 22.10.2026: two marina photo credits from the 20.10. Wikimedia import carried the author's name in Cyrillic /
-- Greek script with the Latin transliteration in parentheses ("Foto: Николай Максимович (Nikolai Maksimovich) /
-- Wikimedia Commons (CC BY 3.0)"). On the English (and every Latin-script) yacht page that reads as a stray
-- alphabet; the SEO regression check T11 flagged it on EY and b4y (/yachts|boat/...-11338). CC BY attribution by
-- the transliterated name is sufficient, so the credit keeps the Latin name only. Idempotent: matches the original
-- form only.
UPDATE catalogue_image
   SET credit = regexp_replace(credit, '^Foto: [^(/]+\((Nikolai Maksimovich|Giannis Houvardas)\) /', 'Foto: \1 /')
 WHERE id IN (2000000069, 2000000141)
   AND credit ~ '^Foto: [^(/]+\((Nikolai Maksimovich|Giannis Houvardas)\) /';
