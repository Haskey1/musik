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


def _index(artists: list[str], *, d: int = 16, seed: int = 1, tight: set[str] = frozenset()):
    """An index where the artists in `tight` sit right next to the taste vector."""
    rng = np.random.default_rng(seed)
    base = rng.normal(size=d).astype(np.float32)
    base /= np.linalg.norm(base)
    rows = []
    for a in artists:
        noise = rng.normal(size=d).astype(np.float32)
        v = base + noise * (0.05 if a in tight else 0.6)
        rows.append(v / np.linalg.norm(v))
    mat = np.stack(rows).astype(np.float32)
    meta = [{"id": i + 1, "artist": a, "title": f"T{i}", "path": "", "file_md5": str(i)}
            for i, a in enumerate(artists)]
    idx = EmbeddingIndex(track_ids=np.arange(1, len(artists) + 1, dtype=np.int64), matrix=mat,
                         meta=meta, md5s=[str(i) for i in range(len(artists))])
    return idx, base


def test_big_discography_does_not_fill_the_mix():
    from collections import Counter

    artists = ["Лида"] * 120 + ["GSPD"] * 80 + [f"Artist {i}" for i in range(60) for _ in range(2)]
    idx, base = _index(artists, tight={"Лида", "GSPD"})
    ranked = rank_index(idx, taste=base, size=40, explore_ratio=0.25)
    assert len(ranked) == 40
    counts = Counter(item["artist"] for item in ranked)
    assert counts["Лида"] <= 3 and counts["GSPD"] <= 3, counts
    assert len(counts) >= 15


def test_feat_credits_count_as_the_main_artist():
    from collections import Counter

    from musik.brain.ranker import artist_key

    assert artist_key("Лида feat. GSPD") == artist_key("лида") == "лида"
    artists = (["Лида"] * 30 + ["Лида feat. GSPD"] * 30 + ["Лида, Кто-то"] * 30
               + [f"A{i}" for i in range(40)])
    idx, base = _index(artists, tight={"Лида", "Лида feat. GSPD", "Лида, Кто-то"})
    ranked = rank_index(idx, taste=base, size=30, explore_ratio=0.25)
    assert sum(artist_key(i["artist"]) == "лида" for i in ranked) <= 2


def test_same_artist_is_spread_apart():
    artists = [f"A{i % 8}" for i in range(80)]
    idx, base = _index(artists)
    ranked = rank_index(idx, taste=base, size=24, explore_ratio=0.25)
    seq = [i["artist"] for i in ranked]
    assert all(seq[i] != seq[i + 1] for i in range(len(seq) - 1)), seq


def test_small_library_still_fills_the_mix():
    artists = ["One"] * 20 + ["Two"] * 20
    idx, base = _index(artists)
    ranked = rank_index(idx, taste=base, size=30, explore_ratio=0.25)
    assert len(ranked) == 30
    assert len({i["track_id"] for i in ranked}) == 30


def test_recent_artists_wait():
    artists = ["Hot"] * 30 + [f"A{i}" for i in range(30)]
    idx, base = _index(artists, tight={"Hot"})
    nxt = rank_index(idx, taste=base, size=1, recent_artists=["Hot"])
    assert nxt and nxt[0]["artist"] != "Hot"
