"""Shared feature schema, linear score, quotas and MMR for Go↔Python parity."""

from __future__ import annotations

import json
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

FEATURE_SCHEMA_VERSION = 1
FEATURE_ORDER = [
    "sim_taste",
    "sim_current",
    "sim_session",
    "sim_daypart",
    "sim_mood",
    "sim_place",
    "sim_activity",
    "sim_centroid_max",
    "transition_weight",
    "transition_score",
    "bpm_distance",
    "bpm_missing",
    "lufs_diff",
    "lufs_missing",
    "key_compat",
    "energy_diff",
    "artist_repeat",
    "album_repeat",
    "new_boost",
    "source_exploit",
    "source_transition",
    "source_explore_adjacent",
    "source_resurface",
    "source_new_in_library",
    "source_wildcard",
    "plays_log",
    "early_skip_rate",
    "hours_since_played",
    "never_played",
    "negative_penalty",
]

SOURCE_PRIORITY = [
    "exploit",
    "transition",
    "explore_adjacent",
    "resurface",
    "new_in_library",
    "wildcard",
]

DEFAULT_QUOTAS = {
    "exploit": (2, 4),
    "transition": (0, 2),
    "explore_adjacent": (1, 2),
    "resurface": (0, 1),
    "new_in_library": (0, 1),
    "wildcard": (0, 1),
}

# Same cap as the radio's `Select` (ArtistLimit): at most this many tracks of one artist
# in a mix. The candidate pool holds a few times that per artist, so the cap has material
# to choose from but one artist's discography cannot crowd out everyone else.
DEFAULT_ARTIST_LIMIT = 2
POOL_ARTIST_FACTOR = 3
POOL_ARTIST_MIN = 5
RELAX_STEPS = 6

DEFAULT_MODEL_PATH = (
    Path(__file__).resolve().parents[3]
    / "player"
    / "internal"
    / "queue"
    / "model_default.json"
)


@dataclass(frozen=True)
class Model:
    schema_version: int
    model_version: str
    feature_order: list[str]
    mean: list[float]
    std: list[float]
    weights: list[float]
    bias: float
    score_min: float
    score_max: float

    def validate(self) -> None:
        if self.schema_version != FEATURE_SCHEMA_VERSION:
            raise ValueError("model schema_version does not match feature schema")
        if self.feature_order != FEATURE_ORDER:
            raise ValueError("feature_order mismatch")
        n = len(FEATURE_ORDER)
        if not (len(self.weights) == len(self.mean) == len(self.std) == n):
            raise ValueError("model vector lengths must match feature_order")
        if any(std == 0 for std in self.std):
            raise ValueError("std must be non-zero")
        if self.score_max <= self.score_min:
            raise ValueError("score bounds invalid")

    def score(self, values: list[float]) -> float:
        if len(values) != len(self.weights):
            return self.bias
        total = self.bias
        for value, weight, mean, std in zip(values, self.weights, self.mean, self.std, strict=True):
            total += weight * (value - mean) / std
        return min(self.score_max, max(self.score_min, total))


def load_model(path: Path | None = None) -> Model:
    raw = json.loads((path or DEFAULT_MODEL_PATH).read_text())
    model = Model(
        schema_version=int(raw["schema_version"]),
        model_version=str(raw["model_version"]),
        feature_order=list(raw["feature_order"]),
        mean=[float(x) for x in raw["mean"]],
        std=[float(x) for x in raw["std"]],
        weights=[float(x) for x in raw["weights"]],
        bias=float(raw["bias"]),
        score_min=float(raw["score_min"]),
        score_max=float(raw["score_max"]),
    )
    model.validate()
    return model


EXPLORE_SOURCES = (
    "explore_adjacent",
    "resurface",
    "new_in_library",
    "wildcard",
)


def primary_source(sources: Iterable[str]) -> str:
    have = set(sources)
    for source in SOURCE_PRIORITY:
        if source in have:
            return source
    return "wildcard"


