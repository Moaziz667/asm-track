-- Client-uploaded return photos (public self-service RMA on the tracking page).
-- A return can carry several evidence photos; store one row per photo (child of rma).
CREATE TABLE rma_photo (
    id         UUID PRIMARY KEY,
    rma_id     UUID NOT NULL REFERENCES rma(id) ON DELETE CASCADE,
    url        TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_rma_photo_rma ON rma_photo(rma_id);
