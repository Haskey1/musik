"""Profile-local rotation for personalized mixes."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any

from musik.brain.ranker import MixPriorities, _parse_time, _song_key, rank_index
from musik.db.schema import connect
from musik.index.brute import EmbeddingIndex

MIX_HISTORY_VERSIONS = 3
MIX_RECENT_DAYS = 2


def _song_keys(track: Any, md5: str = "") -> tuple[tuple[str, str], ...]:
    song = _song_key(track["artist"], track["title"])
    return tuple(
        (kind, value)
        for kind, value in (("name", song), ("md5", md5))
        if value
    )


def _recent_mix_tracks(kind: str) -> list:
    with connect() as conn:
        return conn.execute(
            """
            WITH versions AS (
                SELECT id FROM playlists
                WHERE profile_id = :profile AND kind = :kind AND type = 'generated'
                ORDER BY id DESC LIMIT :versions
            )
            SELECT v.id AS version, pt.track_id, t.artist, t.title, t.file_md5
            FROM versions v
            JOIN playlist_tracks pt ON pt.playlist_id = v.id AND pt.profile_id = :profile
            JOIN tracks t ON t.id = pt.track_id
            """,
            {"profile": conn.profile_id, "kind": kind, "versions": MIX_HISTORY_VERSIONS},
        ).fetchall()


def _recently_played_tracks(now: datetime, recent_days: int) -> list:
    cutoff = (now - timedelta(days=max(1, recent_days))).strftime("%Y-%m-%dT%H:%M:%S")
    with connect() as conn:
        return conn.execute(
            """
            SELECT t.id AS track_id, t.artist, t.title, t.file_md5, MAX(h.ts) AS last_played
            FROM listening_history h JOIN tracks t ON t.id = h.track_id
            WHERE h.profile_id = :profile AND h.ts >= :cutoff
              AND h.action IN ('start', 'finish', 'track_end')
            GROUP BY t.id, t.artist, t.title, t.file_md5
            """,
            {"profile": conn.profile_id, "cutoff": cutoff},
        ).fetchall()


@dataclass
class MixPackRotation:
    opening_size: int
    earlier_openings: set[int] = field(default_factory=set)
    earlier_tracks: set[int] = field(default_factory=set)
    now: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    _recent_plays: dict[int, list] = field(default_factory=dict)

    def played(self, recent_days: int) -> list:
        if recent_days not in self._recent_plays:
            self._recent_plays[recent_days] = _recently_played_tracks(self.now, recent_days)
        return self._recent_plays[recent_days]


def rank_mix(
    index: EmbeddingIndex,
    *,
    kind: str,
    taste: Any,
    size: int,
    explore_ratio: float,
    forbidden: set[int],
    pack: MixPackRotation | None = None,
    recent_days: int = MIX_RECENT_DAYS,
) -> tuple[list[dict[str, Any]], MixPriorities]:
    pack = pack if pack is not None else MixPackRotation(opening_size=0)
    recent_versions = _recent_mix_tracks(kind)
    recent_plays = pack.played(recent_days)
    last_version: dict[int, int] = {}
    version_by_song: dict[tuple[str, str], int] = {}
    last_play: dict[int, str] = {}
    play_by_song: dict[tuple[str, str], str] = {}
    for track in recent_versions:
        version = int(track["version"])
        track_id = int(track["track_id"])
        last_version[track_id] = max(last_version.get(track_id, 0), version)
        for key in _song_keys(track, track["file_md5"]):
            version_by_song[key] = max(version_by_song.get(key, 0), version)
    for track in recent_plays:
        played = _parse_time(track["last_played"])
        if played is None:
            continue
        timestamp = played.isoformat()
        last_play[int(track["track_id"])] = timestamp
        for key in _song_keys(track, track["file_md5"]):
            play_by_song[key] = max(play_by_song.get(key, ""), timestamp)

    md5s = getattr(index, "md5s", ())
    track_keys = [
        _song_keys(
            track, track.get("file_md5") or (md5s[row] if row < len(md5s) else ""),
        )
        for row, track in enumerate(index.meta)
    ]
    pack_songs: set[tuple[str, str]] = set()
    opening_songs: set[tuple[str, str]] = set()
    for track_id in pack.earlier_tracks:
        row = index.row_of(track_id)
        if row is not None:
            pack_songs.update(track_keys[row])
            if track_id in pack.earlier_openings:
                opening_songs.update(track_keys[row])

    history: dict[int, tuple[str, int, bool]] = {}
    openings: set[int] = set()
    for row, (track, keys) in enumerate(zip(index.meta, track_keys)):
        track_id = int(track["id"])
        played = max((last_play.get(track_id, ""), *(play_by_song.get(key, "") for key in keys)))
        version = max((last_version.get(track_id, 0), *(version_by_song.get(key, 0) for key in keys)))
        overlap = track_id in pack.earlier_tracks or any(key in pack_songs for key in keys)
        if played or version or overlap:
            history[row] = played, version, overlap
        if track_id in pack.earlier_openings or any(key in opening_songs for key in keys):
            openings.add(row)

    priorities = MixPriorities(history, openings, pack.opening_size)
    ranked = rank_index(
        index, taste=taste, forbidden=forbidden, size=min(size, index.size - len(forbidden)),
        explore_ratio=explore_ratio, now=pack.now, mix_priorities=priorities,
    )
    return ranked, priorities
