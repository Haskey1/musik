package api

import (
	"html/template"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

// Share links from the iOS app: /open/track/{id}, /open/artist?name=…,
// /open/album?artist=…&album=…. The page is public and reads nothing from the
// library (titles come from the link itself), so it leaks nothing on a private
// server. It hands the link to the app through the musik:// scheme, with a
// button for in-app browsers (Telegram) that block an automatic jump, and a way
// to the web UI for people without the app.
var openLinkPage = template.Must(template.New("open").Parse(`<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{{.Title}}</title>
<meta property="og:site_name" content="musik">
<meta property="og:title" content="{{.Title}}">
{{if .Subtitle}}<meta property="og:description" content="{{.Subtitle}}">{{end}}
<style>
:root{color-scheme:dark}
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
background:#16121f;color:#f4f1fa;font:16px/1.4 -apple-system,system-ui,sans-serif}
main{max-width:360px;padding:32px 24px;text-align:center}
.logo{font-size:15px;letter-spacing:.2em;text-transform:uppercase;opacity:.6}
h1{font-size:24px;margin:18px 0 6px}
p{margin:0 0 28px;opacity:.7}
a.btn{display:block;padding:15px;border-radius:999px;background:#f2994a;color:#16121f;
font-weight:700;text-decoration:none}
a.web{display:block;margin-top:18px;color:#f4f1fa;opacity:.6}
</style>
</head>
<body>
<main>
<div class="logo">musik</div>
<h1>{{.Title}}</h1>
{{if .Subtitle}}<p>{{.Subtitle}}</p>{{else}}<p>&nbsp;</p>{{end}}
<a class="btn" href="{{.App}}">Открыть в приложении</a>
<a class="web" href="/">Открыть в браузере</a>
</main>
<script>location.href = {{.App}};</script>
</body>
</html>`))

type openLinkData struct {
	Title    string
	Subtitle string
	App      template.URL
}

func (s *Server) handleOpenLink(w http.ResponseWriter, r *http.Request) {
	rest := strings.Trim(strings.TrimPrefix(r.URL.Path, "/open/"), "/")
	q := r.URL.Query()
	clean := func(v string) string {
		v = strings.TrimSpace(v)
		if r := []rune(v); len(r) > 200 {
			v = string(r[:200])
		}
		return v
	}
	var data openLinkData
	switch {
	case strings.HasPrefix(rest, "track/"):
		id, err := strconv.ParseInt(strings.TrimPrefix(rest, "track/"), 10, 64)
		if err != nil || id <= 0 {
			http.NotFound(w, r)
			return
		}
		data.Title = clean(q.Get("t"))
		if data.Title == "" {
			data.Title = "Трек"
		}
		data.Subtitle = clean(q.Get("a"))
		data.App = template.URL("musik://track/" + strconv.FormatInt(id, 10))
	case rest == "artist":
		name := clean(q.Get("name"))
		if name == "" {
			http.NotFound(w, r)
			return
		}
		data.Title = name
		data.Subtitle = "Артист"
		data.App = template.URL("musik://artist?" + url.Values{"name": {name}}.Encode())
	case rest == "album":
		album := clean(q.Get("album"))
		if album == "" {
			http.NotFound(w, r)
			return
		}
		artist := clean(q.Get("artist"))
		data.Title = album
		data.Subtitle = "Альбом"
		if artist != "" {
			data.Subtitle = "Альбом · " + artist
		}
		data.App = template.URL("musik://album?" + url.Values{"artist": {artist}, "album": {album}}.Encode())
	default:
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_ = openLinkPage.Execute(w, data)
}
