CREATE TABLE trainer (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE pokemon_cache (
    cache_id     BIGSERIAL PRIMARY KEY,
    pokemon_id   INTEGER NOT NULL,
    pokemon_name VARCHAR(100) NOT NULL,
    data         JSONB NOT NULL,
    cached_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_pokemon_cache_pokemon_id UNIQUE (pokemon_id),
    CONSTRAINT uq_pokemon_cache_pokemon_name UNIQUE (pokemon_name)
);

CREATE TABLE pokemon_collection_entry (
    id               BIGSERIAL PRIMARY KEY,
    trainer_id       BIGINT NOT NULL REFERENCES trainer(id),
    pokemon_cache_id BIGINT NOT NULL REFERENCES pokemon_cache(cache_id),
    caught_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (trainer_id, pokemon_cache_id)
);
