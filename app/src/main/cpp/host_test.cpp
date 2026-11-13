// Runs on a normal computer: verifies word building / UTF-8 safety and that the Whisper API links.
#include "whisper_core.h"
#include "whisper.h"
#include <cstdio>
#include <cstdlib>

static int fails = 0;
#define CHECK(c) do { if (!(c)) { printf("FAIL line %d: %s\n", __LINE__, #c); fails++; } } while (0)

int main() {
    // 1) simple English words
    {
        std::vector<TokenPiece> t = {
            {" And", 0, 30, false}, {" so", 30, 60, false}, {",", 60, 70, false},
            {" my", 70, 100, false}, {"<eot>", 100, 100, true}};
        auto w = build_words(t);
        CHECK(w.size() == 3);
        CHECK(w[0].text == "And" && w[0].t0_ms == 0);
        CHECK(w[1].text == "so," && w[1].t1_ms == 70 + 0 || w[1].text == "so,");
        CHECK(w[2].text == "my");
    }
    // 2) a Devanagari character split across two tokens must be re-joined (नमस्ते)
    {
        std::string full = " \xE0\xA4\xA8\xE0\xA4\xAE\xE0\xA4\xB8\xE0\xA5\x8D\xE0\xA4\xA4\xE0\xA5\x87";
        std::string a = full.substr(0, 5);   // cuts inside a character
        std::string b = full.substr(5);
        std::vector<TokenPiece> t = {{a, 0, 40, false}, {b, 40, 90, false}, {" \xE0\xA4\xAD\xE0\xA4\xBE\xE0\xA4\x88", 90, 150, false}};
        auto w = build_words(t);
        CHECK(w.size() == 2);
        CHECK(w[0].text == "\xE0\xA4\xA8\xE0\xA4\xAE\xE0\xA4\xB8\xE0\xA5\x8D\xE0\xA4\xA4\xE0\xA5\x87");
        CHECK(w[0].t0_ms == 0 && w[0].t1_ms >= 40);
    }
    // 3) invalid UTF-8 never escapes
    {
        std::string bad = "ab\xE0\xA4"; // truncated
        std::string s = sanitize_utf8(bad);
        CHECK(s == "ab\xEF\xBF\xBD\xEF\xBF\xBD" || s == "ab\xEF\xBF\xBD");
        CHECK(json_escape("a\"b\\c\n") == "a\\\"b\\\\c\\n");
    }
    // 4) backwards timestamps are repaired
    {
        std::vector<TokenPiece> t = {{" a", 100, 50, false}, {" b", 20, 10, false}};
        auto w = build_words(t);
        CHECK(w.size() == 2 && w[0].t1_ms >= w[0].t0_ms && w[1].t0_ms >= w[0].t0_ms && w[1].t1_ms >= w[1].t0_ms);
    }
    // 5) API links and missing model fails cleanly instead of crashing
    {
        whisper_context_params cp = whisper_context_default_params();
        cp.use_gpu = false;
        whisper_context * c = whisper_init_from_file_with_params("/nonexistent/model.bin", cp);
        CHECK(c == nullptr);
        std::string err;
        std::string j = transcribe_pcm16_file(nullptr, "/nonexistent.pcm", 2, "en", false, nullptr, nullptr, nullptr, err);
        CHECK(j.empty() && !err.empty());
        printf("system info: %s\n", whisper_print_system_info());
    }
    printf(fails ? "HOST TEST FAILED (%d)\n" : "HOST TEST OK\n", fails);
    return fails ? 1 : 0;
}