def label_sources(
    *,
    sim_taste: float,
    sim_current: float = 0.0,
    sim_centroid_max: float = 0.0,
    plays: int = 0,
    finishes: int = 0,
    hours_since_played: float | None = None,
    hours_since_created: float | None = None,
) -> list[str]:
    out: list[str] = []
    if sim_taste >= 0.45 or sim_centroid_max >= 0.5:
        out.append("exploit")
    if sim_current >= 0.55:
        out.append("transition")
    if 0.15 <= sim_taste < 0.45:
        out.append("explore_adjacent")
    if hours_since_played is not None and hours_since_played >= 14 * 24 and finishes > 0:
        out.append("resurface")
    if hours_since_created is not None and hours_since_created <= 30 * 24 and plays < 2:
        out.append("new_in_library")
    if sim_taste < 0.15:
        out.append("wildcard")
    return out or ["wildcard"]


def feature_vector(
    *,
    sim_taste: float = 0.0,
    sim_current: float = 0.0,
    sim_session: float = 0.0,
    sim_daypart: float = 0.0,
    sim_mood: float = 0.0,
    sim_place: float = 0.0,
    sim_activity: float = 0.0,
    sim_centroid_max: float = 0.0,
    transition_weight: float = 0.0,
    sources: Iterable[str] | None = None,
    bpm_missing: float = 1.0,
    lufs_missing: float = 1.0,
    new_boost: float = 0.0,
    plays: int = 0,
    early_skip_rate: float = 0.0,
    hours_since_played: float = 0.0,
    never_played: float = 1.0,
    negative_penalty: float = 0.0,
) -> list[float]:
    flags = {source: 0.0 for source in SOURCE_PRIORITY}
    for source in sources or ():
        if source in flags:
            flags[source] = 1.0
    values = {
        "sim_taste": sim_taste,
        "sim_current": sim_current,
        "sim_session": sim_session,
        "sim_daypart": sim_daypart,
        "sim_mood": sim_mood,
        "sim_place": sim_place,
        "sim_activity": sim_activity,
        "sim_centroid_max": sim_centroid_max,
        "transition_weight": transition_weight,
        "transition_score": 0.0,
        "bpm_distance": 0.0,
        "bpm_missing": bpm_missing,
        "lufs_diff": 0.0,
        "lufs_missing": lufs_missing,
        "key_compat": 0.0,
        "energy_diff": 0.0,
        "artist_repeat": 0.0,
        "album_repeat": 0.0,
        "new_boost": new_boost,
        "source_exploit": flags["exploit"],
        "source_transition": flags["transition"],
        "source_explore_adjacent": flags["explore_adjacent"],
        "source_resurface": flags["resurface"],
        "source_new_in_library": flags["new_in_library"],
        "source_wildcard": flags["wildcard"],
        "plays_log": math.log1p(plays),
        "early_skip_rate": early_skip_rate,
        "hours_since_played": hours_since_played,
        "never_played": never_played,
        "negative_penalty": negative_penalty,
    }
    return [float(values[name]) for name in FEATURE_ORDER]


def scale_quotas(
    size: int,
    base: dict[str, tuple[int, int]] | None = None,
    explore_ratio: float | None = None,
) -> dict[str, tuple[int, int]]:
    size = max(size, 1)
    base = base or DEFAULT_QUOTAS
    if explore_ratio is not None and explore_ratio <= 0:
        out = {source: (lo, hi) for source, (lo, hi) in base.items()}
        for source in EXPLORE_SOURCES:
            out[source] = (0, 0)
        out["exploit"] = (max(1, size - 1), size)
        return out
    scale = size / 6
    out: dict[str, tuple[int, int]] = {}
    mins = 0
    for source in SOURCE_PRIORITY:
        lo, hi = base[source]
        min_n = int(math.floor(lo * scale + 1e-9))
        max_n = int(round(hi * scale))
        if lo > 0 and min_n < 1 and size >= 3:
            min_n = 1
        if max_n < min_n:
            max_n = min_n
        out[source] = (min_n, max_n)
        mins += min_n
    for source in reversed(SOURCE_PRIORITY):
        if mins <= size:
            break
        lo, hi = out[source]
        if lo > 0:
            out[source] = (lo - 1, hi)
            mins -= 1
    return out


