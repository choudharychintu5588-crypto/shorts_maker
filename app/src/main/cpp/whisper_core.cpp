#include "whisper_core.h"
#include "whisper.h"

#include <cstdio>
#include <cstring>
#include <cmath>

std::string sanitize_utf8(const std::string & in) {
    std::string out;
    out.reserve(in.size());
    const unsigned char * s = (const unsigned char *) in.data();
    size_t n = in.size(), i = 0;
    while (i < n) {
        unsigned char c = s[i];
        size_t len = 0;
        if (c < 0x80) len = 1;
        else if (c >= 0xC2 && c <= 0xDF) len = 2;
        else if (c >= 0xE0 && c <= 0xEF) len = 3;
        else if (c >= 0xF0 && c <= 0xF4) len = 4;
        bool ok = len > 0 && i + len <= n;
        for (size_t k = 1; ok && k < len; k++) {
            if ((s[i + k] & 0xC0) != 0x80) ok = false;
        }
        if (ok && len == 3) {
            // reject overlong forms and UTF-16 surrogates
            if (c == 0xE0 && s[i + 1] < 0xA0) ok = false;
            if (c == 0xED && s[i + 1] >= 0xA0) ok = false;
        }
        if (ok && len == 4) {
            if (c == 0xF0 && s[i + 1] < 0x90) ok = false;
            if (c == 0xF4 && s[i + 1] >= 0x90) ok = false;
        }
        if (ok) {
            out.append(in, i, len);
            i += len;
        } else {
            out += "\xEF\xBF\xBD";  // U+FFFD
            i += 1;
        }
    }
    return out;
}

std::string json_escape(const std::string & in) {
    std::string out;
    out.reserve(in.size() + 8);
    for (unsigned char c : in) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += (char) c;
                }
        }
    }
    return out;
}

static bool is_blank(const std::string & s) {
    for (char c : s) {
        if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return false;
    }
    return true;
}

std::vector<WordOut> build_words(const std::vector<TokenPiece> & tokens) {
    std::vector<WordOut> words;
    std::string cur;
    int64_t cur_t0 = 0, cur_t1 = 0;
    bool have = false;

    auto flush = [&]() {
        if (have) {
            std::string clean = sanitize_utf8(cur);
            // trim spaces
            size_t a = 0, b = clean.size();
            while (a < b && (clean[a] == ' ' || clean[a] == '\n' || clean[a] == '\t')) a++;
            while (b > a && (clean[b - 1] == ' ' || clean[b - 1] == '\n' || clean[b - 1] == '\t')) b--;
            if (b > a) {
                WordOut w;
                w.text = clean.substr(a, b - a);
                w.t0_ms = cur_t0;
                w.t1_ms = cur_t1 < cur_t0 ? cur_t0 : cur_t1;
                words.push_back(w);
            }
        }
        cur.clear();
        have = false;
    };

    for (const TokenPiece & t : tokens) {
        if (t.special) continue;
        if (t.text.empty()) continue;
        bool starts_space = t.text[0] == ' ';
        if (starts_space) flush();
        if (!have) {
            if (is_blank(t.text)) continue;
            cur_t0 = t.t0_ms;
            have = true;
        }
        cur += t.text;
        cur_t1 = t.t1_ms;
    }
    flush();

    // Whisper token times can overlap or go backwards; make them strictly ordered.
    for (size_t i = 0; i < words.size(); i++) {
        if (i > 0 && words[i].t0_ms < words[i - 1].t0_ms) words[i].t0_ms = words[i - 1].t0_ms;
        if (words[i].t1_ms < words[i].t0_ms) words[i].t1_ms = words[i].t0_ms;
        if (i + 1 < words.size() && words[i].t1_ms > words[i + 1].t0_ms && words[i + 1].t0_ms >= words[i].t0_ms) {
            words[i].t1_ms = words[i + 1].t0_ms;
        }
    }
    return words;
}

struct CbData {
    ProgressFn progress;
    AbortFn abort_fn;
    void * user;
};

static void progress_tramp(struct whisper_context *, struct whisper_state *, int progress, void * ud) {
    CbData * d = (CbData *) ud;
    if (d && d->progress) d->progress(progress, d->user);
}

static bool abort_tramp(void * ud) {
    CbData * d = (CbData *) ud;
    return d && d->abort_fn && d->abort_fn(d->user);
}

