package api

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

type recapBotUpdate struct {
	ID      int64 `json:"update_id"`
	Message struct {
		From struct {
			ID int64 `json:"id"`
		} `json:"from"`
		Chat struct {
			ID   int64  `json:"id"`
			Type string `json:"type"`
		} `json:"chat"`
		Text string `json:"text"`
	} `json:"message"`
}

// StartRecapBot uses a dedicated Telegram bot token. The command is accepted
// only from the configured Telegram user in a direct chat.
func (s *Server) StartRecapBot(ctx context.Context) {
	token := strings.TrimSpace(os.Getenv("MUSIK_RECAP_BOT_TOKEN"))
	owner, _ := strconv.ParseInt(strings.TrimSpace(os.Getenv("MUSIK_RECAP_TELEGRAM_ID")), 10, 64)
	if !s.Cfg.MultiUser || token == "" || owner <= 0 || strings.TrimSpace(os.Getenv("MUSIK_RECAP_OWNER_USER_ID")) == "" {
		return
	}
	client := &http.Client{
		Timeout:       35 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	go func() {
		var offset int64
		for ctx.Err() == nil {
			var updates []recapBotUpdate
			err := recapTelegram(ctx, client, token, "getUpdates", map[string]any{
				"offset": offset, "timeout": 22, "allowed_updates": []string{"message"},
			}, &updates)
			if err != nil {
				if ctx.Err() != nil {
					return
				}
				log.Printf("year recap: Telegram polling unavailable")
				select {
				case <-ctx.Done():
					return
				case <-time.After(10 * time.Second):
					continue
				}
			}
			for _, update := range updates {
				if update.ID >= offset {
					reply := s.recapBotReply(update, owner)
					offset = update.ID + 1
					if reply != "" {
						_ = recapTelegram(ctx, client, token, "sendMessage", map[string]any{
							"chat_id": update.Message.Chat.ID, "text": reply,
						}, nil)
					}
				}
			}
		}
	}()
}

func (s *Server) recapBotReply(update recapBotUpdate, owner int64) string {
	message := update.Message
	if owner <= 0 || message.From.ID != owner || message.Chat.ID != owner || message.Chat.Type != "private" {
		return ""
	}
	switch strings.TrimSpace(message.Text) {
	case "/recap_on":
		if err := os.MkdirAll(filepath.Dir(s.recapFlag()), 0700); err != nil {
			return "Не удалось включить итоги."
		}
		if err := recapWriteFile(s.recapFlag(), []byte("on\n")); err != nil {
			return "Не удалось включить итоги."
		}
		return "Итоги 2026 открыты всем пользователям."
	case "/recap_off":
		if err := os.Remove(s.recapFlag()); err != nil && !errors.Is(err, os.ErrNotExist) {
			return "Не удалось выключить итоги."
		}
		return "Итоги 2026 доступны только владельцу."
	case "/recap_status":
		if s.recapPublished() {
			return "Итоги 2026 открыты всем пользователям."
		}
		return "Итоги 2026 доступны только владельцу."
	case "/start", "/help":
		return "/recap_status — проверить доступ\n/recap_on — открыть всем\n/recap_off — вернуть режим предпросмотра"
	default:
		return ""
	}
}

func recapTelegram(ctx context.Context, client *http.Client, token, method string, payload any, out any) error {
	body, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, "https://api.telegram.org/bot"+token+"/"+method, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	var envelope struct {
		OK     bool            `json:"ok"`
		Result json.RawMessage `json:"result"`
	}
	if resp.StatusCode != http.StatusOK || json.NewDecoder(resp.Body).Decode(&envelope) != nil || !envelope.OK {
		return errors.New("Telegram rejected the request")
	}
	if out == nil {
		return nil
	}
	return json.Unmarshal(envelope.Result, out)
}
