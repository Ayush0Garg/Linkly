-- Destination URLs may now be up to 8192 characters (after percent-encoding).
ALTER TABLE links ALTER COLUMN long_url TYPE VARCHAR(8192);