std::string transcribe_pcm16_file(whisper_context * ctx, const std::string & pcm_path, int threads,
                                  const std::string & language, bool translate,
                                  ProgressFn progress, AbortFn abort_fn, void * user, std::string & err) {
    if (!ctx) { err = "model not loaded"; return ""; }

    FILE * f = fopen(pcm_path.c_str(), "rb");
    if (!f) { err = "cannot open audio file"; return ""; }
    fseek(f, 0, SEEK_END);
    long bytes = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (bytes < 2 * 16000 / 2) { fclose(f); err = "audio too short"; return ""; }
    size_t n = (size_t) bytes / 2;

    std::vector<float> pcm;
    try {
        pcm.resize(n);
    } catch (...) {
        fclose(f);
        err = "not enough memory for this audio length";
        return "";
    }
    {
        std::vector<int16_t> buf(1 << 16);
        size_t done = 0;
        while (done < n) {
            size_t want = std::min(buf.size(), n - done);
            size_t got = fread(buf.data(), sizeof(int16_t), want, f);
            if (got == 0) break;
            for (size_t i = 0; i < got; i++) pcm[done + i] = buf[i] / 32768.0f;
            done += got;
        }
        n = done;
    }
    fclose(f);
    if (n == 0) { err = "audio file empty"; return ""; }

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads > 0 ? threads : 4;
    p.print_realtime = false;
    p.print_progress = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.translate = translate;
    p.no_context = true;
    p.single_segment = false;
    p.token_timestamps = true;
    p.temperature_inc = 0.0f;       // no slow retries
    p.greedy.best_of = 1;
    static std::string lang_storage;
    lang_storage = language.empty() ? "auto" : language;
    p.language = lang_storage.c_str();
    p.detect_language = false;

    CbData cb{progress, abort_fn, user};
    p.progress_callback = progress_tramp;
    p.progress_callback_user_data = &cb;
    p.abort_callback = abort_tramp;
    p.abort_callback_user_data = &cb;

    whisper_reset_timings(ctx);
    int rc = whisper_full(ctx, p, pcm.data(), (int) n);
    pcm.clear();
    pcm.shrink_to_fit();
    if (abort_fn && abort_fn(user)) { err = "cancelled"; return ""; }
    if (rc != 0) { err = "whisper_full failed (code " + std::to_string(rc) + ")"; return ""; }

    const int nseg = whisper_full_n_segments(ctx);
    const whisper_token eot = whisper_token_eot(ctx);
    const char * lang = whisper_lang_str(whisper_full_lang_id(ctx));

    std::string out = "{\"language\":\"";
    out += json_escape(lang ? lang : "");
    out += "\",\"segments\":[";
    for (int i = 0; i < nseg; i++) {
        if (i) out += ",";
        const int64_t s0 = whisper_full_get_segment_t0(ctx, i) * 10;
        const int64_t s1 = whisper_full_get_segment_t1(ctx, i) * 10;
        const char * st = whisper_full_get_segment_text(ctx, i);
        std::vector<TokenPiece> toks;
        const int nt = whisper_full_n_tokens(ctx, i);
        for (int k = 0; k < nt; k++) {
            whisper_token_data td = whisper_full_get_token_data(ctx, i, k);
            const char * tt = whisper_full_get_token_text(ctx, i, k);
            TokenPiece tp;
            tp.text = tt ? tt : "";
            tp.t0_ms = td.t0 * 10;
            tp.t1_ms = td.t1 * 10;
            tp.special = td.id >= eot;
            toks.push_back(tp);
        }
        std::vector<WordOut> words = build_words(toks);
        out += "{\"t0\":" + std::to_string(s0) + ",\"t1\":" + std::to_string(s1) + ",\"text\":\"";
        out += json_escape(sanitize_utf8(st ? st : ""));
        out += "\",\"words\":[";
        for (size_t w = 0; w < words.size(); w++) {
            if (w) out += ",";
            out += "{\"w\":\"" + json_escape(words[w].text) + "\",\"t0\":" + std::to_string(words[w].t0_ms) +
                   ",\"t1\":" + std::to_string(words[w].t1_ms) + "}";
        }
        out += "]}";
    }
    out += "]}";
    return out;
}
