from __future__ import annotations

from musik.artwork.artists import ArtistPhotoResult
from musik.jobs import runner


def test_artist_photos_job_runs_the_fetch(monkeypatch):
    calls = {}

    def fake_fetch(*, limit=None, force=False, delay_sec=0.5):
        calls.update(limit=limit, force=force)
        return ArtistPhotoResult(total=3, found=2, missing=1)

    monkeypatch.setattr("musik.artwork.artists.fetch_library_artist_photos", fake_fetch)
    assert "artist_photos" in runner.JOB_KINDS
    out = runner._process_job({"id": 1, "kind": "artist_photos", "payload": {"limit": 5}})
    assert calls == {"limit": 5, "force": False}
    assert out["found"] == 2 and out["missing"] == 1 and out["queued"] == 3
