ALTER TABLE pokemon_cache
    ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_pokemon_cache_active_pokemon_id
    ON pokemon_cache (pokemon_id)
    WHERE deleted = FALSE;

CREATE INDEX idx_pokemon_cache_active_pokemon_name
    ON pokemon_cache (pokemon_name)
    WHERE deleted = FALSE;

CREATE INDEX idx_pokemon_collection_entry_trainer_caught_at
    ON pokemon_collection_entry (trainer_id, caught_at DESC);