def mmr_select(
    scores: list[float],
    vectors: list[list[float]],
    artists: list[str],
    *,
    size: int,
    lambda_: float = 0.7,
    artist_penalty: float = 0.15,
    forbidden: Iterable[int] | None = None,
) -> list[int]:
    remaining = set(range(len(scores)))
    if forbidden:
        remaining -= set(forbidden)
    selected: list[int] = []
    counts: dict[str, int] = {}
    while remaining and len(selected) < size:
        best_i = None
        best = -1e9
        for i in remaining:
            div = 0.0
            if selected:
                div = max(_dot(vectors[i], vectors[j]) for j in selected)
            artist = (artists[i] or "").strip().lower()
            value = lambda_ * scores[i] - (1.0 - lambda_) * div - artist_penalty * counts.get(artist, 0)
            if value > best:
                best = value
                best_i = i
        if best_i is None:
            break
        selected.append(best_i)
        remaining.remove(best_i)
        artist = (artists[best_i] or "").strip().lower()
        counts[artist] = counts.get(artist, 0) + 1
    return selected


def _artist_key(item: dict[str, Any]) -> str:
    return str(item.get("artist") or "").strip().lower()


def apply_quotas(
    items: list[dict[str, Any]],
    *,
    size: int,
    quotas: dict[str, tuple[int, int]] | None = None,
    artist_limit: int | None = None,
) -> list[dict[str, Any]]:
    """Pick by source quotas; `artist_limit` caps tracks per artist (None = no cap).

    Tracks with no artist tag are not capped: there is nothing to tell them apart by.
    """
    quotas = quotas or scale_quotas(size)
    artist_counts: dict[str, int] = {}

    def artist_ok(item: dict[str, Any]) -> bool:
        if artist_limit is None:
            return True
        artist = _artist_key(item)
        return not artist or artist_counts.get(artist, 0) < artist_limit

    def note_artist(item: dict[str, Any]) -> None:
        artist = _artist_key(item)
        if artist:
            artist_counts[artist] = artist_counts.get(artist, 0) + 1

    by_source: dict[str, list[dict[str, Any]]] = {src: [] for src in SOURCE_PRIORITY}
    for item in sorted(items, key=lambda x: (-float(x["score"]), int(x.get("track_id", 0)))):
        by_source.setdefault(item["source"], []).append(item)
    chosen: list[dict[str, Any]] = []
    used: set[int] = set()
    counts: dict[str, int] = {src: 0 for src in SOURCE_PRIORITY}
    for source in SOURCE_PRIORITY:
        want = quotas.get(source, (0, 0))[0]
        for item in by_source.get(source, []):
            if len(chosen) >= size or counts[source] >= want:
                break
            tid = int(item["track_id"])
            if tid in used or not artist_ok(item):
                continue
            chosen.append(item)
            used.add(tid)
            note_artist(item)
            counts[source] += 1
    for item in sorted(items, key=lambda x: (-float(x["score"]), int(x.get("track_id", 0)))):
        if len(chosen) >= size:
            break
        tid = int(item["track_id"])
        source = item["source"]
        if tid in used or counts.get(source, 0) >= quotas.get(source, (0, size))[1]:
            continue
        if not artist_ok(item):
            continue
        chosen.append(item)
        used.add(tid)
        note_artist(item)
        counts[source] = counts.get(source, 0) + 1
    return chosen


def outcome_transition_delta(outcome: str, manual: bool) -> float:
    delta = {"finished": 1.0, "partial": 0.3, "early_skip": -0.1}.get(outcome, 0.0)
    if manual:
        delta *= 1.5
    return delta


def _dot(a: list[float], b: list[float]) -> float:
    return float(sum(x * y for x, y in zip(a, b, strict=False)))


def write_model_atomic(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f".{path.name}.tmp")
    tmp.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    tmp.replace(path)


def _song_key(artist: Any, title: Any) -> str:
    """Same song under another id (re-upload, another release): artist + title, folded."""
    a = " ".join(str(artist or "").lower().split())
    t = " ".join(str(title or "").lower().split())
    return f"{a}|{t}" if a and t else ""


