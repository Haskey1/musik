package api

import (
	"context"
	"net/http/httptest"
	"testing"

	"github.com/torwin-job/musik/player/internal/config"
)

func TestRecapOwnerAndTelegramRollout(t *testing.T) {
	t.Setenv("MUSIK_RECAP_OWNER_USER_ID", "owner")
	srv := &Server{Cfg: config.Config{MultiUser: true, DataDir: t.TempDir()}}
	route, found := matchRoute(httptest.NewRequest("GET", "/api/year-recap/availability", nil))
	if !found || !deviceMayCall(route, httptest.NewRequest("GET", route.path, nil)) {
		t.Fatal("mobile token cannot check availability")
	}
	assertAccess := func(p *principal, want bool) {
		t.Helper()
		req := httptest.NewRequest("GET", "/api/year-recap", nil)
		if p != nil {
			req = req.WithContext(context.WithValue(req.Context(), principalKey{}, p))
		}
		if got := srv.recapAllowed(req); got != want {
			t.Fatalf("access for %+v: got %v, want %v", p, got, want)
		}
	}
	owner := &principal{UserID: "owner", DeviceToken: true}
	other := &principal{UserID: "other", Admin: true}
	assertAccess(owner, true)
	assertAccess(other, false)
	assertAccess(nil, false)

	update := recapBotUpdate{}
	update.Message.Text = "/recap_on"
	update.Message.From.ID = 123
	update.Message.Chat.ID = 123
	update.Message.Chat.Type = "group"
	if reply := srv.recapBotReply(update, 123); reply != "" {
		t.Fatal("accepted group command")
	}
	update.Message.Chat.Type = "private"
	if reply := srv.recapBotReply(update, 456); reply != "" {
		t.Fatal("accepted unauthorized command")
	}
	assertAccess(other, false)
	srv.recapBotReply(update, 123)
	assertAccess(other, true)
	assertAccess(nil, false)

	restarted := &Server{Cfg: srv.Cfg}
	if !restarted.recapPublished() {
		t.Fatal("rollout did not persist")
	}
	update.Message.Text = "/recap_off"
	restarted.recapBotReply(update, 123)
	assertAccess(other, false)
	assertAccess(owner, true)
}
