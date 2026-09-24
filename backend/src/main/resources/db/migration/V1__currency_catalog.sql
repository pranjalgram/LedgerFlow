CREATE TABLE currency (
    code varchar(3) PRIMARY KEY,
    exponent smallint NOT NULL CHECK (exponent BETWEEN 0 AND 3),
    enabled boolean NOT NULL DEFAULT false,
    CONSTRAINT currency_code_uppercase CHECK (code ~ '^[A-Z]{3}$')
);

INSERT INTO currency (code, exponent, enabled) VALUES ('INR', 2, true);