def rank_index(
    index: Any,
    *,
    taste: Any,
    current: Any | None = None,
    forbidden: set[int] | None = None,
    size: int,
    explore_ratio: float | None = None,
    model: Model | None = None,
    pool: int = 200,
    artist_limit: int | None = DEFAULT_ARTIST_LIMIT,
) -> list[dict[str, Any]]:
    """Score an embedding index with the shared linear model and source quotas.

    Like the radio (Go `Select`), no artist gets more than `artist_limit` tracks and a
    prolific artist cannot fill the candidate pool: in a library where a few artists own
    hundreds of tracks each, every mix would otherwise be made of those artists.
    `artist_limit=None` switches the cap off. The cap relaxes (limit + 1, …) only when
    the library has too few artists to fill the list.
    """
    import numpy as np

    if index.size == 0 or size < 1:
        return []
    model = model or load_model()
    forbidden = forbidden or set()
    taste = np.asarray(taste, dtype=np.float32)
    taste_sims = index.sims_to_vector(taste)
    current_sims = index.sims_to_vector(current) if current is not None else taste_sims

    def make_item(row_i: int) -> dict[str, Any]:
        meta = index.meta[row_i]
        sim_taste = float(taste_sims[row_i])
        sim_current = float(current_sims[row_i])
        sources = label_sources(sim_taste=sim_taste, sim_current=sim_current)
        features = feature_vector(
            sim_taste=sim_taste,
            sim_current=sim_current,
            sources=sources,
            bpm_missing=0.0 if meta.get("bpm") is not None else 1.0,
            lufs_missing=0.0 if meta.get("lufs") is not None else 1.0,
        )
        return {
            "track_id": int(meta["id"]),
            "row": row_i,
            "source": primary_source(sources),
            "sources": sources,
            "score": model.score(features),
            "features": features,
            "sim_taste": sim_taste,
            "sim_current": sim_current,
            "artist": meta.get("artist") or "",
            "vector": index.matrix[row_i].tolist(),
            "cluster_id": meta.get("cluster_id"),
        }

    pool_per_artist = (
        None if artist_limit is None else max(artist_limit * POOL_ARTIST_FACTOR, POOL_ARTIST_MIN)
    )
    pool_counts: dict[str, int] = {}
    seen_md5: set[str] = set()
    seen_songs: set[str] = set()
    items: list[dict[str, Any]] = []
    spill: list[int] = []  # skipped only because their artist already has its share of the pool
    order = np.argsort(-np.maximum(taste_sims, current_sims))
    for row in order:
        row_i = int(row)
        if row_i in forbidden:
            continue
        meta = index.meta[row_i]
        # the same file, or the same song under another id: one is enough
        md5 = str(meta.get("file_md5") or "")
        song = _song_key(meta.get("artist"), meta.get("title"))
        if (md5 and md5 in seen_md5) or (song and song in seen_songs):
            continue
        artist = str(meta.get("artist") or "").strip().lower()
        if pool_per_artist is not None and artist and pool_counts.get(artist, 0) >= pool_per_artist:
            if len(spill) < pool:
                spill.append(row_i)
            continue
        if md5:
            seen_md5.add(md5)
        if song:
            seen_songs.add(song)
        if artist:
            pool_counts[artist] = pool_counts.get(artist, 0) + 1
        items.append(make_item(row_i))
        if len(items) >= pool:
            break

    quotas = scale_quotas(size, explore_ratio=explore_ratio)
    chosen = _fill_to_size(
        apply_quotas(items, size=size, quotas=quotas, artist_limit=artist_limit),
        items, size, artist_limit,
    )
    if len(chosen) < size and artist_limit is not None:
        # Too few artists for the cap: bring in the pool's leftovers and allow one more
        # track per artist at a time, until the list is full.
        items.extend(make_item(r) for r in spill)
        limit = artist_limit
        while len(chosen) < size and limit < artist_limit + RELAX_STEPS:
            limit += 1
            chosen = _fill_to_size(
                apply_quotas(items, size=size, quotas=quotas, artist_limit=limit),
                items, size, limit,
            )
    if len(chosen) < size:
        chosen = _fill_to_size(chosen, items, size, None)
    return chosen[:size]


def _fill_to_size(
    chosen: list[dict[str, Any]],
    items: list[dict[str, Any]],
    size: int,
    artist_limit: int | None,
) -> list[dict[str, Any]]:
    """Top up a short selection (source quotas ran dry) from the rest of the pool."""
    if len(chosen) >= size:
        return chosen
    chosen = list(chosen)
    used = {int(item["track_id"]) for item in chosen}
    counts: dict[str, int] = {}
    for item in chosen:
        artist = _artist_key(item)
        if artist:
            counts[artist] = counts.get(artist, 0) + 1
    for item in items:
        if len(chosen) >= size:
            break
        if int(item["track_id"]) in used:
            continue
        artist = _artist_key(item)
        if artist_limit is not None and artist and counts.get(artist, 0) >= artist_limit:
            continue
        chosen.append(item)
        used.add(int(item["track_id"]))
        if artist:
            counts[artist] = counts.get(artist, 0) + 1
    return chosen
