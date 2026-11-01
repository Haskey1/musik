import Foundation

struct APIError: LocalizedError {
    let status: Int
    let code: String?
    let message: String

    var errorDescription: String? { message }
    var isUnauthorized: Bool { status == 401 }
    var isNotFound: Bool { status == 404 }
}

private struct ErrorEnvelope: Decodable {
    let error: String?
    let code: String?
}

/// Thin JSON client for the Go player (`:8787`). Auth is a Bearer token when one is
/// stored, otherwise the `musik_session` cookie set by password login.
final class APIClient: @unchecked Sendable {
    let settings: Settings
    let session: URLSession
    /// Called on the main actor when the server answers 401 to an authed request.
    var onUnauthorized: (@MainActor () -> Void)?

    private let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.keyDecodingStrategy = .convertFromSnakeCase
        return d
    }()

    init(settings: Settings) {
        self.settings = settings
        let cfg = URLSessionConfiguration.default
        cfg.httpCookieStorage = .shared
        cfg.httpCookieAcceptPolicy = .always
        cfg.urlCache = URLCache(memoryCapacity: 32 << 20, diskCapacity: 256 << 20)
        cfg.timeoutIntervalForRequest = 20
        session = URLSession(configuration: cfg)
    }

    // MARK: URLs

    func url(_ path: String, query: [URLQueryItem] = []) throws -> URL {
        try url(path, query: query, baseURL: settings.baseURL)
    }

    private func url(_ path: String, query: [URLQueryItem], baseURL: String) throws -> URL {
        guard !baseURL.isEmpty, var comps = URLComponents(string: baseURL + path) else {
            throw APIError(status: 0, code: "bad_url", message: "Неверный адрес сервера")
        }
        if !query.isEmpty {
            comps.queryItems = (comps.queryItems ?? []) + query
            // Go decodes "+" in a query as a space; keep literal pluses literal.
            comps.percentEncodedQuery = comps.percentEncodedQuery?
                .replacingOccurrences(of: "+", with: "%2B")
        }
        guard let url = comps.url else {
            throw APIError(status: 0, code: "bad_url", message: "Неверный адрес сервера")
        }
        return url
    }

    func authHeaders() -> [String: String] {
        if let token = settings.token, !token.isEmpty {
            return ["Authorization": "Bearer \(token)"]
        }
        return [:]
    }

    func cookies(for url: URL) -> [HTTPCookie] {
        HTTPCookieStorage.shared.cookies(for: url) ?? []
    }

    func artworkURL(trackId: Int, width: Int) -> URL? {
        try? url("/api/artwork/\(trackId)", query: [URLQueryItem(name: "w", value: String(width))])
    }

    func streamURL(for track: Track) -> URL? {
        let path = track.stream ?? "/api/stream/\(track.id)"
        let query = settings.mobileStream ? [URLQueryItem(name: "q", value: "mobile")] : []
        return try? url(path, query: query)
    }

    /// AVPlayer does not use URLSession, so the player asks for this switch when its
    /// stream fails. The next stream URL is then built on the fallback domain.
    func switchToFallback() -> Bool {
        settings.activateFallback(after: settings.baseURL) != nil
    }

    // MARK: Requests

    func send(_ method: String, _ path: String, query: [URLQueryItem] = [],
              body: [String: Any]? = nil, redirectOn401: Bool = true,
              timeout: TimeInterval? = nil) async throws -> Data {
        var base = settings.baseURL
        var triedFallback = false
        while true {
            do {
                return try await performSend(method, path, query: query, body: body,
                                             redirectOn401: redirectOn401, timeout: timeout, baseURL: base)
            } catch {
                guard !triedFallback,
                      let e = error as? APIError,
                      Self.isUnavailable(e),
                      let fallback = settings.activateFallback(after: base) else { throw error }
                triedFallback = true
                base = fallback
            }
        }
    }

    private func performSend(_ method: String, _ path: String, query: [URLQueryItem],
                             body: [String: Any]?, redirectOn401: Bool,
                             timeout: TimeInterval?, baseURL: String) async throws -> Data {
        var req = URLRequest(url: try url(path, query: query, baseURL: baseURL))
        req.httpMethod = method
        if let timeout { req.timeoutInterval = timeout }
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        for (k, v) in authHeaders() { req.setValue(v, forHTTPHeaderField: k) }
        if method != "GET" {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: body ?? [:])
        }
        let data: Data
        let resp: URLResponse
        do {
            (data, resp) = try await session.data(for: req)
        } catch let e as URLError where e.code == .cancelled {
            throw CancellationError()
        } catch let e as URLError {
            throw APIError(status: 0, code: "network", message: Self.describe(e))
        }
        let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            let env = try? decoder.decode(ErrorEnvelope.self, from: data)
            if status == 401 && redirectOn401, let handler = onUnauthorized {
                await handler()
            }
            throw APIError(status: status, code: env?.code,
                           message: env?.error ?? "Ошибка сервера (HTTP \(status))")
        }
        return data
    }

    func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        do {
            return try decoder.decode(type, from: data)
        } catch {
            throw APIError(status: 0, code: "decode", message: "Неожиданный ответ сервера")
        }
    }

    func get<T: Decodable>(_ path: String, query: [URLQueryItem] = [], as type: T.Type = T.self) async throws -> T {
        try decode(type, from: try await send("GET", path, query: query))
    }

    func post<T: Decodable>(_ path: String, _ body: [String: Any]? = nil, as type: T.Type = T.self) async throws -> T {
        try decode(type, from: try await send("POST", path, body: body))
    }

    /// Fetches raw bytes (artwork) with the same auth as API calls.
    func data(from url: URL) async throws -> Data {
        var target = url
        var triedFallback = false
        while true {
            do {
                return try await performData(from: target)
            } catch {
                guard !triedFallback,
                      let e = error as? APIError,
                      Self.isUnavailable(e),
                      let fallbackTarget = rehostedOnFallback(target) else { throw error }
                triedFallback = true
                target = fallbackTarget
            }
        }
    }

    private func performData(from url: URL) async throws -> Data {
        var req = URLRequest(url: url)
        for (k, v) in authHeaders() { req.setValue(v, forHTTPHeaderField: k) }
        let data: Data
        let resp: URLResponse
        do {
            (data, resp) = try await session.data(for: req)
        } catch let e as URLError where e.code == .cancelled {
            throw CancellationError()
        } catch let e as URLError {
            throw APIError(status: 0, code: "network", message: Self.describe(e))
        }
        let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw APIError(status: status, code: nil, message: "HTTP \(status)")
        }
        return data
    }

    /// Downloads audio with the same failover as JSON and artwork requests.
    func download(from url: URL, timeout: TimeInterval) async throws -> (URL, URLResponse) {
        var target = url
        var triedFallback = false
        while true {
            do {
                return try await performDownload(from: target, timeout: timeout)
            } catch {
                guard !triedFallback,
                      let e = error as? APIError,
                      Self.isUnavailable(e),
                      let fallbackTarget = rehostedOnFallback(target) else { throw error }
                triedFallback = true
                target = fallbackTarget
            }
        }
    }

    private func performDownload(from url: URL, timeout: TimeInterval) async throws -> (URL, URLResponse) {
        var req = URLRequest(url: url)
        req.timeoutInterval = timeout
        for (k, v) in authHeaders() { req.setValue(v, forHTTPHeaderField: k) }
        let tmp: URL
        let resp: URLResponse
        do {
            (tmp, resp) = try await session.download(for: req)
        } catch let e as URLError where e.code == .cancelled {
            throw CancellationError()
        } catch let e as URLError {
            throw APIError(status: 0, code: "network", message: Self.describe(e))
        }
        let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            try? FileManager.default.removeItem(at: tmp)
            throw APIError(status: status, code: nil, message: "HTTP \(status)")
        }
        return (tmp, resp)
    }

    /// Rehosts an internal URL on the fallback, preserving its escaped path and query.
    private func rehostedOnFallback(_ url: URL) -> URL? {
        let candidates = [settings.baseURL, Settings.builtInServer, Settings.builtInFallbackServer]
        guard let base = candidates.first(where: {
            !$0.isEmpty && (url.absoluteString == $0 || url.absoluteString.hasPrefix($0 + "/"))
        }), let fallback = settings.activateFallback(after: base) else { return nil }
        let suffix = url.absoluteString.dropFirst(base.count)
        guard let target = URL(string: fallback + String(suffix)) else { return nil }
        return target
    }

    private static func isUnavailable(_ e: APIError) -> Bool {
        (e.status == 0 && e.code == "network") || e.status >= 500
    }

    private static func describe(_ e: URLError) -> String {
        switch e.code {
        case .notConnectedToInternet: return "Нет сети"
        case .timedOut: return "Сервер не отвечает"
        case .cannotConnectToHost, .cannotFindHost: return "Не удаётся подключиться к серверу"
        default: return e.localizedDescription
        }
    }
}
