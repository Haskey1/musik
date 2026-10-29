from __future__ import annotations

import numpy as np

from musik.brain.ranker import label_sources, primary_source, rank_index
from musik.index.brute import EmbeddingIndex


def test_source_labels_split_near_and_far():
    assert primary_source(label_sources(sim_taste=0.7)) == "exploit"
    assert "explore_adjacent" in label_sources(sim_taste=0.3)
    assert primary_source(label_sources(sim_taste=0.05)) == "wildcard"


def test_rank_index_respects_forbidden_and_size():
    rng = np.random.default_rng(0)
    n, d = 40, 16
    mat = rng.normal(size=(n, d)).astype(np.float32)
    mat /= np.linalg.norm(mat, axis=1, keepdims=True)
    meta = [
        {"id": i, "artist": f"A{i % 7}", "title": f"T{i}", "path": "", "file_md5": str(i)}
        for i in range(n)
    ]
    idx = EmbeddingIndex(
        track_ids=np.arange(n, dtype=np.int64),
        matrix=mat,
        meta=meta,
        md5s=[str(i) for i in range(n)],
    )
    ranked = rank_index(idx, taste=idx.centroid(), forbidden={0, 1}, size=8, explore_ratio=0.25)
    assert len(ranked) == 8
    assert {item["track_id"] for item in ranked}.isdisjoint({0, 1})
    assert all(item["source"] for item in ranked)


def _index_with_heavy_artists(heavy: int = 120, others: int = 60):
    """Two artists own most of the library and sit right at the taste centre."""
    rng = np.random.default_rng(1)
    d = 16
    centre = np.ones(d, dtype=np.float32) / np.sqrt(d)
    rows, meta = [], []
    for i in range(heavy):
        rows.append(centre + rng.normal(scale=0.02, size=d))
        meta.append({"id": i, "artist": "Heavy A" if i % 2 else "Heavy B", "title": f"H{i}", "path": "", "file_md5": f"h{i}"})
    for j in range(others):
        rows.append(centre + rng.normal(scale=0.35, size=d))
        meta.append({"id": heavy + j, "artist": f"Solo {j}", "title": f"S{j}", "path": "", "file_md5": f"s{j}"})
    mat = np.asarray(rows, dtype=np.float32)
    mat /= np.linalg.norm(mat, axis=1, keepdims=True)
    n = len(rows)
    idx = EmbeddingIndex(
        track_ids=np.arange(n, dtype=np.int64),
        matrix=mat,
        meta=meta,
        md5s=[m["file_md5"] for m in meta],
    )
    return idx, centre


def test_rank_index_caps_tracks_per_artist():
    idx, centre = _index_with_heavy_artists()
    ranked = rank_index(idx, taste=centre, size=30, explore_ratio=0.15)
    assert len(ranked) == 30
    counts: dict[str, int] = {}
    for item in ranked:
        counts[item["artist"]] = counts.get(item["artist"], 0) + 1
    assert max(counts.values()) <= 2, counts
    assert len(counts) >= 15


def test_rank_index_without_cap_lets_heavy_artists_dominate():
    idx, centre = _index_with_heavy_artists()
    ranked = rank_index(idx, taste=centre, size=30, explore_ratio=0.15, artist_limit=None)
    heavy = sum(1 for item in ranked if item["artist"].startswith("Heavy"))
    assert heavy > 20


def test_rank_index_relaxes_the_cap_when_artists_run_out():
    # 3 artists, 10 tracks each: a list of 12 cannot be built with 2 per artist
    n, d = 30, 8
    rng = np.random.default_rng(2)
    mat = rng.normal(size=(n, d)).astype(np.float32)
    mat /= np.linalg.norm(mat, axis=1, keepdims=True)
    meta = [
        {"id": i, "artist": f"A{i % 3}", "title": f"T{i}", "path": "", "file_md5": str(i)}
        for i in range(n)
    ]
    idx = EmbeddingIndex(
        track_ids=np.arange(n, dtype=np.int64), matrix=mat, meta=meta, md5s=[str(i) for i in range(n)]
    )
    ranked = rank_index(idx, taste=idx.centroid(), size=12, explore_ratio=0.25)
    assert len(ranked) == 12
    assert len({item["track_id"] for item in ranked}) == 12
    counts: dict[str, int] = {}
    for item in ranked:
        counts[item["artist"]] = counts.get(item["artist"], 0) + 1
    assert max(counts.values()) <= 4  # spread evenly, not 10 + 2 + 0


def test_rank_index_skips_the_same_song_under_another_id():
    idx, centre = _index_with_heavy_artists(heavy=0, others=30)
    idx.meta[1] = {**idx.meta[0]} | {"id": 1, "file_md5": "other"}  # same artist + title
    ranked = rank_index(idx, taste=centre, size=29, explore_ratio=0.15)
    keys = {(m["artist"], m["title"]) for m in (idx.meta[item["row"]] for item in ranked)}
    assert len(keys) == len(ranked)
